package com.akane.inspiration.dashboard

import com.akane.inspiration.diagnostics.analysis.AnalysisSelection
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.analysis.IntervalAnalysis
import com.akane.inspiration.diagnostics.analysis.Timeline
import com.akane.inspiration.diagnostics.model.DiagnosticReport
import com.akane.inspiration.diagnostics.story.BatteryStory
import com.akane.inspiration.diagnostics.story.StorySentence
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.diff.ResolvedReference
import com.akane.inspiration.telemetry.dump.SystemTimers
import com.akane.inspiration.telemetry.interval.IntervalMetrics
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.DiffValidity
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SleepStats
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotDiff
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.store.Marker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.ContinuationInterceptor

internal const val HOUR_MS = 3_600_000L
internal const val MIN_MS = 60_000L
internal const val NOW_MS = 1_000L * HOUR_MS

internal class FakeClocks(
    var wall: Long = NOW_MS,
) : Clocks {
    override fun wallMs() = wall

    override fun elapsedRealtimeMs() = wall

    override fun uptimeMs() = wall

    override fun bootCount() = 1
}

/** A scripted app side: counts what the ViewModel asks for so tests can prove what it stopped asking for. */
internal class FakeCockpitDeps(
    override val clocks: FakeClocks = FakeClocks(),
) : CockpitDependencies {
    override val analysis = MutableStateFlow<AnalysisState>(AnalysisState.Loading)
    override val selection = AnalysisSelection()
    override val shizukuState = MutableStateFlow<ShizukuState>(ShizukuState.Ready(uid = 2000, apiVersion = 13))

    var refreshResult: RefreshResult = RefreshResult.Refreshed(NOW_MS)
    var refreshCalls = 0

    /** While set, refresh() suspends until it completes, so a test can act during a running refresh. */
    var refreshGate: CompletableDeferred<Unit>? = null
    var permissionRequests = 0

    /** Readings the live flow produced, i.e. how many reads were requested of the device. */
    var readingsRequested = 0
    var liveCollections = 0
    var requestedPeriodMs = 0L
    var bindingsOpened = 0
    var bindingsClosed = 0
    var timelineCalls = mutableListOf<Pair<Long, Long>>()
    var timelineResult: Timeline? = emptyTimeline()

    /** The dispatcher the last timeline read ran on. */
    var timelineDispatcher: ContinuationInterceptor? = null
    var markerList: List<Marker> = emptyList()
    var createMarkerResult: Long? = 1L
    val createdLabels = mutableListOf<String>()

    override suspend fun refresh(): RefreshResult {
        refreshCalls++
        refreshGate?.await()
        return refreshResult
    }

    var recomputeCalls = 0

    /** What a recompute publishes, standing in for the session re-running the analysis; null leaves it as is. */
    var recomputeResult: AnalysisState? = null

    override fun recomputeAnalysis() {
        recomputeCalls++
        recomputeResult?.let { analysis.value = it }
    }

    override fun requestShizukuPermission() {
        permissionRequests++
    }

    /** When set, the live readings come from this flow instead of a reading every period. */
    var liveFlow: Flow<LiveReading>? = null

    override fun liveReadings(periodMs: Long): Flow<LiveReading> =
        liveFlow ?: flow {
            liveCollections++
            requestedPeriodMs = periodMs
            while (true) {
                readingsRequested++
                emit(liveReading(currentMa = -300 - readingsRequested))
                delay(periodMs)
            }
        }

    override fun holdBinding(): AutoCloseable {
        bindingsOpened++
        return AutoCloseable { bindingsClosed++ }
    }

    override suspend fun timeline(
        from: Long,
        to: Long,
    ): Timeline {
        timelineCalls += from to to
        timelineDispatcher = currentCoroutineContext()[ContinuationInterceptor]
        return timelineResult ?: error("timeline unavailable")
    }

    override suspend fun markers(): List<Marker> = markerList

    override suspend fun createMarker(label: String): Long? {
        createdLabels += label
        return createMarkerResult?.also { id -> markerList = markerList + Marker(id, snapshotId = id, label = label, wallMs = clocks.wall) }
    }
}

internal fun emptyTimeline(
    from: Long = NOW_MS - 24 * HOUR_MS,
    to: Long = NOW_MS,
) = Timeline(emptyList(), emptyList(), emptyList(), emptyList(), from, to)

internal fun liveReading(
    wallMs: Long = NOW_MS,
    currentMa: Int? = -300,
    plugged: Boolean? = false,
    chargeCounterUah: Long? = null,
    temperatureDeciC: Int? = 315,
) = LiveReading(
    wallMs = wallMs,
    elapsedRealtimeMs = wallMs,
    bootCount = 1,
    currentMa = currentMa,
    voltageMv = 4_000,
    watts = currentMa?.let { it * 4.0 / 1000 },
    temperatureDeciC = temperatureDeciC,
    levelPct = 80,
    plugged = plugged,
    chargeCounterUah = chargeCounterUah,
)

internal fun snapshotClock(wallMs: Long) = SnapshotClock(wallMs, wallMs, wallMs, 1, statsStartMs = 0, statsDurationMs = wallMs)

internal fun systemTimers(dischargeTimeRemainingMs: Long?) =
    SystemTimers(
        startClockTimeMs = 0,
        totalRealtimeMs = 0,
        totalUptimeMs = 0,
        batteryRealtimeMs = 0,
        batteryUptimeMs = 0,
        screenOffRealtimeMs = 0,
        screenOffUptimeMs = 0,
        screenDozeMs = 0,
        screenOnMs = 0,
        interactiveMs = 0,
        deepDozeMs = 0,
        deepDozeCount = 0,
        lightDozeMs = 0,
        dischargeTotalMah = 0.0,
        dischargeScreenOffMah = 0.0,
        chargeTimeRemainingMs = null,
        dischargeTimeRemainingMs = dischargeTimeRemainingMs,
    )

internal fun snapshot(
    wallMs: Long,
    system: SystemTimers? = null,
) = BatterySnapshot(
    id = wallMs,
    kind = SnapshotKind.FULL,
    trigger = CaptureTrigger.APP_OPEN,
    clock = snapshotClock(wallMs),
    battery = BatteryState(80, false, null, 300, 4_000, -300, null, null),
    system = system,
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
    nowSystem: SystemTimers? = null,
) = ResolvedReference(
    ref = snapshot(coveredFrom),
    now = snapshot(coveredTo, nowSystem),
    boundaryFromWallMs = boundaryFrom,
    boundaryToWallMs = boundaryTo,
    coveredFromWallMs = coveredFrom,
    coveredToWallMs = coveredTo,
    gapMs = (coveredFrom - boundaryFrom).coerceAtLeast(0) + (boundaryTo - coveredTo).coerceAtLeast(0),
    perUidAvailable = true,
)

internal fun intervalMetrics(
    screenOnMs: Long = 2 * HOUR_MS,
    screenOffMs: Long = 8 * HOUR_MS,
    screenOnPctPerHour: Double? = 8.2,
    screenOffPctPerHour: Double? = 0.5,
) = IntervalMetrics(
    fromWallMs = NOW_MS - 10 * HOUR_MS,
    toWallMs = NOW_MS,
    screenOnMs = screenOnMs,
    screenOffMs = screenOffMs,
    unknownMs = 0,
    chargingMs = 0,
    startLevel = 90,
    endLevel = 60,
    levelUsedPct = 30,
    levelDropScreenOn = 16,
    levelDropScreenOff = 4,
    screenOnPctPerHour = screenOnPctPerHour,
    screenOffPctPerHour = screenOffPctPerHour,
    discontinuities = emptyList(),
)

internal fun snapshotDiff(
    dischargeMah: Double? = 1_000.0,
    dischargeScreenOffMah: Double? = 200.0,
    sleep: SleepStats? =
        SleepStats(
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
    validity = DiffValidity(batteryStats = true, kernelCounters = true, problems = emptyList()),
    durationMs = 10 * HOUR_MS,
    uids = emptyList(),
    deviceComponentsMah = emptyMap(),
    kernelWakelocks = emptyList(),
    sleep = sleep,
    levelDropPct = 30,
    dischargedMah = dischargeMah,
    signalStrengthMs = emptyList(),
    signalScanningMs = null,
    mobileRadioActiveMs = null,
    dischargeMah = dischargeMah,
    dischargeScreenOffMah = dischargeScreenOffMah,
)

internal val TestStory = BatteryStory(listOf(StorySentence.DeepSleep(0.7)))

internal fun intervalAnalysis(
    resolved: ResolvedReference = resolved(),
    diff: SnapshotDiff = snapshotDiff(),
    metrics: IntervalMetrics = intervalMetrics(),
) = IntervalAnalysis(resolved, diff, metrics, DiagnosticReport(emptyList(), emptyMap(), 0), TestStory)

/** [analysis] as the session publishes it for [kind]; the default is the default selection. */
internal fun ready(
    analysis: IntervalAnalysis = intervalAnalysis(),
    kind: ReferenceKind = ReferenceKind.SinceUnplugged,
) = AnalysisState.Ready(analysis, kind)
