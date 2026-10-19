package com.akane.inspiration.telemetry.live

import com.akane.inspiration.ipc.model.KernelBatterySnapshot
import com.akane.inspiration.telemetry.capacity.KernelCapacity

/**
 * Pure unit and sign conversions for raw kernel / BatteryManager values.
 *
 * Every function passes `null` through, and a zero through as zero, so callers never invent a value
 * for a missing or idle reading.
 */
object KernelUnits {
    /** Converts a micro-amp-hour counter (`charge_counter`, `charge_full`, ...) to milli-amp-hours. */
    fun uahToMah(uah: Long?): Double? = uah?.let { it / MICRO_PER_MILLI }

    /**
     * Normalizes a current so that discharge is negative and charge is positive.
     *
     * Kernels disagree on the sign of `current_now`: most report discharge as negative, some report it
     * as positive. [charging] is the battery's own direction (status `charging` is true, status
     * `discharging` is false, anything else, `full` included, is null). While charging, current must be
     * positive; while discharging it must be negative. A reading with the opposite sign comes from a kernel using the
     * inverted convention, so its sign is flipped. With [charging] unknown, or a zero current, the value
     * is returned unchanged because the sign carries no direction evidence.
     *
     * Limitation: a plugged battery that is genuinely draining (weak charger under heavy load) reports
     * status `discharging`/`not_charging`, so callers must pass the battery status, not merely "plugged".
     */
    fun normalizeCurrentMa(
        currentMa: Int?,
        charging: Boolean?,
    ): Int? {
        if (currentMa == null || currentMa == 0 || charging == null) return currentMa
        val invertedKernel = if (charging) currentMa < 0 else currentMa > 0
        return if (invertedKernel) -currentMa else currentMa
    }

    /** Maps the sysfs capacity nodes (all in micro-amp-hours) to the estimator's mAh [KernelCapacity]. */
    fun kernelCapacity(kernel: KernelBatterySnapshot): KernelCapacity =
        KernelCapacity(
            chargeFullMah = uahToMah(kernel.chargeFullRaw)?.takeIf { it > 0.0 },
            designMah = uahToMah(kernel.chargeFullDesignRaw)?.takeIf { it > 0.0 },
            cycleCount = kernel.cycleCount,
        )

    private const val MICRO_PER_MILLI = 1_000.0
}
