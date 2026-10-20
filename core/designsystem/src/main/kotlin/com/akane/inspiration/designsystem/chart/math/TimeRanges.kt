package com.akane.inspiration.designsystem.chart.math

import com.akane.inspiration.designsystem.chart.model.TimeRange
import java.time.Instant
import java.time.ZoneId

/** One day in milliseconds: the length the charts' date rules ([showsDates], [usesDateTicks]) measure against. */
internal const val DAY_MS = 24 * 60 * 60_000L

/** True when [tMs] lies in this closed interval, ends included. */
internal operator fun TimeRange.contains(tMs: Long): Boolean = tMs >= startMs && tMs <= endMs

/**
 * True when the two intervals overlap by more than a shared end point. A selection that does not overlap a chart's
 * window (the window slid past it) has nothing in the plot to dim, draw or adjust.
 */
internal fun TimeRange.overlaps(other: TimeRange): Boolean = startMs < other.endMs && other.startMs < endMs

/**
 * The one date rule of both charts' readouts and accessibility text: a clock time names its date when this window
 * spans a day or more, or its ends fall on different local dates in [zone]. A rule on the length alone read a
 * 22:00 to 01:00 window as "22:00 to 01:00" without saying which night.
 */
internal fun TimeRange.showsDates(zone: ZoneId): Boolean =
    durationMs >= DAY_MS || localDateOf(startMs, zone) != localDateOf(endMs, zone)

private fun localDateOf(
    tMs: Long,
    zone: ZoneId,
) = Instant.ofEpochMilli(tMs).atZone(zone).toLocalDate()
