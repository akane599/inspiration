package com.akane.inspiration.telemetry.toolkit

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
 * SQ-96: the chain walk covers DONE and RESTORED rows, so restoring one row of a chain never turns an older
 * row's `before` (an intermediate value) into the original of the rows above it. Every case asserts the
 * device read-back and the exact writes issued.
 */
class ToolkitActionsRestoreChainTest {
    private val backend = FakeBackend()
    private val audit = FakeAudit()
    private val holds = FakeHolds()
    private var clock = 10_000L
    private val actions = ToolkitActions(backend, audit, holds::hold, nowMs = { clock++ })

    private val op = AppOp.RUN_ANY_IN_BACKGROUND
    private val original = StandbyBucket.ACTIVE.rawValue

    private suspend fun bucket(bucket: StandbyBucket): ToolkitActionRecord =
        (actions.execute(ToolkitAction.SetBucket(PKG, bucket), confirmed = true) as ToolkitOutcome.Done).value

    private suspend fun assertRestoreIsAlreadyRestored(row: ToolkitActionRecord) {
        backend.writes.clear()
        assertEquals(ToolkitPlan.Refused(ToolkitRefusal.ALREADY_RESTORED), actions.planRestore(row.id))
        assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.ALREADY_RESTORED), actions.restore(row.id, confirmed = true))
        assertTrue("no write expected: ${backend.writes}", backend.writes.isEmpty())
    }

    // ---- (1) Scenario A: R1 A to B, R2 B to C ----------------------------------------------

    @Test
    fun restoringTheOlderRowFirstLeavesTheNewerRowAlreadyRestoredWithoutAnIntermediateWrite() = runTest {
        val r1 = bucket(StandbyBucket.RARE)
        val r2 = bucket(StandbyBucket.RESTRICTED)
        backend.writes.clear()

        // the target is at R2's value, not R1's, so restoring R1 asks first
        assertTrue(actions.planRestore(r1.id) is ToolkitPlan.NeedsConfirmation)
        assertTrue(actions.restore(r1.id, confirmed = true) is ToolkitOutcome.Done)
        assertEquals(listOf("setBucket($PKG,active)"), backend.writes)
        assertEquals(original, backend.buckets[PKG])
        assertEquals(ToolkitActionStatus.RESTORED, audit.byId(r1.id).status)

        assertRestoreIsAlreadyRestored(r2)
        assertEquals(original, backend.buckets[PKG])
        assertEquals(ToolkitActionStatus.DONE, audit.byId(r2.id).status)
    }

    @Test
    fun scenarioAOnADozeChainWritesTheCustomOriginalOnceAndNeverTheIntermediateProfile() = runTest {
        backend.dozeRaw = "inactive_to=1234"
        val r1 = (actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED), confirmed = true) as ToolkitOutcome.Done).value
        val r2 = (actions.execute(ToolkitAction.ApplyDoze(DozeProfile.AGGRESSIVE_NIGHT), confirmed = true) as ToolkitOutcome.Done).value
        backend.writes.clear()

        assertTrue(actions.restore(r1.id, confirmed = true) is ToolkitOutcome.Done)
        assertEquals(listOf("restoreDoze(inactive_to=1234,${DozeProfile.AGGRESSIVE_NIGHT.constants})"), backend.writes)
        assertEquals("inactive_to=1234", backend.dozeRaw)

        assertRestoreIsAlreadyRestored(r2)
        assertEquals("inactive_to=1234", backend.dozeRaw)
    }

    // ---- (2) Scenario B: R1 A to B, R2 B to C, R3 C to D -----------------------------------

    @Test
    fun restoringTheMiddleRowThenTheNewestRowDoesNotWriteTheIntermediate() = runTest {
        bucket(StandbyBucket.RARE)
        val r2 = bucket(StandbyBucket.RESTRICTED)
        val r3 = bucket(StandbyBucket.FREQUENT)
        backend.writes.clear()

        assertTrue(actions.restore(r2.id, confirmed = true) is ToolkitOutcome.Done)
        assertEquals(listOf("setBucket($PKG,active)"), backend.writes)
        assertEquals(original, backend.buckets[PKG])

        assertRestoreIsAlreadyRestored(r3)
        assertEquals(original, backend.buckets[PKG])
    }

    @Test
    fun afterTheMiddleRestoreTheNewestRowStillGoesToTheOriginalWhenTheTargetMovedAgain() = runTest {
        bucket(StandbyBucket.RARE)
        val r2 = bucket(StandbyBucket.RESTRICTED)
        val r3 = bucket(StandbyBucket.FREQUENT)
        assertTrue(actions.restore(r2.id, confirmed = true) is ToolkitOutcome.Done)
        backend.buckets[PKG] = StandbyBucket.WORKING_SET.rawValue // changed outside this app
        backend.writes.clear()

        assertTrue(actions.planRestore(r3.id) is ToolkitPlan.NeedsConfirmation)
        assertTrue(actions.restore(r3.id, confirmed = true) is ToolkitOutcome.Done)

        assertEquals(listOf("setBucket($PKG,active)"), backend.writes)
        assertEquals(original, backend.buckets[PKG])
    }

    // ---- (3) R1 A to B, R2 B to A: restoring R1 is a no-op ---------------------------------

    @Test
    fun restoringTheFirstRowOfAChainThatCameBackToItsOriginalIsAlreadyRestored() = runTest {
        val r1 = bucket(StandbyBucket.RARE)
        bucket(StandbyBucket.ACTIVE)
        assertEquals(original, backend.buckets[PKG])

        assertRestoreIsAlreadyRestored(r1)
        assertEquals(original, backend.buckets[PKG])
        assertEquals(ToolkitActionStatus.DONE, audit.byId(r1.id).status)
    }

    @Test
    fun appOpIgnoreThenAllowSpelledAsDefaultMakesTheFirstRowsRestoreAlreadyRestored() = runTest {
        val r1 = (actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE), confirmed = true) as ToolkitOutcome.Done).value
        actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.DEFAULT), confirmed = true)

        // the device started as "allow" and now reads "default": the same mode
        assertRestoreIsAlreadyRestored(r1)
        assertEquals("default", backend.device(PKG)[op])
    }

    // ---- (4) an external mid-chain change, then an older-row restore -----------------------

    @Test
    fun anExternalChangeAfterTheChainThenTheOlderRowRestoreAsksAndWritesTheOriginal() = runTest {
        val r1 = bucket(StandbyBucket.RARE)
        val r2 = bucket(StandbyBucket.RESTRICTED)
        backend.buckets[PKG] = StandbyBucket.FREQUENT.rawValue // changed outside this app
        backend.writes.clear()

        assertEquals(
            ToolkitWarning.RESTORE_STATE_CHANGED,
            (actions.planRestore(r1.id) as ToolkitPlan.NeedsConfirmation).warning,
        )
        assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.NOT_CONFIRMED, ToolkitClientCode.NOT_CONFIRMED), actions.restore(r1.id))
        assertTrue(backend.writes.isEmpty())

        assertTrue(actions.restore(r1.id, confirmed = true) is ToolkitOutcome.Done)
        assertEquals(listOf("setBucket($PKG,active)"), backend.writes)
        assertEquals(original, backend.buckets[PKG])

        assertRestoreIsAlreadyRestored(r2)
    }

    @Test
    fun anExternalChangeBetweenTwoRowsBreaksTheChainSoEachRowRestoresToItsOwnOriginal() = runTest {
        val r1 = bucket(StandbyBucket.RARE)
        backend.buckets[PKG] = StandbyBucket.WORKING_SET.rawValue // changed outside this app, between the rows
        val r2 = bucket(StandbyBucket.RESTRICTED)
        backend.writes.clear()

        // R2's chain starts at what it found (working_set), not at R1's `before`
        assertTrue(actions.restore(r2.id) is ToolkitOutcome.Done)
        assertEquals(listOf("setBucket($PKG,working_set)"), backend.writes)
        assertEquals(StandbyBucket.WORKING_SET.rawValue, backend.buckets[PKG])
        backend.writes.clear()

        // R1 (RESTORED rows do not matter here, R2's restore row only ends chains above it) goes to active
        assertTrue(actions.restore(r1.id, confirmed = true) is ToolkitOutcome.Done)
        assertEquals(listOf("setBucket($PKG,active)"), backend.writes)
        assertEquals(original, backend.buckets[PKG])
    }

    // ---- (5) negative control: a single row still writes its `before` ----------------------

    @Test
    fun aSingleRowRestoreStillWritesItsBefore() = runTest {
        val row = bucket(StandbyBucket.RESTRICTED)
        backend.writes.clear()

        assertEquals(ToolkitPlan.Ready, actions.planRestore(row.id))
        assertTrue(actions.restore(row.id) is ToolkitOutcome.Done)

        assertEquals(listOf("setBucket($PKG,active)"), backend.writes)
        assertEquals(original, backend.buckets[PKG])
        assertEquals(ToolkitActionStatus.RESTORED, audit.byId(row.id).status)
    }

    @Test
    fun aRestoreOfARestoredRowIsAlreadyRestoredAndWritesNothing() = runTest {
        val row = bucket(StandbyBucket.RESTRICTED)
        assertTrue(actions.restore(row.id) is ToolkitOutcome.Done)

        assertRestoreIsAlreadyRestored(row)
    }

    // ---- (6) activeOverrides and resetAll agree with per-item restore after a partial restore

    @Test
    fun activeOverridesAndResetAllAgreeWithPerItemRestoreAfterAPartialRestore() = runTest {
        val r1 = bucket(StandbyBucket.RARE)
        val r2 = bucket(StandbyBucket.RESTRICTED)
        val appOp = (actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE), confirmed = true) as ToolkitOutcome.Done).value
        assertEquals(setOf(r2.id, appOp.id), actions.activeOverrides().first().map { it.actionId }.toSet())

        // restore the older bucket row: the whole bucket chain is back at its original
        assertTrue(actions.restore(r1.id, confirmed = true) is ToolkitOutcome.Done)

        assertEquals(listOf(appOp.id), actions.activeOverrides().first().map { it.actionId })
        assertRestoreIsAlreadyRestored(r2)

        val report = actions.resetAll()

        assertEquals(listOf(appOp.id), report.map { it.override.actionId })
        assertTrue(report.single().outcome is ToolkitOutcome.Done)
        assertEquals(listOf("setAppOp($PKG,${op.argv},default)"), backend.writes)
        assertEquals("default", backend.device(PKG)[op])
        assertEquals(original, backend.buckets[PKG])
        assertTrue(actions.activeOverrides().first().isEmpty())
    }
}
