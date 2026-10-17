package com.akane.inspiration.ipc.model

import kotlinx.parcelize.Parcelize

@Parcelize
data class CpuTimeSnapshot(
    val available: Boolean,
    val reason: String?,
    val entries: List<ClusterFreqTime>,
) : android.os.Parcelable {
    companion object {
        fun unavailable(reason: String): CpuTimeSnapshot = CpuTimeSnapshot(
            available = false,
            reason = reason,
            entries = emptyList(),
        )
    }
}
