package com.akane.inspiration.status

import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    fun `a burst of ticks with no collector never blocks and is not replayed`() = runTest {
        val ticker = StatusTicks()

        repeat(5) { ticker.emit() }

        // A late collector sees nothing replayed, so the service only ever redraws on a new tick.
        assertTrue(ticker.ticks.replayCache.isEmpty())
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
