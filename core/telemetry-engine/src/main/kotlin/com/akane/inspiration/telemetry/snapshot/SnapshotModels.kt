package com.akane.inspiration.telemetry.snapshot

import com.akane.inspiration.telemetry.dump.KernelWakelock
import com.akane.inspiration.telemetry.dump.SystemTimers
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.dump.UidWakelock

enum class CaptureTrigger {
    APP_OPEN,
    SCREEN_OFF,
    SCREEN_ON,
    POWER_CONNECTED,
    POWER_DISCONNECTED,
    BATTERY_LOW,
    BATTERY_OKAY,
    CHARGE_JOB,
    BOOT,
    MARKER,
}

enum class SnapshotKind {
    FULL,
    LIGHT,
}

data class SnapshotClock(
    val wallMs: Long,
    val elapsedRealtimeMs: Long,
    val uptimeMs: Long,
    val bootCount: Int,
    val statsStartMs: Long?,
    val statsDurationMs: Long?,
)

data class BatterySnapshot(
    val id: Long,
    val kind: SnapshotKind,
    val trigger: CaptureTrigger,
    val clock: SnapshotClock,
    val battery: BatteryState,
    val system: SystemTimers?,
    val uids: List<UidSnapshot>,
    val deviceComponentsMah: Map<String, Double>,
    val kernelWakelocks: List<KernelWakelock>,
    val wakeupReasons: List<KernelWakelock>,
    val cpuFreq: List<CpuFreqTime>,
    val availability: Map<String, SourceAvailability>,
    val truncatedUids: Int,
)

data class SourceAvailability(
    val available: Boolean,
    val reason: String?,
)

data class BatteryState(
    val levelPct: Int?,
    val plugged: Boolean?,
    val status: String?,
    val temperatureDeciC: Int?,
    val voltageMv: Int?,
    val currentMa: Int?,
    val chargeCounterUah: Long?,
    val dischargedMah: Double?,
)

data class UidSnapshot(
    val uid: Int,
    val packageName: String?,
    val totalMah: Double,
    val fgMah: Double?,
    val bgMah: Double?,
    val fgsMah: Double?,
    val cachedMah: Double?,
    val componentsMah: Map<String, Double>,
    val cpuUserMs: Long,
    val cpuSystemMs: Long,
    val fgActivityMs: Long,
    val fgServiceMs: Long,
    val partialWakelocks: List<UidWakelock>,
    val wakeupAlarms: List<UidAlarm>,
)

data class CpuFreqTime(
    val policy: Int,
    val freqKhz: Long,
    val timeMs: Long,
)

data class UidDelta(
    val uid: Int,
    val packageName: String?,
    val packageChanged: Boolean,
    val valid: Boolean,
    val totalMah: Double,
    val fgMah: Double?,
    val bgMah: Double?,
    val fgsMah: Double?,
    val cachedMah: Double?,
    val componentsMah: Map<String, Double>,
    val cpuUserMs: Long,
    val cpuSystemMs: Long,
    val fgActivityMs: Long,
    val fgServiceMs: Long,
    val partialWakelocks: List<UidWakelock>,
    val wakeupAlarms: List<UidAlarm>,
)

data class DiffValidity(
    val batteryStats: Boolean,
    val kernelCounters: Boolean,
    val problems: List<String>,
    val invalidDeviceComponents: Set<String> = emptySet(),
    val invalidKernelWakelocks: Set<String> = emptySet(),
)

data class SleepStats(
    val deepSleepMs: Long,
    val screenOffDeepSleepMs: Long?,
    val screenOffAwakeMs: Long?,
    val screenOffMs: Long?,
    val screenOnMs: Long?,
    val deepSleepRatio: Double?,
)

data class SnapshotDiff(
    val ref: SnapshotClock,
    val now: SnapshotClock,
    val validity: DiffValidity,
    val durationMs: Long,
    val uids: List<UidDelta>,
    val deviceComponentsMah: Map<String, Double>,
    val kernelWakelocks: List<KernelWakelock>,
    val sleep: SleepStats?,
    val levelDropPct: Int?,
    val dischargedMah: Double?,
)

data class LiveReading(
    val wallMs: Long,
    val elapsedRealtimeMs: Long,
    val bootCount: Int,
    val currentMa: Int?,
    val voltageMv: Int?,
    val watts: Double?,
    val temperatureDeciC: Int?,
    val levelPct: Int?,
    val plugged: Boolean?,
)
