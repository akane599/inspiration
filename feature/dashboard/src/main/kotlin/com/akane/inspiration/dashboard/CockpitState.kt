package com.akane.inspiration.dashboard

import androidx.compose.runtime.Immutable
import com.akane.inspiration.designsystem.chart.model.ScrubChartData
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.component.PulseStatus
import com.akane.inspiration.diagnostics.story.BatteryStory
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Which reference the pill shows. [TimeSlice] is set by the chart brush, never picked from the pill menu. */
sealed interface ReferenceUi {
    data object SinceUnplugged : ReferenceUi
    data object SinceScreenOff : ReferenceUi
    data object Last24h : ReferenceUi
    data object CustomMarker : ReferenceUi
    data object TimeSlice : ReferenceUi
}

/** The references the pill menu offers directly; the custom marker opens the marker sheet instead. */
enum class ReferenceChoice { SinceUnplugged, SinceScreenOff, Last24h }

@Immutable
data class ShizukuUi(
    val status: PulseStatus,
    val reason: ShizukuReason,
    /** The Shizuku UID when [reason] is [ShizukuReason.Ready]. */
    val uid: Int?,
    /** True when asking for permission can help (required or denied). */
    val canGrant: Boolean,
)

enum class ShizukuReason { Ready, PermissionRequired, PermissionDenied, NotRunning, NotInstalled }

/** One reading of the live gauge. Every field may be missing; the UI shows a dash for it. */
@Immutable
data class LiveUi(
    val currentMa: Int?,
    val watts: Double?,
    val voltageMv: Int?,
    val temperatureC: Double?,
    /** Estimated time to empty, null when it cannot be estimated (charging, no data). */
    val remainingMs: Long?,
    val charging: Boolean,
)

/** Raw numbers for the 2x2 grid; the UI formats them. Null is rendered as a dash. */
@Immutable
data class MetricsUi(
    val activePctPerHour: Double?,
    val activeMahPerHour: Double?,
    val idlePctPerHour: Double?,
    val idleMahPerHour: Double?,
    /** CPU suspend ("deep sleep"), a fraction 0..1. Not doze. */
    val deepSleepRatio: Double?,
    val deepSleepMs: Long?,
    val screenOnMs: Long?,
    val screenOffAwakeMs: Long?,
)

sealed interface AnalysisUi {
    data object Loading : AnalysisUi

    /** The selected period has no snapshot pair yet. */
    data object Empty : AnalysisUi

    data object Error : AnalysisUi

    data class Ready(val story: BatteryStory, val metrics: MetricsUi) : AnalysisUi
}

sealed interface ChartUi {
    data object Loading : ChartUi
    data object Failed : ChartUi
    data class Ready(val data: ScrubChartData) : ChartUi
}

/** The time-slice banner: what the brush asked for, what the data covers, and a gap worth warning about. */
@Immutable
data class SliceUi(
    val requested: TimeRange,
    val covered: TimeRange?,
    /** Distance between the requested and covered ranges when it is over five minutes, else null. */
    val gapMs: Long?,
)

@Immutable
data class MarkerUi(val id: Long, val label: String, val wallMs: Long)

@Immutable
data class MarkerSheetUi(
    val loading: Boolean,
    val markers: ImmutableList<MarkerUi>,
    val creating: Boolean,
    val failed: Boolean,
    /** The marker currently used as the reference, if any. */
    val selectedId: Long?,
) {
    companion object {
        fun loading(selectedId: Long?) = MarkerSheetUi(
            loading = true,
            markers = persistentListOf(),
            creating = false,
            failed = false,
            selectedId = selectedId,
        )
    }
}

@Immutable
data class CockpitState(
    val reference: ReferenceUi,
    val shizuku: ShizukuUi,
    val analysis: AnalysisUi,
    val live: LiveUi?,
    val chart: ChartUi,
    /** The committed brush range; also the chart's selection. Null outside time-slice mode. */
    val slice: SliceUi?,
    /** Null while the marker sheet is closed. */
    val markerSheet: MarkerSheetUi?,
    val refreshing: Boolean,
) {
    companion object {
        val Initial = CockpitState(
            reference = ReferenceUi.SinceUnplugged,
            shizuku = ShizukuUi(PulseStatus.BLOCKED, ShizukuReason.NotRunning, uid = null, canGrant = false),
            analysis = AnalysisUi.Loading,
            live = null,
            chart = ChartUi.Loading,
            slice = null,
            markerSheet = null,
            refreshing = false,
        )
    }
}

sealed interface CockpitIntent {
    /** The Cockpit entered the visible state (start, or tab shown). Starts the live gauge and refreshes. */
    data object Visible : CockpitIntent

    /** The Cockpit left the screen (stop, or tab hidden). Stops every read and releases the binding. */
    data object Hidden : CockpitIntent

    /** Pull to refresh or a retry button. */
    data object Refresh : CockpitIntent
    data class SelectReference(val choice: ReferenceChoice) : CockpitIntent
    data object OpenMarkers : CockpitIntent
    data object DismissMarkers : CockpitIntent
    data class SelectMarker(val markerId: Long) : CockpitIntent
    data class MarkNow(val label: String) : CockpitIntent

    /** The chart brush was released over [fromMs]..[toMs]. */
    data class CommitBrush(val fromMs: Long, val toMs: Long) : CockpitIntent
    data object ClearSlice : CockpitIntent
    data object GrantShizuku : CockpitIntent
}

/** One-shot events the screen shows once (a snackbar), never state. */
sealed interface CockpitEffect {
    data object RefreshFailed : CockpitEffect
}
