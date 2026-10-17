package com.akane.inspiration.health

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private const val DAY_MS = 24L * 3_600_000

/** A wear-chart axis label: a short date, with the time as well when the whole chart spans under two days. */
internal fun formatStamp(ms: Long, spanMs: Long, locale: Locale, zone: ZoneId): String {
    val formatter = if (spanMs < 2 * DAY_MS) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    } else {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    }
    return formatter.withLocale(locale).withZone(zone).format(Instant.ofEpochMilli(ms))
}

/** The smallest value span each charge-curve metric is drawn over, so sensor noise is not blown up to full height. */
internal fun minAxisSpan(metric: CurveMetric): Float = when (metric) {
    CurveMetric.Watts -> 2f
    CurveMetric.Current -> 200f
    CurveMetric.Voltage -> 100f
    CurveMetric.Temperature -> 6f
}

/** Fraction digits of a curve axis label. */
internal fun axisDecimals(metric: CurveMetric): Int = if (metric == CurveMetric.Watts) 1 else 0
