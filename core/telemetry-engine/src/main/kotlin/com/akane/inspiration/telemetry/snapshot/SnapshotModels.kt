package com.akane.inspiration.telemetry.snapshot

import com.akane.inspiration.telemetry.dump.KernelWakelock
import com.akane.inspiration.telemetry.dump.SystemTimers
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.dump.UidSensor
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

    /** This app was updated (`MY_PACKAGE_REPLACED`): not a reboot, so it says nothing about the screen state. */
    PACKAGE_REPLACED,
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
    val uidIdentityScope: UidIdentityScope? = null,
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
    // Batterystats wakeup-alarm counts per tag.
    val wakeupAlarms: List<UidAlarm>,
    // Default is a placeholder until persistence (T1b) maps sensors.
    val sensors: List<UidSensor> = emptyList(),
    // The alarm dump's own wakeup count per tag (count = wakeupCount). A separate counter, never added
    // into [wakeupAlarms] (the two overlap), so consumers must not sum them.
    val alarmDumpWakeups: List<UidAlarm> = emptyList(),
    // Capture-local complete ownership/install evidence, never inferred from the attribution hint.
    val ownerInstallFingerprint: String? = null,
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
    val sensors: List<UidSensor>,
    // Delta of [UidSnapshot.alarmDumpWakeups]; kept apart from [wakeupAlarms].
    val alarmDumpWakeups: List<UidAlarm> = emptyList(),
)

data class DiffValidity(
    val batteryStats: Boolean,
    val kernelCounters: Boolean,
    val problems: List<String>,
    val invalidDeviceComponents: Set<String> = emptySet(),
    val invalidKernelWakelocks: Set<String> = emptySet(),
    // Source-specific availability; defaults preserve manually constructed/legacy diffs.
    val dumpCounters: Boolean = batteryStats,
    val kernelWakelocks: Boolean = kernelCounters,
    val cpuFreq: Boolean = kernelCounters,
    val alarmDumpCounters: Boolean = batteryStats,
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
    val dischargedMah: Double?,
    // Delta of the SystemTimers signal bins (empty if either side lacks bins), scanning and radio-active
    // time; null when absent on either side or negative (a problem string is recorded then).
    val signalStrengthMs: List<Long>,
    val signalScanningMs: Long?,
    val mobileRadioActiveMs: Long?,
    // Deltas of snapshot_system's discharge fields; null when either side lacks them or the two sides
    // carry different statsStartMs (a battery-stats reset makes the counters incomparable).
    // Screen-on mAh is dischargeMah - dischargeScreenOffMah.
    val dischargeMah: Double? = null,
    val dischargeScreenOffMah: Double? = null,
    // Per (policy, freq) time-in-state delta; empty unless validity.kernelCounters.
    val cpuFreqDelta: List<CpuFreqDelta> = emptyList(),
)

data class CpuFreqDelta(
    val policy: Int,
    val freqKhz: Long,
    val timeMs: Long,
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
    val chargeCounterUah: Long? = null,
    // PowerManager.THERMAL_STATUS_* (0 none .. 6 shutdown); null when the platform call is unavailable.
    val thermalStatus: Int? = null,
    // The battery's own status, as in LightRead: "charging", "discharging", "full", "not_charging" or "unknown".
    // A plugged battery can be full, held at a charge limit or losing to the load, so [plugged] is not "charging".
    // Live only: not persisted with the sample.
    val status: String? = null,
)
