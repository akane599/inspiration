package com.akane.inspiration.designsystem.chart.math

import com.akane.inspiration.designsystem.chart.model.TimePoint
import com.akane.inspiration.designsystem.chart.model.TimeRange

/**
 * Reduces time-sorted [points] to the per-column min and max (kept in time order) over [buckets] equal
 * x-columns of [scale], always keeping the first and last point. Output size is at most `2 * buckets + 2`.
 * Points outside the window fall into the first or last column.
 */
fun decimateMinMax(points: List<TimePoint>, scale: TimeScale, buckets: Int): List<TimePoint> {
    if (buckets < 1 || scale.widthPx <= 0f || points.size <= 2 * buckets + 2) return points
    val out = ArrayList<TimePoint>(2 * buckets + 2)
    var lastEmitted = 0
    out += points[0]
    var bucket = -1
    var minIdx = 0
    var maxIdx = 0
    fun flush() {
        val lo = minOf(minIdx, maxIdx)
        val hi = maxOf(minIdx, maxIdx)
        if (lo > lastEmitted) { out += points[lo]; lastEmitted = lo }
        if (hi > lastEmitted) { out += points[hi]; lastEmitted = hi }
    }
    for (i in points.indices) {
        val column = (scale.toX(points[i].tMs) / scale.widthPx * buckets).toInt().coerceIn(0, buckets - 1)
        if (column != bucket) {
            if (bucket >= 0) flush()
            bucket = column
            minIdx = i
            maxIdx = i
        } else {
            if (points[i].value < points[minIdx].value) minIdx = i
            if (points[i].value > points[maxIdx].value) maxIdx = i
        }
    }
    flush()
    val lastIdx = points.lastIndex
    if (lastIdx > lastEmitted) out += points[lastIdx]
    return out
}

/** Index of the point closest in time to [tMs] in time-sorted [points] (earlier on ties), -1 when empty. */
fun nearestIndex(points: List<TimePoint>, tMs: Long): Int {
    if (points.isEmpty()) return -1
    val after = lowerBound(points, tMs)
    if (after == 0) return 0
    if (after == points.size) return points.lastIndex
    val before = after - 1
    return if (tMs - points[before].tMs <= points[after].tMs - tMs) before else after
}

/** Linear interpolation of time-sorted [points] at [tMs]; null when empty or outside the data's time span. */
fun valueAt(points: List<TimePoint>, tMs: Long): Float? {
    if (points.isEmpty() || tMs < points.first().tMs || tMs > points.last().tMs) return null
    val after = lowerBound(points, tMs)
    val hi = points[after]
    if (hi.tMs == tMs || after == 0) return hi.value
    val lo = points[after - 1]
    val fraction = (tMs - lo.tMs).toDouble() / (hi.tMs - lo.tMs)
    return (lo.value + (hi.value - lo.value) * fraction).toFloat()
}

/** First index whose time is >= [tMs], or `points.size`. */
private fun lowerBound(points: List<TimePoint>, tMs: Long): Int {
    var lo = 0
    var hi = points.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (points[mid].tMs < tMs) lo = mid + 1 else hi = mid
    }
    return lo
}

/**
 * Orders the ends, clamps to [window], and widens a span shorter than [minDurationMs] up to it
 * (keeping inside the window). Null when [window] itself is shorter than the minimum.
 */
fun normalizeSelection(aMs: Long, bMs: Long, window: TimeRange, minDurationMs: Long): TimeRange? {
    if (window.durationMs < minDurationMs) return null
    val start = minOf(aMs, bMs).coerceIn(window.startMs, window.endMs)
    val end = maxOf(aMs, bMs).coerceIn(window.startMs, window.endMs)
    if (end - start >= minDurationMs) return TimeRange.of(start, end)
    val widenedEnd = minOf(start + minDurationMs, window.endMs)
    return TimeRange.of(widenedEnd - minDurationMs, widenedEnd)
}
