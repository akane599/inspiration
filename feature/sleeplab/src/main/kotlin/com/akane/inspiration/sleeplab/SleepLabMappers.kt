package com.akane.inspiration.sleeplab

import com.akane.inspiration.designsystem.chart.model.HypnogramData
import com.akane.inspiration.designsystem.chart.model.HypnogramStage
import com.akane.inspiration.designsystem.chart.model.StageSegment
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.analysis.IntervalAnalysis
import com.akane.inspiration.diagnostics.analysis.SegmentKind
import com.akane.inspiration.diagnostics.analysis.Timeline
import com.akane.inspiration.diagnostics.analysis.forSelection
import com.akane.inspiration.diagnostics.model.AwakeViolation
import com.akane.inspiration.diagnostics.model.RuleId
import com.akane.inspiration.diagnostics.rules.NO_AWAKE_TIME
import com.akane.inspiration.diagnostics.rules.NO_SCREEN_OFF_FIGURES
import com.akane.inspiration.diagnostics.rules.NO_SCREEN_OFF_TIME
import com.akane.inspiration.diagnostics.story.reportableDeepSleepRatio
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotDiff
import kotlinx.collections.immutable.toImmutableList

/** Frequencies listed per cluster; the rest is summed into "other". */
internal const val MAX_FREQ_ROWS = 5

// --- Timeline -> HypnogramData ---

/**
 * Maps a [Timeline] to the hypnogram. The hypnogram is an x-ray of screen-off time, so every stage segment is
 * clipped to the spans where the screen was off (the complement of the SCREEN_ON lane inside the timeline's
 * window); SCREEN_ON and CHARGING have no stage of their own. Light doze, deep doze, maintenance windows and
 * "awake" map one to one. Wake pulses (H6: screen-ons and deep-doze exits that were not re-entered) are kept
 * when they fall in a screen-off span, boundaries included, since a screen-on ends the span it wakes. They are
 * drawn and labelled as "wake-ups" and no cause is implied.
 *
 * The window is the extent of the screen-off stage data. A period with no such data (no screen-off span, or a
 * screen-off span with no doze history) comes back with no segments and no pulses, which the screen shows as
 * its empty state.
 */
internal fun hypnogramData(timeline: Timeline): HypnogramData {
    val screenOff = screenOffSpans(timeline)
    val segments = ArrayList<StageSegment>()
    for (segment in timeline.segments) {
        val stage = segment.kind.stage() ?: continue
        for (span in screenOff) {
            val start = maxOf(segment.startMs, span.startMs)
            val end = minOf(segment.endMs, span.endMs)
            if (end > start) segments += StageSegment(start, end, stage)
        }
    }
    if (segments.isEmpty()) return HypnogramData.of(TimeRange.of(timeline.from, timeline.to), emptyList(), emptyList())

    val window = TimeRange.of(segments.minOf { it.startMs }, segments.maxOf { it.endMs })
    val pulses =
        timeline.wakePulsesMs.filter { t ->
            t >= window.startMs && t <= window.endMs && screenOff.any { t >= it.startMs && t <= it.endMs }
        }
    return HypnogramData.of(window, segments, pulses)
}

/** The chart draws a gap as hatching from about two pixels up; on a phone-width plot that is this share of the window. */
private const val MIN_VISIBLE_GAP_DIVISOR = 200L

/**
 * Whether the hypnogram will draw any hatched "no data" band: time inside its window that no stage segment covers,
 * at least [MIN_VISIBLE_GAP_DIVISOR]th of the window long (shorter gaps are not drawn). The caption that explains
 * hatching is only worth showing when this is true.
 */
internal fun hasHatchedGaps(data: HypnogramData): Boolean {
    val window = data.window
    if (window.durationMs <= 0L) return false
    val minGap = maxOf(window.durationMs / MIN_VISIBLE_GAP_DIVISOR, 1L)
    var cursor = window.startMs
    for (segment in data.segments) {
        val start = maxOf(segment.startMs, window.startMs)
        val end = minOf(segment.endMs, window.endMs)
        if (end <= start) continue
        if (start - cursor >= minGap) return true
        cursor = maxOf(cursor, end)
    }
    return window.endMs - cursor >= minGap
}

/** The stretches of [timeline]'s window with no SCREEN_ON segment, in order. */
private fun screenOffSpans(timeline: Timeline): List<TimeRange> {
    val spans = ArrayList<TimeRange>()
    var cursor = timeline.from
    for (segment in timeline.segments.filter { it.kind == SegmentKind.SCREEN_ON }.sortedBy { it.startMs }) {
        if (segment.startMs > cursor) spans += TimeRange.of(cursor, minOf(segment.startMs, timeline.to))
        cursor = maxOf(cursor, segment.endMs)
    }
    if (cursor < timeline.to) spans += TimeRange.of(cursor, timeline.to)
    return spans
}

/** "Deep idle" is Doze, never the CPU-suspend "deep sleep" metric. */
private fun SegmentKind.stage(): HypnogramStage? =
    when (this) {
        SegmentKind.DEEP_IDLE -> HypnogramStage.DEEP_IDLE
        SegmentKind.LIGHT_IDLE -> HypnogramStage.LIGHT_IDLE
        SegmentKind.MAINTENANCE -> HypnogramStage.MAINTENANCE
        SegmentKind.AWAKE -> HypnogramStage.AWAKE
        SegmentKind.SCREEN_ON, SegmentKind.CHARGING -> null
    }

// --- Time slice ---

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

// --- Tab lists ---

private fun <T> ranked(
    items: List<T>,
    comparator: Comparator<T>,
    excluded: Int = 0,
): ListUi.Rows<T> {
    val sorted = items.sortedWith(comparator)
    return ListUi.Rows(
        sorted.take(MAX_ROWS).toImmutableList(),
        hidden = (sorted.size - MAX_ROWS).coerceAtLeast(0),
        excluded = excluded,
    )
}

/** The UIDs the per-app tabs keep: a valid delta whose package did not change, as the rule engine checks them. */
private fun SnapshotDiff.checkedUids() = uids.filter { it.valid && !it.packageChanged }

/**
 * The UIDs the per-app tabs leave out, counted as the rule engine counts `DiagnosticReport.skippedInvalidUids`, so
 * the tabs, the culprit card and X-Ray state one number for the period.
 */
private fun SnapshotDiff.excludedUids() = uids.count { !it.valid || it.packageChanged }

private fun SnapshotDiff.perUidState(perUidAvailable: Boolean): ListUi<Nothing>? =
    when {
        !perUidAvailable -> ListUi.NeedsFullSnapshots
        !validity.batteryStats -> ListUi.Unavailable(batteryStatsCause())
        else -> null
    }

/**
 * Why the battery-stats dump counters don't compare across the period (audit B-04). The differ also rejects a
 * session it can't name, so only when both ends name their session does "battery stats" mean a reset; otherwise one
 * end never read it (a light snapshot, a failed usage read) or missed the dump.
 */
private fun SnapshotDiff.batteryStatsCause(): UnavailableCause =
    if (statsSessionChanged()) UnavailableCause.StatsReset else UnavailableCause.NotCaptured

private fun SnapshotDiff.statsSessionChanged() = !validity.batteryStats && ref.namesStatsSession() && now.namesStatsSession()

private fun SnapshotClock.namesStatsSession() = statsStartMs != null && statsDurationMs != null

/**
 * Partial wakelocks per app, longest first. A uid the period could not check (owner unproven, counters reset under
 * it, package changed) is left out rather than shown as a wrong number, and counted in [ListUi.Rows.excluded].
 * [labels] maps package names to app labels; a package missing from it is shown by its package name. [sharedBy] maps
 * a UID no owner names to how many apps share it (`UidIdentity.sharedBy`), so its row says so.
 */
internal fun appWakelockRows(
    diff: SnapshotDiff,
    perUidAvailable: Boolean,
    labels: Map<String, String>,
    sharedBy: Map<Int, Int> = emptyMap(),
): ListUi<AppWakelockRow> {
    diff.perUidState(perUidAvailable)?.let { return it }
    if (!diff.validity.dumpCounters) return ListUi.Unavailable(diff.batteryStatsCause())
    val rows =
        diff.checkedUids().flatMap { uid ->
            uid.partialWakelocks.filter { it.durationMs > 0 || it.count > 0 }.map {
                AppWakelockRow(
                    uid = uid.uid,
                    packageName = uid.packageName,
                    appLabel = uid.packageName?.let { pkg -> labels[pkg] ?: pkg },
                    name = it.name,
                    durationMs = it.durationMs,
                    count = it.count,
                    sharedBy = sharedBy[uid.uid] ?: 0,
                )
            }
        }
    return ranked(
        rows,
        compareByDescending<AppWakelockRow> { it.durationMs }
            .thenByDescending { it.count }
            .thenBy { it.name }
            .thenBy { it.uid },
        excluded = diff.excludedUids(),
    )
}

/**
 * Kernel wakelocks as batterystats reports them, longest first. Unavailable when they cannot be compared across the
 * interval: after a restart, or when the battery-stats dump that carries them doesn't compare (a reset, or an end
 * without it). Names whose delta went negative are dropped. `/sys/class/wakeup` is not read.
 */
internal fun kernelWakelockRows(diff: SnapshotDiff): ListUi<KernelWakelockRow> {
    if (!diff.validity.kernelWakelocks) {
        return ListUi.Unavailable(if (diff.validity.kernelCounters) diff.batteryStatsCause() else UnavailableCause.DeviceRestarted)
    }
    val rows =
        diff.kernelWakelocks
            .filter { it.name !in diff.validity.invalidKernelWakelocks && (it.durationMs > 0 || it.count > 0) }
            .map { KernelWakelockRow(it.name, it.durationMs, it.count) }
    return ranked(rows, compareByDescending<KernelWakelockRow> { it.durationMs }.thenByDescending { it.count }.thenBy { it.name })
}

/**
 * Wake-up alarms per app, most wake-ups first, each with its busiest tags. The counts come from batterystats;
 * only when a uid has none is the alarm dump's own counter used instead. The two overlap, so they are never added.
 * Uids the period could not check are left out and counted in [ListUi.Rows.excluded], and owners are named, as in
 * [appWakelockRows].
 */
internal fun alarmRows(
    diff: SnapshotDiff,
    perUidAvailable: Boolean,
    labels: Map<String, String>,
    sharedBy: Map<Int, Int> = emptyMap(),
): ListUi<AlarmRow> {
    diff.perUidState(perUidAvailable)?.let { return it }
    // Battery stats compare here, so batterystats' own alarm counts were not captured at one end.
    if (!diff.validity.dumpCounters && !diff.validity.alarmDumpCounters) return ListUi.Unavailable(diff.batteryStatsCause())
    val rows =
        diff.checkedUids().mapNotNull { uid ->
            val tags = uid.wakeupAlarms.positive().ifEmpty { uid.alarmDumpWakeups.positive() }
            if (tags.isEmpty()) {
                null
            } else {
                AlarmRow(
                    uid = uid.uid,
                    packageName = uid.packageName,
                    appLabel = uid.packageName?.let { pkg -> labels[pkg] ?: pkg },
                    wakeups = tags.sumOf { it.count },
                    tags = tags.map { AlarmTagRow(it.tag, it.count) }.toImmutableList(),
                    sharedBy = sharedBy[uid.uid] ?: 0,
                )
            }
        }
    return ranked(
        rows,
        compareByDescending<AlarmRow> { it.wakeups }.thenBy { it.packageName.orEmpty() }.thenBy { it.uid },
        excluded = diff.excludedUids(),
    )
}

private fun List<UidAlarm>.positive(): List<UidAlarm> =
    filter { it.count > 0 }.sortedWith(compareByDescending<UidAlarm> { it.count }.thenBy { it.tag })

/** The packages of the rows the per-app tabs and the culprit card will show, so only those labels are looked up. */
internal fun visiblePackages(analysis: IntervalAnalysis): List<String> {
    val diff = analysis.diff
    val perUid = analysis.resolved.perUidAvailable
    val apps = appWakelockRows(diff, perUid, emptyMap()).rowsOrEmpty().mapNotNull { it.packageName }
    val alarms = alarmRows(diff, perUid, emptyMap()).rowsOrEmpty().mapNotNull { it.packageName }
    val culprits = (awakeCheckUi(analysis, emptyMap()) as? AwakeCheckUi.Violation)?.culprits.orEmpty().mapNotNull { it.packageName }
    return (apps + alarms + culprits).distinct()
}

internal fun <T> ListUi<T>.rowsOrEmpty(): List<T> = if (this is ListUi.Rows<T>) rows else emptyList()

// --- CPU residency ---

/**
 * Time-in-state per CPU policy (cluster). Only valid when the kernel counters compare across the interval and both
 * ends read them (audit B-04): after a restart [CpuUi.Unavailable]; with a light snapshot at either end, which never
 * reads them ([fullAtBothEnds] false, as `ResolvedReference.perUidAvailable` says), [CpuUi.NotCaptured]; otherwise
 * the kernel reported none, [CpuUi.NoData]. Each cluster lists its [MAX_FREQ_ROWS] busiest frequencies in ascending
 * order and sums the rest into [ClusterUi.otherShare]. Two or three clusters get a Little/Big or Little/Mid/Prime
 * role by policy order, which is how SoCs number them; any other count stays generic.
 */
internal fun cpuUi(
    diff: SnapshotDiff,
    fullAtBothEnds: Boolean,
): CpuUi {
    if (!diff.validity.cpuFreq) {
        return when {
            !diff.validity.kernelCounters -> CpuUi.Unavailable
            !fullAtBothEnds -> CpuUi.NotCaptured
            else -> CpuUi.NoData
        }
    }
    val byPolicy =
        diff.cpuFreqDelta
            .filter { it.timeMs > 0 }
            .groupBy { it.policy }
            .toSortedMap()
    if (byPolicy.isEmpty()) return CpuUi.NoData
    val roles = clusterRoles(byPolicy.size)
    val clusters =
        byPolicy.entries.mapIndexed { index, (policy, deltas) ->
            val byFreq = deltas.groupBy { it.freqKhz }.mapValues { (_, same) -> same.sumOf { it.timeMs } }
            val total = byFreq.values.sum()
            val shown =
                byFreq.entries
                    .sortedByDescending { it.value }
                    .take(MAX_FREQ_ROWS)
                    .sortedBy { it.key }
            ClusterUi(
                policy = policy,
                role = roles[index],
                totalMs = total,
                rows = shown.map { FreqRow(it.key, it.value, it.value.toFloat() / total) }.toImmutableList(),
                otherShare = (total - shown.sumOf { it.value }).toFloat() / total,
            )
        }
    return CpuUi.Ready(clusters.toImmutableList())
}

private fun clusterRoles(count: Int): List<ClusterRole> =
    when (count) {
        2 -> listOf(ClusterRole.Little, ClusterRole.Big)
        3 -> listOf(ClusterRole.Little, ClusterRole.Mid, ClusterRole.Prime)
        else -> List(count) { ClusterRole.Generic }
    }

// --- Culprit card ---

/**
 * The screen-off awake check (Rule 2) as the culprit card shows it. A rule that could not run is
 * [AwakeCheckUi.NotEvaluable] with its reason, never [AwakeCheckUi.WithinLimit]. Each culprit's owner comes from the
 * (already package-resolved) [IntervalAnalysis.diff] by UID, and [labels] maps its package to an app label. A
 * violation needs screen-off figures, which only a full snapshot at both ends carries, so a period without per-UID
 * data (H14) is [AwakeCheckUi.NotEvaluable] before any culprit is read.
 */
internal fun awakeCheckUi(
    analysis: IntervalAnalysis,
    labels: Map<String, String>,
    sharedBy: Map<Int, Int> = emptyMap(),
): AwakeCheckUi {
    val report = analysis.report
    report.notEvaluable[RuleId.AWAKE_VIOLATION]?.let { return AwakeCheckUi.NotEvaluable(awakeSkipReason(it, analysis.diff)) }
    val violation = report.insights.firstNotNullOfOrNull { it as? AwakeViolation } ?: return AwakeCheckUi.WithinLimit
    val packages = analysis.diff.uids.associate { it.uid to it.packageName }
    val culprits =
        violation.culprits
            .map { culprit ->
                val pkg = culprit.uid?.let { packages[it] }
                CulpritRow(
                    kind = culprit.kind,
                    name = culprit.name,
                    uid = culprit.uid,
                    packageName = pkg,
                    appLabel = pkg?.let { labels[it] ?: it },
                    durationMs = culprit.durationMs,
                    count = culprit.count,
                    sharedBy = culprit.uid?.let { sharedBy[it] } ?: 0,
                )
            }
    return AwakeCheckUi.Violation(
        severity = violation.severity,
        awakeRatio = violation.awakeRatio,
        screenOffMs = violation.screenOffMs,
        scope = violation.culpritScope,
        culprits = culprits.toImmutableList(),
        skippedApps = report.skippedInvalidUids,
    )
}

/** Missing screen-off figures come from the battery-stats dump, so they share its cause (audit B-04). */
private fun awakeSkipReason(
    reason: String,
    diff: SnapshotDiff,
): AwakeSkipReason =
    when (reason) {
        NO_SCREEN_OFF_TIME -> AwakeSkipReason.ShortScreenOff
        NO_SCREEN_OFF_FIGURES -> if (diff.statsSessionChanged()) AwakeSkipReason.StatsReset else AwakeSkipReason.NotCaptured
        NO_AWAKE_TIME -> AwakeSkipReason.NoAwakeTime
        else -> AwakeSkipReason.Other
    }

// --- Whole analysis ---

/**
 * The CPU-suspend share of screen-off time; both fields stay null when the interval cannot measure it. Accumulated
 * BatteryStats timers can remain comparable across boots in the same stats epoch, unlike raw kernel clocks and
 * CPU-frequency residency. SnapshotDiffer gates the raw-clock fallback itself. Under 15 minutes of screen-off the
 * share is noise and is withheld, as in the Battery Story and the Cockpit.
 */
internal fun deepSleepUi(diff: SnapshotDiff): DeepSleepUi {
    val sleep = diff.sleep
    val ratio = sleep?.reportableDeepSleepRatio()
    return DeepSleepUi(ratio, sleep?.deepSleepMs?.takeIf { ratio != null })
}

/**
 * Everything the culprit card, the three tabs and the CPU card show for one analysed interval. [labels] and [sharedBy]
 * name the owners, as [appWakelockRows] says.
 */
internal fun sleepLabAnalysis(
    analysis: IntervalAnalysis,
    labels: Map<String, String>,
    sharedBy: Map<Int, Int> = emptyMap(),
): AnalysisUi.Ready {
    val diff = analysis.diff
    val perUid = analysis.resolved.perUidAvailable
    return AnalysisUi.Ready(
        appWakelocks = appWakelockRows(diff, perUid, labels, sharedBy),
        kernelWakelocks = kernelWakelockRows(diff),
        alarms = alarmRows(diff, perUid, labels, sharedBy),
        cpu = cpuUi(diff, fullAtBothEnds = perUid),
        deepSleep = deepSleepUi(diff),
        awake = awakeCheckUi(analysis, labels, sharedBy),
    )
}
