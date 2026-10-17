package com.akane.inspiration.ipc.client

import com.akane.inspiration.ipc.model.PowerMonitorReading
import com.akane.inspiration.ipc.model.Reasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerStatsClientTest {
    private class FakePowerStatsCalls(
        private val monitors: List<PowerMonitorInfo>? = listOf(
            PowerMonitorInfo("VSYS_PWR_DISPLAY", 0),
            PowerMonitorInfo("CPU", 1),
        ),
        private val values: PowerMonitorValues? = PowerMonitorValues(longArrayOf(100L, 200L), longArrayOf(10L, 20L)),
        private val answerMonitors: Boolean = true,
        private val answerReadings: Boolean = true,
        private val onBackgroundThread: Boolean = false,
    ) : PowerStatsCalls {
        var readingIndices: IntArray? = null

        override fun getSupportedPowerMonitors(onResult: (List<PowerMonitorInfo>?) -> Unit) {
            if (answerMonitors) deliver { onResult(monitors) }
        }

        override fun getPowerMonitorReadings(indices: IntArray, onResult: (PowerMonitorValues?) -> Unit) {
            readingIndices = indices
            if (answerReadings) deliver { onResult(values) }
        }

        private fun deliver(block: () -> Unit) {
            if (onBackgroundThread) Thread(block).start() else block()
        }
    }

    private fun client(calls: PowerStatsCalls, sdkInt: Int = 35, timeoutMs: Long = 2_000L) =
        PowerStatsClient({ fakeBinder }, sdkInt, timeoutMs) { calls }

    @Test
    fun `api below 35 is unavailable with api reason and never resolves the binder`() {
        for (sdk in listOf(31, 33, 34)) {
            var binderCalls = 0
            val snapshot = PowerStatsClient({ binderCalls++; fakeBinder }, sdk, 2_000L) { FakePowerStatsCalls() }.read()
            assertUnavailable(Reasons.API, snapshot.available, snapshot.reason)
            assertEquals(0, binderCalls)
        }
    }

    @Test
    fun `api 35 and 37 read monitors with positional indices`() {
        for (sdk in listOf(35, 37)) {
            val calls = FakePowerStatsCalls(onBackgroundThread = true)
            val snapshot = client(calls, sdk).read()
            assertTrue(snapshot.available)
            assertNull(snapshot.reason)
            assertEquals(listOf(0, 1), calls.readingIndices?.toList())
            assertEquals(
                listOf(
                    PowerMonitorReading("VSYS_PWR_DISPLAY", 0, 100L, 10L),
                    PowerMonitorReading("CPU", 1, 200L, 20L),
                ),
                snapshot.monitors,
            )
        }
    }

    @Test
    fun `empty monitor list is available and skips readings`() {
        val calls = FakePowerStatsCalls(monitors = emptyList())
        val snapshot = client(calls).read()
        assertTrue(snapshot.available)
        assertTrue(snapshot.monitors.isEmpty())
        assertNull(calls.readingIndices)
    }

    @Test
    fun `no monitor callback times out`() {
        val snapshot = client(FakePowerStatsCalls(answerMonitors = false), timeoutMs = 50L).read()
        assertUnavailable(Reasons.TIMEOUT, snapshot.available, snapshot.reason)
    }

    @Test
    fun `no readings callback times out`() {
        val calls = FakePowerStatsCalls(answerReadings = false)
        val snapshot = client(calls, timeoutMs = 50L).read()
        assertUnavailable(Reasons.TIMEOUT, snapshot.available, snapshot.reason)
        assertEquals(listOf(0, 1), calls.readingIndices?.toList())
    }

    @Test
    fun `failed result maps to remote`() {
        val noMonitors = client(FakePowerStatsCalls(monitors = null)).read()
        assertUnavailable(Reasons.REMOTE, noMonitors.available, noMonitors.reason)
        val noValues = client(FakePowerStatsCalls(values = null)).read()
        assertUnavailable(Reasons.REMOTE, noValues.available, noValues.reason)
    }

    @Test
    fun `reading count mismatch maps to remote`() {
        val calls = FakePowerStatsCalls(values = PowerMonitorValues(longArrayOf(1L), longArrayOf(1L)))
        val snapshot = client(calls).read()
        assertUnavailable(Reasons.REMOTE, snapshot.available, snapshot.reason)
    }

    @Test
    fun `null binder and thrown errors map to reasons`() {
        val nullBinder = PowerStatsClient({ null }, 35, 2_000L) { FakePowerStatsCalls() }.read()
        assertUnavailable(Reasons.REMOTE, nullBinder.available, nullBinder.reason)

        val throwing = object : PowerStatsCalls {
            override fun getSupportedPowerMonitors(onResult: (List<PowerMonitorInfo>?) -> Unit) {
                throw NoSuchMethodError("getSupportedPowerMonitors")
            }

            override fun getPowerMonitorReadings(indices: IntArray, onResult: (PowerMonitorValues?) -> Unit) = Unit
        }
        val snapshot = client(throwing).read()
        assertUnavailable(Reasons.NO_SUCH_METHOD, snapshot.available, snapshot.reason)
    }
}
