package com.akane.inspiration.health

import com.akane.inspiration.designsystem.component.format.TelemetryFormat
import java.time.ZoneId

private const val DAY_MS = 24L * 3_600_000

/** A wear-chart axis label: a short date, with the time as well when the whole chart spans under two days. */
internal fun formatStamp(ms: Long, spanMs: Long, format: TelemetryFormat, zone: ZoneId): String =
    if (spanMs < 2 * DAY_MS) format.dateTime(ms, zone) else format.date(ms, zone)

/** The smallest value span each charge-curve metric is drawn over, so sensor noise is not blown up to full height. */
internal fun minAxisSpan(metric: CurveMetric): Float = when (metric) {
    CurveMetric.Watts -> 2f
    CurveMetric.Current -> 200f
    CurveMetric.Voltage -> 100f
    CurveMetric.Temperature -> 6f
}

/** Fraction digits of a curve axis label. */
internal fun axisDecimals(metric: CurveMetric): Int = if (metric == CurveMetric.Watts) 1 else 0
