package com.akane.inspiration.designsystem.chart.hypnogram

import com.akane.inspiration.designsystem.chart.model.HypnogramData
import com.akane.inspiration.designsystem.chart.model.HypnogramStage
import com.akane.inspiration.designsystem.chart.model.StageSegment
import com.akane.inspiration.designsystem.chart.model.TimeRange

private const val MINUTE_MS = 60_000L

/**
 * Clips every segment accepted by [include] to [window] and merges overlapping or touching pieces.
 * The result is sorted, non-overlapping and never empty-length. [StageSegment]s arrive sorted by
 * start from the factory, which is all the sweep needs.
 */
private fun unionWithin(window: TimeRange, segments: List<StageSegment>, include: (StageSegment) -> Boolean): List<TimeRange> {
    val out = ArrayList<TimeRange>()
    var curStart = 0L
    var curEnd = 0L
    var open = false
    for (segment in segments) {
        if (!include(segment)) continue
        val start = maxOf(segment.startMs, window.startMs)
        val end = minOf(segment.endMs, window.endMs)
        if (end <= start) continue
        if (open && start <= curEnd) {
            if (end > curEnd) curEnd = end
        } else {
            if (open) out += TimeRange.of(curStart, curEnd)
            curStart = start
            curEnd = end
            open = true
        }
    }
    if (open) out += TimeRange.of(curStart, curEnd)
    return out
}

/**
 * Time inside the window covered by no segment of any stage, as sorted disjoint ranges. These
 * render as "no data" and are never interpolated across.
 */
internal fun hypnogramGaps(data: HypnogramData): List<TimeRange> {
    val window = data.window
    if (window.durationMs <= 0L) return emptyList()
    val covered = unionWithin(window, data.segments) { true }
    val gaps = ArrayList<TimeRange>()
    var cursor = window.startMs
    for (range in covered) {
        if (range.startMs > cursor) gaps += TimeRange.of(cursor, range.startMs)
        cursor = range.endMs
    }
    if (cursor < window.endMs) gaps += TimeRange.of(cursor, window.endMs)
    return gaps
}

/** Milliseconds spent in each stage inside the window, indexed by [HypnogramStage.ordinal]. Overlaps within a stage count once. */
internal fun stageDurationsMs(data: HypnogramData): LongArray {
    val totals = LongArray(HypnogramStage.entries.size)
    if (data.window.durationMs <= 0L) return totals
    for (stage in HypnogramStage.entries) {
        totals[stage.ordinal] = unionWithin(data.window, data.segments) { it.stage == stage }.sumOf { it.durationMs }
    }
    return totals
}

/** Whole hours and remaining minutes of [durationMs], rounded to the nearest minute. */
internal fun hoursAndMinutes(durationMs: Long): Pair<Int, Int> {
    val totalMinutes = ((durationMs.coerceAtLeast(0L) + MINUTE_MS / 2) / MINUTE_MS).toInt()
    return totalMinutes / 60 to totalMinutes % 60
}
