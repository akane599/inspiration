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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-01: a write left PENDING (its reply was lost, or the app died mid-call) is credited with what a read-back finds
 * only while that read-back is prompt. Later, the value read may be anything the platform, the user or another tool
 * set in the meantime, and a write that never ran looks the same. Such a row is observed, not attributed: FAILED with
 * [ToolkitClientCode.UNATTRIBUTED] and the value read as its `after`.
 * - When that value is what the write asked for, the change is probably ours: it is listed as
 *   [ToolkitRefusal.UNATTRIBUTED], left out of Reset all, and restored only after [ToolkitWarning.UNATTRIBUTED_CHANGE].
 * - Any other value is neither listed nor restorable, but it still shows the change below it is gone, so it hides that.
 * - Either way it stays a link of its chain, so a newer change of ours walks through it to the original (B03).
 * - A late restore that reads its original is DONE: whoever put the original back, that chain is closed.
 *
 * This process times its own rows from the write's reply on the clock since boot; a row an earlier process left is
 * timed from its insert on the wall clock, within the time since boot. A read-back seconds after the reply keeps its
 * attribution.
 */
class ToolkitActionsLateReconcileTest {
    private val backend = FakeBackend()
    private val audit = FakeAudit()
    private val holds = FakeHolds()

    /** The wall clock and the time since boot (elapsedRealtime); [pass] moves both, as real time does. */
    private var wall = 1_800_000_000_000L
    private var sinceBoot = 3 * HOUR_MS
    private val actions = ToolkitActions(backend, audit, holds::hold, nowMs = { wall }, elapsedMs = { sinceBoot })

    private val active = StandbyBucket.ACTIVE.rawValue
    private val workingSet = StandbyBucket.WORKING_SET.rawValue
    private val frequent = StandbyBucket.FREQUENT.rawValue
    private val rare = StandbyBucket.RARE.rawValue
    private val restricted = StandbyBucket.RESTRICTED.rawValue

    private fun pass(ms: Long) {
        wall += ms
        sinceBoot += ms
    }

    /** [action] is sent, but its reply is lost and the write does not commit: the device is untouched. */
    private suspend fun lostWrite(action: ToolkitAction): ToolkitActionRecord {
        backend.writeUnavailable = GatewayUnavailable.NO_SERVICE
        assertEquals(ToolkitOutcome.Unavailable(ToolkitUnavailable.NO_SERVICE), actions.execute(action, confirmed = true))
        backend.writeUnavailable = null
        return audit.rows.last().also { assertEquals(ToolkitActionStatus.PENDING, it.status) }
    }

    /** [action] commits, but its reply is lost on the way back (the shell-UID service still finished it). */
    private suspend fun committedLostWrite(action: ToolkitAction): ToolkitActionRecord {
        backend.unavailableAfterWrite = GatewayUnavailable.NO_SERVICE
        assertEquals(ToolkitOutcome.Unavailable(ToolkitUnavailable.NO_SERVICE), actions.execute(action, confirmed = true))
        backend.unavailableAfterWrite = null
        return audit.rows.last().also { assertEquals(ToolkitActionStatus.PENDING, it.status) }
    }

    /** The restore of [original] is sent; when [commits] it also takes effect. Either way its reply is lost. */
    private suspend fun lostRestore(
        original: ToolkitActionRecord,
        commits: Boolean,
    ): ToolkitActionRecord {
        if (commits) backend.unavailableAfterWrite = GatewayUnavailable.NO_SERVICE else backend.writeUnavailable = GatewayUnavailable.NO_SERVICE
        assertEquals(ToolkitOutcome.Unavailable(ToolkitUnavailable.NO_SERVICE), actions.restore(original.id, confirmed = true))
        backend.unavailableAfterWrite = null
        backend.writeUnavailable = null
        return audit.rows.last().also {
            assertEquals(ToolkitActionStatus.PENDING, it.status)
            assertEquals(original.id, it.restoresId)
        }
    }

    /** Shizuku stays down for [ms] (not restarted after a reboot, say): a reconcile meanwhile cannot read anything. */
    private suspend fun outage(
        row: ToolkitActionRecord,
        ms: Long,
    ) {
        backend.unavailable = GatewayUnavailable.NO_SERVICE
        actions.reconcile()
        assertEquals(ToolkitActionStatus.PENDING, audit.byId(row.id).status)
        pass(ms)
        backend.unavailable = null
    }

    /** A bucket row an earlier process inserted at [startedAt] and never settled (it was killed mid-write). */
    private suspend fun orphanedBucketWrite(
        startedAt: Long,
        bucket: StandbyBucket = StandbyBucket.RARE,
    ): ToolkitActionRecord {
        val record =
            ToolkitActionRecord(
                startedAt = startedAt,
                kind = ToolkitAuditLog.KIND_BUCKET,
                pkg = PKG,
                op = "standby_bucket",
                param = bucket.argv,
                status = ToolkitActionStatus.PENDING,
                beforeRaw = "$active",
            )
        return record.copy(id = audit.insert(record))
    }

    /** [row] settled as observed, not attributed: FAILED, [ToolkitClientCode.UNATTRIBUTED], with [after] read late. */
    private fun assertObserved(
        row: ToolkitActionRecord,
        after: Any?,
    ) {
        val settled = audit.byId(row.id)
        assertEquals("settled=$settled", ToolkitActionStatus.FAILED, settled.status)
        assertEquals("settled=$settled", ToolkitClientCode.UNATTRIBUTED, settled.code)
        assertEquals("settled=$settled", after?.toString(), settled.afterRaw)
        assertTrue("settled=$settled", ToolkitAuditLog.unattributed(settled))
    }

    /** Nothing lists, resets or restores the value the device has now, and [rows] are not restorable either. */
    private suspend fun assertNothingIsListedOrWritten(vararg rows: ToolkitActionRecord) {
        val overrides = actions.activeOverrides().first()
        backend.writes.clear()
        val report = actions.resetAll()
        assertTrue("overrides=$overrides", overrides.isEmpty())
        assertTrue("report=$report", report.isEmpty())
        for (row in rows) {
            assertEquals(ToolkitPlan.Refused(ToolkitRefusal.NOT_RESTORABLE), actions.planRestore(row.id))
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.NOT_RESTORABLE), actions.restore(row.id, confirmed = true))
        }
        assertTrue("nothing may be written over a value that is not ours: ${backend.writes}", backend.writes.isEmpty())
    }

    /**
     * [row] is listed as a probable change of ours, from [previous] to [current], and Reset all leaves it alone: it is
     * reported as not restored, and nothing is written.
     */
    private suspend fun assertListedAsUnattributedAndLeftOutOfResetAll(
        row: ToolkitActionRecord,
        current: String,
        previous: String,
    ): Override {
        val override = actions.activeOverrides().first().single()
        assertEquals(row.id, override.actionId)
        assertEquals(ToolkitRefusal.UNATTRIBUTED, override.unverified)
        assertEquals(current, override.currentRaw)
        assertEquals(previous, override.previousRaw)
        assertTrue("its restore can still be asked for, after a warning", override.restorable)
        backend.writes.clear()
        val report = actions.resetAll()
        assertEquals(listOf(ResetItem(override, ToolkitOutcome.Refused(ToolkitRefusal.UNATTRIBUTED))), report)
        assertTrue("Reset all must not write over a value it cannot attribute: ${backend.writes}", backend.writes.isEmpty())
        return override
    }

    // ---- late, at another value: neither listed nor restorable --------------------------------

    @Test
    fun aLostWriteReconciledADayLaterOverADriftedBucketIsNeitherListedNorWritten() =
        runTest {
            backend.buckets[PKG] = active
            val row = lostWrite(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            assertEquals(active, backend.buckets[PKG])

            outage(row, DAY_MS)
            backend.buckets[PKG] = frequent // the platform's own decay
            actions.reconcile() // Shizuku Ready

            assertObserved(row, after = frequent)
            assertNothingIsListedOrWritten(row)
            assertEquals(frequent, backend.buckets[PKG])
        }

    @Test
    fun aLostDozeWriteReconciledLaterOverAnotherToolsValueIsNeitherListedNorWritten() =
        runTest {
            val row = lostWrite(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))
            assertNull(backend.dozeRaw)

            outage(row, 2 * HOUR_MS)
            backend.dozeRaw = "inactive_to=77" // another tool's custom device_idle_constants
            actions.reconcile()

            assertObserved(row, after = "inactive_to=77")
            assertNothingIsListedOrWritten(row)
            assertEquals("inactive_to=77", backend.dozeRaw)
        }

    @Test
    fun aRowOfAnEarlierBootIsLateEvenWhenTheWallClockSaysSeconds() =
        runTest {
            // Written 40 s ago by the wall clock, but the phone booted 15 s ago: that write ran before the reboot.
            sinceBoot = 15_000L
            val row = orphanedBucketWrite(startedAt = wall - 40_000L)
            backend.buckets[PKG] = frequent

            actions.reconcile()

            assertObserved(row, after = frequent)
            assertNothingIsListedOrWritten(row)
            assertEquals(frequent, backend.buckets[PKG])
        }

    @Test
    fun aRowDatedAfterNowIsLate() =
        runTest {
            // An earlier process wrote it; the wall clock has since been set back past it.
            val row = orphanedBucketWrite(startedAt = wall + 30_000L)
            backend.buckets[PKG] = frequent

            actions.reconcile()

            assertObserved(row, after = frequent)
            assertNothingIsListedOrWritten(row)
        }

    @Test
    fun aWallClockSetBackOverALongOutageDoesNotMakeALateReadBackOfThisProcessPrompt() =
        runTest {
            backend.buckets[PKG] = active
            val row = lostWrite(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))

            outage(row, DAY_MS)
            wall -= DAY_MS // by the wall clock alone, the write is seconds old
            backend.buckets[PKG] = frequent
            actions.reconcile()

            // A prompt read-back would have kept FREQUENT as a clamp of ours, listed and reset to ACTIVE.
            assertObserved(row, after = frequent)
            assertNothingIsListedOrWritten(row)
        }

    // ---- late, at the intended value: observed, not attributed --------------------------------

    @Test
    fun aLostWriteReadBackADayLaterAtTheIntendedValueIsListedButLeftOutOfResetAll() =
        runTest {
            backend.buckets[PKG] = active
            val row = lostWrite(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))

            outage(row, DAY_MS)
            backend.buckets[PKG] = rare // the very value the write asked for: ours, or reached by itself
            actions.reconcile()

            assertObserved(row, after = rare)
            assertListedAsUnattributedAndLeftOutOfResetAll(row, current = "$rare", previous = "$active")
            assertEquals(rare, backend.buckets[PKG])
        }

    @Test
    fun aCommittedAppOpWriteWhoseReplyWasLostStaysListedWhenReadBackFiveMinutesLater() =
        runTest {
            val op = AppOp.RUN_ANY_IN_BACKGROUND
            val row = committedLostWrite(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE))
            assertEquals("ignore", backend.device(PKG)[op])

            // While Shizuku is down the unsettled write is listed as unverified ...
            backend.unavailable = GatewayUnavailable.NO_SERVICE
            assertEquals(ToolkitRefusal.READ_FAILED, actions.activeOverrides().first().single().unverified)
            pass(5 * 60_000L)
            backend.unavailable = null
            actions.reconcile()

            // ... and after a late read-back it still is: the restriction is on the device, whoever put it there.
            assertObserved(row, after = "ignore")
            assertListedAsUnattributedAndLeftOutOfResetAll(row, current = "ignore", previous = "allow")
            assertEquals("ignore", backend.device(PKG)[op])
        }

    @Test
    fun anUnattributedChangeIsRestoredOnItsOwnOnlyAfterAnExplicitWarning() =
        runTest {
            val op = AppOp.RUN_ANY_IN_BACKGROUND
            val row = committedLostWrite(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE))
            outage(row, 10 * 60_000L)
            actions.reconcile()
            assertObserved(row, after = "ignore")
            backend.writes.clear()

            assertEquals(ToolkitPlan.NeedsConfirmation(ToolkitWarning.UNATTRIBUTED_CHANGE), actions.planRestore(row.id))
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.NOT_CONFIRMED, ToolkitClientCode.NOT_CONFIRMED), actions.restore(row.id))
            assertTrue("an unconfirmed restore writes nothing: ${backend.writes}", backend.writes.isEmpty())

            val restored = actions.restore(row.id, confirmed = true)

            assertTrue("$restored", restored is ToolkitOutcome.Done)
            assertEquals(listOf("setAppOp($PKG,${op.argv},allow)"), backend.writes)
            assertEquals("allow", backend.device(PKG)[op])
            assertEquals(ToolkitActionStatus.RESTORED, audit.byId(row.id).status)
            assertTrue(actions.activeOverrides().first().isEmpty())
        }

    @Test
    fun aCommittedWriteReadBackLateAndThenUndoneByHandLeavesNothingForResetAll() =
        runTest {
            // B03 with a gap: RESTRICTED commits, the service's own read-back fails, and the row stays PENDING.
            backend.buckets[PKG] = active
            backend.onWrite = {
                backend.buckets[PKG] = restricted
                backend.onWrite = {}
            }
            backend.scriptedWrite = ToolkitResult.unavailable(ToolkitCode.UNVERIFIED, before = "$active")
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RESTRICTED), confirmed = true)
            val first = audit.rows.single()
            assertEquals(ToolkitActionStatus.PENDING, first.status)

            // Two minutes later the user switches the app back to ACTIVE in Inspiration: the first row settles late.
            pass(2 * 60_000L)
            val second = actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.ACTIVE), confirmed = true)

            assertTrue("$second", second is ToolkitOutcome.Done)
            assertObserved(first, after = restricted)
            assertNothingIsListedOrWritten()
            assertEquals(active, backend.buckets[PKG])
        }

    // ---- late restores ------------------------------------------------------------------------

    @Test
    fun aRestoreReadBackADayLaterAtItsOriginalClosesItsChainSoALaterDecayIsNotOurs() =
        runTest {
            backend.buckets[PKG] = active
            assertTrue(actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE), confirmed = true) is ToolkitOutcome.Done)
            val original = audit.rows.single()
            val restore = lostRestore(original, commits = true)
            assertEquals(active, backend.buckets[PKG])

            outage(restore, DAY_MS)
            actions.reconcile()

            assertEquals(ToolkitActionStatus.DONE, audit.byId(restore.id).status)
            assertEquals(ToolkitActionStatus.RESTORED, audit.byId(original.id).status)
            backend.buckets[PKG] = rare // the platform decays the app back to RARE by itself
            assertNothingIsListedOrWritten(restore)
            assertEquals(ToolkitPlan.Refused(ToolkitRefusal.ALREADY_RESTORED), actions.planRestore(original.id))
            assertEquals(rare, backend.buckets[PKG])
        }

    @Test
    fun aRestoreReadBackLateAtAThirdValueHidesTheChangeItWasUndoingWithoutClaimingTheValue() =
        runTest {
            backend.buckets[PKG] = active
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE), confirmed = true)
            val original = audit.rows.single()
            val restore = lostRestore(original, commits = false)

            outage(restore, DAY_MS)
            backend.buckets[PKG] = frequent
            actions.reconcile()

            assertObserved(restore, after = frequent)
            assertNothingIsListedOrWritten(restore)
            // The read-back showed RARE gone; RARE again later is the platform's, not the original change's.
            backend.buckets[PKG] = rare
            assertNothingIsListedOrWritten(restore)
            assertEquals(rare, backend.buckets[PKG])
        }

    @Test
    fun aLateRowOverAnOpenChangeHidesItOnceItsReadBackShowedThatChangeGone() =
        runTest {
            backend.buckets[PKG] = active
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE), confirmed = true)
            val row = lostWrite(ToolkitAction.SetBucket(PKG, StandbyBucket.FREQUENT))

            outage(row, DAY_MS)
            backend.buckets[PKG] = workingSet
            actions.reconcile()

            assertObserved(row, after = workingSet)
            backend.buckets[PKG] = rare // back at the older change's value, by the platform's doing
            assertNothingIsListedOrWritten(row)
        }

    @Test
    fun aLateLegacyDefaultRestoreIsNotTakenAsOursLikeARestoreThatReadsItsOriginal() =
        runTest {
            val op = AppOp.RUN_ANY_IN_BACKGROUND
            actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE), confirmed = true)
            val original = audit.rows.single()
            // An older release's restore wrote the stored mode `default`, then died before it settled the row.
            val legacy =
                original.copy(
                    id = 0L,
                    startedAt = wall - DAY_MS,
                    endedAt = null,
                    param = AppOpMode.DEFAULT.argv,
                    status = ToolkitActionStatus.PENDING,
                    code = null,
                    beforeRaw = "ignore",
                    afterRaw = null,
                    restoresId = original.id,
                )
            val restore = legacy.copy(id = audit.insert(legacy))
            backend.device(PKG)[op] = "default" // still restricted, whoever set it

            actions.reconcile()

            assertObserved(restore, after = "default")
            assertEquals(ToolkitActionStatus.DONE, audit.byId(original.id).status)
            assertListedAsUnattributedAndLeftOutOfResetAll(restore, current = "default", previous = "allow")
            assertEquals("default", backend.device(PKG)[op])
        }

    // ---- prompt: the read-back is the write's own outcome ----------------------------------------

    @Test
    fun aReplyLostSecondsAgoKeepsItsClampedValueBeforeTheNextToolkitUse() =
        runTest {
            backend.buckets[PKG] = active
            // RARE commits, the platform clamps it to FREQUENT, and the reply is lost on the way back.
            backend.afterWrite = { backend.buckets[PKG] = frequent }
            backend.unavailableAfterWrite = GatewayUnavailable.NO_SERVICE
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE), confirmed = true)
            backend.afterWrite = {}
            backend.unavailableAfterWrite = null
            val row = audit.rows.single()
            assertEquals(ToolkitActionStatus.PENDING, row.status)

            pass(20_000L)
            val override = actions.activeOverrides().first().single() // settles the row first (reconcileTargets)

            val settled = audit.byId(row.id)
            assertEquals(ToolkitActionStatus.FAILED, settled.status)
            assertEquals(ToolkitCode.NOT_EFFECTIVE, settled.code)
            assertEquals("$frequent", settled.afterRaw)
            assertEquals(row.id, override.actionId)
            assertEquals("$active", override.previousRaw)
            assertNull(override.unverified)
            backend.writes.clear()
            assertTrue(actions.resetAll().single().outcome is ToolkitOutcome.Done)
            assertEquals(listOf("setBucket($PKG,${StandbyBucket.ACTIVE.argv})"), backend.writes)
            assertEquals(active, backend.buckets[PKG])
        }

    @Test
    fun aWriteOfAProcessKilledMidCallAndReconciledSecondsLaterInTheSameBootIsStillOurs() =
        runTest {
            // The write committed, the app was killed before the reply, and Shizuku was Ready again 30 s later.
            val row = orphanedBucketWrite(startedAt = wall - 30_000L)
            backend.buckets[PKG] = rare

            actions.reconcile()

            val settled = audit.byId(row.id)
            assertEquals(ToolkitActionStatus.DONE, settled.status)
            assertEquals("$rare", settled.afterRaw)
            val override = actions.activeOverrides().first().single()
            assertEquals(row.id, override.actionId)
            assertEquals("$active", override.previousRaw)
            assertNull(override.unverified)
            backend.writes.clear()
            assertTrue(actions.resetAll().single().outcome is ToolkitOutcome.Done)
            assertEquals(listOf("setBucket($PKG,${StandbyBucket.ACTIVE.argv})"), backend.writes)
        }

    @Test
    fun aWallClockSetBackRightAfterTheReplyDoesNotMoveTheWindowOfThisProcess() =
        runTest {
            backend.buckets[PKG] = active
            val row = committedLostWrite(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))

            pass(5_000L)
            wall -= 60_000L // by the wall clock the write now looks dated after its read-back
            actions.reconcile()

            assertEquals(ToolkitActionStatus.DONE, audit.byId(row.id).status)
            assertNull(actions.activeOverrides().first().single().unverified)
        }

    @Test
    fun aWallClockSetForwardRightAfterTheReplyDoesNotMoveTheWindowOfThisProcess() =
        runTest {
            backend.buckets[PKG] = active
            val row = committedLostWrite(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))

            pass(5_000L)
            wall += DAY_MS
            actions.reconcile()

            assertEquals(ToolkitActionStatus.DONE, audit.byId(row.id).status)
        }

    @Test
    fun theReadBackIsTimedWhenItStartsNotWhenASlowReadReturns() =
        runTest {
            backend.buckets[PKG] = active
            val row = committedLostWrite(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))

            pass(50_000L)
            backend.onRead = { pass(20_000L) } // the read-back's own read takes 20 s
            actions.reconcile()
            backend.onRead = {}

            assertEquals(ToolkitActionStatus.DONE, audit.byId(row.id).status)
        }

    // ---- the window's edges, on each clock -------------------------------------------------------

    /** A write of this process whose call took [writeMs] before its reply was lost, read back [replyToReadMs] after it. */
    private suspend fun slowLostWrite(
        writeMs: Long,
        replyToReadMs: Long,
    ): ToolkitActionRecord {
        backend.buckets[PKG] = active
        backend.afterWrite = { pass(writeMs) }
        val row = committedLostWrite(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
        backend.afterWrite = {}
        pass(replyToReadMs)
        actions.reconcile()
        return audit.byId(row.id)
    }

    @Test
    fun aRowOfThisProcessIsTimedFromItsReplyAndIsStillPromptAtTheWindowEdge() =
        runTest {
            // The call itself took 50 s (a slow protection gate); only reply to read-back counts.
            val settled = slowLostWrite(writeMs = 50_000L, replyToReadMs = 60_000L)

            assertEquals("settled=$settled", ToolkitActionStatus.DONE, settled.status)
            assertFalse(ToolkitAuditLog.unattributed(settled))
        }

    @Test
    fun aRowOfThisProcessReadBackOneMillisecondPastTheWindowIsLate() =
        runTest {
            val settled = slowLostWrite(writeMs = 0L, replyToReadMs = 60_001L)

            assertObserved(settled, after = rare)
        }

    @Test
    fun aRowOfAnEarlierProcessIsStillPromptAtTheWindowEdgeOfTheWallClock() =
        runTest {
            val row = orphanedBucketWrite(startedAt = wall - 60_000L)
            backend.buckets[PKG] = rare

            actions.reconcile()

            assertEquals(ToolkitActionStatus.DONE, audit.byId(row.id).status)
        }

    @Test
    fun aRowOfAnEarlierProcessReadBackOneMillisecondPastTheWindowIsLate() =
        runTest {
            val row = orphanedBucketWrite(startedAt = wall - 60_001L)
            backend.buckets[PKG] = rare

            actions.reconcile()

            assertObserved(row, after = rare)
        }

    private companion object {
        const val HOUR_MS = 60L * 60_000L
        const val DAY_MS = 24L * HOUR_MS
    }
}
