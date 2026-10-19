package com.akane.inspiration.diagnostics.story

import com.akane.inspiration.diagnostics.model.DiagnosticReport
import com.akane.inspiration.diagnostics.model.Insight
import com.akane.inspiration.diagnostics.model.ModemDrain
import com.akane.inspiration.diagnostics.model.NightVampire
import com.akane.inspiration.diagnostics.model.Severity
import com.akane.inspiration.telemetry.dump.SystemTimers
import com.akane.inspiration.telemetry.interval.IntervalMetrics
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SleepStats
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind

internal const val HOUR_MS = 3_600_000L
internal const val MINUTE_MS = 60_000L
private const val STATS_START_MS = 1_000_000L

internal fun metrics(
    hours: Double = 14.0,
    levelUsedPct: Int = 48,
    screenOnPctPerHour: Double? = 8.2,
    screenOffPctPerHour: Double? = 0.5,
) = IntervalMetrics(
    fromWallMs = 1_000_000L,
    toWallMs = 1_000_000L + (hours * HOUR_MS).toLong(),
    screenOnMs = (hours * HOUR_MS).toLong(),
    screenOffMs = 0,
    unknownMs = 0,
    chargingMs = 0,
    startLevel = 100,
    endLevel = 100 - levelUsedPct,
    levelUsedPct = levelUsedPct,
    levelDropScreenOn = 0,
    levelDropScreenOff = 0,
    screenOnPctPerHour = screenOnPctPerHour,
    screenOffPctPerHour = screenOffPctPerHour,
    discontinuities = emptyList(),
)

internal fun sleep(ratio: Double? = 0.946, screenOffMs: Long? = 10 * HOUR_MS) = SleepStats(
    deepSleepMs = 0,
    screenOffDeepSleepMs = null,
    screenOffAwakeMs = null,
    screenOffMs = screenOffMs,
    screenOnMs = null,
    deepSleepRatio = ratio,
)

/** batterystats system timers with the clock and screen fields set and everything else zero. */
internal fun systemTimers(
    batteryRealtimeMs: Long,
    batteryUptimeMs: Long,
    screenOffRealtimeMs: Long,
    screenOffUptimeMs: Long,
    screenOnMs: Long,
) = SystemTimers(
    startClockTimeMs = STATS_START_MS,
    totalRealtimeMs = batteryRealtimeMs,
    totalUptimeMs = batteryUptimeMs,
    batteryRealtimeMs = batteryRealtimeMs,
    batteryUptimeMs = batteryUptimeMs,
    screenOffRealtimeMs = screenOffRealtimeMs,
    screenOffUptimeMs = screenOffUptimeMs,
    screenDozeMs = 0,
    screenOnMs = screenOnMs,
    interactiveMs = screenOnMs,
    deepDozeMs = 0,
    deepDozeCount = 0,
    lightDozeMs = 0,
    dischargeTotalMah = 0.0,
    dischargeScreenOffMah = 0.0,
    chargeTimeRemainingMs = null,
    dischargeTimeRemainingMs = null,
)

/** A FULL snapshot in one boot and one batterystats epoch, so the differ compares its [system] timers. */
internal fun fullSnapshot(wallMs: Long, system: SystemTimers) = BatterySnapshot(
    id = wallMs,
    kind = SnapshotKind.FULL,
    trigger = CaptureTrigger.APP_OPEN,
    clock = SnapshotClock(wallMs, wallMs, wallMs, bootCount = 1, statsStartMs = STATS_START_MS, statsDurationMs = wallMs),
    battery = BatteryState(80, false, null, null, null, null, null, null),
    system = system,
    uids = emptyList(),
    deviceComponentsMah = emptyMap(),
    kernelWakelocks = emptyList(),
    wakeupReasons = emptyList(),
    cpuFreq = emptyList(),
    availability = emptyMap(),
    truncatedUids = 0,
)

internal fun report(vararg insights: Insight) = DiagnosticReport(insights.toList(), emptyMap(), 0)

internal val nightVampire = NightVampire(
    severity = Severity.RED,
    subjectUid = 10_123,
    packageName = "com.example.app",
    totalMah = 120.0,
    bgShare = 0.8,
    fgActivityMs = 1_000,
)

internal val modemDrain = ModemDrain(
    severity = Severity.AMBER,
    mobileRadioMah = 90.0,
    screenMah = 100.0,
    signalAvailable = true,
    signalBinPct = emptyList(),
    scanningMs = null,
    radioActiveMs = null,
)
