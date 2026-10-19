package com.akane.inspiration.telemetry.calibration

import kotlin.math.abs

/**
 * One moment of raw battery values. The sysfs and BatteryManager currents are compared with each other, so both are
 * raw (BatteryManager in its reported µA, never rounded to mA) and read as close together as possible.
 */
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
    /** One calibration over [samples]; a live stream keeps a [CalibrationEvidence] instead, so its choices persist. */
    fun calibrate(samples: List<CalibrationInput>): PowerCalibration = CalibrationEvidence().apply { samples.forEach(::add) }.calibration()

    fun toReading(
        raw: CalibrationInput,
        cal: PowerCalibration,
    ): PowerReading? {
        if (cal.confidence == Confidence.UNCALIBRATED) return null

        val unnormalizedCurrentMa =
            raw.sysfsCurrentRaw?.toDouble()?.times(cal.currentScaleToMa)
                ?: raw.bmCurrentNowUa?.toDouble()?.times(MICRO_TO_MILLI)
                ?: return null
        val currentMa = if (cal.dischargeIsNegative) unnormalizedCurrentMa else -unnormalizedCurrentMa
        val voltageMv =
            raw.sysfsVoltageRaw?.toDouble()?.times(cal.voltageScaleToMv)
                ?: raw.bmVoltageMv?.toDouble()
                ?: return null
        val voltageV = voltageMv / MILLIVOLTS_PER_VOLT

        return PowerReading(
            currentMa = currentMa,
            voltageV = voltageV,
            watts = voltageV * currentMa / MILLIS_PER_AMPERE,
        )
    }
}

/**
 * The current unit, sign convention, cell layout and voltage unit are constants of the device, so each is decided by
 * agreement across many samples, never re-decided from the latest one. A choice that [LOCK_LEAD] more samples support
 * than oppose is kept for good. Until then the evidence so far decides, and with none the documented units apply
 * (µA and µV, discharge negative, one cell).
 *
 * A sample whose current is under [NOISE_FLOOR_MA] adds no current evidence. Near zero, a little noise or a load
 * change between the two reads swings the sysfs/BatteryManager ratio anywhere, and a small value cannot tell µA from
 * mA. A sample whose two currents fit no known ratio adds no ratio evidence either.
 *
 * Not thread-safe: a shared instance needs external synchronization.
 */
class CalibrationEvidence {
    private var hasCurrent = false
    private var hasVoltage = false
    private var dualCellVoltage = false

    // Unit of the sysfs current. Proof: a value over 20 A in mA can only be µA, and BatteryManager (µA) reading a
    // thousand times sysfs at a real load means mA. Weaker: an unplugged device running the screen draws well over
    // 20 mA, so a smaller non-zero value suggests mA; with no µA proof at all, only a run of them switches units.
    private var microampProofs = 0
    private var milliampProofs = 0
    private var unpluggedSmallCurrents = 0
    private var milliamps: Boolean? = null

    // sysfs reports the per-cell current of a two-cell pack: half of what BatteryManager reports. Doubles the current
    // only once locked, so a load swing between the two reads cannot double it.
    private val halfCurrent = Vote()

    // An unplugged battery can only discharge, so unplugged samples prove the kernel's sign convention. A plugged
    // battery's status cannot (one reading "charging" can still lose to the load), so it decides only without proof.
    private val unpluggedDischargePositive = Vote()
    private val statusDischargePositive = Vote()

    private val microvolts = Vote()

    fun add(sample: CalibrationInput) {
        hasCurrent = hasCurrent || sample.sysfsCurrentRaw != null || sample.bmCurrentNowUa != null
        hasVoltage = hasVoltage || sample.sysfsVoltageRaw != null || sample.bmVoltageMv != null
        sample.sysfsVoltageRaw
            ?.absoluteMagnitude()
            ?.takeIf { it > 0L }
            ?.let { microvolts.add(it > MICROVOLT_THRESHOLD) }
        addUnitEvidence(sample)

        val currentScale = baseCurrentScale()
        val voltageV = sample.voltageMv(voltageScale()) / MILLIVOLTS_PER_VOLT
        if (voltageV in DUAL_CELL_MIN_VOLTAGE..DUAL_CELL_MAX_VOLTAGE) dualCellVoltage = true
        if (voltageV in SINGLE_CELL_MIN_VOLTAGE..SINGLE_CELL_MAX_VOLTAGE) addHalfCurrentEvidence(sample, currentScale)
        addSignEvidence(sample, currentScale)
    }

    fun calibration(): PowerCalibration {
        if (!hasCurrent && !hasVoltage) return UNCALIBRATED
        val perCellCurrent = halfCurrent.locked == true
        val dischargePositive = unpluggedDischargePositive.decision() ?: statusDischargePositive.decision() ?: false
        val signVotes = unpluggedDischargePositive + statusDischargePositive
        return PowerCalibration(
            // Series pack voltage already includes both cells; only a per-cell current needs doubling.
            currentScaleToMa = baseCurrentScale() * if (perCellCurrent) 2 else 1,
            dischargeIsNegative = !dischargePositive,
            voltageScaleToMv = voltageScale(),
            cellCount = if (dualCellVoltage || perCellCurrent) 2 else 1,
            confidence = if (signVotes.yes > 0 && signVotes.no > 0) Confidence.LOW else Confidence.HIGH,
        )
    }

    private fun addUnitEvidence(sample: CalibrationInput) {
        if (milliamps != null) return
        val sysfs = sample.sysfsCurrentRaw?.absoluteMagnitude() ?: return
        val batteryManager = sample.bmCurrentNowUa?.toLong()?.absoluteMagnitude()
        when {
            sysfs > MICROAMP_THRESHOLD -> microampProofs++

            batteryManager != null && batteryManager >= NOISE_FLOOR_UA && sysfs > 0L &&
                (sysfs.toDouble() / batteryManager).isApproximately(MICRO_TO_MILLI) -> milliampProofs++

            !sample.plugged && sysfs >= MIN_UNPLUGGED_DRAW_MA -> unpluggedSmallCurrents++
        }
        milliamps =
            when {
                milliampProofs - microampProofs >= LOCK_LEAD -> true
                microampProofs - milliampProofs >= LOCK_LEAD -> false
                microampProofs == 0 && unpluggedSmallCurrents >= UNPLUGGED_SMALL_LOCK -> true
                else -> null
            }
    }

    private fun addHalfCurrentEvidence(
        sample: CalibrationInput,
        currentScale: Double,
    ) {
        val sysfsMa = sample.sysfsCurrentRaw?.let { abs(it * currentScale) } ?: return
        val batteryManagerMa = sample.bmCurrentNowUa?.let { abs(it * MICRO_TO_MILLI) } ?: return
        if (batteryManagerMa < NOISE_FLOOR_MA) return
        val ratio = sysfsMa / batteryManagerMa
        when {
            ratio.isApproximately(HALF) -> halfCurrent.add(true)
            ratio.isApproximately(1.0) -> halfCurrent.add(false)
        }
    }

    private fun addSignEvidence(
        sample: CalibrationInput,
        currentScale: Double,
    ) {
        val (current, toMa) =
            sample.sysfsCurrentRaw?.let { it to currentScale }
                ?: sample.bmCurrentNowUa?.let { it.toLong() to MICRO_TO_MILLI }
                ?: return
        if (abs(current * toMa) < NOISE_FLOOR_MA) return
        if (!sample.plugged) {
            unpluggedDischargePositive.add(current > 0L)
        } else {
            statusDischargePositive.add(if (sample.isCharging) current < 0L else current > 0L)
        }
    }

    // Until units are locked only proof decides: weak hints act through the lock alone, never one sample at a time.
    private fun baseCurrentScale(): Double = if (milliamps ?: (milliampProofs > microampProofs)) 1.0 else MICRO_TO_MILLI

    private fun voltageScale(): Double = if (microvolts.decision() == false) 1.0 else MICRO_TO_MILLI

    private fun CalibrationInput.voltageMv(voltageScale: Double): Double =
        sysfsVoltageRaw?.toDouble()?.times(voltageScale) ?: bmVoltageMv?.toDouble() ?: Double.NaN

    /** Two-way agreement; once one side leads by [LOCK_LEAD] it is [locked] and later samples are ignored. */
    private class Vote(
        var yes: Int = 0,
        var no: Int = 0,
    ) {
        var locked: Boolean? = null
            private set

        fun add(value: Boolean) {
            if (locked != null) return
            if (value) yes++ else no++
            if (abs(yes - no) >= LOCK_LEAD) locked = yes > no
        }

        /** The locked choice, else the majority so far; null with no evidence or a tie. */
        fun decision(): Boolean? =
            locked ?: when {
                yes > no -> true
                no > yes -> false
                else -> null
            }

        operator fun plus(other: Vote) = Vote(yes + other.yes, no + other.no)
    }

    private companion object {
        /** Agreeing samples (net of contrary ones) that settle a device constant for good. */
        const val LOCK_LEAD = 3

        /** Unplugged small currents, with no µA proof at all, that settle mA. */
        const val UNPLUGGED_SMALL_LOCK = 10
        const val NOISE_FLOOR_MA = 50.0
        const val NOISE_FLOOR_UA = 50_000L
        const val MIN_UNPLUGGED_DRAW_MA = 50L
        const val HALF = 0.5

        val UNCALIBRATED =
            PowerCalibration(
                currentScaleToMa = 0.0,
                dischargeIsNegative = true,
                voltageScaleToMv = 0.0,
                cellCount = 1,
                confidence = Confidence.UNCALIBRATED,
            )
    }
}

private fun Long.absoluteMagnitude(): Long = if (this == Long.MIN_VALUE) Long.MAX_VALUE else abs(this)

private fun Double.isApproximately(expected: Double): Boolean = this in expected * APPROXIMATION_MIN..expected * APPROXIMATION_MAX

private const val MICRO_TO_MILLI = 0.001
private const val MILLIVOLTS_PER_VOLT = 1_000.0
private const val MILLIS_PER_AMPERE = 1_000.0
private const val MICROAMP_THRESHOLD = 20_000L
private const val MICROVOLT_THRESHOLD = 20_000L
private const val DUAL_CELL_MIN_VOLTAGE = 7.0
private const val DUAL_CELL_MAX_VOLTAGE = 9.0
private const val SINGLE_CELL_MIN_VOLTAGE = 3.0
private const val SINGLE_CELL_MAX_VOLTAGE = 5.0
private const val APPROXIMATION_MIN = 0.8
private const val APPROXIMATION_MAX = 1.2
