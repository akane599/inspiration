package com.akane.inspiration.di

import com.akane.inspiration.telemetry.app.RefreshResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshCoordinatorTest {
    private var now = 1_000_000L
    private var captures = 0

    private fun coordinator(capture: suspend () -> RefreshResult = { RefreshResult.Refreshed(now) }) =
        RefreshCoordinator(nowWallMs = { now }, debounceMs = 60_000L) {
            captures += 1
            capture()
        }

    @Test
    fun `two concurrent calls run one capture and the second is debounced`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val coordinator = coordinator {
            gate.await()
            RefreshResult.Refreshed(now)
        }

        val first = async { coordinator.refresh() }
        val second = async { coordinator.refresh() }
        runCurrent()
        assertEquals("only the first caller is inside the capture", 1, captures)

        gate.complete(Unit)
        assertEquals(RefreshResult.Refreshed(now), first.await())
        assertEquals(RefreshResult.Debounced(now), second.await())
        assertEquals(1, captures)
    }

    @Test
    fun `a call inside 60 seconds is skipped and one at 60 seconds captures again`() = runTest {
        val coordinator = coordinator()
        val firstAt = now
        assertEquals(RefreshResult.Refreshed(firstAt), coordinator.refresh())

        now = firstAt + 59_999L
        assertEquals(RefreshResult.Debounced(firstAt), coordinator.refresh())
        assertEquals(1, captures)

        now = firstAt + 60_000L
        assertEquals(RefreshResult.Refreshed(now), coordinator.refresh())
        assertEquals(2, captures)
    }

    @Test
    fun `a failed capture is not remembered so a retry runs at once`() = runTest {
        var succeed = false
        val coordinator = coordinator {
            if (succeed) RefreshResult.Refreshed(now) else RefreshResult.Failed("service_unavailable")
        }

        assertEquals(RefreshResult.Failed("service_unavailable"), coordinator.refresh())
        assertNull(coordinator.lastRefreshWallMs.value)

        succeed = true
        now += 1_000L
        assertEquals(RefreshResult.Refreshed(now), coordinator.refresh())
        assertEquals(2, captures)
    }

    @Test
    fun `lastRefreshWallMs follows successful refreshes only`() = runTest {
        val coordinator = coordinator()
        assertNull(coordinator.lastRefreshWallMs.value)

        coordinator.refresh()
        assertEquals(now, coordinator.lastRefreshWallMs.value)

        val before = now
        now += 10_000L
        coordinator.refresh() // debounced
        assertEquals(before, coordinator.lastRefreshWallMs.value)
    }

    @Test
    fun `a wall clock that moved backwards does not debounce`() = runTest {
        val coordinator = coordinator()
        coordinator.refresh()

        now -= 5_000L
        assertTrue(coordinator.refresh() is RefreshResult.Refreshed)
        assertEquals(2, captures)
    }

    @Test
    fun `a cancelled caller does not abort the capture in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var finished = false
        val coordinator = coordinator {
            gate.await()
            finished = true
            RefreshResult.Refreshed(now)
        }

        val caller = launch { coordinator.refresh() }
        runCurrent()
        caller.cancel()
        gate.complete(Unit)
        runCurrent()

        assertTrue("the capture ran to its end", finished)
        assertEquals(now, coordinator.lastRefreshWallMs.value)
    }
}
