package com.akane.inspiration.ipc.model

import kotlinx.parcelize.Parcelize

/**
 * [statsDurationMs] is the framework session duration where available, else end − start.
 * [deviceComponents] is the device-scope aggregate consumer's per-component mAh (empty when the
 * framework has none). [truncatedUids] counts the lowest-`totalMah` UIDs dropped to keep the
 * parcel within the E3b byte budget.
 */
@Parcelize
data class BatteryUsageSnapshot(
    val available: Boolean,
    val reason: String?,
    val statsStartMs: Long,
    val statsEndMs: Long,
    val dischargedPowerMah: Double,
    val batteryTimeRemainingMs: Long,
    val chargeTimeRemainingMs: Long,
    val uids: List<UidUsage>,
    val statsDurationMs: Long = statsEndMs - statsStartMs,
    val deviceComponents: Map<String, Double> = emptyMap(),
    val truncatedUids: Int = 0,
) : android.os.Parcelable {
    companion object {
        fun unavailable(reason: String): BatteryUsageSnapshot = BatteryUsageSnapshot(
            available = false,
            reason = reason,
            statsStartMs = 0L,
            statsEndMs = 0L,
            dischargedPowerMah = 0.0,
            batteryTimeRemainingMs = 0L,
            chargeTimeRemainingMs = 0L,
            uids = emptyList(),
        )
    }
}
