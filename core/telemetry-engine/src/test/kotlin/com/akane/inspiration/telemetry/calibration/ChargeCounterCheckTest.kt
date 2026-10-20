package com.akane.inspiration.telemetry.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeCounterCheckTest {
    private val calibrator = PowerCalibrator()

    @Test
    fun `a plugged microamp kernel near zero settles its unit from the charge counter`() {
        val evidence = CalibrationEvidence()
        // A fresh install held at a charge limit: 3,000 is 3 mA in µA or 3 A in mA. The counter gains 3 mA's worth,
        // 1.25 µAh a tick, not the 1,250 µAh a 3 A charge would add.
        val ticks = held(current = 3_000, counterPerTick = 1.25, count = 3 * TICKS_PER_MINUTE + 1)

        ticks.take(TICKS_PER_MINUTE / 2).forEach(evidence::add)
        assertTrue("half a minute is too short to tell", evidence.calibration().currentUnitAssumed)

        ticks.drop(TICKS_PER_MINUTE / 2).take(TICKS_PER_MINUTE / 2 + 1).forEach(evidence::add)
        val settled = evidence.calibration()
        assertFalse(settled.currentUnitAssumed)
        assertFalse(settled.batteryManagerCurrentUnitAssumed)
        assertEquals(0.001, settled.currentScaleToMa, 0.0)
        assertEquals(0.001, settled.batteryManagerCurrentScaleToMa, 0.0)
        assertEquals(3.0, calibrator.toReading(ticks.last(), settled)!!.currentMa, 1e-9)

        ticks.drop(TICKS_PER_MINUTE + 1).forEach(evidence::add)
        assertEquals("three windows lock it for the next process", false, evidence.locks().sysfsMilliamps)
        assertEquals(false, evidence.locks().batteryManagerMilliamps)
    }

    @Test
    fun `a plugged milliamp kernel settles its unit from the charge counter`() {
        val evidence = CalibrationEvidence()
        // A 2 A charge in mA: the counter gains about 833 µAh every 1.5 s tick.
        held(current = 2_000, counterPerTick = 833.33, count = TICKS_PER_MINUTE + 1).forEach(evidence::add)

        val settled = evidence.calibration()
        assertFalse(settled.currentUnitAssumed)
        assertEquals(1.0, settled.currentScaleToMa, 0.0)
        assertEquals(1.0, settled.batteryManagerCurrentScaleToMa, 0.0)
    }

    @Test
    fun `a charge counter that never moves settles nothing`() {
        val evidence = CalibrationEvidence()
        // A stuck counter would read every current as µA.
        held(current = 2_000, counterPerTick = 0.0, count = 5 * TICKS_PER_MINUTE).forEach(evidence::add)

        assertTrue(evidence.calibration().currentUnitAssumed)
        assertNull(evidence.locks().sysfsMilliamps)
    }

    @Test
    fun `a counter too small to be in microamp hours is not trusted`() {
        val evidence = CalibrationEvidence()
        // A mA kernel whose counter is in mAh: 2 A adds 0.83 a tick, which reads like a 2 mA current in µAh.
        held(current = 2_000, counterPerTick = 0.8333, count = 3 * TICKS_PER_MINUTE, counterStart = 3_000)
            .forEach(evidence::add)

        assertTrue(evidence.calibration().currentUnitAssumed)
    }

    @Test
    fun `the shared counter floor takes only a counter over 20,000 as microamp hours`() {
        // The same rule the cross-check uses is what every other counter reader gets: a mAh kernel's counter is null.
        assertNull(microampHourCounter(null))
        assertNull(microampHourCounter(0L))
        assertNull(microampHourCounter(3_000L))
        assertNull(microampHourCounter(MIN_MICROAMP_HOUR_COUNTER))
        assertEquals(MIN_MICROAMP_HOUR_COUNTER + 1, microampHourCounter(MIN_MICROAMP_HOUR_COUNTER + 1))
        assertEquals(3_000_000L, microampHourCounter(3_000_000L))
    }

    @Test
    fun `a charge counter jump never reads a microamp kernel as milliamps`() {
        val evidence = CalibrationEvidence()
        // A gauge re-learning its capacity moves the counter 40 mAh in one tick, far beyond what 20 A could add.
        val ticks =
            held(current = 3_000, counterPerTick = 1.25, count = 2 * TICKS_PER_MINUTE).mapIndexed { i, tick ->
                if (i >= 10) tick.copy(chargeCounterUah = tick.chargeCounterUah!! + 40_000) else tick
            }

        for (tick in ticks) {
            evidence.add(tick)
            assertEquals(0.001, evidence.calibration().currentScaleToMa, 0.0)
        }
        assertFalse("the window after the jump still settles µA", evidence.calibration().currentUnitAssumed)
    }

    @Test
    fun `charge is never integrated across a gap between readings`() {
        val evidence = CalibrationEvidence()
        // Two half-minute stretches an hour apart: neither window lasts long enough on its own.
        val first = held(current = 3_000, counterPerTick = 1.25, count = TICKS_PER_MINUTE / 2 + 1)
        val second =
            held(current = 3_000, counterPerTick = 1.25, count = TICKS_PER_MINUTE / 2 + 1, startMs = 3_600_000L)
        (first + second).forEach(evidence::add)

        assertTrue(evidence.calibration().currentUnitAssumed)
    }

    /** Readings every 1.5 s of a battery held plugged at [current] (sysfs and BatteryManager agreeing). */
    private fun held(
        current: Int,
        counterPerTick: Double,
        count: Int,
        counterStart: Long = 3_000_000L,
        startMs: Long = 100_000L,
    ): List<CalibrationInput> =
        List(count) { i ->
            CalibrationInput(
                sysfsCurrentRaw = current.toLong(),
                sysfsVoltageRaw = 4_350_000L,
                bmCurrentNowUa = current,
                bmVoltageMv = 4_350,
                isCharging = false,
                plugged = true,
                chargeCounterUah = counterStart + (i * counterPerTick).toLong(),
                elapsedRealtimeMs = startMs + i * TICK_MS,
            )
        }

    private companion object {
        const val TICK_MS = 1_500L
        const val TICKS_PER_MINUTE = 40
    }
}
