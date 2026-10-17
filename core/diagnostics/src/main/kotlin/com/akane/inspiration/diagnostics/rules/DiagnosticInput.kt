package com.akane.inspiration.diagnostics.rules

import com.akane.inspiration.telemetry.interval.IntervalMetrics
import com.akane.inspiration.telemetry.snapshot.SnapshotDiff

/**
 * [metrics] must describe the same covered interval as [diff]. [perUidAvailable] is false for a
 * LIGHT endpoint, which carries no per-UID data.
 */
data class DiagnosticInput(
    val diff: SnapshotDiff,
    val metrics: IntervalMetrics?,
    val perUidAvailable: Boolean,
)
