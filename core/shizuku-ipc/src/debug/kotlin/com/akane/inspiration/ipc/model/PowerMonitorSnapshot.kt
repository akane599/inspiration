package com.akane.inspiration.ipc.model

import kotlinx.parcelize.Parcelize

@Parcelize
data class PowerMonitorSnapshot(
    val available: Boolean,
    val reason: String?,
    val monitors: List<PowerMonitorReading>,
) : android.os.Parcelable {
    companion object {
        fun unavailable(reason: String): PowerMonitorSnapshot = PowerMonitorSnapshot(
            available = false,
            reason = reason,
            monitors = emptyList(),
        )
    }
}

@Parcelize
data class PowerMonitorReading(
    val name: String,
    val type: Int,
    val energyUws: Long,
    val timestampMs: Long,
) : android.os.Parcelable
