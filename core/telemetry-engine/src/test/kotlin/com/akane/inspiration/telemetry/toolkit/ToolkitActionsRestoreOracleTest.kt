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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SQ-104 (SQ-103 m1/m2): `restore` and `planRestore` agree (ALREADY_RESTORED beats the confirmation), and the
 * audit log, a failed read-back and a race inside `resetAll` are asserted, not just the writes.
 */
class ToolkitActionsRestoreOracleTest {
    private val backend = FakeBackend()
    private val audit = FakeAudit()
    private val holds = FakeHolds()
    private var clock = 10_000L
    private val actions = ToolkitActions(backend, audit, holds::hold, nowMs = { clock++ })

    private suspend fun bucket(bucket: StandbyBucket): ToolkitActionRecord =
        (actions.execute(ToolkitAction.SetBucket(PKG, bucket), confirmed = true) as ToolkitOutcome.Done).value

    private suspend fun appOp(op: AppOp, mode: AppOpMode): ToolkitActionRecord =
        (actions.execute(ToolkitAction.SetAppOp(PKG, op, mode), confirmed = true) as ToolkitOutcome.Done).value

    private val alreadyRestored = ToolkitOutcome.Refused(ToolkitRefusal.ALREADY_RESTORED)

    /** Plan and both confirmation spellings of restore agree: ALREADY_RESTORED, no write, no audit row, row untouched. */
    private suspend fun assertAlreadyRestoredEverywhere(row: ToolkitActionRecord) {
        backend.writes.clear()
        val rowsBefore = audit.rows.size
        assertEquals(ToolkitPlan.Refused(ToolkitRefusal.ALREADY_RESTORED), actions.planRestore(row.id))
        assertEquals(alreadyRestored, actions.restore(row.id, confirmed = false))
        assertEquals(alreadyRestored, actions.restore(row.id, confirmed = true))
        assertTrue("no write expected: ${backend.writes}", backend.writes.isEmpty())
        assertEquals("no audit row expected", rowsBefore, audit.rows.size)
        assertEquals(ToolkitActionStatus.DONE, audit.byId(row.id).status)
    }

    // ---- m1: ALREADY_RESTORED outranks NOT_CONFIRMED ---------------------------------------

    @Test
    fun restoreWithoutConfirmationIsAlreadyRestoredWhenTheOriginalIsARestrictedBucketAndTheTargetReadsIt() = runTest {
        backend.buckets[PKG] = StandbyBucket.RESTRICTED.rawValue // the user's own state before this app touched it
        val row = bucket(StandbyBucket.ACTIVE)
        backend.buckets[PKG] = StandbyBucket.RESTRICTED.rawValue // put back by hand

        assertAlreadyRestoredEverywhere(row)
        assertEquals(StandbyBucket.RESTRICTED.rawValue, backend.buckets[PKG])
    }

    @Test
    fun restoreWithoutConfirmationIsAlreadyRestoredWhenTheOriginalIsAnIgnoredRiskyOpAndTheTargetReadsIt() = runTest {
        for (op in listOf(AppOp.WAKE_LOCK, AppOp.RUN_ANY_IN_BACKGROUND)) {
            backend.device(PKG)[op] = "ignore" // the user's own setting
            val row = appOp(op, AppOpMode.DEFAULT)
            backend.device(PKG)[op] = "ignore" // put back by hand

            assertAlreadyRestoredEverywhere(row)
            assertEquals("ignore", backend.device(PKG)[op])
        }
    }

    @Test
    fun restoreWithoutConfirmationStillAsksWhenTheTargetDoesNotReadTheRiskyOriginal() = runTest {
        backend.buckets[PKG] = StandbyBucket.RESTRICTED.rawValue
        val row = bucket(StandbyBucket.ACTIVE)
        backend.buckets[PKG] = StandbyBucket.WORKING_SET.rawValue // moved elsewhere, restoring would restrict again
        backend.writes.clear()
        val rowsBefore = audit.rows.size

        assertTrue(actions.planRestore(row.id) is ToolkitPlan.NeedsConfirmation)
        assertEquals(
            ToolkitOutcome.Refused(ToolkitRefusal.NOT_CONFIRMED, ToolkitClientCode.NOT_CONFIRMED),
            actions.restore(row.id, confirmed = false),
        )
        assertTrue(backend.writes.isEmpty())
        assertEquals(rowsBefore + 1, audit.rows.size)
        assertEquals(ToolkitActionStatus.REFUSED, audit.rows.last().status)
        assertEquals(StandbyBucket.WORKING_SET.rawValue, backend.buckets[PKG])
    }

    @Test
    fun anUnconfirmedNewRiskyActionStillTouchesNothingNotEvenAReadBack() = runTest {
        backend.reads.clear()

        val outcome = actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RESTRICTED))

        assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.NOT_CONFIRMED, ToolkitClientCode.NOT_CONFIRMED), outcome)
        assertTrue(backend.writes.isEmpty())
        assertTrue(backend.reads.isEmpty())
    }

    // ---- m2: audit row counts --------------------------------------------------------------

    @Test
    fun aSuccessfulRestoreAddsExactlyOneRowAndMarksTheOriginalRestored() = runTest {
        val row = bucket(StandbyBucket.RESTRICTED)
        val rowsBefore = audit.rows.size

        val outcome = actions.restore(row.id)

        val restoreRow = (outcome as ToolkitOutcome.Done).value
        assertEquals(rowsBefore + 1, audit.rows.size)
        assertEquals(row.id, restoreRow.restoresId)
        assertEquals(ToolkitActionStatus.DONE, restoreRow.status)
        assertEquals(ToolkitActionStatus.RESTORED, audit.byId(row.id).status)
    }

    @Test
    fun alreadyRestoredAddsNoRowForARestoredRowOrAChainThatCameBack() = runTest {
        val restored = bucket(StandbyBucket.RESTRICTED)
        assertTrue(actions.restore(restored.id) is ToolkitOutcome.Done)
        val rowsBefore = audit.rows.size

        assertEquals(alreadyRestored, actions.restore(restored.id, confirmed = true))
        assertEquals(alreadyRestored, actions.restore(restored.id, confirmed = false))
        assertEquals(rowsBefore, audit.rows.size)

        val first = appOp(AppOp.RUN_ANY_IN_BACKGROUND, AppOpMode.IGNORE)
        appOp(AppOp.RUN_ANY_IN_BACKGROUND, AppOpMode.DEFAULT) // back to the original: nothing left to undo
        val rowsAfterChain = audit.rows.size

        assertEquals(alreadyRestored, actions.restore(first.id, confirmed = true))
        assertEquals(rowsAfterChain, audit.rows.size)
    }

    @Test
    fun resetAllAddsOneRestoreRowPerActiveOverrideAndNothingWhenThereIsNone() = runTest {
        val rowsEmpty = audit.rows.size
        assertTrue(actions.resetAll().isEmpty())
        assertEquals(rowsEmpty, audit.rows.size)

        val bucketRow = bucket(StandbyBucket.RESTRICTED)
        val opRow = appOp(AppOp.RUN_ANY_IN_BACKGROUND, AppOpMode.IGNORE)
        val rowsBefore = audit.rows.size

        val report = actions.resetAll()

        assertEquals(setOf(bucketRow.id, opRow.id), report.map { it.override.actionId }.toSet())
        assertTrue(report.all { it.outcome is ToolkitOutcome.Done })
        assertEquals(rowsBefore + 2, audit.rows.size)
        assertEquals(setOf(bucketRow.id, opRow.id), audit.rows.drop(rowsBefore).map { it.restoresId }.toSet())
        assertEquals(ToolkitActionStatus.RESTORED, audit.byId(bucketRow.id).status)
        assertEquals(ToolkitActionStatus.RESTORED, audit.byId(opRow.id).status)

        // a second reset has nothing to do and leaves the log alone
        val rowsAfter = audit.rows.size
        assertTrue(actions.resetAll().isEmpty())
        assertEquals(rowsAfter, audit.rows.size)
    }

    // ---- m2: a failed read-back during restore ---------------------------------------------

    @Test
    fun anUnreachableServiceDuringRestoreWritesNothingAndKeepsTheRowRestorable() = runTest {
        val row = bucket(StandbyBucket.RESTRICTED)
        backend.writes.clear()
        val rowsBefore = audit.rows.size
        backend.unavailable = GatewayUnavailable.NO_SERVICE

        assertEquals(ToolkitPlan.Refused(ToolkitRefusal.SERVICE_UNAVAILABLE), actions.planRestore(row.id))
        assertEquals(ToolkitOutcome.Unavailable(ToolkitUnavailable.NO_SERVICE), actions.restore(row.id, confirmed = true))
        assertEquals(ToolkitOutcome.Unavailable(ToolkitUnavailable.NO_SERVICE), actions.restore(row.id, confirmed = false))

        assertTrue(backend.writes.isEmpty())
        assertEquals(rowsBefore, audit.rows.size)
        assertEquals(ToolkitActionStatus.DONE, audit.byId(row.id).status)

        backend.unavailable = null
        assertTrue(actions.restore(row.id) is ToolkitOutcome.Done)
        assertEquals(listOf("setBucket($PKG,active)"), backend.writes)
    }

    @Test
    fun anUnreadableBucketDuringRestoreIsAReadFailureNeverAnAlreadyRestored() = runTest {
        val row = bucket(StandbyBucket.RESTRICTED)
        backend.writes.clear()
        val rowsBefore = audit.rows.size
        backend.scriptedRead = ToolkitResult.done(after = "not-a-bucket")

        assertEquals(ToolkitPlan.Refused(ToolkitRefusal.READ_FAILED), actions.planRestore(row.id))
        assertEquals(ToolkitOutcome.Failed(ToolkitCode.PARSE), actions.restore(row.id, confirmed = true))

        assertTrue(backend.writes.isEmpty())
        assertEquals(rowsBefore, audit.rows.size)
        assertEquals(ToolkitActionStatus.DONE, audit.byId(row.id).status)
    }

    @Test
    fun aFailedDozeReadDuringRestoreIsNotTreatedAsTheNullOriginal() = runTest {
        // the original is "no override" (null): a failed read must not look like null and so like "already restored"
        val row = (actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED), confirmed = true) as ToolkitOutcome.Done).value
        backend.writes.clear()
        val rowsBefore = audit.rows.size
        backend.scriptedRead = ToolkitResult.failed(ToolkitCode.PARSE)

        assertEquals(ToolkitPlan.Refused(ToolkitRefusal.READ_FAILED), actions.planRestore(row.id))
        assertEquals(ToolkitOutcome.Failed(ToolkitCode.PARSE), actions.restore(row.id, confirmed = true))

        assertTrue(backend.writes.isEmpty())
        assertEquals(rowsBefore, audit.rows.size)
        assertEquals(ToolkitActionStatus.DONE, audit.byId(row.id).status)
        assertEquals(DozeProfile.BALANCED.constants, backend.dozeRaw)
    }

    // ---- m2: ALREADY_RESTORED inside resetAll after a race ---------------------------------

    @Test
    fun resetAllReportsAlreadyRestoredWhenTheTargetChangedBackBetweenListingAndRestoringAndGoesOn() = runTest {
        val bucketRow = bucket(StandbyBucket.RESTRICTED)
        val opRow = appOp(AppOp.RUN_ANY_IN_BACKGROUND, AppOpMode.IGNORE)
        backend.writes.clear()
        val rowsBefore = audit.rows.size
        var bucketReads = 0
        backend.onRead = { name ->
            // read 1 lists the override; the user puts the bucket back before read 2, the restore's own read-back
            if (name.startsWith("getBucket") && ++bucketReads == 2) backend.buckets[PKG] = StandbyBucket.ACTIVE.rawValue
        }

        val report = actions.resetAll().associateBy { it.override.actionId }

        assertEquals(2, bucketReads)
        assertEquals(setOf(bucketRow.id, opRow.id), report.keys)
        assertEquals(alreadyRestored, report.getValue(bucketRow.id).outcome)
        assertTrue(report.getValue(opRow.id).outcome is ToolkitOutcome.Done)
        assertEquals(listOf("setAppOp($PKG,${AppOp.RUN_ANY_IN_BACKGROUND.argv},default)"), backend.writes)
        assertEquals(rowsBefore + 1, audit.rows.size)
        assertEquals(ToolkitActionStatus.DONE, audit.byId(bucketRow.id).status)
        assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])

        backend.onRead = {}
        assertTrue(actions.activeOverrides().first().isEmpty())
    }
}
