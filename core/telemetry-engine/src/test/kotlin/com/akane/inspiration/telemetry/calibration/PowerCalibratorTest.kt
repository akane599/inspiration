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
        val calibration =
            calibrator.calibrate(
                listOf(charging(sysfsCurrent = 500, bmCurrent = 500_000, sysfsVoltage = 4_200)),
            )

        val reading = calibrator.toReading(charging(sysfsCurrent = 500, sysfsVoltage = 4_200), calibration)

        assertEquals(1.0, calibration.currentScaleToMa, 0.0)
        assertEquals(500.0, reading!!.currentMa, 0.0)
    }

    @Test
    fun `positive discharge OEM current is inverted to charging-positive convention`() {
        val calibration =
            calibrator.calibrate(
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
    fun `dual cell pack voltage preserves measured current in both directions`() {
        for (current in listOf(-400_000, 400_000)) {
            val input =
                discharge(sysfsCurrent = current.toLong(), bmCurrent = current, sysfsVoltage = 8_400_000)
                    .copy(isCharging = current > 0, plugged = current > 0)
            val calibration = calibrator.calibrate(listOf(input))
            val reading = calibrator.toReading(input, calibration)!!
            assertEquals(2, calibration.cellCount)
            assertEquals(current / 1000.0, reading.currentMa, 0.0)
            assertEquals(8.4, reading.voltageV, 0.0)
            assertEquals(current / 1000.0 * 8.4 / 1000, reading.watts, 1e-9)
        }
    }

    @Test
    fun `single cell voltage with repeatedly half sysfs current selects dual cell current scaling`() {
        val calibration =
            calibrator.calibrate(
                List(3) { discharge(sysfsCurrent = -500_000, bmCurrent = -1_000_000, sysfsVoltage = 4_000_000) },
            )

        val reading = calibrator.toReading(discharge(sysfsCurrent = -500_000, sysfsVoltage = 4_000_000), calibration)

        assertEquals(2, calibration.cellCount)
        assertEquals(0.002, calibration.currentScaleToMa, 0.0)
        assertEquals(-1_000.0, reading!!.currentMa, 0.0)
    }

    @Test
    fun `one load swing between the two current reads does not double the current`() {
        // 400 mA in sysfs, 800 mA when BatteryManager was read: the ratio looks like a per-cell current once.
        val input = discharge(sysfsCurrent = -400_000, bmCurrent = -800_000, sysfsVoltage = 4_000_000)

        val calibration = calibrator.calibrate(listOf(input))

        assertEquals(1, calibration.cellCount)
        assertEquals(-400.0, calibrator.toReading(input, calibration)!!.currentMa, 0.0)
    }

    @Test
    fun `near zero microamp currents are never read as milliamps`() {
        // Sub-20 mA values on a µA kernel: at a charge limit, with BatteryManager skewed, rounded to 0 or matching.
        val cases =
            listOf(
                charging(sysfsCurrent = 1_500, bmCurrent = 1_000, sysfsVoltage = 4_350_000) to 1.5,
                charging(sysfsCurrent = 700, bmCurrent = 0, sysfsVoltage = 4_350_000) to 0.7,
                charging(sysfsCurrent = 15_000, bmCurrent = 11_000, sysfsVoltage = 4_350_000) to 15.0,
                charging(sysfsCurrent = 1_500, bmCurrent = 1_500, sysfsVoltage = 4_350_000) to 1.5,
            )

        for ((input, expectedMa) in cases) {
            val reading = calibrator.toReading(input, calibrator.calibrate(listOf(input)))!!
            assertEquals("$input", expectedMa, reading.currentMa, 1e-9)
        }
    }

    @Test
    fun `a stream keeps its learned scale through near zero and swinging currents`() {
        val evidence = CalibrationEvidence()

        fun read(input: CalibrationInput): Double {
            evidence.add(input)
            return calibrator.toReading(input, evidence.calibration())!!.currentMa
        }

        // Unplugged at a real load: µA, discharge negative, sysfs and BatteryManager agree.
        repeat(3) { assertEquals(-300.0, read(discharge(sysfsCurrent = -300_000, bmCurrent = -300_000, sysfsVoltage = 3_900_000)), 0.0) }
        // Then plugged and held: a few mA, skewed reads, a load doubling between the reads.
        assertEquals(1.5, read(charging(sysfsCurrent = 1_500, bmCurrent = 1_000, sysfsVoltage = 4_350_000)), 1e-9)
        assertEquals(15.0, read(charging(sysfsCurrent = 15_000, bmCurrent = 11_000, sysfsVoltage = 4_350_000)), 1e-9)
        assertEquals(-400.0, read(discharge(sysfsCurrent = -400_000, bmCurrent = -800_000, sysfsVoltage = 3_900_000)), 0.0)
        assertEquals(-2.0, read(charging(sysfsCurrent = -2_000, bmCurrent = -2_000, sysfsVoltage = 4_350_000)), 1e-9)
        assertEquals(1, evidence.calibration().cellCount)
    }

    @Test
    fun `a learned sign convention shows a plugged battery losing to the load as draining`() {
        val evidence = CalibrationEvidence()
        repeat(3) { evidence.add(discharge(sysfsCurrent = -300_000, bmCurrent = -300_000, sysfsVoltage = 3_900_000)) }
        // Status "charging" on a weak charger, while the battery still loses 250 mA.
        val weakCharger = charging(sysfsCurrent = -250_000, bmCurrent = -250_000, sysfsVoltage = 3_900_000)

        evidence.add(weakCharger)

        assertTrue(evidence.calibration().dischargeIsNegative)
        assertEquals(-250.0, calibrator.toReading(weakCharger, evidence.calibration())!!.currentMa, 0.0)
    }

    @Test
    fun `an inverted kernel sign is learned from unplugged samples, not from the plugged status`() {
        val evidence = CalibrationEvidence()
        repeat(3) { evidence.add(discharge(sysfsCurrent = 300_000, bmCurrent = 300_000, sysfsVoltage = 3_900_000)) }
        repeat(5) { evidence.add(charging(sysfsCurrent = 250_000, bmCurrent = 250_000, sysfsVoltage = 3_900_000)) }

        val reading = calibrator.toReading(discharge(sysfsCurrent = 300_000, sysfsVoltage = 3_900_000), evidence.calibration())

        assertFalse(evidence.calibration().dischargeIsNegative)
        assertEquals(-300.0, reading!!.currentMa, 0.0)
    }

    @Test
    fun `a milliamp kernel matching battery manager reads milliamps from its first unplugged reading`() {
        // The HAL passes current_now through, so both read the same mA value and their ratio proves nothing.
        val evidence = CalibrationEvidence()
        val unplugged = discharge(sysfsCurrent = -400, bmCurrent = -400, sysfsVoltage = 3_900_000)

        evidence.add(unplugged)

        assertEquals(1.0, evidence.calibration().currentScaleToMa, 0.0)
        assertEquals(-400.0, calibrator.toReading(unplugged, evidence.calibration())!!.currentMa, 0.0)
        // A plugged reading near zero then stays in mA as well.
        evidence.add(charging(sysfsCurrent = 3, bmCurrent = 3, sysfsVoltage = 4_350_000))
        assertEquals(1.0, evidence.calibration().currentScaleToMa, 0.0)
    }

    @Test
    fun `a reading over 20 A in milliamps overrides the unplugged milliamp prior`() {
        val evidence = CalibrationEvidence()
        repeat(2) { evidence.add(discharge(sysfsCurrent = -8_000, bmCurrent = -8_000, sysfsVoltage = 3_900_000)) }

        evidence.add(discharge(sysfsCurrent = -300_000, bmCurrent = -300_000, sysfsVoltage = 3_900_000))

        assertEquals(0.001, evidence.calibration().currentScaleToMa, 0.0)
    }

    @Test
    fun `plugged readings never feed the milliamp prior`() {
        // A µA kernel held at a charge limit reads a few mA for as long as it stays plugged.
        val evidence = CalibrationEvidence()
        val held = charging(sysfsCurrent = 2_000, bmCurrent = 2_000, sysfsVoltage = 4_350_000)

        repeat(20) { evidence.add(held) }

        assertEquals(0.001, evidence.calibration().currentScaleToMa, 0.0)
        assertEquals(2.0, calibrator.toReading(held, evidence.calibration())!!.currentMa, 1e-9)
    }

    @Test
    fun `battery manager only milliamps are learned from unplugged readings`() {
        // sysfs unreadable, and a HAL that passes the kernel's mA through as BatteryManager's current.
        val evidence = CalibrationEvidence()
        val unplugged = discharge(bmCurrent = -400, bmVoltage = 3_900)

        evidence.add(unplugged)

        assertEquals(-400.0, calibrator.toReading(unplugged, evidence.calibration())!!.currentMa, 0.0)
    }

    @Test
    fun `plugged status votes never lock the sign, so a weak charger cannot invert a later charge`() {
        val evidence = CalibrationEvidence()
        // A weak charger: status "charging" while a discharge-negative battery still loses 250 mA.
        repeat(5) { evidence.add(charging(sysfsCurrent = -250_000, bmCurrent = -250_000, sysfsVoltage = 3_900_000)) }
        // Then the load drops and the charger wins: 1 A into the battery.
        val charge = charging(sysfsCurrent = 1_000_000, bmCurrent = 1_000_000, sysfsVoltage = 3_900_000)

        repeat(2) { evidence.add(charge) }

        assertTrue(evidence.calibration().dischargeIsNegative)
        assertEquals(1_000.0, calibrator.toReading(charge, evidence.calibration())!!.currentMa, 0.0)
    }

    @Test
    fun `a plugged battery that is not charging casts no sign vote`() {
        // Held at a charge limit or full: the source does not report it as charging, and a top-up current into a
        // discharge-negative battery says nothing about which way it is flowing.
        val evidence = CalibrationEvidence()
        val held = charging(sysfsCurrent = 120_000, bmCurrent = 120_000, sysfsVoltage = 4_350_000).copy(isCharging = false)

        repeat(5) { evidence.add(held) }

        assertTrue(evidence.calibration().dischargeIsNegative)
        assertEquals(120.0, calibrator.toReading(held, evidence.calibration())!!.currentMa, 0.0)
    }

    @Test
    fun `three agreeing readings settle the constants a later process starts from, and a plugged status none`() {
        val evidence = CalibrationEvidence()
        repeat(10) { evidence.add(charging(sysfsCurrent = -250_000, bmCurrent = -250_000, sysfsVoltage = 3_900_000)) }
        assertNull("a plugged status never settles the sign", evidence.locks().dischargePositive)

        repeat(3) { evidence.add(discharge(sysfsCurrent = -400_000, bmCurrent = -400_000, sysfsVoltage = 3_900_000)) }

        assertEquals(
            CalibrationLocks(
                sysfsMilliamps = false,
                batteryManagerMilliamps = false,
                dischargePositive = false,
                perCellCurrent = false,
                sysfsMicrovolts = true,
            ),
            evidence.locks(),
        )
    }

    @Test
    fun `restored locks apply from the first reading and outweigh it`() {
        val restored = CalibrationLocks(sysfsMilliamps = true, dischargePositive = true, perCellCurrent = true, sysfsMicrovolts = false)
        val evidence = CalibrationEvidence(restored)
        // A reading that alone would mean µA, a discharge-negative kernel and a single cell.
        val reading = discharge(sysfsCurrent = -300_000, bmCurrent = -300_000, sysfsVoltage = 3_900)

        evidence.add(reading)

        assertEquals(restored, evidence.locks())
        assertEquals(2.0, evidence.calibration().currentScaleToMa, 0.0)
        assertFalse(evidence.calibration().dischargeIsNegative)
        assertEquals(1.0, evidence.calibration().voltageScaleToMv, 0.0)
    }

    @Test
    fun `small unplugged readings never switch a kernel that has shown microamps`() {
        val evidence = CalibrationEvidence()
        evidence.add(discharge(sysfsCurrent = -300_000, bmCurrent = -300_000, sysfsVoltage = 3_900_000))
        repeat(20) { evidence.add(discharge(sysfsCurrent = -8_000, bmCurrent = -8_000, sysfsVoltage = 3_900_000)) }

        assertEquals(0.001, evidence.calibration().currentScaleToMa, 0.0)
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
        val calibration =
            calibrator.calibrate(
                listOf(charging(sysfsCurrent = 600_000, bmCurrent = 600, sysfsVoltage = 4_100_000)),
            )

        val reading = calibrator.toReading(charging(sysfsCurrent = 600_000, sysfsVoltage = 4_100_000), calibration)

        assertEquals(0.001, calibration.currentScaleToMa, 0.0)
        assertEquals(1.0, calibration.batteryManagerCurrentScaleToMa, 0.0)
        assertEquals(600.0, reading!!.currentMa, 0.0)
    }

    @Test
    fun `contradictory sign samples have low confidence`() {
        val calibration =
            calibrator.calibrate(
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

    @Test
    fun `matching low microamp readings retain documented units during taper`() {
        for (current in listOf(-10_000, 10_000)) {
            val input =
                discharge(sysfsCurrent = current.toLong(), bmCurrent = current, sysfsVoltage = 4_000)
                    .copy(isCharging = current > 0, plugged = true)
            val calibration = calibrator.calibrate(listOf(input))
            val reading = calibrator.toReading(input, calibration)!!

            assertEquals(0.001, calibration.currentScaleToMa, 0.0)
            assertEquals(current / 1_000.0, reading.currentMa, 0.0)
        }
    }

    @Test
    fun `plugged discharge keeps a negative current and power`() {
        val input =
            discharge(sysfsCurrent = -100_000, bmCurrent = -100_000, sysfsVoltage = 4_000)
                .copy(plugged = true)
        val reading = calibrator.toReading(input, calibrator.calibrate(listOf(input)))!!

        assertEquals(-100.0, reading.currentMa, 0.0)
        assertEquals(-0.4, reading.watts, 0.0)
    }

    private fun charging(
        sysfsCurrent: Long? = null,
        sysfsVoltage: Long? = null,
        bmCurrent: Int? = null,
        bmVoltage: Int? = null,
    ): CalibrationInput =
        CalibrationInput(
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
    ): CalibrationInput =
        CalibrationInput(
            sysfsCurrentRaw = sysfsCurrent,
            sysfsVoltageRaw = sysfsVoltage,
            bmCurrentNowUa = bmCurrent,
            bmVoltageMv = bmVoltage,
            isCharging = false,
            plugged = false,
        )
}
