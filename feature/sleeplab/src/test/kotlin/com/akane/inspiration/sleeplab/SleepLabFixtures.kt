package com.akane.inspiration.sleeplab

import android.graphics.Bitmap
import com.akane.inspiration.diagnostics.analysis.AnalysisSelection
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.analysis.IntervalAnalysis
import com.akane.inspiration.diagnostics.analysis.SegmentKind
import com.akane.inspiration.diagnostics.analysis.Timeline
import com.akane.inspiration.diagnostics.analysis.TimelineSegment
import com.akane.inspiration.diagnostics.dictionary.DiagnosticDictionary
import com.akane.inspiration.diagnostics.model.DiagnosticReport
import com.akane.inspiration.diagnostics.story.BatteryStory
import com.akane.inspiration.diagnostics.story.StorySentence
import com.akane.inspiration.telemetry.app.AppLabels
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.diff.ResolvedReference
import com.akane.inspiration.telemetry.dump.KernelWakelock
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.dump.UidWakelock
import com.akane.inspiration.telemetry.interval.IntervalMetrics
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.CpuFreqDelta
import com.akane.inspiration.telemetry.snapshot.DiffValidity
import com.akane.inspiration.telemetry.snapshot.SleepStats
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotDiff
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.UidDelta
import kotlinx.coroutines.flow.MutableStateFlow

internal const val HOUR_MS = 3_600_000L
internal const val MIN_MS = 60_000L
internal const val NOW_MS = 1_000L * HOUR_MS

/** The app side of Sleep Lab, scripted. It counts what the ViewModel asked for. */
internal class FakeSleepLabDeps : SleepLabDependencies {
    override val analysis = MutableStateFlow<AnalysisState>(AnalysisState.Loading)
    override val selection = AnalysisSelection()
    override val appLabels = FakeAppLabels()

    var refreshResult: RefreshResult = RefreshResult.Refreshed(NOW_MS)
    var refreshCalls = 0
    val timelineCalls = mutableListOf<Pair<Long, Long>>()
    var timelineResult: Timeline? = emptyTimeline()
    var dictionary = DiagnosticDictionary(emptyList())

    override suspend fun refresh(): RefreshResult {
        refreshCalls++
        return refreshResult
    }

    override suspend fun timeline(from: Long, to: Long): Timeline {
        timelineCalls += from to to
        return timelineResult ?: error("timeline unavailable")
    }

    override fun dictionary(): DiagnosticDictionary = dictionary
}

internal class FakeAppLabels(val labels: MutableMap<String, String> = mutableMapOf()) : AppLabels {
    val looked = mutableListOf<String>()

    override suspend fun label(packageName: String): String {
        looked += packageName
        return labels[packageName] ?: packageName
    }

    override suspend fun icon(packageName: String): Bitmap? = null
}

internal fun emptyTimeline(from: Long = NOW_MS - 10 * HOUR_MS, to: Long = NOW_MS) =
    Timeline(emptyList(), emptyList(), emptyList(), emptyList(), from, to)

internal fun segment(kind: SegmentKind, from: Long, to: Long) = TimelineSegment(kind, from, to)

internal fun snapshotClock(wallMs: Long) = SnapshotClock(wallMs, wallMs, wallMs, 1, statsStartMs = 0, statsDurationMs = wallMs)

internal fun snapshot(wallMs: Long) = BatterySnapshot(
    id = wallMs,
    kind = SnapshotKind.FULL,
    trigger = CaptureTrigger.APP_OPEN,
    clock = snapshotClock(wallMs),
    battery = BatteryState(80, false, null, 300, 4_000, -300, null, null),
    system = null,
    uids = emptyList(),
    deviceComponentsMah = emptyMap(),
    kernelWakelocks = emptyList(),
    wakeupReasons = emptyList(),
    cpuFreq = emptyList(),
    availability = emptyMap(),
    truncatedUids = 0,
)

internal fun resolved(
    boundaryFrom: Long = NOW_MS - 10 * HOUR_MS,
    boundaryTo: Long = NOW_MS,
    coveredFrom: Long = boundaryFrom,
    coveredTo: Long = boundaryTo,
    perUidAvailable: Boolean = true,
) = ResolvedReference(
    ref = snapshot(coveredFrom),
    now = snapshot(coveredTo),
    boundaryFromWallMs = boundaryFrom,
    boundaryToWallMs = boundaryTo,
    coveredFromWallMs = coveredFrom,
    coveredToWallMs = coveredTo,
    gapMs = (coveredFrom - boundaryFrom).coerceAtLeast(0) + (boundaryTo - coveredTo).coerceAtLeast(0),
    perUidAvailable = perUidAvailable,
)

internal fun intervalMetrics() = IntervalMetrics(
    fromWallMs = NOW_MS - 10 * HOUR_MS, toWallMs = NOW_MS, screenOnMs = 2 * HOUR_MS, screenOffMs = 8 * HOUR_MS,
    unknownMs = 0, chargingMs = 0, startLevel = 90, endLevel = 60, levelUsedPct = 30, levelDropScreenOn = 16,
    levelDropScreenOff = 4, screenOnPctPerHour = 8.2, screenOffPctPerHour = 0.5, discontinuities = emptyList(),
)

internal fun wakelock(name: String, durationMs: Long, count: Long) = UidWakelock(name, durationMs, bgDurationMs = 0, count = count)

internal fun uidDelta(
    uid: Int,
    packageName: String? = "pkg.$uid",
    valid: Boolean = true,
    wakelocks: List<UidWakelock> = emptyList(),
    alarms: List<UidAlarm> = emptyList(),
    alarmDump: List<UidAlarm> = emptyList(),
) = UidDelta(
    uid = uid, packageName = packageName, packageChanged = false, valid = valid, totalMah = 1.0, fgMah = null, bgMah = null,
    fgsMah = null, cachedMah = null, componentsMah = emptyMap(), cpuUserMs = 0, cpuSystemMs = 0, fgActivityMs = 0,
    fgServiceMs = 0, partialWakelocks = wakelocks, wakeupAlarms = alarms, sensors = emptyList(), alarmDumpWakeups = alarmDump,
)

internal fun snapshotDiff(
    uids: List<UidDelta> = emptyList(),
    kernel: List<KernelWakelock> = emptyList(),
    cpu: List<CpuFreqDelta> = emptyList(),
    batteryStatsValid: Boolean = true,
    kernelCountersValid: Boolean = true,
    invalidKernel: Set<String> = emptySet(),
    sleep: SleepStats? = SleepStats(
        deepSleepMs = 7 * HOUR_MS,
        screenOffDeepSleepMs = 7 * HOUR_MS,
        screenOffAwakeMs = 40 * MIN_MS,
        screenOffMs = 8 * HOUR_MS,
        screenOnMs = 2 * HOUR_MS,
        deepSleepRatio = 0.7,
    ),
) = SnapshotDiff(
    ref = snapshotClock(NOW_MS - 10 * HOUR_MS),
    now = snapshotClock(NOW_MS),
    validity = DiffValidity(
        batteryStats = batteryStatsValid,
        kernelCounters = kernelCountersValid,
        problems = emptyList(),
        invalidKernelWakelocks = invalidKernel,
    ),
    durationMs = 10 * HOUR_MS,
    uids = uids,
    deviceComponentsMah = emptyMap(),
    kernelWakelocks = kernel,
    sleep = sleep,
    levelDropPct = 30,
    dischargedMah = 1_000.0,
    signalStrengthMs = emptyList(),
    signalScanningMs = null,
    mobileRadioActiveMs = null,
    cpuFreqDelta = cpu,
)

internal val TestStory = BatteryStory(listOf(StorySentence.DeepSleep(0.7)))

internal fun intervalAnalysis(
    resolved: ResolvedReference = resolved(),
    diff: SnapshotDiff = snapshotDiff(),
) = IntervalAnalysis(resolved, diff, intervalMetrics(), DiagnosticReport(emptyList(), emptyMap(), 0), TestStory)
