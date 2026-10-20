package com.akane.inspiration.health

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/** A point of a chart. The meaning of [x] and [y] belongs to the series that owns it. */
@Immutable
data class ChartPoint(
    val x: Float,
    val y: Float,
)

/** The four readings of the charge curve, each drawn in its own small chart (no dual axes). */
enum class CurveMetric { Watts, Current, Voltage, Temperature }

/**
 * Where the curve's points come from (H9). [Live] is being recorded right now, [Recorded] was recorded while the
 * screen was open earlier, [History] is the system's battery history (level, temperature, voltage only).
 */
enum class CurveSource { Live, Recorded, History }

/** One small chart of the curve: [points] are (battery level %, value in the metric's unit). */
@Immutable
data class CurveSeriesUi(
    val metric: CurveMetric,
    val points: ImmutableList<ChartPoint>,
    /** Recorded device thermal status was moderate or higher here, on this series' own scale. */
    val throttled: ImmutableList<ChartPoint>,
    val min: Float,
    val max: Float,
    val latest: Float,
)

/** Moderate-or-higher device thermal status observations; null when no reading reaches that threshold. */
@Immutable
data class ThrottleUi(
    val count: Int,
    val fromLevelPct: Int,
    val toLevelPct: Int,
)

sealed interface CurveUi {
    /** No charge in the data window, or too little of it to draw. */
    data object None : CurveUi

    @Immutable
    data class Ready(
        val source: CurveSource,
        val series: ImmutableList<CurveSeriesUi>,
        val startLevelPct: Int,
        val endLevelPct: Int,
        /** Set when only the tail of the curve has power and current: the level where those readings begin. */
        val detailFromLevelPct: Int?,
        /** At least one reading carries device thermal status; a negative summary applies only to those readings. */
        val throttleKnown: Boolean,
        val throttle: ThrottleUi?,
    ) : CurveUi
}

/** Why a figure on the health card is missing. Never a made-up number. */
enum class MissingReason {
    /** The kernel read failed or Shizuku is not ready, and nothing was recorded before. */
    NoKernelRead,

    /** The kernel answered but does not expose this node. */
    NotReported,

    /** No charge was long and clean enough to measure capacity yet. */
    NotMeasuredYet,
}

@Immutable
data class CapacityUi(
    val mah: Double?,
    /** [mah] as a percentage of the design capacity; null when either is unknown. */
    val pctOfDesign: Double?,
    val missing: MissingReason?,
)

@Immutable
data class HealthCardUi(
    val design: CapacityUi,
    /** The kernel's `charge_full` (what the battery holds when full today). */
    val reported: CapacityUi,
    /** The app's own coulomb-counter estimate. */
    val measured: CapacityUi,
    val cycles: Int?,
    val cyclesMissing: MissingReason?,
)

sealed interface WearUi {
    /** Fewer than two usable health records: a designed empty state, not a flat line. */
    data object NotEnough : WearUi

    /** x = milliseconds since [startMs], y = full-charge capacity as a percentage of design. */
    @Immutable
    data class Ready(
        val points: ImmutableList<ChartPoint>,
        val startMs: Long,
        val endMs: Long,
        val minPct: Float,
        val maxPct: Float,
        val firstPct: Float,
        val latestPct: Float,
    ) : WearUi
}

/**
 * Time spent hot and at high voltage during the curve's charge. A null duration means the data had no such
 * reading at all; [observedMs] counts only the stretches that had readings close enough to trust.
 */
@Immutable
data class WearAnalysisUi(
    val source: CurveSource,
    /** Length of the charge the analysis is about. */
    val chargeMs: Long,
    val hotMs: Long?,
    val hotObservedMs: Long,
    val highVoltageMs: Long?,
    val voltageObservedMs: Long,
    val maxTemperatureC: Double?,
    val maxVoltageMv: Int?,
    /** True when readings are sparse battery-history events, so brief peaks can be missed. */
    val sparse: Boolean,
)

enum class HealthPhase { Loading, Ready, Empty, Error }

@Immutable
data class HealthState(
    val phase: HealthPhase,
    /** Plugged in while the screen is visible. Always false while hidden. */
    val charging: Boolean,
    val curve: CurveUi,
    val card: HealthCardUi?,
    val wear: WearUi,
    val analysis: WearAnalysisUi?,
    val refreshing: Boolean,
) {
    companion object {
        val Initial =
            HealthState(
                phase = HealthPhase.Loading,
                charging = false,
                curve = CurveUi.None,
                card = null,
                wear = WearUi.NotEnough,
                analysis = null,
                refreshing = false,
            )
    }
}

sealed interface HealthIntent {
    /** The screen entered the visible state (start, or tab shown). Loads data; live recording follows plug state. */
    data object Visible : HealthIntent

    /** The screen left the screen (stop, or tab hidden). Stops every read and releases the Shizuku binding. */
    data object Hidden : HealthIntent

    /** The plug state changed (or was first read) while the screen is visible. */
    data class PlugChanged(
        val charging: Boolean,
    ) : HealthIntent

    /** Pull to refresh or a retry button. */
    data object Refresh : HealthIntent
}

/** One-shot events the screen shows once (a snackbar), never state. */
sealed interface HealthEffect {
    data object RefreshFailed : HealthEffect
}
