package com.akane.inspiration.designsystem.chart.scrub

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.unit.dp

/** Sentinel for "no time" in the primitive Long states, so the draw path never boxes a nullable Long. */
internal const val NO_TIME = Long.MIN_VALUE

/** Geometry the spec fixes in dp. Spacing between elements comes from `MaterialTheme.spacing`. */
internal object ScrubChartMetrics {
    val PlotHeight = 160.dp
    val LaneHeight = 12.dp
    val LaneCornerRadius = 4.dp
    val LaneMinWidth = 2.dp
    val LineWidth = 2.dp
    val HairlineWidth = 1.dp
    val EdgeWidth = 2.dp
    val HandleRadius = 6.dp
    val MarkerRadius = 4.dp
    val MarkerRing = 2.dp
    val CursorWidth = 1.5.dp
    val TickLength = 4.dp
    val HandleHit = 48.dp
    val GestureExclusionMax = 200.dp
    const val DIM_FADE_MS = 150
}

/**
 * The draw-phase state of the chart. Every field is a primitive snapshot state that is read **only
 * inside draw lambdas** (and by the isolated readout child through a lambda), so a drag frame
 * redraws without recomposing or re-laying-out the chart, its plot or its parent.
 *
 * - [cursorMs]: scrub cursor while a local long-press scrub is active, else [NO_TIME].
 * - [previewStartMs] / [previewEndMs]: ordered live brush or handle-drag range, else [NO_TIME].
 * - [dimProgress]: 0..1 fade of the committed selection's scrim.
 * - [commitCount]: bumped when a selection is committed; the preview is held until the caller's new
 *   selection has drawn, then cleared (so there is no one-frame gap between preview and committed range).
 */
@Stable
internal class ScrubInteractionState(initialDim: Float) {
    val cursorMs = mutableLongStateOf(NO_TIME)
    val previewStartMs = mutableLongStateOf(NO_TIME)
    val previewEndMs = mutableLongStateOf(NO_TIME)
    val dimProgress = mutableFloatStateOf(initialDim)
    val commitCount = mutableIntStateOf(0)

    /** Plain flag, not state: only gesture code and the preview-clearing effect touch it. */
    var gestureActive = false

    fun setPreview(a: Long, b: Long) {
        previewStartMs.longValue = minOf(a, b)
        previewEndMs.longValue = maxOf(a, b)
    }

    fun clearPreview() {
        previewStartMs.longValue = NO_TIME
        previewEndMs.longValue = NO_TIME
    }
}
