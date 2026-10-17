package com.akane.inspiration.telemetry.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerCalibratorTest {
    private val calibrator = PowerCalibrator()

    @Test
    fun `microamp single cell discharge becomes negative`() {
        val calibration = calibrator.calibrate(listOf(discharge(sysfsCurrent = -500_000, sysfsVoltage = 4_200_000)))

        val reading = calibrator.toReading(discharge(sysfsCurrent = -500_000, sysfsVoltage = 4_200_000), calibration)

        assertEquals(0.001, calibration.currentScaleToMa, 0.0)
        assertTrue(calibration.dischargeIsNegative)
        assertEquals(-500.0, reading!!.currentMa, 0.0)
        assertEquals(4.2, reading.voltageV, 0.0)
        assertEquals(-2.1, reading.watts, 0.0)
    }

    @Test
    fun `milliamps are detected when battery manager reports microamps`() {
        val calibration = calibrator.calibrate(
            listOf(charging(sysfsCurrent = 500, bmCurrent = 500_000, sysfsVoltage = 4_200)),
        )

        val reading = calibrator.toReading(charging(sysfsCurrent = 500, sysfsVoltage = 4_200), calibration)

        assertEquals(1.0, calibration.currentScaleToMa, 0.0)
        assertEquals(500.0, reading!!.currentMa, 0.0)
    }

    @Test
    fun `positive discharge OEM current is inverted to charging-positive convention`() {
        val calibration = calibrator.calibrate(
            listOf(
                charging(sysfsCurrent = -400_000, sysfsVoltage = 4_100_000),
                discharge(sysfsCurrent = 300_000, sysfsVoltage = 4_100_000),
            ),
        )

        val reading = calibrator.toReading(discharge(sysfsCurrent = 300_000, sysfsVoltage = 4_100_000), calibration)

        assertFalse(calibration.dischargeIsNegative)
        assertEquals(-300.0, reading!!.currentMa, 0.0)
    }

    @Test
    fun `dual cell pack voltage selects two cells`() {
        val calibration = calibrator.calibrate(listOf(discharge(sysfsCurrent = -400_000, sysfsVoltage = 8_400_000)))

        val reading = calibrator.toReading(discharge(sysfsCurrent = -400_000, sysfsVoltage = 8_400_000), calibration)

        assertEquals(2, calibration.cellCount)
        assertEquals(-800.0, reading!!.currentMa, 0.0)
        assertEquals(8.4, reading.voltageV, 0.0)
    }

    @Test
    fun `single cell voltage with half sysfs current selects dual cell current scaling`() {
        val calibration = calibrator.calibrate(
            listOf(discharge(sysfsCurrent = -500_000, bmCurrent = -1_000_000, sysfsVoltage = 4_000_000)),
        )

        val reading = calibrator.toReading(discharge(sysfsCurrent = -500_000, sysfsVoltage = 4_000_000), calibration)

        assertEquals(2, calibration.cellCount)
        assertEquals(0.002, calibration.currentScaleToMa, 0.0)
        assertEquals(-1_000.0, reading!!.currentMa, 0.0)
    }

    @Test
    fun `battery manager only values use documented microamp and millivolt units`() {
        val calibration = calibrator.calibrate(listOf(discharge(bmCurrent = -250_000, bmVoltage = 3_900)))

        val reading = calibrator.toReading(discharge(bmCurrent = -250_000, bmVoltage = 3_900), calibration)

        assertEquals(-250.0, reading!!.currentMa, 0.0)
        assertEquals(3.9, reading.voltageV, 0.0)
        assertEquals(-0.975, reading.watts, 0.0)
    }

    @Test
    fun `sysfs only values use magnitude heuristics`() {
        val calibration = calibrator.calibrate(listOf(charging(sysfsCurrent = 750_000, sysfsVoltage = 4_000_000)))

        val reading = calibrator.toReading(charging(sysfsCurrent = 750_000, sysfsVoltage = 4_000_000), calibration)

        assertEquals(0.001, calibration.currentScaleToMa, 0.0)
        assertEquals(0.001, calibration.voltageScaleToMv, 0.0)
        assertEquals(750.0, reading!!.currentMa, 0.0)
    }

    @Test
    fun `battery manager milliamps are detected by thousand to one ratio`() {
        val calibration = calibrator.calibrate(
            listOf(charging(sysfsCurrent = 600_000, bmCurrent = 600, sysfsVoltage = 4_100_000)),
        )

        val reading = calibrator.toReading(charging(sysfsCurrent = 600_000, sysfsVoltage = 4_100_000), calibration)

        assertEquals(0.001, calibration.currentScaleToMa, 0.0)
        assertEquals(600.0, reading!!.currentMa, 0.0)
    }

    @Test
    fun `contradictory sign samples have low confidence`() {
        val calibration = calibrator.calibrate(
            listOf(
                charging(sysfsCurrent = 300_000, sysfsVoltage = 4_000_000),
                discharge(sysfsCurrent = 200_000, sysfsVoltage = 4_000_000),
            ),
        )

        assertEquals(Confidence.LOW, calibration.confidence)
    }

    @Test
    fun `empty input is uncalibrated`() {
        val calibration = calibrator.calibrate(emptyList())

        assertEquals(Confidence.UNCALIBRATED, calibration.confidence)
        assertNull(calibrator.toReading(discharge(sysfsCurrent = -1, sysfsVoltage = 4_000), calibration))
    }

    @Test
    fun `reading is null when current is missing`() {
        val calibration = calibrator.calibrate(listOf(charging(sysfsCurrent = 100_000, sysfsVoltage = 4_000_000)))

        assertNull(calibrator.toReading(charging(sysfsVoltage = 4_000_000), calibration))
    }

    @Test
    fun `reading is null when voltage is missing`() {
        val calibration = calibrator.calibrate(listOf(charging(sysfsCurrent = 100_000, sysfsVoltage = 4_000_000)))

        assertNull(calibrator.toReading(charging(sysfsCurrent = 100_000), calibration))
    }

    private fun charging(
        sysfsCurrent: Long? = null,
        sysfsVoltage: Long? = null,
        bmCurrent: Int? = null,
        bmVoltage: Int? = null,
    ): CalibrationInput = CalibrationInput(
        sysfsCurrentRaw = sysfsCurrent,
        sysfsVoltageRaw = sysfsVoltage,
        bmCurrentNowUa = bmCurrent,
        bmVoltageMv = bmVoltage,
        isCharging = true,
        plugged = true,
    )

    private fun discharge(
        sysfsCurrent: Long? = null,
        sysfsVoltage: Long? = null,
        bmCurrent: Int? = null,
        bmVoltage: Int? = null,
    ): CalibrationInput = CalibrationInput(
        sysfsCurrentRaw = sysfsCurrent,
        sysfsVoltageRaw = sysfsVoltage,
        bmCurrentNowUa = bmCurrent,
        bmVoltageMv = bmVoltage,
        isCharging = false,
        plugged = false,
    )
}
