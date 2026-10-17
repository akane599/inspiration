package com.akane.inspiration.telemetry.dump

data class DumpSnapshot(
    val reportVersion: Int,
    val system: SystemTimers,
    val kernelWakelocks: List<KernelWakelock>,
    val wakeupReasons: List<KernelWakelock>,
    val uids: List<UidDump>,
    val cpuFrequenciesKhz: List<Long>,
    val warnings: List<String>,
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
    val processStateMs: Map<Int, Long>,
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

data class PowerDump(
    val wakefulness: Int,
    val heldWakeLocks: List<HeldWakeLock>,
    val lastWakeMs: Long,
    val lastSleepMs: Long,
)

data class HeldWakeLock(
    val tag: String,
    val uid: Int,
    val levelFlags: Int,
    val acquiredMs: Long,
)

data class AlarmDump(
    val filterStats: List<AlarmFilterStat>,
    val pendingWakeups: Int,
)

data class AlarmFilterStat(
    val uid: Int,
    val packageName: String,
    val tag: String,
    val count: Long,
    val wakeupCount: Long,
    val totalFlightMs: Long,
)

sealed interface DumpParse<out T> {
    data class Ok<T>(val value: T) : DumpParse<T>

    data class Unavailable(val reason: String) : DumpParse<Nothing>
}
