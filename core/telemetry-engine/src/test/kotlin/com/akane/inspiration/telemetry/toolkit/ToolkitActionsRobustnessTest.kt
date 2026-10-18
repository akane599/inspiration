package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** SQ-79 findings M1 (cancellation and exceptions around the write), M2 (hold leak) and m1 (client mutex). */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolkitActionsRobustnessTest {
    private val backend = FakeBackend()
    private val audit = FakeAudit()
    private val holds = FakeHolds()

    private fun TestScope.actionsOnVirtualTime() =
        ToolkitActions(backend, audit, holds::hold, nowMs = { currentTime }, supervisorScope = backgroundScope)

    @Test
    fun lostReplyAfterEffectCanReconcileAndRestoreTheOriginal() = runTest {
        val actions = actionsOnVirtualTime()
        backend.unavailableAfterWrite = GatewayUnavailable.NO_SERVICE
        assertTrue(actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE)) is ToolkitOutcome.Unavailable)
        assertEquals(StandbyBucket.RARE.rawValue, backend.buckets[PKG])
        assertEquals(ToolkitActionStatus.PENDING, audit.rows.single().status)
        backend.unavailableAfterWrite = null
        actions.reconcile()
        assertEquals(ToolkitActionStatus.DONE, audit.rows.single().status)
        assertEquals(1, actions.resetAll().size)
        assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])
    }

    @Test
    fun exceptionAfterEffectCanReconcileAndRestoreTheOriginal() = runTest {
        val actions = actionsOnVirtualTime()
        backend.afterWrite = { error("reply lost") }
        try {
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            fail("expected reply failure")
        } catch (_: IllegalStateException) {
            assertEquals(ToolkitActionStatus.PENDING, audit.rows.single().status)
        }
        backend.afterWrite = {}
        actions.reconcile()
        assertEquals(ToolkitActionStatus.DONE, audit.rows.single().status)
        actions.resetAll()
        assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])
    }

    // ---- M1 --------------------------------------------------------------------------------

    @Test
    fun callerCancelledAfterTheWriteStillSettlesTheRow() = runTest {
        val actions = actionsOnVirtualTime()
        lateinit var job: Job
        backend.afterWrite = { job.cancel() }

        job = launch { actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE)) }
        job.join()

        assertEquals(StandbyBucket.RARE.rawValue, backend.buckets[PKG])
        val row = audit.rows.single()
        assertEquals(ToolkitActionStatus.DONE, row.status)
        assertEquals("10", row.beforeRaw)
        assertEquals("${StandbyBucket.RARE.rawValue}", row.afterRaw)
        assertNotNull(row.endedAt)
    }

    @Test
    fun callerCancelledAfterARestoreWriteStillMarksTheOriginalRestored() = runTest {
        val actions = actionsOnVirtualTime()
        val original = (actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE)) as ToolkitOutcome.Done).value
        lateinit var job: Job
        backend.afterWrite = { job.cancel() }

        job = launch { actions.restore(original.id) }
        job.join()

        assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])
        assertEquals(ToolkitActionStatus.RESTORED, audit.byId(original.id).status)
        val restoreRow = audit.rows.single { it.restoresId == original.id }
        assertEquals(ToolkitActionStatus.DONE, restoreRow.status)
        assertTrue(audit.pending().isEmpty())
    }

    @Test
    fun aBackendExceptionRetainsTheRowForReadback() = runTest {
        val actions = actionsOnVirtualTime()
        backend.writeFailure = IllegalStateException("binder exploded")

        try {
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            fail("the exception must propagate")
        } catch (e: IllegalStateException) {
            assertEquals("binder exploded", e.message)
        }

        val row = audit.rows.single()
        assertEquals(ToolkitActionStatus.PENDING, row.status)
        assertEquals(ToolkitClientCode.WRITE_ERROR, row.code)
        assertNull(row.endedAt)
        assertEquals(listOf(row), audit.pending())
    }

    @Test
    fun aDexoptStartCancelledAfterTheServiceStartedStillTakesTheHoldAndKeepsTheRow() = runTest {
        val actions = actionsOnVirtualTime()
        lateinit var job: Job
        backend.afterWrite = { job.cancel() }

        job = launch { actions.startBgDexopt() }
        job.join()

        assertEquals(1, holds.open)
        assertEquals(ToolkitActionStatus.PENDING, audit.rows.single().status)
        assertEquals(ToolkitCode.OK, audit.rows.single().code)
    }

    // ---- m1 --------------------------------------------------------------------------------

    @Test
    fun theClientLockIsNotHeldAcrossTheDexoptServiceCall() = runTest {
        val actions = actionsOnVirtualTime()
        val gate = CompletableDeferred<Unit>()
        backend.onWrite = { name -> if (name == "startBgDexopt") gate.await() }

        val start = async { actions.startBgDexopt() }
        runCurrent()
        // The row is already written and the call is in flight: a PENDING row exists and other work proceeds.
        assertEquals(ToolkitActionStatus.PENDING, audit.rows.single().status)
        val plan = async { actions.plan(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE)) }
        val cancel = async { actions.cancelBgDexopt() }
        runCurrent()
        assertTrue("plan must not wait for the service call", plan.isCompleted)
        assertTrue("cancel must not wait for the service call", cancel.isCompleted)
        assertFalse(start.isCompleted)

        gate.complete(Unit)
        runCurrent()
        assertTrue(start.await() is ToolkitOutcome.Done)
    }

    // ---- M2 --------------------------------------------------------------------------------

    private fun TestScope.statuses(vararg states: Int) {
        backend.dexoptStatuses.clear()
        backend.dexoptStatuses.addAll(states.map { serviceStatus(it) })
    }

    @Test
    fun theHoldIsReleasedWhenTheJobFinishesEvenIfNobodyCollects() = runTest {
        val actions = actionsOnVirtualTime()
        val started = (actions.startBgDexopt() as ToolkitOutcome.Done).value
        assertEquals(1, holds.open)

        // a screen collects for a moment and goes away while the job still runs
        statuses(BgDexoptState.RUNNING)
        val collector = launch { actions.bgDexoptStatus().collect { } }
        runCurrent()
        collector.cancelAndJoin()
        assertEquals(1, holds.open)

        // the job finishes with nobody watching
        statuses(BgDexoptState.FINISHED)
        advanceTimeBy(61_000)
        runCurrent()

        assertEquals(0, holds.open)
        assertEquals(ToolkitActionStatus.DONE, audit.byId(started.id).status)
    }

    @Test
    fun theSupervisorRechecksAtALowCadenceOnlyWhileRunning() = runTest {
        val actions = actionsOnVirtualTime()
        actions.startBgDexopt()
        statuses(BgDexoptState.RUNNING)

        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(1, holds.open)
        assertTrue("about one read per minute, not a tight poll: ${backend.dexoptReads}", backend.dexoptReads in 8..11)

        statuses(BgDexoptState.CANCELLED)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, holds.open)
        val reads = backend.dexoptReads
        advanceTimeBy(30 * 60_000L)
        runCurrent()
        assertEquals("no checks once the hold is gone", reads, backend.dexoptReads)
    }

    @Test
    fun theHoldIsForceReleasedAtTheCapPlusMarginWhenTheServiceCannotBeAsked() = runTest {
        val actions = actionsOnVirtualTime()
        val started = (actions.startBgDexopt() as ToolkitOutcome.Done).value
        backend.unavailable = GatewayUnavailable.NO_SERVICE

        advanceTimeBy(59 * 60_000L)
        runCurrent()
        assertEquals("still inside the 60-minute cap", 1, holds.open)

        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertEquals(0, holds.open)
        assertEquals("only the hold is dropped; the row waits for a status read", ToolkitActionStatus.PENDING, audit.byId(started.id).status)
    }

    @Test
    fun userCancelStopsTheSupervisor() = runTest {
        val actions = actionsOnVirtualTime()
        actions.startBgDexopt()
        assertEquals(ToolkitOutcome.Done(Unit), actions.cancelBgDexopt())
        assertEquals(0, holds.open)
        val reads = backend.dexoptReads

        advanceTimeBy(70 * 60_000L)
        runCurrent()

        assertEquals(reads, backend.dexoptReads)
        assertEquals(1, holds.opened)
    }

    @Test
    fun aTearDownReleaseStopsTheSupervisor() = runTest {
        val actions = actionsOnVirtualTime()
        actions.startBgDexopt()
        actions.release()
        assertEquals(0, holds.open)
        val reads = backend.dexoptReads

        advanceTimeBy(70 * 60_000L)
        runCurrent()

        assertEquals(reads, backend.dexoptReads)
    }

    @Test
    fun theSupervisorScopeParameterIsOptional() = runTest {
        val actions = ToolkitActions(backend, audit, holds::hold)
        val row = (actions.execute(ToolkitAction.SetAppOp(PKG, AppOp.RUN_IN_BACKGROUND, AppOpMode.IGNORE)) as ToolkitOutcome.Done).value
        assertEquals(ToolkitActionStatus.DONE, row.status)
    }
}
