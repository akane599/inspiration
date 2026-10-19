package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A-04: a failed audit query must not abandon the independent client binding-hold deadline. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class ToolkitActionsHoldSupervisorTest {
    private val backend = FakeBackend()
    private val audit = FakeAudit()
    private val holds = FakeHolds()
    private val queryingAudit = QueryingAudit(audit)

    private fun TestScope.actions(
        supervisionScope: CoroutineScope = backgroundScope,
        wallShiftMs: () -> Long = { 0L },
    ) = ToolkitActions(
        backend,
        queryingAudit,
        holds::hold,
        nowMs = { currentTime + wallShiftMs() },
        supervisorScope = supervisionScope,
        elapsedMs = { currentTime },
    )

    @Test
    fun aTransientPendingQueryFailureIsRetriedAndALaterTerminalStatusReleasesTheHold() =
        runTest {
            val actions = actions()
            val started = (actions.startBgDexopt() as ToolkitOutcome.Done).value
            runCurrent()
            var failures = 1
            queryingAudit.onPending = { if (failures-- > 0) throw IllegalStateException("temporary Room query failure") }

            advanceTimeBy(MINUTE_MS)
            runCurrent()
            assertEquals(1, holds.open)
            assertEquals(0, backend.dexoptReads)
            assertEquals(ToolkitActionStatus.PENDING, audit.byId(started.id).status)
            val readsAfterFailure = queryingAudit.pendingReads

            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.add(serviceStatus(BgDexoptState.FINISHED))
            advanceTimeBy(MINUTE_MS)
            runCurrent()

            assertTrue("the failed query did not end supervision", queryingAudit.pendingReads > readsAfterFailure)
            assertEquals(1, backend.dexoptReads)
            assertEquals(0, holds.open)
            assertEquals(1, holds.opened)
            assertEquals(ToolkitActionStatus.DONE, audit.byId(started.id).status)
        }

    @Test
    fun repeatedPendingQueryFailuresStillReleaseAtTheMonotonicSixtyTwoMinuteCap() =
        runTest {
            var wallShiftMs = 0L
            val actions = actions(wallShiftMs = { wallShiftMs })
            val started = (actions.startBgDexopt() as ToolkitOutcome.Done).value
            runCurrent()
            queryingAudit.onPending = { throw IllegalStateException("Room remains unavailable") }
            val initialReads = queryingAudit.pendingReads
            wallShiftMs = -2 * 60 * MINUTE_MS

            advanceTimeBy(61 * MINUTE_MS)
            runCurrent()
            assertEquals("still within the independent client cap", 1, holds.open)
            assertTrue(queryingAudit.pendingReads > initialReads)
            assertEquals("audit failures happen before the backend status read", 0, backend.dexoptReads)

            advanceTimeBy(MINUTE_MS)
            runCurrent()
            assertEquals(0, holds.open)
            assertEquals("only the hold expires, not the unresolved audit row", ToolkitActionStatus.PENDING, audit.byId(started.id).status)
            val readsAtCap = queryingAudit.pendingReads
            advanceTimeBy(5 * MINUTE_MS)
            runCurrent()
            assertEquals("no query retries after release", readsAtCap, queryingAudit.pendingReads)
        }

    @Test
    fun cancellationFromAPendingQueryIsRethrownInsteadOfRetriedAsAStorageFailure() =
        runTest {
            val supervisionJob = SupervisorJob(backgroundScope.coroutineContext[Job])
            val supervisionScope = CoroutineScope(backgroundScope.coroutineContext + supervisionJob)
            val actions = actions(supervisionScope = supervisionScope)
            actions.startBgDexopt()
            runCurrent()
            val supervisor = supervisionJob.children.single()
            queryingAudit.onPending = { throw CancellationException("pending query cancelled") }

            advanceTimeBy(MINUTE_MS)
            runCurrent()

            assertTrue("cancellation must leave the supervisor cancelled", supervisor.isCancelled)
            val readsAfterCancellation = queryingAudit.pendingReads
            advanceTimeBy(2 * MINUTE_MS)
            runCurrent()
            assertEquals(readsAfterCancellation, queryingAudit.pendingReads)
            assertEquals(0, backend.dexoptReads)
            actions.release()
            assertEquals(0, holds.open)
            supervisionJob.cancel()
        }

    @Test
    fun anOldCancelledSupervisorDoesNotCloseItsReplacementHoldAndUserCancelStopsTheReplacement() =
        runTest {
            val supervisionJob = SupervisorJob(backgroundScope.coroutineContext[Job])
            val supervisionScope = CoroutineScope(backgroundScope.coroutineContext + supervisionJob)
            val actions = actions(supervisionScope = supervisionScope)
            val started = (actions.startBgDexopt() as ToolkitOutcome.Done).value
            runCurrent()
            val oldSupervisor = supervisionJob.children.single()
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.add(serviceStatus(BgDexoptState.RUNNING))
            queryingAudit.onPending = {
                queryingAudit.onPending = {}
                // The old iteration is already NonCancellable. Its subsequent RUNNING read takes a new
                // holder; cleanup of the cancelled generation must not close that replacement holder.
                actions.release()
            }

            advanceTimeBy(MINUTE_MS)
            runCurrent()

            assertTrue(oldSupervisor.isCancelled)
            assertEquals(2, holds.opened)
            assertEquals("the replacement generation is still supervised", 1, holds.open)
            assertEquals(1, supervisionJob.children.count())
            assertEquals(ToolkitOutcome.Done(Unit), actions.cancelBgDexopt())
            assertEquals(0, holds.open)
            assertEquals(ToolkitActionStatus.CANCELLED, audit.byId(started.id).status)
            val readsAfterCancel = backend.dexoptReads
            advanceTimeBy(70 * MINUTE_MS)
            runCurrent()
            assertEquals("user cancellation stops the replacement supervisor too", readsAfterCancel, backend.dexoptReads)
            assertEquals(0, holds.open)
            supervisionJob.cancel()
        }

    private class QueryingAudit(
        private val delegate: ToolkitAuditStore,
    ) : ToolkitAuditStore by delegate {
        var onPending: suspend () -> Unit = {}
        var pendingReads = 0

        override suspend fun pending(): List<ToolkitActionRecord> {
            pendingReads++
            onPending()
            return delegate.pending()
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
