package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
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

    /** Both clocks run on virtual time; [wallShiftMs] moves only the wall clock, like a manual date change. */
    private fun TestScope.actionsOnVirtualTime(wallShiftMs: () -> Long = { 0L }) =
        ToolkitActions(
            backend,
            audit,
            holds::hold,
            nowMs = { currentTime + wallShiftMs() },
            supervisorScope = backgroundScope,
            elapsedMs = { currentTime },
        )

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

    @Test
    fun restoreStillFindsOriginalAfterMoreThanFiveHundredUnrelatedActions() = runTest {
        val actions = actionsOnVirtualTime()
        actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
        repeat(510) { n ->
            audit.insert(ToolkitActionRecord(
                startedAt = n.toLong(), kind = "force_stop", op = "force_stop", status = ToolkitActionStatus.REFUSED,
            ))
        }
        actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RESTRICTED), confirmed = true)
        assertEquals(1, actions.resetAll().size)
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
    fun aForwardWallClockChangeDoesNotDropTheHoldOfARunningJob() = runTest {
        var wallShiftMs = 0L
        val actions = actionsOnVirtualTime { wallShiftMs }
        actions.startBgDexopt()
        statuses(BgDexoptState.RUNNING)
        assertEquals(1, holds.open)

        // One minute into the job the wall clock is set two hours ahead; the job keeps running.
        wallShiftMs = 2 * HOUR_MS
        advanceTimeBy(60_000L + 1L)
        runCurrent()

        assertEquals("the 62-minute cap is not a wall-clock deadline", 1, holds.open)
    }

    @Test
    fun aBackwardWallClockChangeDoesNotStretchTheHoldPastTheCap() = runTest {
        var wallShiftMs = 0L
        val actions = actionsOnVirtualTime { wallShiftMs }
        actions.startBgDexopt()
        backend.unavailable = GatewayUnavailable.NO_SERVICE

        wallShiftMs = -2 * HOUR_MS
        advanceTimeBy(64 * 60_000L)
        runCurrent()

        assertEquals(0, holds.open)
    }

    @Test
    fun aHoldTakenForARunningJobEndsAtTheCapCountedFromTheReportedStart() = runTest {
        // The service reports a job that started (wall 1_000) 50 minutes before this process saw it.
        var wallShiftMs = 1_000L + 50 * 60_000L
        val actions = actionsOnVirtualTime { wallShiftMs }
        statuses(BgDexoptState.RUNNING)
        val collector = launch { actions.bgDexoptStatus().collect { } }
        runCurrent()
        collector.cancelAndJoin()
        assertEquals(1, holds.open)
        backend.unavailable = GatewayUnavailable.NO_SERVICE
        // Once the hold is taken, a wall-clock change no longer moves its deadline.
        wallShiftMs += 3 * HOUR_MS

        advanceTimeBy(11 * 60_000L)
        runCurrent()
        assertEquals("12 of the 62 minutes were left", 1, holds.open)

        advanceTimeBy(2 * 60_000L)
        runCurrent()
        assertEquals(0, holds.open)
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

    @Test
    fun runningDexoptPollsDoNotRecomputeOverridesWhileNothingChanges() = runTest {
        val actions = actionsOnVirtualTime()
        actions.execute(ToolkitAction.SetAppOp(PKG, AppOp.RUN_ANY_IN_BACKGROUND, AppOpMode.IGNORE), confirmed = true)
        actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
        actions.startBgDexopt()
        backend.dexoptStatuses.clear()
        backend.dexoptStatuses.add(serviceStatus(BgDexoptState.RUNNING))
        var emissions = 0
        val overrides = launch { actions.activeOverrides().collect { emissions++ } }
        val status = launch { actions.bgDexoptStatus().collect { } }
        runCurrent()
        backend.reads.clear()
        val before = emissions
        val polls = backend.dexoptReads

        advanceTimeBy(30_000)
        runCurrent()

        assertTrue("the status was polled", backend.dexoptReads > polls)
        assertEquals(before, emissions)
        assertTrue("no privileged override read: ${backend.reads}", backend.reads.isEmpty())

        // A real settlement still refreshes the overrides.
        backend.dexoptStatuses.clear()
        backend.dexoptStatuses.add(serviceStatus(BgDexoptState.FINISHED))
        advanceTimeBy(6_000)
        runCurrent()
        assertTrue(emissions > before)
        overrides.cancel()
        status.cancel()
    }

    private companion object {
        const val HOUR_MS = 60L * 60_000L
    }
}
