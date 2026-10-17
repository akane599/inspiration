package com.akane.inspiration.ipc.model

import kotlinx.parcelize.Parcelize

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
