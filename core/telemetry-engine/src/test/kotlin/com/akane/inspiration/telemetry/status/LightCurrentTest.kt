package com.akane.inspiration.telemetry.status

import com.akane.inspiration.telemetry.calibration.CalibrationLocks
import com.akane.inspiration.telemetry.calibration.CalibrationStart
import com.akane.inspiration.telemetry.calibration.CalibrationStore
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.source.LightRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LightCurrentTest {
    @Test
    fun `a settled microamp unit with the documented sign keeps the current`() {
        assertEquals(-300, LightCurrent.calibratedMa(-300, locked(milliamps = false, dischargePositive = false)))
    }

    @Test
    fun `a kernel that reports discharge as positive has its sign flipped`() {
        assertEquals(-300, LightCurrent.calibratedMa(300, locked(milliamps = false, dischargePositive = true)))
        assertEquals(450, LightCurrent.calibratedMa(-450, locked(milliamps = false, dischargePositive = true)))
    }

    @Test
    fun `an earlier build's settled values still apply`() {
        val start = CalibrationStart(guesses = CalibrationLocks(batteryManagerMilliamps = false, dischargePositive = true))
        assertEquals(-120, LightCurrent.calibratedMa(120, start))
    }

    @Test
    fun `an unsettled unit is no reading, never a value that may be 1000x off`() {
        assertNull(LightCurrent.calibratedMa(-300, CalibrationStart(CalibrationLocks(dischargePositive = false))))
    }

    @Test
    fun `a milliamp unit is no reading, since the light read already cut it as microamps`() {
        assertNull(LightCurrent.calibratedMa(0, locked(milliamps = true, dischargePositive = false)))
    }

    @Test
    fun `an unsettled sign is no reading`() {
        assertNull(LightCurrent.calibratedMa(-300, CalibrationStart(CalibrationLocks(batteryManagerMilliamps = false))))
    }

    @Test
    fun `a missing current stays missing`() {
        assertNull(LightCurrent.calibratedMa(null, locked(milliamps = false, dischargePositive = false)))
    }

    @Test
    fun `calibrate applies the stored calibration to the light read's current only`() {
        val read = light(currentMa = 250)
        val calibrated = LightCurrent.calibrate(read, store(locked(milliamps = false, dischargePositive = true)))

        assertEquals(read.copy(currentMa = -250), calibrated)
    }

    @Test
    fun `nothing stored or a store that fails leaves no current`() {
        assertNull(LightCurrent.calibrate(light(currentMa = -250), store(null)).currentMa)
        val failing =
            object : CalibrationStore {
                override fun load(): CalibrationStart = error("disk")

                override fun save(start: CalibrationStart) = Unit
            }
        assertNull(LightCurrent.calibrate(light(currentMa = -250), failing).currentMa)
    }

    @Test
    fun `without a store the read is returned as it is`() {
        val read = light(currentMa = 250)
        assertSame(read, LightCurrent.calibrate(read, null))
    }

    private fun locked(
        milliamps: Boolean,
        dischargePositive: Boolean,
    ) = CalibrationStart(CalibrationLocks(batteryManagerMilliamps = milliamps, dischargePositive = dischargePositive))

    private fun store(start: CalibrationStart?) =
        object : CalibrationStore {
            override fun load(): CalibrationStart? = start

            override fun save(start: CalibrationStart) = Unit
        }

    private fun light(currentMa: Int?) =
        LightRead(
            clock = SnapshotClock(1, 2, 3, 4, null, null),
            levelPct = 70,
            plugged = true,
            status = "charging",
            temperatureDeciC = 250,
            voltageMv = 4_000,
            currentMa = currentMa,
            chargeCounterUah = 1_000_000,
        )
}
