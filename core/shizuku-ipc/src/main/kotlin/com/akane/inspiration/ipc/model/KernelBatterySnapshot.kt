package com.akane.inspiration.ipc.model

import kotlinx.parcelize.Parcelize

@Parcelize
data class KernelBatterySnapshot(
    val available: Boolean,
    val reason: String?,
    val currentNowRaw: Long?,
    val voltageNowRaw: Long?,
    val chargeCounterRaw: Long?,
    val chargeFullRaw: Long?,
    val chargeFullDesignRaw: Long?,
    val cycleCount: Int?,
    val temperatureDeciC: Int?,
    val status: String?,
    val capacityPercent: Int?,
    val sourcePath: String?,
    val readAtElapsedMs: Long,
) : android.os.Parcelable {
    companion object {
        fun unavailable(reason: String): KernelBatterySnapshot = KernelBatterySnapshot(
            available = false,
            reason = reason,
            currentNowRaw = null,
            voltageNowRaw = null,
            chargeCounterRaw = null,
            chargeFullRaw = null,
            chargeFullDesignRaw = null,
            cycleCount = null,
            temperatureDeciC = null,
            status = null,
            capacityPercent = null,
            sourcePath = null,
            readAtElapsedMs = 0L,
        )
    }
}
