package com.akane.inspiration.status

import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatusTicksTest {
    @Test
    fun `emitAfter ticks once after the block returns`() = runTest {
        val ticker = StatusTicks()
        val received = mutableListOf<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { ticker.ticks.collect { received += it } }

        val result = ticker.emitAfter { 7 }
        runCurrent()

        assertEquals(7, result)
        assertEquals(1, received.size)
        job.cancel()
    }

    @Test
    fun `a full capture that times out still ticks`() = runTest {
        val ticker = StatusTicks()
        val received = mutableListOf<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { ticker.ticks.collect { received += it } }

        val captured = ticker.emitAfter {
            withTimeoutOrNull(1_000L) { delay(10_000L) }
        }
        runCurrent()

        assertNull(captured)
        assertEquals(1, received.size)
        job.cancel()
    }

    @Test
    fun `a capture that throws still ticks and the exception propagates`() = runTest {
        val ticker = StatusTicks()
        val received = mutableListOf<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { ticker.ticks.collect { received += it } }

        val thrown = runCatching { ticker.emitAfter { error("capture failed") } }.exceptionOrNull()
        runCurrent()

        assertTrue(thrown is IllegalStateException)
        assertEquals(1, received.size)
        job.cancel()
    }

    @Test
    fun `a collector that subscribes after a burst gets none of it`() = runTest {
        val ticker = StatusTicks()
        repeat(5) { ticker.emit() }

        var received = 0
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { ticker.ticks.collect { received++ } }
        advanceUntilIdle()

        // Nothing is replayed, so the service only ever redraws on a tick that comes after it subscribed.
        assertEquals(0, received)
        job.cancel()
    }

    @Test
    fun `a burst while the collector is busy collapses to one pending tick`() = runTest {
        val ticker = StatusTicks()
        val gate = CompletableDeferred<Unit>()
        var received = 0
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            ticker.ticks.collect {
                received++
                if (received == 1) gate.await()
            }
        }
        ticker.emit()
        advanceUntilIdle()
        assertEquals(1, received)

        // extraBufferCapacity = 1 with DROP_OLDEST: the ticks emitted during the slow first redraw keep one slot, and
        // emit() returns without waiting for the collector.
        repeat(5) { ticker.emit() }
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, received)
        job.cancel()
    }

    @Test
    fun `a tick emitted during the first redraw is not lost (R-14)`() = runTest {
        val ticker = StatusTicks()
        var redraws = 0
        val job = launch {
            ticker.ticks.redrawsFromNow().collect {
                redraws++
                // A capture finishing while the first redraw is still running.
                if (redraws == 1) ticker.emit()
            }
        }
        runCurrent()

        assertEquals(2, redraws)
        job.cancel()
    }

    @Test
    fun `blocked when notifications are off or the channel is silenced, otherwise not`() {
        val none = NotificationManagerCompat.IMPORTANCE_NONE
        assertTrue(StatusChannel.isBlocked(notificationsEnabled = false, channelImportance = null))
        assertTrue(StatusChannel.isBlocked(notificationsEnabled = true, channelImportance = none))
        assertFalse(StatusChannel.isBlocked(notificationsEnabled = true, channelImportance = null))
        assertFalse(StatusChannel.isBlocked(notificationsEnabled = true, channelImportance = NotificationManagerCompat.IMPORTANCE_LOW))
    }
}
