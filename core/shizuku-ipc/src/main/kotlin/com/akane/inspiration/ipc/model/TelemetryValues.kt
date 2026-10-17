package com.akane.inspiration.ipc.model

import kotlinx.parcelize.Parcelize

@Parcelize
data class ClusterFreqTime(
    val policy: Int,
    val freqKhz: Long,
    val timeMs: Long,
) : android.os.Parcelable

@Parcelize
data class UidUsage(
    val uid: Int,
    val packageName: String?,
    val totalMah: Double,
    val components: Map<String, Double>,
    val byProcessState: Map<String, Double>?,
) : android.os.Parcelable

@Parcelize
data class PowerMonitorReading(
    val name: String,
    val type: Int,
    val energyUws: Long,
    val timestampMs: Long,
) : android.os.Parcelable
