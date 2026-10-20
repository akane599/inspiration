package com.akane.inspiration.telemetry.calibration

import kotlin.math.abs

/**
 * The floor under which a charge counter is not read as µAh: 20 Ah in mAh is no phone battery, so a counter over this
 * is in µAh, while a kernel that reports `charge_counter` in mAh reads under it.
 */
const val MIN_MICROAMP_HOUR_COUNTER = 20_000L

/**
 * [chargeCounter] when it can be taken as µAh (over [MIN_MICROAMP_HOUR_COUNTER]), else null. Every reader that turns
 * the counter into a time or a charge goes through this, so none reads a mAh kernel's counter a thousand times small.
 */
fun microampHourCounter(chargeCounter: Long?): Long? = chargeCounter?.takeIf { it > MIN_MICROAMP_HOUR_COUNTER }

/** A window's verdict on each source's current unit: true for mA, false for µA, null for no verdict. */
internal data class CounterVerdict(
    val sysfsMilliamps: Boolean?,
    val batteryManagerMilliamps: Boolean?,
)

/**
 * Cross-checks the current's unit against the charge counter, which counts the charge that actually flowed: over a
 * window, it moves by the current's integral if the current is in µA, and by a thousand times that if it is in mA. It
 * works plugged in too, where nothing else settles a small current's unit (a µA battery held at a charge limit reads
 * a few thousand, which could as well be amps in mA).
 *
 * Only signed charge is compared, so a current swinging across zero predicts little under either unit and decides
 * nothing. The counter is trusted only when it reads over [MIN_MICROAMP_HOUR_COUNTER] (so it is in µAh),
 * once it has been seen to move (a stuck counter would read every current as µA), and never across a step no phone
 * current could make in one tick (a gauge re-learning its capacity) or a gap between readings. A window decides only
 * once the mA reading predicts a move both over [MIN_DECISIVE_UAH] and many counter steps, and only for the unit whose
 * prediction the counter matches within a factor of [TOLERANCE] (wide enough for a per-cell current, half the pack's).
 */
internal class ChargeCounterCheck {
    private var anchorMs = 0L
    private var anchorCounter = 0L
    private var lastMs: Long? = null
    private var lastCounter = 0L

    /** The smallest move seen: the counter's resolution, and proof that it moves at all. */
    private var counterStep: Long? = null
    private val sysfs = Charge()
    private val batteryManager = Charge()

    /** A verdict when [sample] closes a decisive window, else null. */
    fun add(sample: CalibrationInput): CounterVerdict? {
        val now = sample.elapsedRealtimeMs
        val counter = microampHourCounter(sample.chargeCounterUah)
        val previousMs = lastMs
        if (now == null || counter == null) {
            lastMs = null
            return null
        }
        if (previousMs == null || now <= previousMs || now - previousMs > MAX_GAP_MS) {
            restart(now, counter, sample)
            return null
        }
        val hours = (now - previousMs) / MILLIS_PER_HOUR
        val move = abs(counter - lastCounter)
        if (move > MAX_PLAUSIBLE_UA * hours) {
            restart(now, counter, sample)
            return null
        }
        if (move > 0L) counterStep = minOf(counterStep ?: move, move)
        sysfs.extend(sample.sysfsCurrentRaw, hours)
        batteryManager.extend(sample.bmCurrentNowUa?.toLong(), hours)
        lastMs = now
        lastCounter = counter
        if (now - anchorMs < MIN_WINDOW_MS) return null
        val step = counterStep ?: return null
        val observed = abs(counter - anchorCounter).toDouble()
        val verdict = CounterVerdict(sysfs.verdict(observed, step), batteryManager.verdict(observed, step))
        if (verdict.sysfsMilliamps == null && verdict.batteryManagerMilliamps == null) return null
        restart(now, counter, sample)
        return verdict
    }

    private fun restart(
        now: Long,
        counter: Long,
        sample: CalibrationInput,
    ) {
        anchorMs = now
        anchorCounter = counter
        lastMs = now
        lastCounter = counter
        sysfs.start(sample.sysfsCurrentRaw)
        batteryManager.start(sample.bmCurrentNowUa?.toLong())
    }

    /** One source's signed charge over the window, in raw current × hours; null once a reading lacked it. */
    private class Charge {
        private var total: Double? = null
        private var last = 0L

        fun start(raw: Long?) {
            total = raw?.let { 0.0 }
            last = raw ?: 0L
        }

        fun extend(
            raw: Long?,
            hours: Double,
        ) {
            val sum = total
            total = if (sum == null || raw == null) null else sum + (last.toDouble() + raw.toDouble()) / 2 * hours
            last = raw ?: 0L
        }

        fun verdict(
            observedUah: Double,
            step: Long,
        ): Boolean? {
            val ifMicroamps = abs(total ?: return null)
            val ifMilliamps = ifMicroamps * MILLI_PER_MICRO
            if (ifMilliamps < maxOf(MIN_DECISIVE_UAH, DECISIVE_STEPS * step.toDouble())) return null
            return when {
                observedUah.matches(ifMicroamps, step) -> false
                observedUah.matches(ifMilliamps, step) -> true
                else -> null
            }
        }

        private fun Double.matches(
            predictedUah: Double,
            step: Long,
        ): Boolean = this in predictedUah / TOLERANCE - step..predictedUah * TOLERANCE + step
    }

    private companion object {
        const val MILLIS_PER_HOUR = 3_600_000.0
        const val MILLI_PER_MICRO = 1_000.0
        const val MIN_WINDOW_MS = 60_000L
        const val MAX_GAP_MS = 10_000L

        /** 20 A in µA: a counter that moves faster in a tick has jumped, not counted. */
        const val MAX_PLAUSIBLE_UA = 20_000_000.0
        const val MIN_DECISIVE_UAH = 10_000.0
        const val DECISIVE_STEPS = 10.0
        const val TOLERANCE = 3.0
    }
}
