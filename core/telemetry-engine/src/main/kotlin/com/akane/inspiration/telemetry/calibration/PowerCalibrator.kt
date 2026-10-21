package com.akane.inspiration.telemetry.calibration

import kotlin.math.abs

/**
 * One moment of raw battery values. The sysfs and BatteryManager currents are compared with each other, so both are
 * raw (BatteryManager as reported, never rounded to mA) and read as close together as possible. [isCharging] is the
 * battery's own "charging" status only: a full battery, or one held at a charge limit, is not charging.
 * [chargeCounterUah] and [elapsedRealtimeMs] feed the charge-counter cross-check ([ChargeCounterCheck]); without them
 * it adds nothing.
 */
data class CalibrationInput(
    val sysfsCurrentRaw: Long?,
    val sysfsVoltageRaw: Long?,
    val bmCurrentNowUa: Int?,
    val bmVoltageMv: Int?,
    val isCharging: Boolean,
    val plugged: Boolean,
    val chargeCounterUah: Long? = null,
    val elapsedRealtimeMs: Long? = null,
)

data class PowerCalibration(
    val currentScaleToMa: Double,
    val dischargeIsNegative: Boolean,
    val voltageScaleToMv: Double,
    /**
     * 2 when the pack voltage reads as dual-cell or the current reads as per-cell, else 1. It is diagnostic only: the
     * scales above already apply it, and the debug probe's calibration log is its one reader. Kept (audit §3) as the
     * evidence for a dual-cell device's doubled current.
     */
    val cellCount: Int,
    val confidence: Confidence,
    /** For a reading without a sysfs current. µA by contract, but a HAL that passes `current_now` through reports mA. */
    val batteryManagerCurrentScaleToMa: Double = MICRO_TO_MILLI,
    /** Nothing supports the sysfs current's unit yet: µA is the documented default alone, and may be 1000× off. */
    val currentUnitAssumed: Boolean = false,
    /** The same for BatteryManager's current. */
    val batteryManagerCurrentUnitAssumed: Boolean = false,
    /** BatteryManager's sign convention is independent of the sysfs driver's. */
    val batteryManagerDischargeIsNegative: Boolean = true,
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
    /** [currentMa] and [watts] rest on a unit nothing supports yet, so they may be 1000× off. */
    val currentUnitAssumed: Boolean = false,
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
    /** sysfs sign, proved by unplugged readings only, never by a plugged battery's status. */
    val dischargePositive: Boolean? = null,
    /** sysfs reports the per-cell current of a two-cell pack. */
    val perCellCurrent: Boolean? = null,
    /** The sysfs voltage is in µV (true) or mV (false). */
    val sysfsMicrovolts: Boolean? = null,
    /** BatteryManager sign, proved independently by its unplugged readings only. */
    val batteryManagerDischargePositive: Boolean? = null,
) {
    /** Each value settled here, else the one [other] has. */
    fun orElse(other: CalibrationLocks): CalibrationLocks =
        CalibrationLocks(
            sysfsMilliamps = sysfsMilliamps ?: other.sysfsMilliamps,
            batteryManagerMilliamps = batteryManagerMilliamps ?: other.batteryManagerMilliamps,
            dischargePositive = dischargePositive ?: other.dischargePositive,
            perCellCurrent = perCellCurrent ?: other.perCellCurrent,
            sysfsMicrovolts = sysfsMicrovolts ?: other.sysfsMicrovolts,
            batteryManagerDischargePositive = batteryManagerDischargePositive ?: other.batteryManagerDischargePositive,
        )
}

/**
 * Where a process starts. [locks] were settled on this build and hold until evidence disputes them. [guesses] were
 * settled on an earlier build: an OS update can bring a new fuel-gauge driver, so they only lean each choice until
 * this build's evidence decides it, and they keep a monthly update from showing an unknown current again.
 */
data class CalibrationStart(
    val locks: CalibrationLocks = CalibrationLocks(),
    val guesses: CalibrationLocks = CalibrationLocks(),
)

/** Keeps a [CalibrationStart] across processes, so a new one does not re-learn the device from scratch. */
interface CalibrationStore {
    /** What was saved, with an earlier build's locks as [CalibrationStart.guesses]; null when nothing was. */
    fun load(): CalibrationStart?

    fun save(start: CalibrationStart)
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
        val dischargeIsNegative = if (raw.sysfsCurrentRaw != null) cal.dischargeIsNegative else cal.batteryManagerDischargeIsNegative
        val currentMa = if (dischargeIsNegative) unnormalizedCurrentMa else -unnormalizedCurrentMa
        val voltageMv =
            raw.sysfsVoltageRaw?.toDouble()?.times(cal.voltageScaleToMv)
                ?: raw.bmVoltageMv?.toDouble()
                ?: return null
        val voltageV = voltageMv / MILLIVOLTS_PER_VOLT

        return PowerReading(
            currentMa = currentMa,
            voltageV = voltageV,
            watts = voltageV * currentMa / MILLIS_PER_AMPERE,
            currentUnitAssumed = if (raw.sysfsCurrentRaw != null) cal.currentUnitAssumed else cal.batteryManagerCurrentUnitAssumed,
        )
    }
}

/**
 * The current units, sign convention, cell layout and voltage unit are constants of the device, so each is decided by
 * agreement across samples, never re-decided from the latest one. A choice that [LOCK_LEAD] more samples support than
 * oppose is locked ([locks]), and a later process starts from it ([start]). A lock is not forever: evidence that
 * disputes it (a µA proof against mA, [LOCK_LEAD] clearly contrary samples in a row) re-learns it. Until a lock the
 * evidence so far decides, an earlier build's value standing in for missing evidence (or a mere unit hint), and with
 * neither the documented units apply (µA and µV, discharge negative, one cell).
 *
 * A sample whose current is under [NOISE_FLOOR_MA] adds no sign or cell evidence. Near zero, a little noise or a load
 * change between the two reads swings the sysfs/BatteryManager ratio anywhere. A sample whose two currents fit no
 * known ratio adds no ratio evidence either.
 *
 * Not thread-safe: a shared instance needs external synchronization.
 */
class CalibrationEvidence(
    start: CalibrationStart = CalibrationStart(),
) {
    private val guesses = start.guesses
    private var hasCurrent = false
    private var hasVoltage = false
    private var dualCellVoltage = false

    private val sysfsUnit = UnitEvidence(start.locks.sysfsMilliamps, guesses.sysfsMilliamps)
    private val batteryManagerUnit = UnitEvidence(start.locks.batteryManagerMilliamps, guesses.batteryManagerMilliamps)

    // sysfs reports the per-cell current of a two-cell pack: half of what BatteryManager reports. Doubles the current
    // only once locked (or on an earlier build's word), so a load swing between the two reads cannot double it.
    private val halfCurrent = Vote(start.locks.perCellCurrent, guesses.perCellCurrent)

    // An unplugged battery can only discharge, so unplugged samples prove the kernel's sign convention. A plugged
    // "charging" status cannot: a weak charger still loses to the load. Its votes never lock, and overturn the
    // documented convention only by a clear majority and only without proof. A full or held battery casts none.
    private val unpluggedDischargePositive = Vote(start.locks.dischargePositive, guesses.dischargePositive)
    private val statusDischargePositive = Vote(lockable = false)
    private val bmUnpluggedDischargePositive = Vote(start.locks.batteryManagerDischargePositive, guesses.batteryManagerDischargePositive)
    private val bmStatusDischargePositive = Vote(lockable = false)

    private val microvolts = Vote(start.locks.sysfsMicrovolts, guesses.sysfsMicrovolts)

    // Plugged readings never feed the unit prior, so a fresh install kept on the charger learns the unit from here.
    private val chargeCounter = ChargeCounterCheck()

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
        chargeCounter.add(sample)?.let { verdict ->
            verdict.sysfsMilliamps?.let(sysfsUnit::addCounterProof)
            verdict.batteryManagerMilliamps?.let(batteryManagerUnit::addCounterProof)
        }

        val voltageV = sample.voltageMv(voltageScale()) / MILLIVOLTS_PER_VOLT
        if (voltageV in DUAL_CELL_MIN_VOLTAGE..DUAL_CELL_MAX_VOLTAGE) dualCellVoltage = true
        if (voltageV in SINGLE_CELL_MIN_VOLTAGE..SINGLE_CELL_MAX_VOLTAGE) addHalfCurrentEvidence(sample)
        addSignEvidence(sample)
    }

    fun calibration(): PowerCalibration {
        if (!hasCurrent && !hasVoltage) return UNCALIBRATED
        val perCellCurrent = halfCurrent.lockedOrGuessed() == true
        val dischargePositive = unpluggedDischargePositive.decision() ?: statusDischargePositive.clearMajority() ?: false
        fun mixedSigns(unplugged: Vote, status: Vote): Boolean =
            unplugged.yes + status.yes > 0 && unplugged.no + status.no > 0
        // Opposing conventions across sources are valid; only contradictions within a source lower confidence.
        val mixedSigns = mixedSigns(unpluggedDischargePositive, statusDischargePositive) ||
            mixedSigns(bmUnpluggedDischargePositive, bmStatusDischargePositive)
        return PowerCalibration(
            // Series pack voltage already includes both cells; only a per-cell current needs doubling.
            currentScaleToMa = sysfsUnit.scaleToMa() * if (perCellCurrent) 2 else 1,
            dischargeIsNegative = !dischargePositive,
            voltageScaleToMv = voltageScale(),
            cellCount = if (dualCellVoltage || perCellCurrent) 2 else 1,
            confidence = if (mixedSigns) Confidence.LOW else Confidence.HIGH,
            batteryManagerCurrentScaleToMa = batteryManagerUnit.scaleToMa(),
            currentUnitAssumed = sysfsUnit.assumed(),
            batteryManagerCurrentUnitAssumed = batteryManagerUnit.assumed(),
            batteryManagerDischargeIsNegative = !(
                bmUnpluggedDischargePositive.decision() ?: bmStatusDischargePositive.clearMajority() ?: false
            ),
        )
    }

    /** What a later process on this build starts from: the locks, and the earlier build's values this one started with. */
    fun start(): CalibrationStart = CalibrationStart(locks(), guesses)

    /** What is locked now. */
    fun locks(): CalibrationLocks =
        CalibrationLocks(
            sysfsMilliamps = sysfsUnit.locked,
            batteryManagerMilliamps = batteryManagerUnit.locked,
            dischargePositive = unpluggedDischargePositive.locked,
            perCellCurrent = halfCurrent.locked,
            sysfsMicrovolts = microvolts.locked,
            batteryManagerDischargePositive = bmUnpluggedDischargePositive.locked,
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
        fun add(currentMa: Double?, unplugged: Vote, status: Vote) {
            if (currentMa == null || abs(currentMa) < NOISE_FLOOR_MA) return
            when {
                !sample.plugged -> unplugged.add(currentMa > 0.0)
                sample.isCharging -> status.add(currentMa < 0.0)
            }
        }
        add(
            sample.sysfsCurrentRaw?.let { it * sysfsUnit.scaleToMa() },
            unpluggedDischargePositive, statusDischargePositive,
        )
        add(
            sample.bmCurrentNowUa?.let { it * batteryManagerUnit.scaleToMa() },
            bmUnpluggedDischargePositive, bmStatusDischargePositive,
        )
    }

    private fun voltageScale(): Double = if (microvolts.decision() == false) 1.0 else MICRO_TO_MILLI

    private fun CalibrationInput.voltageMv(voltageScale: Double): Double =
        sysfsVoltageRaw?.toDouble()?.times(voltageScale) ?: bmVoltageMv?.toDouble() ?: Double.NaN

    /**
     * The unit of one source's current: µA by contract until shown otherwise.
     *
     * Proof: a value over 20 A in mA can only be µA, the other source reading a thousand times this one at a real
     * load means mA, and so does a charge counter that moved by what the mA reading predicts ([addCounterProof]),
     * plugged or not. A prior: the live gauge runs while the screen is on, and an unplugged phone with its screen on
     * draws well over 20 mA, so an unplugged value of 50 to 20,000 means mA from the first one. Any µA proof overrides
     * the prior, and an earlier build's unit ([guess]) outweighs it. Plugged values never feed it, since a µA battery
     * held at a charge limit reads a few mA.
     */
    private class UnitEvidence(
        restored: Boolean?,
        private val guess: Boolean?,
    ) {
        /** True for mA, false for µA, once locked. */
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
            if (locked == true && magnitude > MICROAMP_THRESHOLD) {
                // 20 A in mA is no phone: the mA lock (small readings of a µA gauge averaging across zero after the
                // unplug, or one an earlier process saved) was wrong. Re-learn, starting from this proof.
                locked = null
                microampProofs = 0
                milliampProofs = 0
                unpluggedHints = 0
            }
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
            locked = decision()
        }

        /** One charge-counter window's proof ([ChargeCounterCheck]); like any proof, it does not re-learn a lock. */
        fun addCounterProof(milliamps: Boolean) {
            if (locked != null) return
            if (milliamps) milliampProofs++ else microampProofs++
            locked = decision()
        }

        private fun decision(): Boolean? =
            when {
                milliampProofs - microampProofs >= LOCK_LEAD -> true
                microampProofs - milliampProofs >= LOCK_LEAD -> false
                microampProofs == 0 && milliampProofs + unpluggedHints >= LOCK_LEAD -> true
                else -> null
            }

        fun scaleToMa(): Double = if (milliamps()) 1.0 else MICRO_TO_MILLI

        /** No lock, proof, hint or earlier build's unit: µA is the documented default alone. */
        fun assumed(): Boolean = locked == null && guess == null && microampProofs + milliampProofs + unpluggedHints == 0

        private fun milliamps(): Boolean =
            locked ?: when {
                microampProofs + milliampProofs > 0 -> milliampProofs > microampProofs
                guess != null -> guess
                else -> unpluggedHints > 0
            }
    }

    /**
     * Two-way agreement. Once one side leads by [LOCK_LEAD] it is [locked] unless the vote is not [lockable]; a lock
     * that [LOCK_LEAD] samples in a row dispute is re-learned from them. Until a lock, an earlier build's [guess]
     * breaks a tie or stands in for missing evidence.
     */
    private class Vote(
        restored: Boolean? = null,
        private val guess: Boolean? = null,
        private val lockable: Boolean = true,
    ) {
        var yes: Int = 0
            private set
        var no: Int = 0
            private set
        var locked: Boolean? = restored
            private set
        private var disputedInARow = 0

        fun add(value: Boolean) {
            val lock = locked
            if (lock == null) {
                if (value) yes++ else no++
            } else {
                disputedInARow = if (value == lock) 0 else disputedInARow + 1
                if (disputedInARow < LOCK_LEAD) return
                locked = null
                yes = if (value) disputedInARow else 0
                no = if (value) 0 else disputedInARow
                disputedInARow = 0
            }
            if (lockable && abs(yes - no) >= LOCK_LEAD) locked = yes > no
        }

        /** The locked choice, else the majority so far, else the [guess]; null with none of them. */
        fun decision(): Boolean? =
            locked ?: when {
                yes > no -> true
                no > yes -> false
                else -> guess
            }

        /** The locked choice, else the [guess]: for a choice one sample's majority must never make. */
        fun lockedOrGuessed(): Boolean? = locked ?: guess

        /**
         * The side that, over at least [CLEAR_MAJORITY_VOTES] votes, leads by [LOCK_LEAD] and outnumbers the other
         * [CLEAR_MAJORITY] to one; null otherwise.
         */
        fun clearMajority(): Boolean? =
            when {
                yes + no < CLEAR_MAJORITY_VOTES -> null
                yes - no >= LOCK_LEAD && yes >= CLEAR_MAJORITY * no -> true
                no - yes >= LOCK_LEAD && no >= CLEAR_MAJORITY * yes -> false
                else -> null
            }
    }

    private companion object {
        /** Agreeing samples (net of contrary ones) that lock a device constant, and disputing ones in a row that unlock it. */
        const val LOCK_LEAD = 3

        /** How many times the other side's count a vote that never locks needs to overturn a default. */
        const val CLEAR_MAJORITY = 3

        /** The fewest votes that may overturn a default without a lock: more than a weak charger's first few ticks. */
        const val CLEAR_MAJORITY_VOTES = 6
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
