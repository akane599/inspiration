package com.akane.inspiration.sleeplab

import androidx.compose.runtime.Immutable
import com.akane.inspiration.designsystem.chart.model.HypnogramData
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.diagnostics.dictionary.EntryKind
import com.akane.inspiration.diagnostics.model.CulpritScope
import com.akane.inspiration.diagnostics.model.Severity
import kotlinx.collections.immutable.ImmutableList

/** The three diagnostic tabs under the hypnogram. */
enum class SleepTab { AppWakelocks, KernelWakelocks, Alarms }

/** One partial wakelock held by an app: the tag, how long and how often. */
@Immutable
data class AppWakelockRow(
    val uid: Int,
    val packageName: String?,
    /** The app's label, or null when the owner is unknown (shown as the UID). */
    val appLabel: String?,
    val name: String,
    val durationMs: Long,
    val count: Long,
)

/** A kernel wakelock as batterystats reports it. */
@Immutable
data class KernelWakelockRow(val name: String, val durationMs: Long, val count: Long)

@Immutable
data class AlarmTagRow(val tag: String, val count: Long)

/** One app that woke the device with alarms: the total, and its busiest tags first. */
@Immutable
data class AlarmRow(
    val uid: Int,
    val packageName: String?,
    val appLabel: String?,
    val wakeups: Long,
    val tags: ImmutableList<AlarmTagRow>,
)

/** What a tab's list can be. An empty [Rows] is a real answer ("nothing in this period"). */
sealed interface ListUi<out T> {
    /** Per-app figures need a full snapshot at both ends of the interval (H14). */
    data object NeedsFullSnapshots : ListUi<Nothing>

    /** The counters cannot be compared across this interval (reset or reboot). */
    data object Unavailable : ListUi<Nothing>

    /** [rows] ranked, at most [MAX_ROWS] of them; [hidden] more existed and are not shown. */
    @Immutable
    data class Rows<T>(val rows: ImmutableList<T>, val hidden: Int) : ListUi<T>
}

/** Which kind of core the cluster most likely is, by policy order; [Generic] when the order says nothing. */
enum class ClusterRole { Little, Big, Mid, Prime, Generic }

/** Time at one frequency; [share] is its part of the cluster's whole time-in-state, 0..1. */
@Immutable
data class FreqRow(val freqKhz: Long, val timeMs: Long, val share: Float)

/** One CPU frequency policy (cluster) with its busiest frequencies in ascending order. */
@Immutable
data class ClusterUi(
    val policy: Int,
    val role: ClusterRole,
    val totalMs: Long,
    val rows: ImmutableList<FreqRow>,
    /** Part of the time spent at frequencies not listed in [rows], 0..1. Zero when everything is shown. */
    val otherShare: Float,
)

sealed interface CpuUi {
    /** The kernel counters cannot be compared across this interval (reboot), so no residency is shown. */
    data object Unavailable : CpuUi

    /** The counters are valid but no time-in-state was recorded. */
    data object NoData : CpuUi

    @Immutable
    data class Ready(val clusters: ImmutableList<ClusterUi>) : CpuUi
}

/** CPU suspend ("deep sleep"), not doze. Both fields are null when the interval cannot measure it. */
@Immutable
data class DeepSleepUi(val ratio: Double?, val suspendedMs: Long?)

/**
 * One of the culprit card's top contributors (TEMPLATE.md §4 Rule 2): the top partial wakelock, kernel wakelock or
 * wake-up alarm. [durationMs] is null for an alarm, which batterystats only counts.
 */
@Immutable
data class CulpritRow(
    val kind: EntryKind,
    val name: String,
    /** Null for a kernel wakelock, which no app owns. */
    val uid: Int?,
    val packageName: String?,
    /** The app's label, or null when the owner is unknown (shown by its UID). */
    val appLabel: String?,
    val durationMs: Long?,
    val count: Long?,
)

/** Why the screen-off awake check (Rule 2) could not run for the period. */
enum class AwakeSkipReason {
    /** Less than the rule's minimum screen-off time, or no screen-off figures at all. */
    ShortScreenOff,

    /** The period has screen-off time but no awake time was recorded for it. */
    NoAwakeTime,

    /** A reason this build does not word; said as "couldn't check", never as a pass. */
    Other,
}

/** The screen-off awake check (TEMPLATE.md §4 Rule 2) for the selected period, and with it the culprit card. */
sealed interface AwakeCheckUi {
    /** Checked and within the limit: no card. */
    data object WithinLimit : AwakeCheckUi

    /** The check could not run; the card says why, so the missing culprits never read as "all good". */
    data class NotEvaluable(val reason: AwakeSkipReason) : AwakeCheckUi

    /**
     * The device stayed awake for too much of its screen-off time ("Suçlu Kartı"). [culprits] holds at most one per
     * kind, in the rule's order.
     */
    @Immutable
    data class Violation(
        val severity: Severity,
        /** Awake share of screen-off time, 0..1. */
        val awakeRatio: Double,
        val screenOffMs: Long,
        val scope: CulpritScope,
        val culprits: ImmutableList<CulpritRow>,
        /** App wakelocks and alarms need a full snapshot at both ends (H14), so only kernel culprits are listed. */
        val needsFullSnapshots: Boolean,
        /** Apps no rule checked because their counters reset or their package changed. */
        val skippedApps: Int,
    ) : AwakeCheckUi
}

sealed interface AnalysisUi {
    data object Loading : AnalysisUi

    /** The selected period has no snapshot pair yet. */
    data object Empty : AnalysisUi

    data object Error : AnalysisUi

    @Immutable
    data class Ready(
        val appWakelocks: ListUi<AppWakelockRow>,
        val kernelWakelocks: ListUi<KernelWakelockRow>,
        val alarms: ListUi<AlarmRow>,
        val cpu: CpuUi,
        val deepSleep: DeepSleepUi,
        val awake: AwakeCheckUi,
    ) : AnalysisUi
}

sealed interface HypnogramUi {
    data object Loading : HypnogramUi
    data object Failed : HypnogramUi

    /** [data] has no segments when the period holds no screen-off doze data; the card then shows its empty state. */
    @Immutable
    data class Ready(val data: HypnogramData) : HypnogramUi
}

/** The time-slice banner: what the brush asked for and what the data covers; the banner words the gaps itself. */
@Immutable
data class SliceUi(
    val requested: TimeRange,
    val covered: TimeRange?,
)

@Immutable
data class SleepLabState(
    val analysis: AnalysisUi,
    val hypnogram: HypnogramUi,
    val tab: SleepTab,
    /** Null outside time-slice mode. */
    val slice: SliceUi?,
    val refreshing: Boolean,
) {
    companion object {
        val Initial = SleepLabState(
            analysis = AnalysisUi.Loading,
            hypnogram = HypnogramUi.Loading,
            tab = SleepTab.AppWakelocks,
            slice = null,
            refreshing = false,
        )
    }
}

sealed interface SleepLabIntent {
    /** The screen entered the visible state (start, or tab shown): refreshes and shows the latest analysis. */
    data object Visible : SleepLabIntent

    /** The screen left the screen: in-flight work stops and nothing is recomputed until it is visible again. */
    data object Hidden : SleepLabIntent

    /** Pull to refresh or a retry button. */
    data object Refresh : SleepLabIntent
    data class SelectTab(val tab: SleepTab) : SleepLabIntent
    data object ClearSlice : SleepLabIntent
}

/** One-shot events the screen shows once (a snackbar), never state. */
sealed interface SleepLabEffect {
    data object RefreshFailed : SleepLabEffect
}

/** Rows shown per tab; a ranked list is for finding the worst offenders, not for reading a dump. */
const val MAX_ROWS = 20
