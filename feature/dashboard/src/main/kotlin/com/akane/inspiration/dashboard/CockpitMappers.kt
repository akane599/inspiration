package com.akane.inspiration.dashboard

import com.akane.inspiration.designsystem.chart.model.Lane
import com.akane.inspiration.designsystem.chart.model.LaneKind
import com.akane.inspiration.designsystem.chart.model.LaneSegment
import com.akane.inspiration.designsystem.chart.model.LineSeries
import com.akane.inspiration.designsystem.chart.model.ScrubChartData
import com.akane.inspiration.designsystem.chart.model.SeriesKind
import com.akane.inspiration.designsystem.chart.model.TimePoint
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.component.GaugeChargeState
import com.akane.inspiration.designsystem.component.PulseStatus
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.analysis.IntervalAnalysis
import com.akane.inspiration.diagnostics.analysis.SegmentKind
import com.akane.inspiration.diagnostics.analysis.Timeline
import com.akane.inspiration.diagnostics.analysis.TimelinePoint
import com.akane.inspiration.diagnostics.analysis.forSelection
import com.akane.inspiration.diagnostics.story.reportableDeepSleepRatio
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.interval.IntervalMetrics
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.status.CHARGING_DRAIN_DEADBAND_MA
import com.akane.inspiration.telemetry.status.ChargeState
import com.akane.inspiration.telemetry.status.DEADBAND_HYSTERESIS_MA
import com.akane.inspiration.telemetry.status.chargeState
import com.akane.inspiration.telemetry.status.deadbandHeldBy
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

internal fun referenceUi(kind: ReferenceKind): ReferenceUi =
    when (kind) {
        ReferenceKind.SinceUnplugged -> ReferenceUi.SinceUnplugged

        // The screen-on session is not offered by the pill; it reads as the closest reference.
        ReferenceKind.SinceScreenOff, ReferenceKind.ScreenOnSession -> ReferenceUi.SinceScreenOff

        ReferenceKind.Last24h -> ReferenceUi.Last24h

        is ReferenceKind.CustomMarker -> ReferenceUi.CustomMarker

        is ReferenceKind.TimeSlice -> ReferenceUi.TimeSlice
    }

internal fun referenceKind(choice: ReferenceChoice): ReferenceKind =
    when (choice) {
        ReferenceChoice.SinceUnplugged -> ReferenceKind.SinceUnplugged
        ReferenceChoice.SinceScreenOff -> ReferenceKind.SinceScreenOff
        ReferenceChoice.Last24h -> ReferenceKind.Last24h
    }

internal fun shizukuUi(state: ShizukuState): ShizukuUi =
    when (state) {
        is ShizukuState.Ready -> {
            ShizukuUi(PulseStatus.ACTIVE, ShizukuReason.Ready, state.uid, canGrant = false)
        }

        ShizukuState.PermissionRequired -> {
            ShizukuUi(PulseStatus.CONNECTING, ShizukuReason.PermissionRequired, uid = null, canGrant = true)
        }

        ShizukuState.PermissionDenied -> {
            ShizukuUi(PulseStatus.BLOCKED, ShizukuReason.PermissionDenied, uid = null, canGrant = true)
        }

        ShizukuState.NotRunning -> {
            ShizukuUi(PulseStatus.BLOCKED, ShizukuReason.NotRunning, uid = null, canGrant = false)
        }

        is ShizukuState.UnsupportedServer -> {
            ShizukuUi(PulseStatus.BLOCKED, ShizukuReason.UnsupportedServer, uid = null, canGrant = false)
        }

        ShizukuState.NotInstalled -> {
            ShizukuUi(PulseStatus.BLOCKED, ShizukuReason.NotInstalled, uid = null, canGrant = false)
        }
    }

internal fun analysisUi(state: AnalysisState): AnalysisUi =
    when (state) {
        AnalysisState.Loading -> AnalysisUi.Loading
        is AnalysisState.Empty -> AnalysisUi.Empty(emptyReason(state), state.historyOnly?.let(::historyOnlyMetricsUi))
        is AnalysisState.Failed -> AnalysisUi.Error
        is AnalysisState.Ready -> AnalysisUi.Ready(state.analysis.story, metricsUi(state.analysis))
    }

/** Only "Since screen off" can tell a period the history times but no capture closed from one with no period at all. */
private fun emptyReason(state: AnalysisState.Empty): EmptyReason =
    when {
        state.kind != ReferenceKind.SinceScreenOff -> EmptyReason.NoSnapshotPair
        state.historyOnly != null -> EmptyReason.ScreenOffNotCaptured
        else -> EmptyReason.NoScreenOffPeriod
    }

/**
 * The grid figures the battery history alone supports: the level-drop rates and the screen time. The mAh figures
 * and CPU suspend come from the counters of a snapshot pair, so they stay dashes.
 */
internal fun historyOnlyMetricsUi(metrics: IntervalMetrics): MetricsUi =
    MetricsUi(
        activePctPerHour = metrics.screenOnPctPerHour,
        activeMahPerHour = null,
        idlePctPerHour = metrics.screenOffPctPerHour,
        idleMahPerHour = null,
        deepSleepRatio = null,
        deepSleepMs = null,
        screenOnMs = metrics.attributedScreenOnMs(),
        screenOffAwakeMs = null,
    )

/**
 * History metrics count only attributed time: with unknown time or a reset in the interval their screen-on total is
 * a partial sum, so the tile shows a dash rather than an undercount.
 */
private fun IntervalMetrics.attributedScreenOnMs(): Long? = screenOnMs.takeIf { unknownMs == 0L && discontinuities.isEmpty() }

/**
 * The grid figures of one interval. Anything the interval cannot support stays null: a rate needs at least
 * [MIN_RATE_WINDOW_MS] in its state, mAh figures need the discharge counters and the stats' screen on/off time on
 * both sides of the same battery stats epoch, and the CPU-suspend ratio needs valid kernel counters and, like the story's sentence, 15 minutes of
 * screen-off time.
 */
internal fun metricsUi(analysis: IntervalAnalysis): MetricsUi {
    val metrics = analysis.metrics
    val diff = analysis.diff
    val total = diff.dischargeMah
    val screenOff = diff.dischargeScreenOffMah
    // Screen-on mAh is total minus screen-off; a negative difference means the counters disagree.
    val screenOn = if (total != null && screenOff != null) (total - screenOff).takeIf { it >= 0.0 } else null
    val sleep = diff.sleep
    val ratio = sleep?.reportableDeepSleepRatio()
    return MetricsUi(
        activePctPerHour = metrics.screenOnPctPerHour,
        // The mAh counters span the whole interval, so their window is the battery stats' own on-battery screen
        // time from the same epoch, never history time, which stops at the history horizon (R-09).
        activeMahPerHour = mahPerHour(screenOn, sleep?.screenOnMs),
        idlePctPerHour = metrics.screenOffPctPerHour,
        idleMahPerHour = mahPerHour(screenOff, sleep?.screenOffMs),
        deepSleepRatio = ratio,
        deepSleepMs = sleep?.deepSleepMs?.takeIf { ratio != null },
        screenOnMs = sleep?.screenOnMs ?: metrics.attributedScreenOnMs(),
        screenOffAwakeMs = sleep?.screenOffAwakeMs,
    )
}

private fun mahPerHour(
    mah: Double?,
    windowMs: Long?,
): Double? =
    if (mah == null || mah < 0.0 || windowMs == null || windowMs < MIN_RATE_WINDOW_MS) {
        null
    } else {
        mah / (windowMs / MS_PER_HOUR)
    }

/**
 * The banner data for a committed brush, or null outside time-slice mode. The covered range comes from the
 * analysis only when it answers this exact slice; while a new slice is still loading it stays unknown.
 */
internal fun sliceUi(
    kind: ReferenceKind,
    analysis: AnalysisState,
): SliceUi? {
    if (kind !is ReferenceKind.TimeSlice) return null
    val resolved = (analysis.forSelection(kind) as? AnalysisState.Ready)?.analysis?.resolved
    return SliceUi(
        requested = TimeRange.of(kind.fromWallMs, kind.toWallMs),
        covered = resolved?.let { TimeRange.of(it.coveredFromWallMs, it.coveredToWallMs) },
    )
}

/** The gauge's label for [reading]: the shared [chargeState] rule, which the status notification uses too. */
internal fun chargeState(
    reading: LiveReading,
    previous: GaugeChargeState? = null,
): GaugeChargeState =
    when (chargeState(reading.plugged, reading.status, reading.currentMa, previous?.toChargeState())) {
        ChargeState.Charging -> GaugeChargeState.Charging
        ChargeState.Discharging -> GaugeChargeState.Discharging
        ChargeState.PluggedNotCharging -> GaugeChargeState.PluggedNotCharging
    }

private fun GaugeChargeState.toChargeState(): ChargeState =
    when (this) {
        GaugeChargeState.Charging -> ChargeState.Charging
        GaugeChargeState.Discharging -> ChargeState.Discharging
        GaugeChargeState.PluggedNotCharging -> ChargeState.PluggedNotCharging
    }

/**
 * Time to empty whenever the battery is not charging: `chargeCounterUah / |currentMa|` while it drains, else the
 * system's own estimate. On a charger only a drain past [CHARGING_DRAIN_DEADBAND_MA] counts: a held battery has no
 * time to empty (its few mA would extrapolate to days), and the system's discharge estimate was made off the charger.
 * [systemEstimateMs] must already be checked for age by the caller. A time left a drain past the deadband gave
 * ([previous], the last reading's) holds until the drain is [DEADBAND_HYSTERESIS_MA] back inside it.
 */
internal fun remainingMs(
    reading: LiveReading,
    systemEstimateMs: Long?,
    previous: LiveUi? = null,
): Long? {
    val onCharger =
        when (chargeState(reading, previous?.chargeState)) {
            GaugeChargeState.Charging -> return null
            GaugeChargeState.PluggedNotCharging -> true
            GaugeChargeState.Discharging -> false
        }
    val drainCounted = previous?.chargeState == GaugeChargeState.PluggedNotCharging && previous.remainingMs != null
    val minDrainMa = if (onCharger) deadbandHeldBy(drainCounted) else 1
    val drainMa = reading.currentMa?.let { -it }?.takeIf { it >= minDrainMa }
    val counterUah = reading.chargeCounterUah
    val extrapolated =
        if (drainMa != null && counterUah != null && counterUah > 0) {
            counterUah * UAH_PER_MA_TO_MS / drainMa
        } else {
            null
        }
    return extrapolated?.takeIf { it <= MAX_EXTRAPOLATED_REMAINING_MS }
        ?: systemEstimateMs?.takeIf { it > 0 && !onCharger }
}

/** The gauge for [reading]; [previous] is the last reading's, so a current hovering at the deadband keeps its label. */
internal fun liveUi(
    reading: LiveReading,
    systemEstimateMs: Long?,
    previous: LiveUi? = null,
): LiveUi =
    LiveUi(
        currentMa = reading.currentMa,
        watts = reading.watts,
        voltageMv = reading.voltageMv,
        temperatureC = reading.temperatureDeciC?.let { it / DECI },
        remainingMs = remainingMs(reading, systemEstimateMs, previous),
        chargeState = chargeState(reading, previous?.chargeState),
    )

/**
 * Maps a [Timeline] to the scrub chart: battery % and temperature lines, and the four lanes. The chart's
 * "deep sleep" lane is [SegmentKind.DEEP_IDLE], which is Doze; the designsystem labels it "Deep doze". Light idle
 * and maintenance have no lane, so they stay unmarked rather than being drawn as awake.
 */
internal fun scrubChartData(timeline: Timeline): ScrubChartData =
    ScrubChartData(
        window = TimeRange.of(timeline.from, timeline.to),
        series =
            listOf(
                LineSeries.of(SeriesKind.BATTERY_PERCENT, timeline.levelPoints.map(TimelinePoint::toChartPoint)),
                LineSeries.of(SeriesKind.TEMPERATURE_C, timeline.temperaturePoints.map(TimelinePoint::toChartPoint)),
            ).toImmutableList(),
        lanes =
            LaneKind.entries
                .map { lane ->
                    val kind = lane.segmentKind()
                    Lane.of(
                        lane,
                        timeline.segments.filter { it.kind == kind }.map { LaneSegment(it.startMs, it.endMs) },
                    )
                }.toImmutableList(),
    )

private fun TimelinePoint.toChartPoint() = TimePoint(tMs, value)

private fun LaneKind.segmentKind(): SegmentKind =
    when (this) {
        LaneKind.SCREEN_ON -> SegmentKind.SCREEN_ON
        LaneKind.DEEP_SLEEP -> SegmentKind.DEEP_IDLE
        LaneKind.AWAKE -> SegmentKind.AWAKE
        LaneKind.CHARGING -> SegmentKind.CHARGING
    }
