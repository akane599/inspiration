package com.akane.inspiration.telemetry.calibration

import kotlin.math.abs

/**
 * One moment of raw battery values. The sysfs and BatteryManager currents are compared with each other, so both are
 * raw (BatteryManager as reported, never rounded to mA) and read as close together as possible. [isCharging] is the
 * battery's own "charging" status only: a full battery, or one held at a charge limit, is not charging.
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
    /** For a reading without a sysfs current. µA by contract, but a HAL that passes `current_now` through reports mA. */
    val batteryManagerCurrentScaleToMa: Double = MICRO_TO_MILLI,
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

/**
 * The device constants a [CalibrationEvidence] has settled for good, null where it has not settled one. They belong to
 * the fuel-gauge driver and the health HAL, not to one process, so a later process starts from them ([CalibrationStore]).
 */
data class CalibrationLocks(
    /** The sysfs current is in mA (true) or µA (false). */
    val sysfsMilliamps: Boolean? = null,
    /** BatteryManager's current is in mA (true) or µA (false). */
    val batteryManagerMilliamps: Boolean? = null,
    /** Proved by unplugged readings only, never by a plugged battery's status. */
    val dischargePositive: Boolean? = null,
    /** sysfs reports the per-cell current of a two-cell pack. */
    val perCellCurrent: Boolean? = null,
    /** The sysfs voltage is in µV (true) or mV (false). */
    val sysfsMicrovolts: Boolean? = null,
)

/** Keeps [CalibrationLocks] across processes, so a new one does not re-learn them from scratch. */
interface CalibrationStore {
    /** The locks saved for this device and build, or null when there are none. */
    fun load(): CalibrationLocks?

    fun save(locks: CalibrationLocks)
}

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
                ?: raw.bmCurrentNowUa?.toDouble()?.times(cal.batteryManagerCurrentScaleToMa)
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
 * The current units, sign convention, cell layout and voltage unit are constants of the device, so each is decided by
 * agreement across samples, never re-decided from the latest one. A choice that [LOCK_LEAD] more samples support than
 * oppose is kept for good ([locks]), and a later process starts from it ([restored]). Until then the evidence so far
 * decides, and with none the documented units apply (µA and µV, discharge negative, one cell).
 *
 * A sample whose current is under [NOISE_FLOOR_MA] adds no sign or cell evidence. Near zero, a little noise or a load
 * change between the two reads swings the sysfs/BatteryManager ratio anywhere. A sample whose two currents fit no
 * known ratio adds no ratio evidence either.
 *
 * Not thread-safe: a shared instance needs external synchronization.
 */
class CalibrationEvidence(
    restored: CalibrationLocks = CalibrationLocks(),
) {
    private var hasCurrent = false
    private var hasVoltage = false
    private var dualCellVoltage = false

    private val sysfsUnit = UnitEvidence(restored.sysfsMilliamps)
    private val batteryManagerUnit = UnitEvidence(restored.batteryManagerMilliamps)

    // sysfs reports the per-cell current of a two-cell pack: half of what BatteryManager reports. Doubles the current
    // only once locked, so a load swing between the two reads cannot double it.
    private val halfCurrent = Vote(restored.perCellCurrent)

    // An unplugged battery can only discharge, so unplugged samples prove the kernel's sign convention. A plugged
    // "charging" status cannot: a weak charger still loses to the load. Its votes never lock, and overturn the
    // documented convention only by a clear majority and only without proof. A full or held battery casts none.
    private val unpluggedDischargePositive = Vote(restored.dischargePositive)
    private val statusDischargePositive = Vote(lockable = false)

    private val microvolts = Vote(restored.sysfsMicrovolts)

    fun add(sample: CalibrationInput) {
        hasCurrent = hasCurrent || sample.sysfsCurrentRaw != null || sample.bmCurrentNowUa != null
        hasVoltage = hasVoltage || sample.sysfsVoltageRaw != null || sample.bmVoltageMv != null
        sample.sysfsVoltageRaw
            ?.absoluteMagnitude()
            ?.takeIf { it > 0L }
            ?.let { microvolts.add(it > MICROVOLT_THRESHOLD) }
        val sysfs = sample.sysfsCurrentRaw?.absoluteMagnitude()
        val batteryManager = sample.bmCurrentNowUa?.toLong()?.absoluteMagnitude()
        sysfs?.let { sysfsUnit.add(it, other = batteryManager, plugged = sample.plugged) }
        batteryManager?.let { batteryManagerUnit.add(it, other = sysfs, plugged = sample.plugged) }

        val voltageV = sample.voltageMv(voltageScale()) / MILLIVOLTS_PER_VOLT
        if (voltageV in DUAL_CELL_MIN_VOLTAGE..DUAL_CELL_MAX_VOLTAGE) dualCellVoltage = true
        if (voltageV in SINGLE_CELL_MIN_VOLTAGE..SINGLE_CELL_MAX_VOLTAGE) addHalfCurrentEvidence(sample)
        addSignEvidence(sample)
    }

    fun calibration(): PowerCalibration {
        if (!hasCurrent && !hasVoltage) return UNCALIBRATED
        val perCellCurrent = halfCurrent.locked == true
        val dischargePositive = unpluggedDischargePositive.decision() ?: statusDischargePositive.clearMajority() ?: false
        val mixedSigns =
            unpluggedDischargePositive.yes + statusDischargePositive.yes > 0 &&
                unpluggedDischargePositive.no + statusDischargePositive.no > 0
        return PowerCalibration(
            // Series pack voltage already includes both cells; only a per-cell current needs doubling.
            currentScaleToMa = sysfsUnit.scaleToMa() * if (perCellCurrent) 2 else 1,
            dischargeIsNegative = !dischargePositive,
            voltageScaleToMv = voltageScale(),
            cellCount = if (dualCellVoltage || perCellCurrent) 2 else 1,
            confidence = if (mixedSigns) Confidence.LOW else Confidence.HIGH,
            batteryManagerCurrentScaleToMa = batteryManagerUnit.scaleToMa(),
        )
    }

    /** What is settled for good, for a later process to start from. */
    fun locks(): CalibrationLocks =
        CalibrationLocks(
            sysfsMilliamps = sysfsUnit.locked,
            batteryManagerMilliamps = batteryManagerUnit.locked,
            dischargePositive = unpluggedDischargePositive.locked,
            perCellCurrent = halfCurrent.locked,
            sysfsMicrovolts = microvolts.locked,
        )

    private fun addHalfCurrentEvidence(sample: CalibrationInput) {
        val sysfsMa = sample.sysfsCurrentRaw?.let { abs(it * sysfsUnit.scaleToMa()) } ?: return
        val batteryManagerMa = sample.bmCurrentNowUa?.let { abs(it * batteryManagerUnit.scaleToMa()) } ?: return
        if (batteryManagerMa < NOISE_FLOOR_MA) return
        val ratio = sysfsMa / batteryManagerMa
        when {
            ratio.isApproximately(HALF) -> halfCurrent.add(true)
            ratio.isApproximately(1.0) -> halfCurrent.add(false)
        }
    }

    private fun addSignEvidence(sample: CalibrationInput) {
        val currentMa =
            sample.sysfsCurrentRaw?.let { it * sysfsUnit.scaleToMa() }
                ?: sample.bmCurrentNowUa?.let { it * batteryManagerUnit.scaleToMa() }
                ?: return
        if (abs(currentMa) < NOISE_FLOOR_MA) return
        when {
            !sample.plugged -> unpluggedDischargePositive.add(currentMa > 0.0)
            sample.isCharging -> statusDischargePositive.add(currentMa < 0.0)
        }
    }

    private fun voltageScale(): Double = if (microvolts.decision() == false) 1.0 else MICRO_TO_MILLI

    private fun CalibrationInput.voltageMv(voltageScale: Double): Double =
        sysfsVoltageRaw?.toDouble()?.times(voltageScale) ?: bmVoltageMv?.toDouble() ?: Double.NaN

    /**
     * The unit of one source's current: µA by contract until shown otherwise.
     *
     * Proof: a value over 20 A in mA can only be µA, and the other source reading a thousand times this one at a real
     * load means mA. A prior: the live gauge runs while the screen is on, and an unplugged phone with its screen on
     * draws well over 20 mA, so an unplugged value of 50 to 20,000 means mA from the first one. Any µA proof overrides
     * the prior. Plugged values never feed it, since a µA battery held at a charge limit reads a few mA.
     */
    private class UnitEvidence(
        restored: Boolean?,
    ) {
        /** True for mA, false for µA, once settled for good. */
        var locked: Boolean? = restored
            private set
        private var microampProofs = 0
        private var milliampProofs = 0
        private var unpluggedHints = 0

        fun add(
            magnitude: Long,
            other: Long?,
            plugged: Boolean,
        ) {
            if (locked != null) return
            when {
                magnitude > MICROAMP_THRESHOLD -> {
                    microampProofs++
                }

                other != null && other >= NOISE_FLOOR_UA && magnitude > 0L &&
                    (magnitude.toDouble() / other).isApproximately(MICRO_TO_MILLI) -> {
                    milliampProofs++
                }

                !plugged && magnitude >= MIN_UNPLUGGED_DRAW_MA -> {
                    unpluggedHints++
                }
            }
            locked =
                when {
                    milliampProofs - microampProofs >= LOCK_LEAD -> true
                    microampProofs - milliampProofs >= LOCK_LEAD -> false
                    microampProofs == 0 && milliampProofs + unpluggedHints >= LOCK_LEAD -> true
                    else -> null
                }
        }

        fun scaleToMa(): Double = if (milliamps()) 1.0 else MICRO_TO_MILLI

        private fun milliamps(): Boolean =
            locked ?: if (microampProofs + milliampProofs > 0) milliampProofs > microampProofs else unpluggedHints > 0
    }

    /**
     * Two-way agreement. Once one side leads by [LOCK_LEAD] it is [locked] and later samples are ignored, unless the
     * vote is not [lockable].
     */
    private class Vote(
        restored: Boolean? = null,
        private val lockable: Boolean = true,
    ) {
        var yes: Int = 0
            private set
        var no: Int = 0
            private set
        var locked: Boolean? = restored
            private set

        fun add(value: Boolean) {
            if (locked != null) return
            if (value) yes++ else no++
            if (lockable && abs(yes - no) >= LOCK_LEAD) locked = yes > no
        }

        /** The locked choice, else the majority so far; null with no evidence or a tie. */
        fun decision(): Boolean? =
            locked ?: when {
                yes > no -> true
                no > yes -> false
                else -> null
            }

        /** The side that leads by [LOCK_LEAD] and outnumbers the other [CLEAR_MAJORITY] to one; null otherwise. */
        fun clearMajority(): Boolean? =
            when {
                yes - no >= LOCK_LEAD && yes >= CLEAR_MAJORITY * no -> true
                no - yes >= LOCK_LEAD && no >= CLEAR_MAJORITY * yes -> false
                else -> null
            }
    }

    private companion object {
        /** Agreeing samples (net of contrary ones) that settle a device constant for good. */
        const val LOCK_LEAD = 3

        /** How many times the other side's count a vote that never locks needs to overturn a default. */
        const val CLEAR_MAJORITY = 3
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
