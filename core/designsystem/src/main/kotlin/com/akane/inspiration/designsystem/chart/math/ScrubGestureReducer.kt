package com.akane.inspiration.designsystem.chart.math

import com.akane.inspiration.designsystem.chart.model.TimeRange
import kotlin.math.abs

/** Which edge of the committed selection a handle drag moves. */
enum class HandleEdge { START, END }

sealed interface ScrubGestureState {
    data object Idle : ScrubGestureState

    /** Pointer is down, slop not yet crossed: tap, brush, scroll hand-off and long press are all still possible. */
    data class Pressed(val downX: Float, val downY: Float) : ScrubGestureState

    data class Scrubbing(val tMs: Long) : ScrubGestureState

    data class Brushing(val anchorMs: Long, val currentMs: Long) : ScrubGestureState

    data class DraggingHandle(val edge: HandleEdge, val otherEdgeMs: Long, val currentMs: Long) : ScrubGestureState
}

/** Pointer events; x and y are in px within the plot. */
sealed interface ScrubGestureEvent {
    data class Down(val x: Float, val y: Float) : ScrubGestureEvent
    data class LongPress(val x: Float) : ScrubGestureEvent
    data class Move(val x: Float, val y: Float) : ScrubGestureEvent
    data class Up(val x: Float) : ScrubGestureEvent
    data object Cancel : ScrubGestureEvent
}

sealed interface SelectionCommit {
    data class Set(val range: TimeRange) : SelectionCommit
    data object Clear : SelectionCommit
}

/**
 * @property commit selection change to report, if any.
 * @property cursorMs scrub cursor to show, null when not scrubbing (so null after Up clears it).
 * @property consume the chart should consume the pointer change (the parent must not scroll).
 * @property release the chart should stop tracking and leave the gesture to the parent.
 */
data class ScrubGestureResult(
    val state: ScrubGestureState,
    val commit: SelectionCommit? = null,
    val cursorMs: Long? = null,
    val consume: Boolean = false,
    val release: Boolean = false,
)

/**
 * Pure gesture state machine for the scrubbing chart: no Compose types, so every transition is unit-tested.
 *
 * - Down within [handleHitPx] of a selection edge starts a handle drag; otherwise it is Pressed.
 * - From Pressed, crossing [slopPx] on either axis decides by the dominant axis: horizontal starts a brush,
 *   vertical releases to the parent. LongPress starts scrubbing.
 * - Up after a brush commits the range, unless it is shorter than [minSelectionMs] (a no-op, not a clear).
 *   Up in Pressed is a tap and clears an existing selection.
 */
class ScrubGestureReducer(val minSelectionMs: Long, val slopPx: Float, val handleHitPx: Float) {

    fun reduce(
        state: ScrubGestureState,
        event: ScrubGestureEvent,
        scale: TimeScale,
        selection: TimeRange?,
    ): ScrubGestureResult = when (event) {
        ScrubGestureEvent.Cancel -> ScrubGestureResult(ScrubGestureState.Idle)
        is ScrubGestureEvent.Down -> down(event, scale, selection)
        is ScrubGestureEvent.LongPress -> longPress(state, event, scale)
        is ScrubGestureEvent.Move -> move(state, event, scale)
        is ScrubGestureEvent.Up -> up(state, scale, selection)
    }

    private fun down(event: ScrubGestureEvent.Down, scale: TimeScale, selection: TimeRange?): ScrubGestureResult {
        val handle = selection?.let { nearestHandle(it, event.x, scale) }
        return if (handle != null) {
            ScrubGestureResult(handle, consume = true)
        } else {
            ScrubGestureResult(ScrubGestureState.Pressed(event.x, event.y))
        }
    }

    private fun nearestHandle(selection: TimeRange, x: Float, scale: TimeScale): ScrubGestureState.DraggingHandle? {
        val startDistance = abs(x - scale.toX(selection.startMs))
        val endDistance = abs(x - scale.toX(selection.endMs))
        if (minOf(startDistance, endDistance) > handleHitPx) return null
        return if (startDistance <= endDistance) {
            ScrubGestureState.DraggingHandle(HandleEdge.START, selection.endMs, selection.startMs)
        } else {
            ScrubGestureState.DraggingHandle(HandleEdge.END, selection.startMs, selection.endMs)
        }
    }

    private fun longPress(state: ScrubGestureState, event: ScrubGestureEvent.LongPress, scale: TimeScale): ScrubGestureResult {
        if (state != ScrubGestureState.Idle && state !is ScrubGestureState.Pressed) return ScrubGestureResult(state)
        val t = timeAt(event.x, scale)
        return ScrubGestureResult(ScrubGestureState.Scrubbing(t), cursorMs = t, consume = true)
    }

    private fun move(state: ScrubGestureState, event: ScrubGestureEvent.Move, scale: TimeScale): ScrubGestureResult =
        when (state) {
            is ScrubGestureState.Pressed -> movePressed(state, event, scale)
            is ScrubGestureState.Scrubbing -> {
                val t = timeAt(event.x, scale)
                ScrubGestureResult(ScrubGestureState.Scrubbing(t), cursorMs = t, consume = true)
            }
            is ScrubGestureState.Brushing ->
                ScrubGestureResult(state.copy(currentMs = timeAt(event.x, scale)), consume = true)
            is ScrubGestureState.DraggingHandle ->
                ScrubGestureResult(state.copy(currentMs = timeAt(event.x, scale)), consume = true)
            ScrubGestureState.Idle -> ScrubGestureResult(state)
        }

    private fun movePressed(
        state: ScrubGestureState.Pressed,
        event: ScrubGestureEvent.Move,
        scale: TimeScale,
    ): ScrubGestureResult {
        val dx = abs(event.x - state.downX)
        val dy = abs(event.y - state.downY)
        return when {
            dx <= slopPx && dy <= slopPx -> ScrubGestureResult(state)
            dy > dx -> ScrubGestureResult(ScrubGestureState.Idle, release = true)
            else -> ScrubGestureResult(
                ScrubGestureState.Brushing(timeAt(state.downX, scale), timeAt(event.x, scale)),
                consume = true,
            )
        }
    }

    private fun up(state: ScrubGestureState, scale: TimeScale, selection: TimeRange?): ScrubGestureResult =
        when (state) {
            ScrubGestureState.Idle -> ScrubGestureResult(state)
            is ScrubGestureState.Pressed -> ScrubGestureResult(
                ScrubGestureState.Idle,
                commit = if (selection != null) SelectionCommit.Clear else null,
            )
            is ScrubGestureState.Scrubbing -> ScrubGestureResult(ScrubGestureState.Idle, consume = true)
            is ScrubGestureState.Brushing -> {
                val long = abs(state.currentMs - state.anchorMs) >= minSelectionMs
                val range = if (long) normalizeSelection(state.anchorMs, state.currentMs, scale.window, minSelectionMs) else null
                ScrubGestureResult(ScrubGestureState.Idle, commit = range?.let { SelectionCommit.Set(it) }, consume = true)
            }
            is ScrubGestureState.DraggingHandle -> {
                // A too-short result widens away from the fixed edge, so only the dragged edge moves.
                val other = state.otherEdgeMs
                val dragged = if (abs(state.currentMs - other) >= minSelectionMs) {
                    state.currentMs
                } else if (state.currentMs >= other) {
                    other + minSelectionMs
                } else {
                    other - minSelectionMs
                }
                val range = normalizeSelection(other, dragged, scale.window, minSelectionMs)
                ScrubGestureResult(ScrubGestureState.Idle, commit = range?.let { SelectionCommit.Set(it) }, consume = true)
            }
        }

    private fun timeAt(x: Float, scale: TimeScale): Long =
        scale.toTime(x).coerceIn(scale.window.startMs, scale.window.endMs)
}
