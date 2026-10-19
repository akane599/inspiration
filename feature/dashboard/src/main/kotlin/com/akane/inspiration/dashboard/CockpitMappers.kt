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

/**
 * A "charging" battery drawing less than this is within noise of zero (a taper ending, a charger regulating), so the
 * gauge keeps the battery's own status rather than flicker between labels.
 */
private const val CHARGING_DRAIN_DEADBAND_MA = 50

// LiveReading.status values (the battery's own BATTERY_STATUS_*).
private const val STATUS_CHARGING = "charging"
private const val STATUS_DISCHARGING = "discharging"
private const val STATUS_FULL = "full"
private const val STATUS_NOT_CHARGING = "not_charging"

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
        AnalysisState.Empty -> AnalysisUi.Empty
        is AnalysisState.Failed -> AnalysisUi.Error
        is AnalysisState.Ready -> AnalysisUi.Ready(state.analysis.story, metricsUi(state.analysis))
    }

/**
 * The grid figures of one interval. Anything the interval cannot support stays null: a rate needs at least
 * [MIN_RATE_WINDOW_MS] in its state, mAh figures need the discharge counters on both sides of the same battery
 * stats epoch, and the CPU-suspend ratio needs valid kernel counters and, like the story's sentence, 15 minutes of
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
        activeMahPerHour = mahPerHour(screenOn, metrics.screenOnMs),
        idlePctPerHour = metrics.screenOffPctPerHour,
        idleMahPerHour = mahPerHour(screenOff, metrics.screenOffMs),
        deepSleepRatio = ratio,
        deepSleepMs = sleep?.deepSleepMs?.takeIf { ratio != null },
        // History metrics count only attributed time: with unknown time or a reset in the interval their
        // screen-on total is a partial sum, so the tile shows a dash rather than an undercount.
        screenOnMs =
            sleep?.screenOnMs
                ?: metrics.screenOnMs.takeIf { metrics.unknownMs == 0L && metrics.discontinuities.isEmpty() },
        screenOffAwakeMs = sleep?.screenOffAwakeMs,
    )
}

private fun mahPerHour(
    mah: Double?,
    windowMs: Long,
): Double? = if (mah == null || windowMs < MIN_RATE_WINDOW_MS) null else mah / (windowMs / MS_PER_HOUR)

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

/**
 * What the battery is doing, from its own status and the measured current, never from the plug alone: a plugged
 * battery can be full, held at a charge limit, or losing to the load. The plug decides only when both are unknown.
 */
internal fun chargeState(reading: LiveReading): GaugeChargeState {
    val currentMa = reading.currentMa
    val plugged = reading.plugged
    return when {
        // Unplugged, the battery can only discharge.
        plugged == false -> {
            GaugeChargeState.Discharging
        }

        // "Charging" means the charger is enabled; a weak one can still lose to the load.
        reading.status == STATUS_CHARGING -> {
            if (currentMa != null && currentMa <= -CHARGING_DRAIN_DEADBAND_MA) {
                GaugeChargeState.PluggedNotCharging
            } else {
                GaugeChargeState.Charging
            }
        }

        reading.status == STATUS_DISCHARGING -> {
            if (plugged == true) GaugeChargeState.PluggedNotCharging else GaugeChargeState.Discharging
        }

        reading.status == STATUS_FULL || reading.status == STATUS_NOT_CHARGING -> {
            GaugeChargeState.PluggedNotCharging
        }

        // No usable status: the measured direction decides.
        currentMa != null && currentMa > 0 -> {
            GaugeChargeState.Charging
        }

        currentMa != null -> {
            if (plugged == true) GaugeChargeState.PluggedNotCharging else GaugeChargeState.Discharging
        }

        plugged == true -> {
            GaugeChargeState.Charging
        }

        else -> {
            GaugeChargeState.Discharging
        }
    }
}

/**
 * Time to empty whenever the battery is not charging: `chargeCounterUah / |currentMa|` while it drains, else the
 * system's own estimate. On a charger only a measured drain counts: a held battery has no time to empty, and the
 * system's discharge estimate was made off the charger. [systemEstimateMs] must already be checked for age by the
 * caller.
 */
internal fun remainingMs(
    reading: LiveReading,
    systemEstimateMs: Long?,
): Long? {
    if (chargeState(reading) == GaugeChargeState.Charging) return null
    val currentMa = reading.currentMa
    val counterUah = reading.chargeCounterUah
    val extrapolated =
        if (currentMa != null && currentMa < 0 && counterUah != null && counterUah > 0) {
            counterUah * UAH_PER_MA_TO_MS / -currentMa
        } else {
            null
        }
    return extrapolated?.takeIf { it <= MAX_EXTRAPOLATED_REMAINING_MS }
        ?: systemEstimateMs?.takeIf { it > 0 && reading.plugged != true }
}

internal fun liveUi(
    reading: LiveReading,
    systemEstimateMs: Long?,
): LiveUi =
    LiveUi(
        currentMa = reading.currentMa,
        watts = reading.watts,
        voltageMv = reading.voltageMv,
        temperatureC = reading.temperatureDeciC?.let { it / DECI },
        remainingMs = remainingMs(reading, systemEstimateMs),
        chargeState = chargeState(reading),
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
