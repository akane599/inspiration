package com.akane.inspiration.di

import com.akane.inspiration.ipc.ShizukuState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

        state.value = ShizukuState.PermissionDenied
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
}
