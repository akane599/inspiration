package com.akane.inspiration.telemetry.calibration

import kotlin.math.abs

data class CalibrationInput(
    val sysfsCurrentRaw: Long?,
    val sysfsVoltageRaw: Long?,
    val bmCurrentNowUa: Int?,
    val bmVoltageMv: Int?,
    val isCharging: Boolean,
    val plugged: Boolean,
)

data class PowerCalibration(
    val currentScaleToMa: Double,
    val dischargeIsNegative: Boolean,
    val voltageScaleToMv: Double,
    val cellCount: Int,
    val confidence: Confidence,
)

enum class Confidence {
    HIGH,
    LOW,
    UNCALIBRATED,
}

data class PowerReading(
    val currentMa: Double,
    val voltageV: Double,
    val watts: Double,
)

class PowerCalibrator {
    fun calibrate(samples: List<CalibrationInput>): PowerCalibration {
        if (samples.none { it.hasCurrent() || it.hasVoltage() }) {
            return UNCALIBRATED
        }

        val baseCurrentScale = samples
            .firstNotNullOfOrNull { sample -> sample.currentScaleFromComparison() }
            ?: samples.firstNotNullOfOrNull { sample -> sample.sysfsCurrentRaw?.let(::currentScaleFromMagnitude) }
            ?: MICRO_TO_MILLI
        val voltageScale = samples
            .firstNotNullOfOrNull { sample -> sample.sysfsVoltageRaw?.let(::voltageScaleFromMagnitude) }
            ?: 1.0
        val signEvidence = samples.mapNotNull { it.dischargeNegativeEvidence() }
        val hasMixedSignEvidence = signEvidence.distinct().size > 1
        val dischargeIsNegative = signEvidence.groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: true
        val voltageV = samples.mapNotNull { it.voltageMv(voltageScale) / MILLIVOLTS_PER_VOLT }
        val hasDualCellVoltage = voltageV.any { it in DUAL_CELL_MIN_VOLTAGE..DUAL_CELL_MAX_VOLTAGE }
        val hasHalfCurrentSingleCellVoltage = samples.any { sample ->
            val sampleVoltageV = sample.voltageMv(voltageScale) / MILLIVOLTS_PER_VOLT
            sampleVoltageV in SINGLE_CELL_MIN_VOLTAGE..SINGLE_CELL_MAX_VOLTAGE &&
                sample.hasHalfOfBatteryManagerCurrent(baseCurrentScale)
        }
        val cellCount = if (hasDualCellVoltage || hasHalfCurrentSingleCellVoltage) 2 else 1
        val currentScale = baseCurrentScale * cellCount

        return PowerCalibration(
            currentScaleToMa = currentScale,
            dischargeIsNegative = dischargeIsNegative,
            voltageScaleToMv = voltageScale,
            cellCount = cellCount,
            confidence = if (hasMixedSignEvidence) Confidence.LOW else Confidence.HIGH,
        )
    }

    fun toReading(raw: CalibrationInput, cal: PowerCalibration): PowerReading? {
        if (cal.confidence == Confidence.UNCALIBRATED) return null

        val unnormalizedCurrentMa = raw.sysfsCurrentRaw?.toDouble()?.times(cal.currentScaleToMa)
            ?: raw.bmCurrentNowUa?.toDouble()?.times(MICRO_TO_MILLI)
            ?: return null
        val currentMa = if (cal.dischargeIsNegative) unnormalizedCurrentMa else -unnormalizedCurrentMa
        val voltageMv = raw.sysfsVoltageRaw?.toDouble()?.times(cal.voltageScaleToMv)
            ?: raw.bmVoltageMv?.toDouble()
            ?: return null
        val voltageV = voltageMv / MILLIVOLTS_PER_VOLT

        return PowerReading(
            currentMa = currentMa,
            voltageV = voltageV,
            watts = voltageV * currentMa / MILLIS_PER_AMPERE,
        )
    }

    private fun CalibrationInput.hasCurrent(): Boolean = sysfsCurrentRaw != null || bmCurrentNowUa != null

    private fun CalibrationInput.hasVoltage(): Boolean = sysfsVoltageRaw != null || bmVoltageMv != null

    private fun CalibrationInput.currentScaleFromComparison(): Double? {
        val sysfs = sysfsCurrentRaw?.absoluteMagnitude() ?: return null
        val batteryManager = bmCurrentNowUa?.toLong()?.absoluteMagnitude() ?: return null
        if (sysfs == 0L || batteryManager == 0L) return null

        val ratio = sysfs.toDouble() / batteryManager
        return when {
            ratio.isApproximately(1.0) -> MICRO_TO_MILLI
            ratio.isApproximately(1_000.0) -> MICRO_TO_MILLI
            ratio.isApproximately(0.001) -> 1.0
            else -> currentScaleFromMagnitude(sysfsCurrentRaw)
        }
    }

    private fun CalibrationInput.dischargeNegativeEvidence(): Boolean? {
        val current = sysfsCurrentRaw ?: bmCurrentNowUa?.toLong() ?: return null
        if (current == 0L) return null
        return if (isCharging) current > 0L else current < 0L
    }

    private fun CalibrationInput.voltageMv(voltageScale: Double): Double {
        return sysfsVoltageRaw?.toDouble()?.times(voltageScale) ?: bmVoltageMv?.toDouble() ?: Double.NaN
    }

    /**
     * A single-cell voltage paired with sysfs current near half the BatteryManager-derived current
     * indicates a dual-cell pack exposing per-cell current, so the sysfs current is doubled.
     */
    private fun CalibrationInput.hasHalfOfBatteryManagerCurrent(currentScale: Double): Boolean {
        val sysfs = sysfsCurrentRaw?.toDouble()?.let { abs(it * currentScale) } ?: return false
        val batteryManager = bmCurrentNowUa?.toDouble()?.let { abs(it * MICRO_TO_MILLI) } ?: return false
        if (batteryManager == 0.0) return false
        return (sysfs / batteryManager).isApproximately(0.5)
    }

    private fun currentScaleFromMagnitude(raw: Long): Double =
        if (raw.absoluteMagnitude() > MICROAMP_THRESHOLD) MICRO_TO_MILLI else 1.0

    private fun voltageScaleFromMagnitude(raw: Long): Double =
        if (raw.absoluteMagnitude() > MICROVOLT_THRESHOLD) MICRO_TO_MILLI else 1.0

    private fun Long.absoluteMagnitude(): Long = if (this == Long.MIN_VALUE) Long.MAX_VALUE else abs(this)

    private fun Double.isApproximately(expected: Double): Boolean =
        this in expected * APPROXIMATION_MIN..expected * APPROXIMATION_MAX

    private companion object {
        const val MICRO_TO_MILLI = 0.001
        const val MILLIVOLTS_PER_VOLT = 1_000.0
        const val MILLIS_PER_AMPERE = 1_000.0
        const val MICROAMP_THRESHOLD = 20_000L
        const val MICROVOLT_THRESHOLD = 20_000L
        const val DUAL_CELL_MIN_VOLTAGE = 7.0
        const val DUAL_CELL_MAX_VOLTAGE = 9.0
        const val SINGLE_CELL_MIN_VOLTAGE = 3.0
        const val SINGLE_CELL_MAX_VOLTAGE = 5.0
        const val APPROXIMATION_MIN = 0.8
        const val APPROXIMATION_MAX = 1.2

        val UNCALIBRATED = PowerCalibration(
            currentScaleToMa = 0.0,
            dischargeIsNegative = true,
            voltageScaleToMv = 0.0,
            cellCount = 1,
            confidence = Confidence.UNCALIBRATED,
        )
    }
}
