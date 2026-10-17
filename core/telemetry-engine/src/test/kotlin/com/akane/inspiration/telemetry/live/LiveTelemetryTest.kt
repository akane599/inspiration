package com.akane.inspiration.telemetry.live

import com.akane.inspiration.telemetry.calibration.PowerCalibrator
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.source.FullRead
import com.akane.inspiration.telemetry.source.KernelRead
import com.akane.inspiration.telemetry.source.LightRead
import com.akane.inspiration.telemetry.source.TelemetrySource
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTelemetryTest {
    @Test
    fun `readings stops kernel polling when collection cancels`() = runTest {
        val source = CountingSource()

        val readings = LiveTelemetry(source, PowerCalibrator(), TestClocks())
            .readings(periodMs = 1_500)
            .take(1)
            .toList()

        assertEquals(1, readings.size)
        assertEquals(1, source.kernelCalls)
        testScheduler.advanceTimeBy(10_000)
        assertEquals(1, source.kernelCalls)
    }

    private class CountingSource : TelemetrySource {
        var kernelCalls = 0

        override suspend fun full(trigger: CaptureTrigger, historyCursor: Pair<Long, Long>?): FullRead? = null

        override fun light(): LightRead = LightRead(
            clock = SnapshotClock(1, 2, 3, 4, null, null),
            levelPct = 50,
            plugged = false,
            status = "discharging",
            temperatureDeciC = 250,
            voltageMv = 4000,
            currentMa = -100,
            chargeCounterUah = null,
        )

        override suspend fun kernelReading(): KernelRead {
            kernelCalls++
            return KernelRead(kernel = null, calibrationInput = null, light = light())
        }
    }

    private class TestClocks : Clocks {
        override fun wallMs(): Long = 1
        override fun elapsedRealtimeMs(): Long = 2
        override fun uptimeMs(): Long = 3
        override fun bootCount(): Int = 4
    }
}
