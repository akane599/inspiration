package com.akane.inspiration.designsystem.chart.scrub

import com.akane.inspiration.designsystem.chart.model.LaneSegment
import com.akane.inspiration.designsystem.chart.model.TimePoint
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.component.format.TelemetryFormat
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/** Temperature at or above this is drawn in the critical hue. */
internal const val CRITICAL_TEMPERATURE_C = 40f

private const val TEMPERATURE_ALIGN_C = 5f

/** A connected stretch of a series that is entirely below or entirely at/above a threshold. */
internal class SeriesRun(
    val points: List<TimePoint>,
    val critical: Boolean,
)

/**
 * Splits time-sorted [points] into runs below / at-or-above [threshold]. Where the line crosses the
 * threshold an interpolated point is added to both neighbouring runs so the pieces join without a gap.
 */
internal fun splitAtThreshold(
    points: List<TimePoint>,
    threshold: Float,
): List<SeriesRun> {
    if (points.isEmpty()) return emptyList()
    val runs = ArrayList<SeriesRun>()
    var current = ArrayList<TimePoint>()
    var critical = points[0].value >= threshold
    current += points[0]
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val bCritical = b.value >= threshold
        if (bCritical == critical) {
            current += b
            continue
        }
        val fraction = ((threshold - a.value) / (b.value - a.value)).toDouble()
        val cross = TimePoint(a.tMs + ((b.tMs - a.tMs) * fraction).roundToLong(), threshold)
        current += cross
        runs += SeriesRun(current, critical)
        current = arrayListOf(cross, b)
        critical = bCritical
    }
    runs += SeriesRun(current, critical)
    return runs
}

/** Three evenly spaced labels `min`, `mid`, `max` for a value axis. */
internal class ValueAxis(
    val min: Float,
    val max: Float,
) {
    val mid: Float get() = (min + max) / 2f
}

/**
 * Temperature axis whose three labels (min, mid, max) are whole multiples of 5 and sit on the
 * battery gridlines, so one set of gridlines serves both value axes. Covers `minValue..maxValue`
 * with a span of at least 10.
 */
internal fun temperatureAxis(
    minValue: Float,
    maxValue: Float,
): ValueAxis {
    val lo = floor(minValue / TEMPERATURE_ALIGN_C) * TEMPERATURE_ALIGN_C
    val half = ceil((maxValue - lo) / 2f / TEMPERATURE_ALIGN_C) * TEMPERATURE_ALIGN_C
    return ValueAxis(lo, lo + 2f * maxOf(half, TEMPERATURE_ALIGN_C))
}

/**
 * The temperature value-axis labels (min, mid, max; empty without an [axis]), formatted like every other temperature
 * in the app: [TelemetryFormat.celsius] gives the locale's digits and the U+2212 minus sign, and keeps the number
 * with its unit.
 */
internal fun temperatureAxisLabels(
    axis: ValueAxis?,
    format: TelemetryFormat,
): List<String> = axis?.let { listOf(it.min, it.mid, it.max).map { value -> format.celsius(value.toDouble()).text } }.orEmpty()

/**
 * Linear interpolation over parallel primitive arrays, so it can run every frame without boxing.
 * NaN when empty or [t] is outside the span.
 */
internal fun interpolateAt(
    times: LongArray,
    values: FloatArray,
    t: Long,
): Float {
    val n = times.size
    if (n == 0 || t < times[0] || t > times[n - 1]) return Float.NaN
    var lo = 0
    var hi = n
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (times[mid] < t) lo = mid + 1 else hi = mid
    }
    if (times[lo] == t || lo == 0) return values[lo]
    val fraction = (t - times[lo - 1]).toDouble() / (times[lo] - times[lo - 1])
    return (values[lo - 1] + (values[lo] - values[lo - 1]) * fraction).toFloat()
}

/**
 * True when [segment] has time inside [window], so its lane bar is drawn (widened to the minimum width when brief).
 * A segment wholly before or after the window, or only touching an end, is skipped: widening it would draw a sliver
 * at the plot's edge for a period the chart does not show. The hypnogram clips its segments by the same rule.
 */
internal fun laneSegmentInWindow(
    segment: LaneSegment,
    window: TimeRange,
): Boolean = segment.endMs > window.startMs && segment.startMs < window.endMs
