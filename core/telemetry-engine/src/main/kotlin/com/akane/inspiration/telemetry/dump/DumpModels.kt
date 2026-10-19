package com.akane.inspiration.telemetry.dump

data class DumpSnapshot(
    val reportVersion: Int,
    val system: SystemTimers,
    val kernelWakelocks: List<KernelWakelock>,
    val wakeupReasons: List<KernelWakelock>,
    val uids: List<UidDump>,
)

data class SystemTimers(
    val startClockTimeMs: Long,
    val totalRealtimeMs: Long,
    val totalUptimeMs: Long,
    val batteryRealtimeMs: Long,
    val batteryUptimeMs: Long,
    val screenOffRealtimeMs: Long,
    val screenOffUptimeMs: Long,
    val screenDozeMs: Long,
    val screenOnMs: Long,
    val interactiveMs: Long,
    val deepDozeMs: Long,
    val deepDozeCount: Long,
    val lightDozeMs: Long,
    val dischargeTotalMah: Double,
    val dischargeScreenOffMah: Double,
    val chargeTimeRemainingMs: Long?,
    val dischargeTimeRemainingMs: Long?,
    // Index = signal bin 0..4; empty when the dump carries no bins. The defaults below are
    // placeholders until persistence (T1b) maps these fields.
    val signalStrengthMs: List<Long> = emptyList(),
    val signalScanningMs: Long? = null,
    val mobileRadioActiveMs: Long? = null,
)

/** Per-UID sensor use; [handle] is the sensor handle (GPS is -10000). */
data class UidSensor(
    val handle: Int,
    val totalMs: Long,
    val bgMs: Long,
    val count: Long,
)

data class KernelWakelock(
    val name: String,
    val durationMs: Long,
    val count: Long,
)

data class UidDump(
    val uid: Int,
    val partialWakelocks: List<UidWakelock>,
    val wakeupAlarms: List<UidAlarm>,
    val cpuUserMs: Long,
    val cpuSystemMs: Long,
    val fgActivityMs: Long,
    val fgServiceMs: Long,
    val sensors: List<UidSensor>,
)

data class UidWakelock(
    val name: String,
    val durationMs: Long,
    val bgDurationMs: Long,
    val count: Long,
)

data class UidAlarm(
    val tag: String,
    val count: Long,
)

data class AlarmDump(
    val filterStats: List<AlarmFilterStat>,
)

data class AlarmFilterStat(
    val uid: Int,
    val packageName: String,
    val tag: String,
    val count: Long,
    val wakeupCount: Long,
)

sealed interface DumpParse<out T> {
    data class Ok<T>(val value: T) : DumpParse<T>

    data class Unavailable(val reason: String) : DumpParse<Nothing>
}
