package com.akane.inspiration.designsystem.chart.scrub

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import com.akane.inspiration.designsystem.chart.math.ScrubGestureEvent
import com.akane.inspiration.designsystem.chart.math.ScrubGestureReducer
import com.akane.inspiration.designsystem.chart.math.ScrubGestureResult
import com.akane.inspiration.designsystem.chart.math.ScrubGestureState
import com.akane.inspiration.designsystem.chart.math.SelectionCommit
import com.akane.inspiration.designsystem.chart.math.TimeScale
import com.akane.inspiration.designsystem.chart.model.TimeRange
import kotlinx.coroutines.withTimeoutOrNull

/** Horizontal room the value-axis labels take either side of the time axis. */
internal class Gutters(
    val leftPx: Float,
    val rightPx: Float,
)

/** Everything the gesture loop reads at event time. All are `rememberUpdatedState` values. */
internal class ScrubGestureInputs(
    val window: State<TimeRange>,
    val selection: State<TimeRange?>,
    val minSelectionMs: State<Long>,
    val gutters: State<Gutters>,
    val onSelectionChange: State<(TimeRange?) -> Unit>,
    val onScrub: State<(Long?) -> Unit>,
    val haptic: State<HapticFeedback>,
)

/**
 * One `pointerInput` with a single [awaitEachGesture] loop feeding [ScrubGestureReducer].
 * Long press is detected here with [withTimeoutOrNull]; the reducer decides everything else.
 * Keyed only on the chart-lifetime [state] and [inputs], so a data reload (a new window) never restarts
 * the loop and cancels a gesture in progress. The window, callbacks and selection are read through
 * [inputs]; each gesture keeps the [TimeScale] built from the window current at its Down.
 */
internal fun Modifier.scrubGestures(
    state: ScrubInteractionState,
    inputs: ScrubGestureInputs,
): Modifier =
    pointerInput(state, inputs) {
        // HandleHit is the 48 dp target's diameter; the reducer takes a radius.
        val handleHitPx = ScrubChartMetrics.HandleHit.toPx() / 2f
        val slopPx = viewConfiguration.touchSlop
        val longPressMs = viewConfiguration.longPressTimeoutMillis
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val gutters = inputs.gutters.value
            val plotWidth = (size.width - gutters.leftPx - gutters.rightPx).coerceAtLeast(1f)
            val driver =
                ScrubGestureDriver(
                    reducer = ScrubGestureReducer(inputs.minSelectionMs.value, slopPx, handleHitPx),
                    scale = TimeScale(inputs.window.value, plotWidth),
                    state = state,
                    inputs = inputs,
                )
            try {
                val originX = gutters.leftPx
                val first = driver.dispatch(ScrubGestureEvent.Down(down.position.x - originX, down.position.y))
                if (first.consume) down.consume()
                var lastX = down.position.x - originX
                var lastUptime = down.uptimeMillis
                while (true) {
                    val pressed =
                        driver.gestureState is ScrubGestureState.Pressed ||
                            driver.gestureState is ScrubGestureState.PressedOnHandle
                    val event =
                        if (pressed) {
                            val remaining = (longPressMs - (lastUptime - down.uptimeMillis)).coerceAtLeast(0L)
                            withTimeoutOrNull(remaining) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                    if (event == null) {
                        driver.dispatch(ScrubGestureEvent.LongPress(lastX))
                        continue
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    lastX = change.position.x - originX
                    lastUptime = change.uptimeMillis
                    if (change.isConsumed) {
                        driver.dispatch(ScrubGestureEvent.Cancel)
                        break
                    }
                    val reducerEvent =
                        if (change.pressed) {
                            ScrubGestureEvent.Move(lastX, change.position.y)
                        } else {
                            ScrubGestureEvent.Up(lastX)
                        }
                    val result = driver.dispatch(reducerEvent)
                    if (result.release) break
                    if (result.consume) {
                        event.changes.forEach { if (it.positionChanged() || !it.pressed) it.consume() }
                    }
                    if (!change.pressed) break
                }
            } finally {
                driver.finish()
            }
        }
    }

/** Runs the reducer for one gesture and mirrors its result into the draw-phase state and callbacks. */
private class ScrubGestureDriver(
    private val reducer: ScrubGestureReducer,
    private val scale: TimeScale,
    private val state: ScrubInteractionState,
    private val inputs: ScrubGestureInputs,
) {
    var gestureState: ScrubGestureState = ScrubGestureState.Idle
        private set

    private var reportedScrub = false
    private var committed = false

    init {
        state.gestureActive = true
    }

    fun dispatch(event: ScrubGestureEvent): ScrubGestureResult {
        val result = reducer.reduce(gestureState, event, scale, inputs.selection.value)
        val wasScrubbing = gestureState is ScrubGestureState.Scrubbing
        gestureState = result.state
        publishPreview(result)
        publishCursor(result, wasScrubbing)
        publishCommit(result.commit)
        return result
    }

    /** Clears gesture-owned state on any exit (up, cancel, release, cancellation of the coroutine). */
    fun finish() {
        state.gestureActive = false
        if (state.cursorMs.longValue != NO_TIME) state.cursorMs.longValue = NO_TIME
        if (reportedScrub) {
            reportedScrub = false
            inputs.onScrub.value(null)
        }
        // A cancelled brush leaves no ghost preview; a committed one is cleared once the caller's selection has drawn.
        if (gestureState is ScrubGestureState.Brushing || gestureState is ScrubGestureState.DraggingHandle) {
            state.clearPreview()
        }
        // A no-op brush marked the dim as full; with no selection to show, rewind it for the next fade-in.
        if (!committed && inputs.selection.value == null) state.dimProgress.floatValue = 0f
    }

    private fun publishPreview(result: ScrubGestureResult) {
        when (val next = result.state) {
            is ScrubGestureState.Brushing -> showPreview(next.anchorMs, next.currentMs)
            is ScrubGestureState.DraggingHandle -> showPreview(next.otherEdgeMs, next.currentMs)
            else -> if (result.commit !is SelectionCommit.Set) state.clearPreview()
        }
    }

    private fun showPreview(
        a: Long,
        b: Long,
    ) {
        state.setPreview(a, b)
        // The live preview is drawn at full dim, so a commit arrives without a second fade.
        state.dimProgress.floatValue = 1f
    }

    private fun publishCursor(
        result: ScrubGestureResult,
        wasScrubbing: Boolean,
    ) {
        val cursor = result.cursorMs
        if (cursor != null) {
            if (!wasScrubbing) inputs.haptic.value.performHapticFeedback(HapticFeedbackType.LongPress)
            state.cursorMs.longValue = cursor
            reportedScrub = true
            inputs.onScrub.value(cursor)
        } else {
            if (state.cursorMs.longValue != NO_TIME) state.cursorMs.longValue = NO_TIME
            if (reportedScrub) {
                reportedScrub = false
                inputs.onScrub.value(null)
            }
        }
    }

    private fun publishCommit(commit: SelectionCommit?) {
        when (commit) {
            null -> {
                Unit
            }

            is SelectionCommit.Set -> {
                committed = true
                state.commitCount.intValue += 1
                inputs.onSelectionChange.value(commit.range)
            }

            SelectionCommit.Clear -> {
                inputs.onSelectionChange.value(null)
            }
        }
    }
}
