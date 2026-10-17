package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SQ-86: `resetAll` puts each active override back to the value before the first change of its chain. */
class ToolkitActionsResetAllTest {
    private val backend = FakeBackend()
    private val audit = FakeAudit()
    private val holds = FakeHolds()
    private var clock = 10_000L
    private val actions = ToolkitActions(backend, audit, holds::hold, nowMs = { clock++ })

    private val op = AppOp.RUN_ANY_IN_BACKGROUND

    private fun opNow() = backend.device(PKG)[op]

    @Test
    fun resetAllDoesNotRestrictAnAppTheUserSwitchedBackByHand() = runTest {
        actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE), confirmed = true)
        actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.DEFAULT))
        backend.writes.clear()

        val report = actions.resetAll()

        assertEquals("default", opNow())
        assertTrue("no write may restrict the app again: ${backend.writes}", backend.writes.none { "ignore" in it })
        // back at its original value, so it is no active override at all (SQ-92)
        assertTrue(report.isEmpty())
    }

    @Test
    fun backToTheOriginalByTwoChangesWritesNothing() = runTest {
        backend.device(PKG)[op] = "ignore" // the user's own setting before this app touched it
        actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.DEFAULT))
        actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE), confirmed = true)
        backend.writes.clear()

        val report = actions.resetAll()

        assertTrue(backend.writes.isEmpty())
        assertEquals("ignore", opNow())
        assertTrue(report.isEmpty())
    }

    @Test
    fun twoChangesToTheSameBucketRestoreTheOriginalNotTheIntermediate() = runTest {
        actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
        actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RESTRICTED), confirmed = true)

        val report = actions.resetAll()

        assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])
        assertEquals(1, report.size)
        assertTrue(report.single().outcome is ToolkitOutcome.Done)
        assertTrue(actions.activeOverrides().first().isEmpty())
    }

    @Test
    fun twoDozeProfilesRestoreTheOriginalNotTheIntermediate() = runTest {
        actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))
        actions.execute(ToolkitAction.ApplyDoze(DozeProfile.AGGRESSIVE_NIGHT))

        val report = actions.resetAll()

        assertNull(backend.dozeRaw)
        assertEquals(1, report.size)
        assertTrue(report.single().outcome is ToolkitOutcome.Done)
        assertTrue(actions.activeOverrides().first().isEmpty())
    }

    @Test
    fun aChainEndsWhereSomethingElseChangedTheTarget() = runTest {
        actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
        backend.buckets[PKG] = StandbyBucket.FREQUENT.rawValue // changed outside the app
        actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RESTRICTED), confirmed = true)

        val report = actions.resetAll()

        // the value found before the second change, not the one before the first
        assertEquals(StandbyBucket.FREQUENT.rawValue, backend.buckets[PKG])
        assertEquals(1, report.size)
        assertTrue(report.single().outcome is ToolkitOutcome.Done)
        assertTrue(actions.activeOverrides().first().isEmpty())
    }

    @Test
    fun anOverrideChangedExternallyIsNotTouched() = runTest {
        actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
        actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE), confirmed = true)
        actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))
        backend.buckets[PKG] = StandbyBucket.WORKING_SET.rawValue
        backend.device(PKG)[op] = "default"
        backend.dozeRaw = "inactive_to=1"
        backend.writes.clear()

        val report = actions.resetAll()

        assertTrue(report.isEmpty())
        assertTrue(backend.writes.isEmpty())
        assertEquals(StandbyBucket.WORKING_SET.rawValue, backend.buckets[PKG])
        assertEquals("default", opNow())
        assertEquals("inactive_to=1", backend.dozeRaw)
    }

    @Test
    fun anOverrideWithoutARestorableOriginalIsRefusedNotReplayedFromTheIntermediate() = runTest {
        backend.nullBeforeOnBucketWrite = true
        actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
        backend.nullBeforeOnBucketWrite = false
        actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.FREQUENT))
        backend.writes.clear()

        val report = actions.resetAll()

        assertEquals(listOf<ToolkitOutcome<*>>(ToolkitOutcome.Refused(ToolkitRefusal.NOT_RESTORABLE)), report.map { it.outcome })
        assertTrue(backend.writes.isEmpty())
    }
}
