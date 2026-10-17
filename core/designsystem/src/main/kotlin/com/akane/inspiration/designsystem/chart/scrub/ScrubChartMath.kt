package com.akane.inspiration.designsystem.chart.scrub

import com.akane.inspiration.designsystem.chart.model.TimePoint
import com.akane.inspiration.designsystem.chart.model.TimeRange
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Temperature at or above this is drawn in the critical hue. */
internal const val CRITICAL_TEMPERATURE_C = 40f

private const val DAY_MS = 24 * 60 * 60_000L
private const val MINUTE_MS = 60_000L
private const val TEMPERATURE_ALIGN_C = 5f

/** A connected stretch of a series that is entirely below or entirely at/above a threshold. */
internal class SeriesRun(val points: List<TimePoint>, val critical: Boolean)

/**
 * Splits time-sorted [points] into runs below / at-or-above [threshold]. Where the line crosses the
 * threshold an interpolated point is added to both neighbouring runs so the pieces join without a gap.
 */
internal fun splitAtThreshold(points: List<TimePoint>, threshold: Float): List<SeriesRun> {
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
internal class ValueAxis(val min: Float, val max: Float) {
    val mid: Float get() = (min + max) / 2f
}

/**
 * Temperature axis whose three labels (min, mid, max) are whole multiples of 5 and sit on the
 * battery gridlines, so one set of gridlines serves both value axes. Covers `minValue..maxValue`
 * with a span of at least 10.
 */
internal fun temperatureAxis(minValue: Float, maxValue: Float): ValueAxis {
    val lo = floor(minValue / TEMPERATURE_ALIGN_C) * TEMPERATURE_ALIGN_C
    val half = ceil((maxValue - lo) / 2f / TEMPERATURE_ALIGN_C) * TEMPERATURE_ALIGN_C
    return ValueAxis(lo, lo + 2f * maxOf(half, TEMPERATURE_ALIGN_C))
}

/**
 * Linear interpolation over parallel primitive arrays, so it can run every frame without boxing.
 * NaN when empty or [t] is outside the span.
 */
internal fun interpolateAt(times: LongArray, values: FloatArray, t: Long): Float {
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

internal class DurationParts(val hours: Int, val minutes: Int)

/** Whole hours and minutes, rounded to the nearest minute. */
internal fun splitDuration(durationMs: Long): DurationParts {
    val totalMinutes = (durationMs.coerceAtLeast(0L).toDouble() / MINUTE_MS).roundToInt()
    return DurationParts(totalMinutes / 60, totalMinutes % 60)
}

/** True when the window spans a day or more, so readouts need the date as well as the time. */
internal fun TimeRange.spansDays(): Boolean = durationMs >= DAY_MS

/** Axis tick labels switch from clock time to the date once the window covers several days. */
internal fun TimeRange.usesDateTicks(): Boolean = durationMs > 2 * DAY_MS

private const val CLOCK_PATTERN = "HH:mm"
private const val DATE_CLOCK_PATTERN = "d MMM HH:mm"
private const val DATE_PATTERN = "d MMM"

internal fun formatClock(tMs: Long, zone: ZoneId, withDate: Boolean, locale: Locale): String {
    val pattern = if (withDate) DATE_CLOCK_PATTERN else CLOCK_PATTERN
    return DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(Instant.ofEpochMilli(tMs))
}

internal fun formatTickLabel(tMs: Long, zone: ZoneId, window: TimeRange, locale: Locale): String =
    if (window.usesDateTicks()) {
        DateTimeFormatter.ofPattern(DATE_PATTERN, locale).withZone(zone).format(Instant.ofEpochMilli(tMs))
    } else {
        formatClock(tMs, zone, withDate = false, locale = locale)
    }
