package com.akane.inspiration.designsystem.chart.math

import com.akane.inspiration.designsystem.chart.model.TimeRange

/** True when [tMs] lies in this closed interval, ends included. */
internal operator fun TimeRange.contains(tMs: Long): Boolean = tMs >= startMs && tMs <= endMs

/**
 * True when the two intervals overlap by more than a shared end point. A selection that does not overlap a chart's
 * window (the window slid past it) has nothing in the plot to dim, draw or adjust.
 */
internal fun TimeRange.overlaps(other: TimeRange): Boolean = startMs < other.endMs && other.startMs < endMs
