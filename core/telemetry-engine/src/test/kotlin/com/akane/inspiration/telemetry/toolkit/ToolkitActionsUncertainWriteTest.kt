package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A write the service issued but could not verify (a timeout after it may have committed, a failed read-back,
 * a doze rollback that did not land) stays PENDING and joins its target's chain before anything builds on it,
 * so Reset all never re-applies a restriction the user removed.
 */
class ToolkitActionsUncertainWriteTest {
    private val backend = FakeBackend()
    private val audit = FakeAudit()
    private val holds = FakeHolds()
    private var clock = 10_000L
    private val actions = ToolkitActions(backend, audit, holds::hold, nowMs = { clock++ })

    private val active = StandbyBucket.ACTIVE.rawValue
    private val restricted = StandbyBucket.RESTRICTED.rawValue

    /** The service committed [commit] and then answered "issued, outcome unknown". */
    private fun uncertainWrite(before: String?, after: String? = null, commit: () -> Unit) {
        backend.onWrite = {
            commit()
            backend.onWrite = {}
        }
        backend.scriptedWrite = ToolkitResult.unavailable(ToolkitCode.UNVERIFIED, before = before, after = after)
    }

    @Test
    fun committedBucketWriteWithFailedReadBackThenManualUndoLeavesNothingForResetAll() = runTest {
        uncertainWrite(before = active.toString()) { backend.buckets[PKG] = restricted }

        val first = actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RESTRICTED), confirmed = true)

        assertEquals(ToolkitOutcome.Unavailable(ToolkitUnavailable.UNVERIFIED, ToolkitCode.UNVERIFIED), first)
        val rowA = audit.rows.single()
        assertEquals(ToolkitActionStatus.PENDING, rowA.status)
        assertEquals(active.toString(), rowA.beforeRaw)

        // The user switches it back to ACTIVE in the app, without a reconcile in between.
        val second = actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.ACTIVE), confirmed = true)
        assertTrue("$second", second is ToolkitOutcome.Done)
        assertEquals(ToolkitActionStatus.DONE, audit.byId(rowA.id).status)

        assertTrue(actions.activeOverrides().first().isEmpty())
        backend.writes.clear()
        val report = actions.resetAll()

        assertTrue("$report", report.isEmpty())
        assertTrue("Reset all must not write: ${backend.writes}", backend.writes.isEmpty())
        assertEquals(active, backend.buckets[PKG])
    }

    @Test
    fun committedAppOpWriteThatTimedOutIsListedAndResetToTheOriginal() = runTest {
        val op = AppOp.RUN_ANY_IN_BACKGROUND
        uncertainWrite(before = "allow") { backend.device(PKG)[op] = "ignore" }

        actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE), confirmed = true)

        // Its effect is visible as an override (the app really is restricted by us) ...
        val override = actions.activeOverrides().first().single()
        assertEquals("allow", override.previousRaw)
        assertEquals(ToolkitActionStatus.DONE, audit.rows.single().status)

        // ... and Reset all undoes it.
        actions.resetAll()
        assertEquals("default", backend.device(PKG)[op])
        assertTrue(actions.activeOverrides().first().isEmpty())
    }

    @Test
    fun uncertainWriteThatDidNotCommitIsSettledFailedAndIsNoOverride() = runTest {
        uncertainWrite(before = active.toString()) { }

        actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RESTRICTED), confirmed = true)

        assertTrue(actions.activeOverrides().first().isEmpty())
        assertEquals(ToolkitActionStatus.FAILED, audit.rows.single().status)
    }

    @Test
    fun dozeRollbackThatDidNotLandStaysPendingAndReconcileSettlesIt() = runTest {
        val profile = DozeProfile.AGGRESSIVE_NIGHT
        uncertainWrite(before = null, after = profile.constants) { backend.dozeRaw = profile.constants }

        val outcome = actions.execute(ToolkitAction.ApplyDoze(profile))

        assertEquals(ToolkitOutcome.Unavailable(ToolkitUnavailable.UNVERIFIED, ToolkitCode.UNVERIFIED), outcome)
        assertEquals(ToolkitActionStatus.PENDING, audit.rows.single().status)

        actions.reconcile()

        val row = audit.rows.single()
        assertEquals(ToolkitActionStatus.DONE, row.status)
        assertEquals(profile.constants, row.afterRaw)
        assertEquals(1, actions.activeOverrides().first().size)
        actions.resetAll()
        assertNull(backend.dozeRaw)
    }

    @Test
    fun aPendingRowThatCannotBeReadBackStaysPending() = runTest {
        uncertainWrite(before = active.toString()) { backend.buckets[PKG] = restricted }
        actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RESTRICTED), confirmed = true)
        backend.unavailable = GatewayUnavailable.NO_SERVICE

        assertTrue(actions.activeOverrides().first().isEmpty())

        assertEquals(ToolkitActionStatus.PENDING, audit.rows.single().status)
    }
}
