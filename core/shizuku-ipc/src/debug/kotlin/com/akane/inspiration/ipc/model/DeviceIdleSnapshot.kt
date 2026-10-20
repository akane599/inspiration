package com.akane.inspiration.ipc.model

import kotlinx.parcelize.Parcelize

@Parcelize
data class DeviceIdleSnapshot(
    val available: Boolean,
    val reason: String?,
    val deepIdle: Boolean,
    val lightIdle: Boolean,
    val powerSaveWhitelist: List<String>,
) : android.os.Parcelable {
    companion object {
        fun unavailable(reason: String): DeviceIdleSnapshot = DeviceIdleSnapshot(
            available = false,
            reason = reason,
            deepIdle = false,
            lightIdle = false,
            powerSaveWhitelist = emptyList(),
        )
    }
}
