package com.akane.inspiration.di

import com.akane.inspiration.ipc.ShizukuState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReconcileOnReadyTest {
    @Test
    fun `reconciles on every transition into Ready, not only the first`() = runTest(UnconfinedTestDispatcher()) {
        val state = MutableStateFlow<ShizukuState>(ShizukuState.NotRunning)
        var runs = 0
        val job = launch { reconcileOnEachReady(state) { runs += 1 } }
        assertEquals("not ready yet", 0, runs)

        state.value = ShizukuState.Ready(2000, 13)
        assertEquals(1, runs)

        state.value = ShizukuState.Ready(2000, 14)
        assertEquals("Ready to Ready is not a transition", 1, runs)

        state.value = ShizukuState.NotRunning
        assertEquals(1, runs)
        state.value = ShizukuState.Ready(2000, 13)
        assertEquals("the second transition into Ready reconciles again", 2, runs)

        state.value = ShizukuState.PermissionDenied()
        state.value = ShizukuState.Ready(2000, 13)
        assertEquals(3, runs)
        job.cancel()
    }

    @Test
    fun `an already Ready state reconciles once at start`() = runTest(UnconfinedTestDispatcher()) {
        val state = MutableStateFlow<ShizukuState>(ShizukuState.Ready(2000, 13))
        var runs = 0
        val job = launch { reconcileOnEachReady(state) { runs += 1 } }

        assertEquals(1, runs)
        job.cancel()
    }

    @Test
    fun `a Ready flip that happens while reconcile is still running reconciles again`() = runTest {
        val state = MutableStateFlow<ShizukuState>(ShizukuState.Ready(2000, 13))
        var runs = 0
        val gate = CompletableDeferred<Unit>()
        val job = launch {
            reconcileOnEachReady(state) {
                runs += 1
                if (runs == 1) gate.await() // the first read-back is still in flight
            }
        }
        runCurrent()
        assertEquals(1, runs)

        state.value = ShizukuState.NotRunning // the binder dies mid-reconcile
        runCurrent()
        state.value = ShizukuState.Ready(2000, 13) // and comes back as an equal Ready
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(2, runs)
        job.cancel()
    }

    @Test
    fun `a reconcile that throws is reported and the next transition into Ready reconciles again`() = runTest(UnconfinedTestDispatcher()) {
        val state = MutableStateFlow<ShizukuState>(ShizukuState.Ready(2000, 13))
        val failures = mutableListOf<Throwable>()
        val diskFull = IllegalStateException("database or disk is full")
        var runs = 0
        val job = launch {
            reconcileOnEachReady(state, onFailure = { failures += it }) {
                runs += 1
                if (runs == 1) throw diskFull
            }
        }
        assertEquals(1, runs)
        assertEquals(listOf<Throwable>(diskFull), failures)
        assertTrue("one failure must not end the collector", job.isActive)

        state.value = ShizukuState.NotRunning
        state.value = ShizukuState.Ready(2000, 13)

        assertEquals("the next Ready reconciles again", 2, runs)
        assertEquals(1, failures.size)
        job.cancel()
    }

    @Test
    fun `cancelling the collector while a reconcile runs ends it and reports no failure`() = runTest {
        val state = MutableStateFlow<ShizukuState>(ShizukuState.Ready(2000, 13))
        val failures = mutableListOf<Throwable>()
        val job = launch { reconcileOnEachReady(state, onFailure = { failures += it }) { awaitCancellation() } }
        runCurrent()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue("a cancellation is not a reconcile failure: $failures", failures.isEmpty())
    }
}
