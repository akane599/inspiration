package com.akane.inspiration.diagnostics.story

import com.akane.inspiration.diagnostics.model.DiagnosticReport
import com.akane.inspiration.diagnostics.model.Insight
import com.akane.inspiration.diagnostics.model.ModemDrain
import com.akane.inspiration.diagnostics.model.NightVampire
import com.akane.inspiration.diagnostics.model.Severity
import com.akane.inspiration.telemetry.interval.IntervalMetrics
import com.akane.inspiration.telemetry.snapshot.SleepStats

internal const val HOUR_MS = 3_600_000L

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

internal fun sleep(ratio: Double? = 0.946) = SleepStats(
    deepSleepMs = 0,
    screenOffDeepSleepMs = null,
    screenOffAwakeMs = null,
    screenOffMs = null,
    screenOnMs = null,
    deepSleepRatio = ratio,
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
