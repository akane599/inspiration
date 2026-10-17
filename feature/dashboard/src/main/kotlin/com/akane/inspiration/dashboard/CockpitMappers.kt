package com.akane.inspiration.dashboard

import com.akane.inspiration.designsystem.chart.model.Lane
import com.akane.inspiration.designsystem.chart.model.LaneKind
import com.akane.inspiration.designsystem.chart.model.LaneSegment
import com.akane.inspiration.designsystem.chart.model.LineSeries
import com.akane.inspiration.designsystem.chart.model.ScrubChartData
import com.akane.inspiration.designsystem.chart.model.SeriesKind
import com.akane.inspiration.designsystem.chart.model.TimePoint
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.component.PulseStatus
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.analysis.IntervalAnalysis
import com.akane.inspiration.diagnostics.analysis.SegmentKind
import com.akane.inspiration.diagnostics.analysis.Timeline
import com.akane.inspiration.diagnostics.analysis.TimelinePoint
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.snapshot.LiveReading
import kotlinx.collections.immutable.toImmutableList

/** Same floor the interval metrics use for a rate: under 15 minutes of a state, a per-hour figure is noise. */
private const val MIN_RATE_WINDOW_MS = 15 * 60_000L
private const val MS_PER_HOUR = 3_600_000.0

/** µAh / mA = 1e-3 h, and one hour is 3.6e6 ms, so ms = µAh * 3600 / mA. */
private const val UAH_PER_MA_TO_MS = 3_600L

/** An extrapolated instantaneous current says nothing beyond a week; fall back to the system estimate. */
private const val MAX_EXTRAPOLATED_REMAINING_MS = 7L * 24 * 3_600_000

/** The system's own estimate is a snapshot value; older than this it no longer describes now. */
internal const val SYSTEM_ESTIMATE_MAX_AGE_MS = 10 * 60_000L

private const val DECI = 10.0

internal fun referenceUi(kind: ReferenceKind): ReferenceUi = when (kind) {
    ReferenceKind.SinceUnplugged -> ReferenceUi.SinceUnplugged
    // The screen-on session is not offered by the pill; it reads as the closest reference.
    ReferenceKind.SinceScreenOff, ReferenceKind.ScreenOnSession -> ReferenceUi.SinceScreenOff
    ReferenceKind.Last24h -> ReferenceUi.Last24h
    is ReferenceKind.CustomMarker -> ReferenceUi.CustomMarker
    is ReferenceKind.TimeSlice -> ReferenceUi.TimeSlice
}

internal fun referenceKind(choice: ReferenceChoice): ReferenceKind = when (choice) {
    ReferenceChoice.SinceUnplugged -> ReferenceKind.SinceUnplugged
    ReferenceChoice.SinceScreenOff -> ReferenceKind.SinceScreenOff
    ReferenceChoice.Last24h -> ReferenceKind.Last24h
}

internal fun shizukuUi(state: ShizukuState): ShizukuUi = when (state) {
    is ShizukuState.Ready -> ShizukuUi(PulseStatus.ACTIVE, ShizukuReason.Ready, state.uid, canGrant = false)
    ShizukuState.PermissionRequired ->
        ShizukuUi(PulseStatus.CONNECTING, ShizukuReason.PermissionRequired, uid = null, canGrant = true)
    ShizukuState.PermissionDenied ->
        ShizukuUi(PulseStatus.BLOCKED, ShizukuReason.PermissionDenied, uid = null, canGrant = true)
    ShizukuState.NotRunning -> ShizukuUi(PulseStatus.BLOCKED, ShizukuReason.NotRunning, uid = null, canGrant = false)
    ShizukuState.NotInstalled ->
        ShizukuUi(PulseStatus.BLOCKED, ShizukuReason.NotInstalled, uid = null, canGrant = false)
}

internal fun analysisUi(state: AnalysisState): AnalysisUi = when (state) {
    AnalysisState.Loading -> AnalysisUi.Loading
    AnalysisState.Empty -> AnalysisUi.Empty
    is AnalysisState.Failed -> AnalysisUi.Error
    is AnalysisState.Ready -> AnalysisUi.Ready(state.analysis.story, metricsUi(state.analysis))
}

/**
 * The grid figures of one interval. Anything the interval cannot support stays null: a rate needs at least
 * [MIN_RATE_WINDOW_MS] in its state, mAh figures need the discharge counters on both sides of the same battery
 * stats epoch, and the CPU-suspend ratio needs valid kernel counters.
 */
internal fun metricsUi(analysis: IntervalAnalysis): MetricsUi {
    val metrics = analysis.metrics
    val diff = analysis.diff
    val total = diff.dischargeMah
    val screenOff = diff.dischargeScreenOffMah
    // Screen-on mAh is total minus screen-off; a negative difference means the counters disagree.
    val screenOn = if (total != null && screenOff != null) (total - screenOff).takeIf { it >= 0.0 } else null
    val sleep = diff.sleep
    val ratio = sleep?.deepSleepRatio
    return MetricsUi(
        activePctPerHour = metrics.screenOnPctPerHour,
        activeMahPerHour = mahPerHour(screenOn, metrics.screenOnMs),
        idlePctPerHour = metrics.screenOffPctPerHour,
        idleMahPerHour = mahPerHour(screenOff, metrics.screenOffMs),
        deepSleepRatio = ratio,
        deepSleepMs = sleep?.deepSleepMs?.takeIf { ratio != null },
        screenOnMs = sleep?.screenOnMs ?: metrics.screenOnMs,
        screenOffAwakeMs = sleep?.screenOffAwakeMs,
    )
}

private fun mahPerHour(mah: Double?, windowMs: Long): Double? =
    if (mah == null || windowMs < MIN_RATE_WINDOW_MS) null else mah / (windowMs / MS_PER_HOUR)

/**
 * The banner data for a committed brush, or null outside time-slice mode. The covered range comes from the
 * analysis only when it answers this exact slice; while a new slice is still loading it stays unknown.
 */
internal fun sliceUi(kind: ReferenceKind, analysis: AnalysisState): SliceUi? {
    if (kind !is ReferenceKind.TimeSlice) return null
    val resolved = (analysis as? AnalysisState.Ready)?.analysis?.resolved
        ?.takeIf { it.boundaryFromWallMs == kind.fromWallMs && it.boundaryToWallMs == kind.toWallMs }
    return SliceUi(
        requested = TimeRange.of(kind.fromWallMs, kind.toWallMs),
        covered = resolved?.let { TimeRange.of(it.coveredFromWallMs, it.coveredToWallMs) },
    )
}

/**
 * Time to empty: `chargeCounterUah / |currentMa|` while discharging, else the system's own estimate. Both are
 * unavailable while charging. [systemEstimateMs] must already be checked for age by the caller.
 */
internal fun remainingMs(reading: LiveReading, systemEstimateMs: Long?): Long? {
    if (reading.plugged == true) return null
    val currentMa = reading.currentMa
    val counterUah = reading.chargeCounterUah
    val extrapolated = if (currentMa != null && currentMa < 0 && counterUah != null && counterUah > 0) {
        counterUah * UAH_PER_MA_TO_MS / -currentMa
    } else {
        null
    }
    return extrapolated?.takeIf { it <= MAX_EXTRAPOLATED_REMAINING_MS } ?: systemEstimateMs?.takeIf { it > 0 }
}

internal fun liveUi(reading: LiveReading, systemEstimateMs: Long?): LiveUi {
    val currentMa = reading.currentMa
    return LiveUi(
        currentMa = currentMa,
        watts = reading.watts,
        voltageMv = reading.voltageMv,
        temperatureC = reading.temperatureDeciC?.let { it / DECI },
        remainingMs = remainingMs(reading, systemEstimateMs),
        charging = reading.plugged ?: (currentMa != null && currentMa > 0),
    )
}

/**
 * Maps a [Timeline] to the scrub chart: battery % and temperature lines, and the four lanes. The chart's
 * "deep sleep" lane is [SegmentKind.DEEP_IDLE], which is Doze; the designsystem labels it "Deep doze". Light idle
 * and maintenance have no lane, so they stay unmarked rather than being drawn as awake.
 */
internal fun scrubChartData(timeline: Timeline): ScrubChartData = ScrubChartData(
    window = TimeRange.of(timeline.from, timeline.to),
    series = listOf(
        LineSeries.of(SeriesKind.BATTERY_PERCENT, timeline.levelPoints.map(TimelinePoint::toChartPoint)),
        LineSeries.of(SeriesKind.TEMPERATURE_C, timeline.temperaturePoints.map(TimelinePoint::toChartPoint)),
    ).toImmutableList(),
    lanes = LaneKind.entries.map { lane ->
        val kind = lane.segmentKind()
        Lane.of(
            lane,
            timeline.segments.filter { it.kind == kind }.map { LaneSegment(it.startMs, it.endMs) },
        )
    }.toImmutableList(),
)

private fun TimelinePoint.toChartPoint() = TimePoint(tMs, value)

private fun LaneKind.segmentKind(): SegmentKind = when (this) {
    LaneKind.SCREEN_ON -> SegmentKind.SCREEN_ON
    LaneKind.DEEP_SLEEP -> SegmentKind.DEEP_IDLE
    LaneKind.AWAKE -> SegmentKind.AWAKE
    LaneKind.CHARGING -> SegmentKind.CHARGING
}
