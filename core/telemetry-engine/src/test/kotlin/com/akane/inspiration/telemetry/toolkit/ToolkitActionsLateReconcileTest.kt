package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-01: a write left PENDING (its reply was lost, or the app died mid-call) is credited with what a read-back finds
 * only while that read-back is prompt. Later, the value read may be anything the platform, the user or another tool
 * set in the meantime, and a write that never ran looks the same. Such a row settles as unverified with no `after`,
 * like a write known to be unsent (N-07): it is not listed, and neither Reset all nor Restore writes over the value.
 * A read-back seconds after the write is still the write's own outcome and keeps its attribution.
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
    private val frequent = StandbyBucket.FREQUENT.rawValue
    private val rare = StandbyBucket.RARE.rawValue

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

    /** [row] settled as not ours, and nothing lists, resets or restores the value the device now has. */
    private suspend fun assertSettledAsNotOursAndNothingIsWritten(row: ToolkitActionRecord) {
        val settled = audit.byId(row.id)
        val overrides = actions.activeOverrides().first()
        backend.writes.clear()
        val report = actions.resetAll()
        val plan = actions.planRestore(row.id)
        val restore = actions.restore(row.id, confirmed = true)

        assertEquals("settled=$settled", ToolkitActionStatus.FAILED, settled.status)
        assertEquals("settled=$settled", ToolkitClientCode.UNVERIFIED, settled.code)
        assertNull("a late read-back is not the write's after: settled=$settled", settled.afterRaw)
        assertTrue("overrides=$overrides", overrides.isEmpty())
        assertTrue("report=$report", report.isEmpty())
        assertEquals(ToolkitPlan.Refused(ToolkitRefusal.NOT_RESTORABLE), plan)
        assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.NOT_RESTORABLE), restore)
        assertTrue("nothing may be written over a value that is not ours: ${backend.writes}", backend.writes.isEmpty())
    }

    // ---- late: the read-back is not the write's -----------------------------------------------

    @Test
    fun aLostWriteReconciledADayLaterIsNotClaimedWhenTheBucketDriftedDuringAnOutage() =
        runTest {
            backend.buckets[PKG] = active
            val row = lostWrite(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            assertEquals(active, backend.buckets[PKG])

            outage(row, DAY_MS)
            backend.buckets[PKG] = frequent // the platform's own decay
            actions.reconcile() // Shizuku Ready

            assertSettledAsNotOursAndNothingIsWritten(row)
            assertEquals(frequent, backend.buckets[PKG])
        }

    @Test
    fun aLostWriteReconciledADayLaterIsNotClaimedWhenTheBucketDecayedToTheIntendedValue() =
        runTest {
            backend.buckets[PKG] = active
            val row = lostWrite(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))

            outage(row, DAY_MS)
            backend.buckets[PKG] = rare // the very value the write asked for, reached by itself
            actions.reconcile()

            assertSettledAsNotOursAndNothingIsWritten(row)
            assertEquals(rare, backend.buckets[PKG])
        }

    @Test
    fun aLostDozeWriteReconciledLaterDoesNotClaimAValueAnotherToolWrote() =
        runTest {
            val row = lostWrite(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))
            assertNull(backend.dozeRaw)

            outage(row, 2 * HOUR_MS)
            backend.dozeRaw = "inactive_to=77" // another tool's custom device_idle_constants
            actions.reconcile()

            assertSettledAsNotOursAndNothingIsWritten(row)
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

            assertSettledAsNotOursAndNothingIsWritten(row)
            assertEquals(frequent, backend.buckets[PKG])
        }

    @Test
    fun aRowDatedAfterNowIsLate() =
        runTest {
            // An earlier process wrote it; the wall clock has since been set back past it.
            val row = orphanedBucketWrite(startedAt = wall + 30_000L)
            backend.buckets[PKG] = frequent

            actions.reconcile()

            assertSettledAsNotOursAndNothingIsWritten(row)
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

            assertSettledAsNotOursAndNothingIsWritten(row)
        }

    @Test
    fun aWallClockSetBackRightAfterTheWriteMakesItsReadBackLate() =
        runTest {
            backend.buckets[PKG] = active
            val row = lostWrite(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))

            pass(5_000L)
            wall -= 60_000L // the write now looks dated after the read-back: fail safe
            backend.buckets[PKG] = frequent
            actions.reconcile()

            assertSettledAsNotOursAndNothingIsWritten(row)
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
            backend.writes.clear()
            assertTrue(actions.resetAll().single().outcome is ToolkitOutcome.Done)
            assertEquals(listOf("setBucket($PKG,${StandbyBucket.ACTIVE.argv})"), backend.writes)
        }

    private companion object {
        const val HOUR_MS = 60L * 60_000L
        const val DAY_MS = 24L * HOUR_MS
    }
}
