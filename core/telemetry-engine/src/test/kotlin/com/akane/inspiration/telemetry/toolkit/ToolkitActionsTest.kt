package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.model.BgDexoptReason
import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolkitActionsTest {
    private val backend = FakeBackend()
    private val audit = FakeAudit()
    private val holds = FakeHolds()
    private var clock = 10_000L
    private val actions = ToolkitActions(backend, audit, holds::hold, nowMs = { clock++ })

    private fun needs(plan: ToolkitPlan): ToolkitWarning = (plan as ToolkitPlan.NeedsConfirmation).warning

    // ---- confirmation ----------------------------------------------------------------------

    @Test
    fun forceStopNeedsConfirmation() =
        runTest {
            assertEquals(ToolkitWarning.FORCE_STOP, needs(actions.plan(ToolkitAction.ForceStop(PKG))))
        }

    @Test
    fun wakeLockIgnoreNeedsConfirmation() =
        runTest {
            val plan = actions.plan(ToolkitAction.SetAppOp(PKG, AppOp.WAKE_LOCK, AppOpMode.IGNORE))
            assertEquals(ToolkitWarning.WAKE_LOCK_IGNORE, needs(plan))
        }

    @Test
    fun restrictedBucketNeedsConfirmation() =
        runTest {
            assertEquals(ToolkitWarning.RESTRICTED_BUCKET, needs(actions.plan(ToolkitAction.SetBucket(PKG, StandbyBucket.RESTRICTED))))
        }

    @Test
    fun runAnyInBackgroundIgnoreNeedsConfirmation() =
        runTest {
            val plan = actions.plan(ToolkitAction.SetAppOp(PKG, AppOp.RUN_ANY_IN_BACKGROUND, AppOpMode.IGNORE))
            assertEquals(ToolkitWarning.RUN_ANY_IN_BACKGROUND_IGNORE, needs(plan))
        }

    @Test
    fun updatedSystemAppNeedsConfirmationEvenForAHarmlessChange() =
        runTest {
            backend.updatedSystem += SYSTEM_PKG
            val plan = actions.plan(ToolkitAction.SetBucket(SYSTEM_PKG, StandbyBucket.RARE))
            assertEquals(ToolkitWarning.UPDATED_SYSTEM_APP, needs(plan))
        }

    @Test
    fun standardOverCustomDozeNeedsConfirmationButOverAProfileDoesNot() =
        runTest {
            backend.dozeRaw = "inactive_to=1234"
            assertEquals(ToolkitWarning.DOZE_OVER_CUSTOM, needs(actions.plan(ToolkitAction.ApplyDoze(DozeProfile.STANDARD))))
            backend.dozeRaw = DozeProfile.BALANCED.constants
            assertEquals(ToolkitPlan.Ready, actions.plan(ToolkitAction.ApplyDoze(DozeProfile.STANDARD)))
        }

    @Test
    fun harmlessActionsAreReady() =
        runTest {
            assertEquals(ToolkitPlan.Ready, actions.plan(ToolkitAction.SetAppOp(PKG, AppOp.RUN_IN_BACKGROUND, AppOpMode.IGNORE)))
            assertEquals(ToolkitPlan.Ready, actions.plan(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE)))
            assertEquals(ToolkitPlan.Ready, actions.plan(ToolkitAction.SetAppOp(PKG, AppOp.WAKE_LOCK, AppOpMode.ALLOW)))
        }

    @Test
    fun unconfirmedExecuteIsRefusedWithoutAnyWriteAndIsRecorded() =
        runTest {
            val outcome = actions.execute(ToolkitAction.ForceStop(PKG), confirmed = false)
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.NOT_CONFIRMED, ToolkitClientCode.NOT_CONFIRMED), outcome)
            assertTrue(backend.writes.isEmpty())
            assertTrue(backend.reads.isEmpty())
            assertEquals(ToolkitActionStatus.REFUSED, audit.rows.single().status)
        }

    @Test
    fun unconfirmedExecuteOnAnUpdatedSystemAppWritesNothing() =
        runTest {
            backend.updatedSystem += SYSTEM_PKG
            val outcome = actions.execute(ToolkitAction.SetBucket(SYSTEM_PKG, StandbyBucket.RARE))
            assertTrue(outcome is ToolkitOutcome.Refused && outcome.reason == ToolkitRefusal.NOT_CONFIRMED)
            assertTrue(backend.writes.isEmpty())
        }

    @Test
    fun confirmedExecuteRunsTheRiskyAction() =
        runTest {
            val outcome = actions.execute(ToolkitAction.ForceStop(PKG), confirmed = true)
            assertEquals(ToolkitActionStatus.DONE, (outcome as ToolkitOutcome.Done).value.status)
            assertEquals(listOf("forceStop($PKG)"), backend.writes)
        }

    @Test
    fun aForceStopThatRanButCouldNotBeLookedUpAfterIsDone() =
        runTest {
            // The service ran the stop, then its package lookup failed: DONE without an after (N-15).
            backend.scriptedWrite = ToolkitResult.done(before = "stopped=false", after = null)

            val outcome = actions.execute(ToolkitAction.ForceStop(PKG), confirmed = true)

            val row = (outcome as ToolkitOutcome.Done).value
            assertEquals(ToolkitActionStatus.DONE, row.status)
            assertEquals(ToolkitCode.OK, row.code)
            assertEquals("stopped=false", row.beforeRaw)
            assertNull(row.afterRaw)
            assertEquals(ToolkitActionStatus.DONE, audit.byId(row.id).status)
        }

    @Test
    fun invalidPackageIsRefusedWithoutTouchingTheService() =
        runTest {
            val outcome = actions.execute(ToolkitAction.SetBucket("--user 5\n", StandbyBucket.RARE))
            assertTrue(outcome is ToolkitOutcome.Refused && outcome.reason == ToolkitRefusal.INVALID_ARGUMENT)
            assertTrue(backend.writes.isEmpty() && backend.reads.isEmpty())
            assertNull(audit.rows.single().pkg)
        }

    @Test
    fun unsafeDozeRawIsRefused() =
        runTest {
            val outcome = actions.execute(ToolkitAction.RestoreDoze("a=1; rm -rf /", null))
            assertTrue(outcome is ToolkitOutcome.Refused && outcome.reason == ToolkitRefusal.INVALID_ARGUMENT)
            assertTrue(backend.writes.isEmpty())
        }

    // ---- write-ahead audit -----------------------------------------------------------------

    @Test
    fun pendingRowExistsWhenTheGatewayWriteIsInvoked() =
        runTest {
            var seen: List<ToolkitActionRecord>? = null
            backend.onWrite = { seen = audit.pending() }
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            val pending = seen!!.single()
            assertEquals(ToolkitActionStatus.PENDING, pending.status)
            assertEquals(PKG, pending.pkg)
            assertEquals("rare", pending.param)
            assertNull(pending.endedAt)
            // and it was finalized afterwards, in place
            assertEquals(ToolkitActionStatus.DONE, audit.byId(pending.id).status)
        }

    @Test
    fun successRecordsServiceBeforeAndAfterNeverNull() =
        runTest {
            val outcome = actions.execute(ToolkitAction.SetAppOp(PKG, AppOp.RUN_IN_BACKGROUND, AppOpMode.IGNORE))
            val row = (outcome as ToolkitOutcome.Done).value
            assertEquals("allow", row.beforeRaw)
            assertEquals("ignore", row.afterRaw)
            assertEquals(ToolkitCode.OK, row.code)
            assertNotNull(row.endedAt)
        }

    @Test
    fun successWithoutABeforeValueIsRecordedDoneButFlaggedUnrestorable() =
        runTest {
            backend.nullBeforeOnAppOpWrite = true
            val outcome = actions.execute(ToolkitAction.SetAppOp(PKG, AppOp.RUN_IN_BACKGROUND, AppOpMode.IGNORE))
            val row = (outcome as ToolkitOutcome.Done).value
            // the change happened, so the log must say so
            assertEquals("ignore", backend.device(PKG)[AppOp.RUN_IN_BACKGROUND])
            assertEquals(ToolkitActionStatus.DONE, row.status)
            assertNull(row.beforeRaw)
            assertEquals("ignore", row.afterRaw)
            assertEquals(ToolkitClientCode.BEFORE_MISSING, row.code)
            assertEquals(ToolkitActionStatus.DONE, audit.rows.single().status)
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.NOT_RESTORABLE), actions.restore(row.id))
        }

    @Test
    fun aBucketSuccessWithoutABeforeValueIsAlsoDoneAndUnrestorable() =
        runTest {
            backend.nullBeforeOnBucketWrite = true
            val row = (actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE)) as ToolkitOutcome.Done).value
            assertEquals(ToolkitActionStatus.DONE, row.status)
            assertNull(row.beforeRaw)
            assertEquals(ToolkitClientCode.BEFORE_MISSING, row.code)
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.NOT_RESTORABLE), actions.restore(row.id))
        }

    @Test
    fun anUnrestorableDoneRowStillListsAsANonRestorableOverride() =
        runTest {
            backend.nullBeforeOnAppOpWrite = true
            val row = (actions.execute(ToolkitAction.SetAppOp(PKG, AppOp.RUN_IN_BACKGROUND, AppOpMode.IGNORE)) as ToolkitOutcome.Done).value
            val bucket = (actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE)) as ToolkitOutcome.Done).value
            val overrides = actions.activeOverrides().first().associateBy { it.actionId }
            assertEquals(false, overrides.getValue(row.id).restorable)
            assertNull(overrides.getValue(row.id).previousRaw)
            assertEquals(true, overrides.getValue(bucket.id).restorable)
        }

    @Test
    fun serviceRefusalIsRecordedAsRefusedWithItsCode() =
        runTest {
            backend.denied[PKG] = ToolkitCode.DENY_PROTECTED
            // the read-back already fails the gate, so the refusal is recorded without a write
            val outcome = actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.PROTECTED_APP, ToolkitCode.DENY_PROTECTED), outcome)
            val row = audit.rows.single()
            assertEquals(ToolkitActionStatus.REFUSED, row.status)
            assertEquals(ToolkitCode.DENY_PROTECTED, row.code)
        }

    @Test
    fun aServiceOutsideUserZeroRefusesPlansReadsAndWritesAsUnsupportedUser() =
        runTest {
            backend.denied[PKG] = ToolkitCode.UNSUPPORTED_USER
            val refused = ToolkitOutcome.Refused(ToolkitRefusal.UNSUPPORTED_USER, ToolkitCode.UNSUPPORTED_USER)

            assertEquals(
                ToolkitPlan.Refused(ToolkitRefusal.UNSUPPORTED_USER),
                actions.plan(ToolkitAction.SetAppOp(PKG, AppOp.WAKE_LOCK, AppOpMode.IGNORE)),
            )
            assertEquals(refused, actions.appState(PKG))
            assertEquals(refused, actions.execute(ToolkitAction.ForceStop(PKG), confirmed = true))
            assertTrue(backend.writes.isEmpty())
        }

    @Test
    fun refusalAtWriteTimeFinalizesThePendingRow() =
        runTest {
            backend.scriptedWrite = ToolkitResult.refused(ToolkitCode.BUSY, before = "x")
            val outcome = actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.BUSY, ToolkitCode.BUSY), outcome)
            val row = audit.rows.single()
            assertEquals(ToolkitActionStatus.REFUSED, row.status)
            assertEquals("x", row.beforeRaw)
        }

    @Test
    fun failedWriteIsRecordedWithServiceValues() =
        runTest {
            backend.scriptedWrite = ToolkitResult.failed(ToolkitCode.NOT_EFFECTIVE, before = "20", after = "20")
            val outcome = actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            assertEquals(ToolkitOutcome.Failed(ToolkitCode.NOT_EFFECTIVE), outcome)
            val row = audit.rows.single()
            assertEquals(ToolkitActionStatus.FAILED, row.status)
            assertEquals(ToolkitCode.NOT_EFFECTIVE, row.code)
            assertEquals("20", row.afterRaw)
            assertNotNull(row.endedAt)
        }

    @Test
    fun dozeRolledBackByTheServiceIsUnavailableNotEffective() =
        runTest {
            backend.scriptedWrite = ToolkitResult.unavailable(ToolkitCode.NOT_EFFECTIVE, before = null, after = null)
            val outcome = actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))
            assertEquals(ToolkitOutcome.Unavailable(ToolkitUnavailable.NOT_EFFECTIVE, ToolkitCode.NOT_EFFECTIVE), outcome)
            assertEquals(ToolkitActionStatus.FAILED, audit.rows.single().status)
        }

    @Test
    fun dozeApplyOverUnsetRecordsANullBeforeAsDone() =
        runTest {
            val row = (actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED)) as ToolkitOutcome.Done).value
            assertEquals(ToolkitActionStatus.DONE, row.status)
            assertNull(row.beforeRaw)
            assertEquals(DozeProfile.BALANCED.constants, row.afterRaw)
        }

    @Test
    fun dozeApplyIsStaleWhenTheValueTheUserSawChangedAndWritesNothing() =
        runTest {
            backend.dozeRaw = "idle_to=2" // the user decided against "idle_to=1"
            val action = ToolkitAction.ApplyDoze(DozeProfile.STANDARD, SeenDoze("idle_to=1"))

            assertEquals(ToolkitPlan.Refused(ToolkitRefusal.STALE), actions.plan(action))
            val outcome = actions.execute(action, confirmed = true)

            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.STALE, ToolkitCode.STALE), outcome)
            assertTrue(backend.writes.isEmpty())
            assertEquals("idle_to=2", backend.dozeRaw)
            assertEquals(ToolkitActionStatus.REFUSED, audit.rows.single().status)
        }

    @Test
    fun dozeApplyWithTheSeenValueStillInPlaceWritesWithItAsTheExpectation() =
        runTest {
            val outcome = actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED, SeenDoze(null)))

            assertTrue(outcome is ToolkitOutcome.Done)
            assertEquals(listOf("applyDoze(${DozeProfile.BALANCED.code},null)"), backend.writes)
        }

    // ---- stale API / unavailable -----------------------------------------------------------

    @Test
    fun staleApiMapsToUnavailableAndNothingIsWrittenOrRecordedAsDone() =
        runTest {
            backend.unavailable = GatewayUnavailable.STALE_SERVICE
            assertEquals(
                ToolkitOutcome.Unavailable(ToolkitUnavailable.STALE_SERVICE),
                actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE)),
            )
            assertEquals(
                ToolkitPlan.Refused(ToolkitRefusal.SERVICE_UNAVAILABLE),
                actions.plan(ToolkitAction.ApplyDoze(DozeProfile.BALANCED)),
            )
            assertEquals(ToolkitOutcome.Unavailable(ToolkitUnavailable.STALE_SERVICE), actions.dozeState())
            assertTrue(backend.writes.isEmpty())
            assertTrue(audit.rows.none { it.status == ToolkitActionStatus.DONE })
        }

    @Test
    fun unavailableWriteRemainsPendingUntilReadback() =
        runTest {
            // reads pass, then the service turns out to be stale: the write gets Unavailable
            backend.writeUnavailable = GatewayUnavailable.STALE_SERVICE
            val outcome = actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            assertEquals(ToolkitOutcome.Unavailable(ToolkitUnavailable.STALE_SERVICE), outcome)
            val row = audit.rows.single()
            assertEquals(ToolkitActionStatus.PENDING, row.status)
            assertEquals(ToolkitClientCode.STALE_SERVICE, row.code)
            assertEquals("10", row.beforeRaw)
            assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG] ?: StandbyBucket.ACTIVE.rawValue)
        }

    // ---- reconcile -------------------------------------------------------------------------

    private fun pendingRow(
        kind: String,
        op: String,
        param: String?,
        pkg: String? = PKG,
        before: String? = "allow",
    ) = ToolkitActionRecord(
        startedAt = 1L,
        kind = kind,
        pkg = pkg,
        op = op,
        param = param,
        status = ToolkitActionStatus.PENDING,
        beforeRaw = before,
    )

    @Test
    fun reconcileFinalizesAMatchingPendingRowAsDone() =
        runTest {
            backend.device(PKG)[AppOp.RUN_IN_BACKGROUND] = "ignore"
            val id = audit.insert(pendingRow("appop", "RUN_IN_BACKGROUND", "ignore"))
            actions.reconcile()
            val row = audit.byId(id)
            assertEquals(ToolkitActionStatus.DONE, row.status)
            assertEquals("ignore", row.afterRaw)
            assertEquals("allow", row.beforeRaw)
            assertNotNull(row.endedAt)
        }

    @Test
    fun reconcileFinalizesAMismatchAsFailed() =
        runTest {
            buckets(PKG, StandbyBucket.ACTIVE)
            val id = audit.insert(pendingRow("bucket", "standby_bucket", "rare", before = "10"))
            actions.reconcile()
            val row = audit.byId(id)
            assertEquals(ToolkitActionStatus.FAILED, row.status)
            assertEquals("10", row.afterRaw)
        }

    @Test
    fun reconcileMatchesDozeAndUnsetDoze() =
        runTest {
            backend.dozeRaw = DozeProfile.BALANCED.constants
            val applied = audit.insert(pendingRow("doze", "apply_profile", "balanced", pkg = null, before = null))
            val standard = audit.insert(pendingRow("doze", "apply_profile", "standard", pkg = null, before = "x"))
            actions.reconcile()
            assertEquals(ToolkitActionStatus.DONE, audit.byId(applied).status)
            assertEquals(ToolkitActionStatus.FAILED, audit.byId(standard).status)
        }

    @Test
    fun reconcileMarksUnverifiableForceStopFailedAndUnknownKindFailed() =
        runTest {
            // A force-stop stays PENDING only when its command timed out or its reply was lost (a stop whose command
            // succeeded is DONE even without a lookup after it). Nothing can read a stop back, so it settles FAILED.
            val stop = audit.insert(pendingRow("force_stop", "force_stop", null, before = null))
            val odd = audit.insert(pendingRow("from_a_newer_build", "x", null))
            actions.reconcile()
            assertEquals(ToolkitClientCode.UNVERIFIED, audit.byId(stop).code)
            assertEquals(ToolkitActionStatus.FAILED, audit.byId(stop).status)
            assertEquals(ToolkitActionStatus.FAILED, audit.byId(odd).status)
        }

    @Test
    fun reconcileLeavesRowsPendingWhileTheServiceIsUnreachable() =
        runTest {
            val id = audit.insert(pendingRow("appop", "RUN_IN_BACKGROUND", "ignore"))
            backend.unavailable = GatewayUnavailable.NO_SERVICE
            actions.reconcile()
            assertEquals(ToolkitActionStatus.PENDING, audit.byId(id).status)
        }

    @Test
    fun reconcileSettlesDexoptFromTheServiceStatus() =
        runTest {
            val finished = audit.insert(pendingRow("dexopt", "bg_dexopt", null, pkg = null, before = "0"))
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.add(serviceStatus(BgDexoptState.FINISHED, exit = 0))
            actions.reconcile()
            assertEquals(ToolkitActionStatus.DONE, audit.byId(finished).status)

            val failed = audit.insert(pendingRow("dexopt", "bg_dexopt", null, pkg = null, before = "0"))
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.add(serviceStatus(BgDexoptState.FINISHED, exit = 3))
            actions.reconcile()
            assertEquals(ToolkitActionStatus.FAILED, audit.byId(failed).status)
            assertEquals(3, audit.byId(failed).code)

            val cancelled = audit.insert(pendingRow("dexopt", "bg_dexopt", null, pkg = null, before = "0"))
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.add(serviceStatus(BgDexoptState.CANCELLED, reason = BgDexoptReason.UNPLUGGED))
            actions.reconcile()
            assertEquals(ToolkitActionStatus.CANCELLED, audit.byId(cancelled).status)

            val lost = audit.insert(pendingRow("dexopt", "bg_dexopt", null, pkg = null, before = "0"))
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.add(serviceStatus(BgDexoptState.IDLE))
            actions.reconcile()
            assertEquals(ToolkitActionStatus.CANCELLED, audit.byId(lost).status)
        }

    @Test
    fun reconcileKeepsDexoptPendingWhileRunningOrUnknown() =
        runTest {
            val id = audit.insert(pendingRow("dexopt", "bg_dexopt", null, pkg = null, before = "0"))
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.add(serviceStatus(BgDexoptState.RUNNING))
            actions.reconcile()
            assertEquals(ToolkitActionStatus.PENDING, audit.byId(id).status)
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.add(serviceStatus(BgDexoptState.UNKNOWN))
            actions.reconcile()
            assertEquals(ToolkitActionStatus.PENDING, audit.byId(id).status)
            // UNKNOWN never takes the binding hold; RUNNING did (and UNKNOWN then released it)
            assertEquals(0, holds.open)
        }

    // ---- restore ---------------------------------------------------------------------------

    @Test
    fun restoreWhenCurrentMatchesWritesALinkedRowAndMarksTheOriginalRestored() =
        runTest {
            val original = (actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE)) as ToolkitOutcome.Done).value
            assertEquals(ToolkitPlan.Ready, actions.planRestore(original.id))
            val restored = (actions.restore(original.id) as ToolkitOutcome.Done).value
            assertEquals(original.id, restored.restoresId)
            assertEquals(ToolkitActionStatus.DONE, restored.status)
            assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])
            assertEquals(ToolkitActionStatus.RESTORED, audit.byId(original.id).status)
        }

    @Test
    fun restoreWhenCurrentDiffersNeedsConfirmationAndWritesNothing() =
        runTest {
            val original = (actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE)) as ToolkitOutcome.Done).value
            backend.buckets[PKG] = StandbyBucket.FREQUENT.rawValue // changed behind our back
            val writesBefore = backend.writes.size
            assertEquals(ToolkitWarning.RESTORE_STATE_CHANGED, needs(actions.planRestore(original.id)))
            val refused = actions.restore(original.id)
            assertTrue(refused is ToolkitOutcome.Refused && refused.reason == ToolkitRefusal.NOT_CONFIRMED)
            assertEquals(writesBefore, backend.writes.size)
            assertEquals(ToolkitActionStatus.DONE, audit.byId(original.id).status)

            assertTrue(actions.restore(original.id, confirmed = true) is ToolkitOutcome.Done)
            assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])
            assertEquals(ToolkitActionStatus.RESTORED, audit.byId(original.id).status)
        }

    @Test
    fun restoreOfAnAppOpDecodesTheStoredModeThroughTheEnum() =
        runTest {
            val original =
                (
                    actions.execute(
                        ToolkitAction.SetAppOp(PKG, AppOp.RUN_IN_BACKGROUND, AppOpMode.IGNORE),
                    ) as ToolkitOutcome.Done
                ).value
            actions.restore(original.id)
            // stored before was the system's "allow", which is written back as allow
            assertEquals("allow", backend.device(PKG)[AppOp.RUN_IN_BACKGROUND])
            assertEquals("setAppOp($PKG,RUN_IN_BACKGROUND,allow)", backend.writes.last())
        }

    @Test
    fun restoreOfDozeReplaysThePriorRawOnlyWhileCurrentIsTheRecordedAfter() =
        runTest {
            backend.dozeRaw = "inactive_to=1234"
            val original = (actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED)) as ToolkitOutcome.Done).value
            assertEquals("inactive_to=1234", original.beforeRaw)
            assertEquals(ToolkitPlan.Ready, actions.planRestore(original.id))
            actions.restore(original.id)
            assertEquals("inactive_to=1234", backend.dozeRaw)
        }

    @Test
    fun restoreOfDozeThatWasUnsetDeletesTheValue() =
        runTest {
            val original = (actions.execute(ToolkitAction.ApplyDoze(DozeProfile.AGGRESSIVE_NIGHT)) as ToolkitOutcome.Done).value
            assertTrue(actions.restore(original.id) is ToolkitOutcome.Done)
            assertNull(backend.dozeRaw)
        }

    @Test
    fun restoreRefusesUnknownRestoredAndNonReversibleRows() =
        runTest {
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.UNKNOWN_ACTION), actions.restore(999))
            val stop = (actions.execute(ToolkitAction.ForceStop(PKG), confirmed = true) as ToolkitOutcome.Done).value
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.NOT_RESTORABLE), actions.restore(stop.id))
            val bucket = (actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE)) as ToolkitOutcome.Done).value
            val restore = (actions.restore(bucket.id) as ToolkitOutcome.Done).value
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.ALREADY_RESTORED), actions.restore(bucket.id))
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.NOT_RESTORABLE), actions.restore(restore.id))
        }

    @Test
    fun restoreDoesNotDecodeAnUnrepresentableStoredValue() =
        runTest {
            val id =
                audit.insert(
                    pendingRow("appop", "RUN_IN_BACKGROUND", "ignore", before = "foreground").copy(
                        status = ToolkitActionStatus.DONE,
                        afterRaw = "ignore",
                    ),
                )
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.NOT_RESTORABLE), actions.restore(id))
            assertTrue(backend.writes.isEmpty())
        }

    @Test
    fun rollbackUsesDurableOrderForListingRestoreAndReset() =
        runTest {
            for (reset in listOf(false, true)) {
                actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
                clock -= 5_000
                val latest =
                    (
                        actions.execute(
                            ToolkitAction.SetBucket(PKG, StandbyBucket.RESTRICTED),
                            confirmed = true,
                        ) as ToolkitOutcome.Done
                    ).value
                assertEquals(
                    latest.id,
                    actions
                        .activeOverrides()
                        .first()
                        .single()
                        .actionId,
                )
                if (reset) actions.resetAll() else actions.restore(latest.id)
                assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])
                assertTrue(actions.activeOverrides().first().isEmpty())
            }
        }

    // ---- active overrides / resetAll -------------------------------------------------------

    @Test
    fun activeOverridesComeFromReadBackNotFromTheLogAlone() =
        runTest {
            val bucket = (actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE)) as ToolkitOutcome.Done).value
            val op = (actions.execute(ToolkitAction.SetAppOp(PKG, AppOp.RUN_IN_BACKGROUND, AppOpMode.IGNORE)) as ToolkitOutcome.Done).value
            val doze = (actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED)) as ToolkitOutcome.Done).value
            assertEquals(
                setOf(bucket.id, op.id, doze.id),
                actions
                    .activeOverrides()
                    .first()
                    .map { it.actionId }
                    .toSet(),
            )

            // the user undid the bucket change outside the app, and the system changed it again
            backend.buckets[PKG] = StandbyBucket.WORKING_SET.rawValue
            val active = actions.activeOverrides().first()
            assertEquals(setOf(op.id, doze.id), active.map { it.actionId }.toSet())
            assertEquals(AppOp.RUN_IN_BACKGROUND, active.first { it.actionId == op.id }.appOp)
            assertEquals("ignore", active.first { it.actionId == op.id }.currentRaw)
        }

    @Test
    fun aRestoredOrStandardChangeIsNotAnOverride() =
        runTest {
            val bucket = (actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE)) as ToolkitOutcome.Done).value
            actions.restore(bucket.id)
            actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))
            actions.execute(ToolkitAction.ApplyDoze(DozeProfile.STANDARD))
            assertTrue(actions.activeOverrides().first().isEmpty())
        }

    @Test
    fun activeOverridesKeepsAChangeAsUnverifiedWhenTheServiceCannotBeRead() =
        runTest {
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            backend.unavailable = GatewayUnavailable.NO_SERVICE
            val override = actions.activeOverrides().first().single()
            assertEquals(ToolkitRefusal.SERVICE_UNAVAILABLE, override.unverified)
            assertEquals(StandbyBucket.RARE.rawValue.toString(), override.currentRaw)
        }

    @Test
    fun resetAllRestoresEveryOverrideAndReportsPartialFailure() =
        runTest {
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            actions.execute(ToolkitAction.SetAppOp(PKG, AppOp.RUN_IN_BACKGROUND, AppOpMode.IGNORE))
            actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))
            // the first restore write that runs is refused by the service; the others must still run
            backend.onWrite = { name ->
                if (name.startsWith("setAppOp")) backend.scriptedWrite = ToolkitResult.refused(ToolkitCode.BUSY)
            }
            val report = actions.resetAll()
            assertEquals(3, report.size)
            val byKind = report.associateBy { it.override.kind }
            assertTrue(byKind.getValue(OverrideKind.APP_OP).outcome is ToolkitOutcome.Refused)
            assertTrue(byKind.getValue(OverrideKind.BUCKET).outcome is ToolkitOutcome.Done)
            assertTrue(byKind.getValue(OverrideKind.DOZE).outcome is ToolkitOutcome.Done)
            assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])
            assertNull(backend.dozeRaw)
            assertEquals(listOf(OverrideKind.APP_OP), actions.activeOverrides().first().map { it.kind })
        }

    // ---- dexopt ----------------------------------------------------------------------------

    @Test
    fun aFreshServiceThatReportsUnknownIsIdleWhenWeNeverStartedAJob() =
        runTest {
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.add(serviceStatus(BgDexoptState.UNKNOWN))
            assertEquals(BgDexoptStatus.Idle, actions.bgDexoptStatus().first())
            assertEquals(0, holds.open)
        }

    @Test
    fun anUnknownServiceStatusStaysUnknownWhileAStartedJobCannotBeConfirmed() =
        runTest {
            val started = (actions.startBgDexopt() as ToolkitOutcome.Done).value
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.add(serviceStatus(BgDexoptState.UNKNOWN))
            assertEquals(BgDexoptStatus.Unknown, actions.bgDexoptStatus().first())
            assertEquals(ToolkitActionStatus.PENDING, audit.byId(started.id).status)
        }

    @Test
    fun startKeepsThePendingRowAndHoldsTheBindingUntilAStatusSaysFinished() =
        runTest {
            val started = (actions.startBgDexopt() as ToolkitOutcome.Done).value
            assertEquals(ToolkitActionStatus.PENDING, started.status)
            assertEquals(1, holds.open)

            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.addAll(
                listOf(serviceStatus(BgDexoptState.RUNNING), serviceStatus(BgDexoptState.FINISHED, exit = 0)),
            )
            val seen = actions.bgDexoptStatus().take(2).toList()
            assertEquals(BgDexoptStatus.Running(1_000L), seen[0])
            assertEquals(BgDexoptStatus.Finished(0, 2_000L), seen[1])
            assertEquals(1, holds.opened) // acquired once, not per poll
            assertEquals(0, holds.open)
            assertEquals(ToolkitActionStatus.DONE, audit.byId(started.id).status)
        }

    @Test
    fun cancelledJobReleasesTheHoldAndCancelsTheRow() =
        runTest {
            val started = (actions.startBgDexopt() as ToolkitOutcome.Done).value
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.addAll(
                listOf(
                    serviceStatus(BgDexoptState.RUNNING),
                    serviceStatus(BgDexoptState.CANCELLED, reason = BgDexoptReason.UNPLUGGED),
                ),
            )
            val seen = actions.bgDexoptStatus().take(2).toList()
            assertEquals(BgDexoptStatus.Cancelled(BgDexoptCancelReason.UNPLUGGED, 2_000L), seen[1])
            assertEquals(0, holds.open)
            assertEquals(ToolkitActionStatus.CANCELLED, audit.byId(started.id).status)
        }

    @Test
    fun userCancelReleasesTheHoldAndFinalizesTheRowOnceTheServiceReportsOurJobCancelled() =
        runTest {
            val started = (actions.startBgDexopt() as ToolkitOutcome.Done).value
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.add(serviceStatus(BgDexoptState.RUNNING))
            assertEquals(1, holds.open)

            assertEquals(ToolkitOutcome.Done(Unit), actions.cancelBgDexopt())

            assertEquals(0, holds.open)
            assertEquals(ToolkitActionStatus.CANCELLED, audit.byId(started.id).status)
            assertEquals(BgDexoptReason.USER, audit.byId(started.id).code)
            assertEquals(BgDexoptStatus.Cancelled(BgDexoptCancelReason.USER, 2_000L), actions.bgDexoptStatus().first())
        }

    @Test
    fun aSuccessfulUserCancelThatOurJobOutlivedKeepsTheRowPendingAndTheCardUnknown() =
        runTest {
            val started = (actions.startBgDexopt() as ToolkitOutcome.Done).value
            // The cancel stopped a job ours was waiting behind; our client outlived every resend.
            backend.cancelEndsOurJob = false
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.add(serviceStatus(BgDexoptState.UNKNOWN, reason = BgDexoptReason.USER))

            assertEquals(ToolkitOutcome.Done(Unit), actions.cancelBgDexopt())

            assertEquals(ToolkitActionStatus.PENDING, audit.byId(started.id).status)
            assertEquals("not Idle: the card keeps its Cancel", BgDexoptStatus.Unknown, actions.bgDexoptStatus().first())
            assertEquals(ToolkitActionStatus.PENDING, audit.byId(started.id).status)
        }

    @Test
    fun aUserCancelKeepsTheHoldWhileOurJobStillRunsAndSettlesOnceTheServiceSaysItStopped() =
        runTest {
            val started = (actions.startBgDexopt() as ToolkitOutcome.Done).value
            // The worker has not seen our client exit yet when the cancel returns.
            backend.cancelEndsOurJob = false
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.addAll(
                listOf(
                    serviceStatus(BgDexoptState.RUNNING),
                    serviceStatus(BgDexoptState.CANCELLED, reason = BgDexoptReason.USER),
                ),
            )

            assertEquals(ToolkitOutcome.Done(Unit), actions.cancelBgDexopt())

            assertEquals("the hold follows the status, as after a start", 1, holds.open)
            assertEquals(ToolkitActionStatus.PENDING, audit.byId(started.id).status)

            assertEquals(BgDexoptStatus.Cancelled(BgDexoptCancelReason.USER, 2_000L), actions.bgDexoptStatus().first())
            assertEquals(0, holds.open)
            assertEquals(1, holds.opened)
            assertEquals(ToolkitActionStatus.CANCELLED, audit.byId(started.id).status)
            assertEquals(BgDexoptReason.USER, audit.byId(started.id).code)
        }

    @Test
    fun aTimedOutUserCancelIsUnverifiedAndFollowedByTheStatusLikeASentOne() =
        runTest {
            val started = (actions.startBgDexopt() as ToolkitOutcome.Done).value
            backend.cancelResult = ToolkitResult.unavailable(ToolkitCode.UNVERIFIED)
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.add(serviceStatus(BgDexoptState.RUNNING))

            assertEquals(
                ToolkitOutcome.Unavailable(ToolkitUnavailable.UNVERIFIED, ToolkitCode.UNVERIFIED),
                actions.cancelBgDexopt(),
            )

            assertEquals("still running: the hold stays", 1, holds.open)
            assertEquals(ToolkitActionStatus.PENDING, audit.byId(started.id).status)
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.add(serviceStatus(BgDexoptState.CANCELLED, reason = BgDexoptReason.USER))
            assertEquals(BgDexoptStatus.Cancelled(BgDexoptCancelReason.USER, 2_000L), actions.bgDexoptStatus().first())
            assertEquals(ToolkitActionStatus.CANCELLED, audit.byId(started.id).status)
            assertEquals(0, holds.open)
        }

    @Test
    fun unknownStatusIsReportedAndNeverTakesTheHold() =
        runTest {
            audit.insert(pendingRow("dexopt", "bg_dexopt", null, pkg = null, before = "0"))
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.add(serviceStatus(BgDexoptState.UNKNOWN))
            assertEquals(BgDexoptStatus.Unknown, actions.bgDexoptStatus().first())
            assertEquals(0, holds.opened)
            // the pending row is kept: a job may still be running
            assertEquals(ToolkitActionStatus.PENDING, audit.rows.single().status)
        }

    @Test
    fun idleServiceWithAPendingRowCancelsTheRowAndReportsIdle() =
        runTest {
            val id = audit.insert(pendingRow("dexopt", "bg_dexopt", null, pkg = null, before = "0"))
            assertEquals(BgDexoptStatus.Idle, actions.bgDexoptStatus().first())
            assertEquals(ToolkitActionStatus.CANCELLED, audit.byId(id).status)
        }

    @Test
    fun refusedStartIsRecordedAndTakesNoHold() =
        runTest {
            backend.startResult = ToolkitResult.refused(ToolkitCode.NOT_PLUGGED)
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.NOT_PLUGGED, ToolkitCode.NOT_PLUGGED), actions.startBgDexopt())
            assertEquals(ToolkitActionStatus.REFUSED, audit.rows.single().status)
            assertEquals(0, holds.opened)
        }

    @Test
    fun anUnsupportedApiLevelIsReportedAsSuchAndRefusesStartAndCancelWithItsOwnReason() =
        runTest {
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.add(serviceStatus(BgDexoptState.UNKNOWN, reason = BgDexoptReason.UNSUPPORTED_API))
            backend.startResult = ToolkitResult.refused(ToolkitCode.UNSUPPORTED_API)
            backend.cancelResult = ToolkitResult.refused(ToolkitCode.UNSUPPORTED_API)
            val refused = ToolkitOutcome.Refused(ToolkitRefusal.UNSUPPORTED_API, ToolkitCode.UNSUPPORTED_API)

            // Not Idle: nothing can be started here, and the card must not offer it.
            assertEquals(BgDexoptStatus.Unsupported, actions.bgDexoptStatus().first())
            assertEquals(refused, actions.startBgDexopt())
            assertEquals(ToolkitActionStatus.REFUSED, audit.rows.single().status)
            assertEquals(refused, actions.cancelBgDexopt())
            assertEquals(0, holds.opened)
        }

    @Test
    fun anUnsupportedApiLevelKeepsAnOlderServicesPendingRowWithoutClaimingAnOutcome() =
        runTest {
            // Started just now: the job could still be running within its cap.
            val id = audit.insert(pendingRow("dexopt", "bg_dexopt", null, pkg = null, before = "0").copy(startedAt = clock))
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.add(serviceStatus(BgDexoptState.UNKNOWN, reason = BgDexoptReason.UNSUPPORTED_API))
            backend.cancelResult = ToolkitResult.refused(ToolkitCode.UNSUPPORTED_API)

            assertEquals(BgDexoptStatus.Unsupported, actions.bgDexoptStatus().first())
            assertTrue(actions.cancelBgDexopt() is ToolkitOutcome.Refused)
            assertEquals(ToolkitActionStatus.PENDING, audit.byId(id).status)
        }

    @Test
    fun anUnsupportedApiLevelSettlesAnOlderServicesPendingRowOnceItIsPastTheCap() =
        runTest {
            val id = audit.insert(pendingRow("dexopt", "bg_dexopt", null, pkg = null, before = "0"))
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.add(serviceStatus(BgDexoptState.UNKNOWN, reason = BgDexoptReason.UNSUPPORTED_API))
            // The row started at 1 ms; the service's 60 minute cap plus the 2 minute wind-down margin have passed.
            clock = 1L + 62 * 60_000L

            assertEquals(BgDexoptStatus.Unsupported, actions.bgDexoptStatus().first())
            val row = audit.byId(id)
            assertEquals(ToolkitActionStatus.CANCELLED, row.status)
            assertEquals("unsupported", row.afterRaw)
        }

    @Test
    fun startingAgainSupersedesAStalePendingDexoptRow() =
        runTest {
            val stale = audit.insert(pendingRow("dexopt", "bg_dexopt", null, pkg = null, before = "0"))
            val fresh = (actions.startBgDexopt() as ToolkitOutcome.Done).value
            assertEquals(ToolkitActionStatus.CANCELLED, audit.byId(stale).status)
            assertEquals(ToolkitActionStatus.PENDING, audit.byId(fresh.id).status)
        }

    // ---- reads -----------------------------------------------------------------------------

    @Test
    fun appStateDecodesReadBack() =
        runTest {
            backend.device(PKG)[AppOp.WAKE_LOCK] = "ignore"
            backend.buckets[PKG] = StandbyBucket.FREQUENT.rawValue
            backend.updatedSystem += PKG
            val state = (actions.appState(PKG) as ToolkitOutcome.Done).value
            assertEquals(AppOpMode.IGNORE, state.appOps[AppOp.WAKE_LOCK])
            assertEquals(AppOpMode.ALLOW, state.appOps[AppOp.RUN_IN_BACKGROUND])
            assertEquals(StandbyBucket.FREQUENT, state.bucket)
            assertTrue(state.updatedSystemApp)
        }

    @Test
    fun dozeStateReportsProfileOrCustom() =
        runTest {
            backend.dozeRaw = DozeProfile.BALANCED.constants
            val balanced = (actions.dozeState() as ToolkitOutcome.Done).value
            assertEquals(DozeProfile.BALANCED, balanced.profile)
            assertEquals(60_000L, balanced.effectiveMs["light_after_inactive_to"])
            backend.dozeRaw = "inactive_to=1"
            assertTrue((actions.dozeState() as ToolkitOutcome.Done).value.isCustom)
        }

    @Test
    fun historyFlowReEmitsAfterAChange() =
        runTest {
            val seen = mutableListOf<Int>()
            val first = actions.history(10).first()
            seen += first.size
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            seen += actions.history(10).first().size
            assertEquals(listOf(0, 1), seen)
        }

    private fun buckets(
        pkg: String,
        bucket: StandbyBucket,
    ) {
        backend.buckets[pkg] = bucket.rawValue
    }
}
