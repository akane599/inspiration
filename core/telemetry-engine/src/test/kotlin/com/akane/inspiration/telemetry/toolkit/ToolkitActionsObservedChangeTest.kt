package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-01, F-13: a row belongs to its target's override chain when the values read back around it show the target
 * changed, whatever the write was reported as. A write the platform moved to a third value (reported not
 * effective) stays listed, resettable and restorable; a write of the value already there does not hide the
 * override it repeats.
 */
class ToolkitActionsObservedChangeTest {
    private val backend = FakeBackend()
    private val audit = FakeAudit()
    private val holds = FakeHolds()
    private var clock = 10_000L

    /** The phone booted at wall-clock 0 and the wall clock was never set: time since boot is [clock]. */
    private val actions = ToolkitActions(backend, audit, holds::hold, nowMs = { clock++ }, elapsedMs = { clock })

    private val active = StandbyBucket.ACTIVE.rawValue
    private val working = StandbyBucket.WORKING_SET.rawValue
    private val frequent = StandbyBucket.FREQUENT.rawValue
    private val rare = StandbyBucket.RARE.rawValue

    private suspend fun bucketOverride(): Override? =
        actions.activeOverrides().first().singleOrNull { it.kind == OverrideKind.BUCKET && it.pkg == PKG }

    /** [bucket] is requested; the platform clamps it to [landsOn] and the service reports what it read back. */
    private suspend fun clampedWrite(
        bucket: StandbyBucket,
        landsOn: Int,
    ): ToolkitOutcome<ToolkitActionRecord> {
        val before = backend.buckets[PKG] ?: active
        backend.scriptedWrite = ToolkitResult.failed(ToolkitCode.NOT_EFFECTIVE, before = "$before", after = "$landsOn")
        backend.afterWrite = { name ->
            if (name == "setBucket($PKG,${bucket.argv})") backend.buckets[PKG] = landsOn
        }
        val outcome = actions.execute(ToolkitAction.SetBucket(PKG, bucket), confirmed = true)
        backend.afterWrite = {}
        assertEquals("precondition: the write did change the device", landsOn, backend.buckets[PKG])
        return outcome
    }

    // ---- F-01: a write that landed on a third value ----------------------------------------

    @Test
    fun aWriteThatLandedOnAThirdValueIsReportedNotEffectiveButListedAndResetAllPutsItBack() =
        runTest {
            backend.buckets[PKG] = active

            val outcome = clampedWrite(StandbyBucket.RARE, landsOn = frequent)

            assertEquals(ToolkitOutcome.Failed(ToolkitCode.NOT_EFFECTIVE), outcome)
            val row = audit.rows.single()
            assertEquals(ToolkitActionStatus.FAILED, row.status)
            assertEquals("$active", row.beforeRaw)
            assertEquals("$frequent", row.afterRaw)

            val override = bucketOverride()
            assertNotNull("the device moved ACTIVE -> FREQUENT; rows=${audit.rows}", override)
            assertEquals(row.id, override?.actionId)
            assertEquals("$frequent", override?.currentRaw)
            assertEquals("$active", override?.previousRaw)

            backend.writes.clear()
            val report = actions.resetAll()
            assertEquals(listOf("setBucket($PKG,${StandbyBucket.ACTIVE.argv})"), backend.writes)
            assertTrue(report.single().outcome is ToolkitOutcome.Done)
            assertEquals(active, backend.buckets[PKG])
            assertEquals(ToolkitActionStatus.RESTORED, audit.byId(row.id).status)
            assertNull(bucketOverride())
        }

    @Test
    fun aWriteThatLandedOnAThirdValueCanBeRestoredFromHistory() =
        runTest {
            backend.buckets[PKG] = active
            clampedWrite(StandbyBucket.RARE, landsOn = frequent)
            val row = audit.rows.single()

            assertEquals(ToolkitPlan.Ready, actions.planRestore(row.id))
            backend.writes.clear()
            assertTrue(actions.restore(row.id) is ToolkitOutcome.Done)

            assertEquals(listOf("setBucket($PKG,${StandbyBucket.ACTIVE.argv})"), backend.writes)
            assertEquals(active, backend.buckets[PKG])
            assertEquals(ToolkitActionStatus.RESTORED, audit.byId(row.id).status)
        }

    @Test
    fun aWriteThatLandedOnAThirdValueOnTopOfAnOverrideKeepsTheChainOriginal() =
        runTest {
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))

            clampedWrite(StandbyBucket.RESTRICTED, landsOn = working)

            val override = bucketOverride()
            assertNotNull("ACTIVE -> RARE -> WORKING_SET is still ours; rows=${audit.rows}", override)
            assertEquals("$working", override?.currentRaw)
            assertEquals("$active", override?.previousRaw)
            backend.writes.clear()
            actions.resetAll()
            assertEquals(listOf("setBucket($PKG,${StandbyBucket.ACTIVE.argv})"), backend.writes)
            assertEquals(active, backend.buckets[PKG])
        }

    @Test
    fun aNotEffectiveWriteThatChangedNothingIsNoLinkAndLeavesTheChainIntact() =
        runTest {
            val first = (actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE)) as ToolkitOutcome.Done).value
            backend.scriptedWrite = ToolkitResult.failed(ToolkitCode.NOT_EFFECTIVE, before = "$rare", after = "$rare")

            val outcome = actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.FREQUENT))

            assertEquals(ToolkitOutcome.Failed(ToolkitCode.NOT_EFFECTIVE), outcome)
            val override = bucketOverride()
            assertEquals(first.id, override?.actionId)
            assertEquals("$active", override?.previousRaw)
            val failed = audit.rows.last()
            assertEquals(ToolkitActionStatus.FAILED, failed.status)
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.NOT_RESTORABLE), actions.restore(failed.id))
        }

    @Test
    fun aPromptlyReconciledWriteThatLandedOnAThirdValueStaysInItsChain() =
        runTest {
            // RARE was issued from ACTIVE five seconds ago, in this boot, and clamped; the app died before the reply.
            // A read-back this soon is the write's own outcome (a late one is not, see ToolkitActionsLateReconcileTest).
            backend.buckets[PKG] = frequent
            val id =
                audit.insert(
                    ToolkitActionRecord(
                        startedAt = clock - 5_000L,
                        kind = "bucket",
                        pkg = PKG,
                        op = "standby_bucket",
                        param = StandbyBucket.RARE.argv,
                        status = ToolkitActionStatus.PENDING,
                        beforeRaw = "$active",
                    ),
                )

            actions.reconcile()

            val row = audit.byId(id)
            assertEquals(ToolkitActionStatus.FAILED, row.status)
            assertEquals(ToolkitCode.NOT_EFFECTIVE, row.code)
            assertEquals("$frequent", row.afterRaw)
            val override = bucketOverride()
            assertEquals(id, override?.actionId)
            assertEquals("$active", override?.previousRaw)
            actions.resetAll()
            assertEquals(active, backend.buckets[PKG])
        }

    // ---- A-03: a restore that changed the target but did not reach the original ---------------

    private suspend fun restrictFromRare(): ToolkitActionRecord {
        backend.buckets[PKG] = rare
        return (actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RESTRICTED), confirmed = true) as ToolkitOutcome.Done).value
    }

    private suspend fun clampedRestore(
        actionId: Long,
        confirmed: Boolean = false,
    ): ToolkitActionRecord {
        val before = backend.buckets.getValue(PKG)
        backend.scriptedWrite = ToolkitResult.failed(ToolkitCode.NOT_EFFECTIVE, before = "$before", after = "$frequent")
        backend.afterWrite = { backend.buckets[PKG] = frequent }
        val outcome = actions.restore(actionId, confirmed)
        backend.afterWrite = {}
        assertEquals(ToolkitOutcome.Failed(ToolkitCode.NOT_EFFECTIVE), outcome)
        return audit.rows.last()
    }

    @Test
    fun aClampedRestoreKeepsItsObservedStateAndTypedOriginalForResetAll() =
        runTest {
            val initial = restrictFromRare()
            val partial = clampedRestore(initial.id)

            assertEquals(initial.id, partial.restoresId)
            assertEquals(ToolkitActionStatus.FAILED, partial.status)
            assertEquals(StandbyBucket.RARE.argv, partial.param)
            assertEquals("${StandbyBucket.RESTRICTED.rawValue}", partial.beforeRaw)
            assertEquals("$frequent", partial.afterRaw)
            val override = bucketOverride()
            assertEquals(partial.id, override?.actionId)
            assertEquals("$frequent", override?.currentRaw)
            assertEquals("$rare", override?.previousRaw)
            assertEquals(true, override?.restorable)
            assertEquals(ToolkitPlan.Ready, actions.planRestore(partial.id))

            backend.writes.clear()
            val report = actions.resetAll()

            assertEquals(partial.id, report.single().override.actionId)
            assertTrue(report.single().outcome is ToolkitOutcome.Done)
            assertEquals(listOf("setBucket($PKG,${StandbyBucket.RARE.argv})"), backend.writes)
            assertEquals(rare, backend.buckets[PKG])
            assertEquals(ToolkitActionStatus.RESTORED, audit.byId(partial.id).status)
            assertNull(bucketOverride())
        }

    @Test
    fun lostReplyAndUnverifiedRestoresReconcileToRecoverablePartialRestores() =
        runTest {
            for (lostReply in listOf(true, false)) {
                val initial = restrictFromRare()
                backend.scriptedWrite =
                    if (lostReply) {
                        ToolkitResult.failed(ToolkitCode.NOT_EFFECTIVE, before = initial.afterRaw, after = "$frequent")
                    } else {
                        ToolkitResult.unavailable(ToolkitCode.UNVERIFIED, before = initial.afterRaw)
                    }
                backend.afterWrite = { backend.buckets[PKG] = frequent }
                backend.unavailableAfterWrite = if (lostReply) GatewayUnavailable.NO_SERVICE else null

                assertTrue(actions.restore(initial.id) is ToolkitOutcome.Unavailable)
                val pending = audit.rows.last()
                assertEquals(ToolkitActionStatus.PENDING, pending.status)
                backend.afterWrite = {}
                backend.unavailableAfterWrite = null
                val recreated = ToolkitActions(backend, audit, holds::hold, nowMs = { clock++ }, elapsedMs = { clock })
                recreated.reconcile()

                assertEquals(ToolkitActionStatus.FAILED, audit.byId(pending.id).status)
                assertEquals("$frequent", audit.byId(pending.id).afterRaw)
                val override = recreated.activeOverrides().first().single()
                assertEquals(pending.id, override.actionId)
                assertEquals("$rare", override.previousRaw)
                assertEquals(ToolkitPlan.Ready, recreated.planRestore(pending.id))
                assertTrue(recreated.restore(pending.id) is ToolkitOutcome.Done)
                assertEquals(rare, backend.buckets[PKG])
                assertTrue(recreated.activeOverrides().first().isEmpty())
            }
        }

    @Test
    fun anExplicitlyConfirmedDivergentRestoreKeepsTheIntendedOriginalNotItsExternalBefore() =
        runTest {
            val initial = restrictFromRare()
            backend.buckets[PKG] = working
            backend.writes.clear()
            assertEquals(ToolkitPlan.NeedsConfirmation(ToolkitWarning.RESTORE_STATE_CHANGED), actions.planRestore(initial.id))
            assertEquals(
                ToolkitOutcome.Refused(ToolkitRefusal.NOT_CONFIRMED, ToolkitClientCode.NOT_CONFIRMED),
                actions.restore(initial.id),
            )
            assertTrue(backend.writes.isEmpty())

            val partial = clampedRestore(initial.id, confirmed = true)

            assertEquals("$working", partial.beforeRaw)
            assertEquals(StandbyBucket.RARE.argv, partial.param)
            assertEquals("$rare", bucketOverride()?.previousRaw)
            assertTrue(actions.restore(partial.id) is ToolkitOutcome.Done)
            assertEquals(rare, backend.buckets[PKG])
        }

    @Test
    fun externalChangesAfterAPartialRestoreAreNotAutomaticallyOwnedOrOverwritten() =
        runTest {
            val partial = clampedRestore(restrictFromRare().id)
            backend.buckets[PKG] = working
            backend.writes.clear()

            assertNull(bucketOverride())
            assertTrue(actions.resetAll().isEmpty())
            assertEquals(ToolkitPlan.NeedsConfirmation(ToolkitWarning.RESTORE_STATE_CHANGED), actions.planRestore(partial.id))
            assertEquals(
                ToolkitOutcome.Refused(ToolkitRefusal.NOT_CONFIRMED, ToolkitClientCode.NOT_CONFIRMED),
                actions.restore(partial.id),
            )
            assertTrue(backend.writes.isEmpty())
            assertEquals(working, backend.buckets[PKG])

            backend.buckets[PKG] = rare
            assertEquals(ToolkitPlan.Refused(ToolkitRefusal.ALREADY_RESTORED), actions.planRestore(partial.id))
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.ALREADY_RESTORED), actions.restore(partial.id, confirmed = true))
            assertTrue(backend.writes.isEmpty())
        }

    @Test
    fun noChangeAndUnknownAfterRestoresDoNotBecomePartialRestoreCandidates() =
        runTest {
            val initial = restrictFromRare()
            backend.scriptedWrite = ToolkitResult.failed(ToolkitCode.NOT_EFFECTIVE, before = initial.afterRaw, after = initial.afterRaw)
            assertTrue(actions.restore(initial.id) is ToolkitOutcome.Failed)
            val noChange = audit.rows.last()
            assertEquals(initial.id, bucketOverride()?.actionId)
            assertEquals(ToolkitPlan.Refused(ToolkitRefusal.NOT_RESTORABLE), actions.planRestore(noChange.id))

            backend.scriptedWrite = ToolkitResult.failed(ToolkitCode.PARSE, before = initial.afterRaw)
            backend.afterWrite = { backend.buckets[PKG] = frequent }
            assertTrue(actions.restore(initial.id) is ToolkitOutcome.Failed)
            backend.afterWrite = {}
            val unknown = audit.rows.last()
            backend.writes.clear()
            assertNull(bucketOverride())
            assertTrue(actions.resetAll().isEmpty())
            assertEquals(ToolkitPlan.Refused(ToolkitRefusal.NOT_RESTORABLE), actions.planRestore(unknown.id))
            assertTrue(backend.writes.isEmpty())
            assertEquals(frequent, backend.buckets[PKG])
        }

    @Test
    fun aFailedRestoreThatReachedTheOriginalIsAlreadyRestoredNotAnActiveOverride() =
        runTest {
            val initial = restrictFromRare()
            backend.scriptedWrite = ToolkitResult.failed(ToolkitCode.NOT_EFFECTIVE, before = initial.afterRaw, after = "$rare")
            backend.afterWrite = { backend.buckets[PKG] = rare }
            assertTrue(actions.restore(initial.id) is ToolkitOutcome.Failed)
            backend.afterWrite = {}
            val restored = audit.rows.last()
            backend.writes.clear()

            assertNull(bucketOverride())
            assertTrue(actions.resetAll().isEmpty())
            assertEquals(ToolkitPlan.Refused(ToolkitRefusal.ALREADY_RESTORED), actions.planRestore(restored.id))
            assertTrue(backend.writes.isEmpty())
        }

    @Test
    fun aPartialAppOpRestoreUsesItsTypedAllowTargetForResetAll() =
        runTest {
            val op = AppOp.RUN_IN_BACKGROUND
            val initial = (actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE)) as ToolkitOutcome.Done).value
            backend.scriptedWrite = ToolkitResult.failed(ToolkitCode.NOT_EFFECTIVE, before = "ignore", after = "foreground")
            backend.afterWrite = { backend.device(PKG)[op] = "foreground" }
            assertTrue(actions.restore(initial.id) is ToolkitOutcome.Failed)
            backend.afterWrite = {}
            val partial = audit.rows.last()

            val override = actions.activeOverrides().first().single()
            assertEquals(partial.id, override.actionId)
            assertEquals("foreground", override.currentRaw)
            assertEquals("allow", override.previousRaw)
            assertEquals(ToolkitPlan.Ready, actions.planRestore(partial.id))
            backend.writes.clear()
            val report = actions.resetAll()
            assertEquals(partial.id, report.single().override.actionId)
            assertTrue(report.single().outcome is ToolkitOutcome.Done)
            assertEquals(listOf("setAppOp($PKG,${op.argv},allow)"), backend.writes)
            assertEquals("allow", backend.device(PKG)[op])
            assertTrue(actions.activeOverrides().first().isEmpty())
        }

    @Test
    fun anObservedUnwritableAppOpOriginalRemainsNotRestorable() =
        runTest {
            val op = AppOp.RUN_IN_BACKGROUND
            backend.device(PKG)[op] = "foreground"
            val row = (actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE)) as ToolkitOutcome.Done).value
            backend.writes.clear()

            val override = actions.activeOverrides().first().single()
            assertEquals("foreground", override.previousRaw)
            assertEquals(false, override.restorable)
            assertEquals(ToolkitPlan.Refused(ToolkitRefusal.NOT_RESTORABLE), actions.planRestore(row.id))
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.NOT_RESTORABLE), actions.restore(row.id, confirmed = true))
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.NOT_RESTORABLE), actions.resetAll().single().outcome)
            assertTrue(backend.writes.isEmpty())
            assertEquals("ignore", backend.device(PKG)[op])
        }

    @Test
    fun aPartialDozeRestorePreservesAnUnsetTypedOriginalDespiteADivergentBefore() =
        runTest {
            val initial = (actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED)) as ToolkitOutcome.Done).value
            backend.dozeRaw = "inactive_to=77"
            backend.scriptedWrite = ToolkitResult.failed(ToolkitCode.NOT_EFFECTIVE, before = backend.dozeRaw, after = "inactive_to=33")
            backend.afterWrite = { backend.dozeRaw = "inactive_to=33" }
            assertTrue(actions.restore(initial.id, confirmed = true) is ToolkitOutcome.Failed)
            backend.afterWrite = {}
            val partial = audit.rows.last()

            val override = actions.activeOverrides().first().single()
            assertEquals(partial.id, override.actionId)
            assertEquals("inactive_to=33", override.currentRaw)
            assertNull(override.previousRaw)
            assertEquals(ToolkitPlan.Ready, actions.planRestore(partial.id))
            assertTrue(actions.resetAll().single().outcome is ToolkitOutcome.Done)
            assertNull(backend.dozeRaw)
            assertTrue(actions.activeOverrides().first().isEmpty())
        }

    // ---- F-13: a write of the value already there --------------------------------------------

    @Test
    fun repeatingTheSameBucketKeepsTheOverrideListedAndResettable() =
        runTest {
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            assertEquals("precondition: one listed override", 1, actions.activeOverrides().first().size)

            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))

            assertEquals(rare, backend.buckets[PKG])
            val override = bucketOverride()
            assertNotNull("the device is still RARE; rows=${audit.rows}", override)
            assertEquals("$active", override?.previousRaw)
            backend.writes.clear()
            actions.resetAll()
            assertEquals(listOf("setBucket($PKG,${StandbyBucket.ACTIVE.argv})"), backend.writes)
            assertNull(bucketOverride())
        }

    @Test
    fun repeatingTheSameAppOpModeKeepsTheOverrideListed() =
        runTest {
            val op = AppOp.RUN_IN_BACKGROUND
            actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE))
            actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE))

            assertEquals("ignore", backend.device(PKG)[op])
            val override = actions.activeOverrides().first().singleOrNull { it.kind == OverrideKind.APP_OP && it.appOp == op }
            assertNotNull("rows=${audit.rows}", override)
            assertEquals("allow", override?.previousRaw)
        }

    @Test
    fun repeatingTheSameDozeProfileKeepsTheOverrideListedAndResetUnsetsIt() =
        runTest {
            actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))
            actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))

            val override = actions.activeOverrides().first().singleOrNull { it.kind == OverrideKind.DOZE }
            assertNotNull("rows=${audit.rows}", override)
            assertNull(override?.previousRaw)
            actions.resetAll()
            assertNull(backend.dozeRaw)
        }

    @Test
    fun aSameValueWriteWithNoEarlierChangeIsNoOverride() =
        runTest {
            backend.buckets[PKG] = rare

            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))

            assertTrue(actions.activeOverrides().first().isEmpty())
            backend.writes.clear()
            assertTrue(actions.resetAll().isEmpty())
            assertTrue(backend.writes.isEmpty())
        }
}
