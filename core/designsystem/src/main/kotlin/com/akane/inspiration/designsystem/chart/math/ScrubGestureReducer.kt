package com.akane.inspiration.designsystem.chart.math

import com.akane.inspiration.designsystem.chart.model.TimeRange
import kotlin.math.abs

/** Which edge of the committed selection a handle drag moves. */
enum class HandleEdge { START, END }

sealed interface ScrubGestureState {
    data object Idle : ScrubGestureState

    /** Pointer is down, slop not yet crossed: tap, brush, scroll hand-off and long press are all still possible. */
    data class Pressed(
        val downX: Float,
        val downY: Float,
    ) : ScrubGestureState

    /**
     * Pointer went down within the hit radius of a selection edge, slop not yet crossed. It becomes a handle
     * drag only on horizontal-dominant movement past the slop; vertical movement releases to the parent and
     * an Up is a tap, exactly as in [Pressed].
     */
    data class PressedOnHandle(
        val edge: HandleEdge,
        val otherEdgeMs: Long,
        val edgeMs: Long,
        val downX: Float,
        val downY: Float,
    ) : ScrubGestureState

    data class Scrubbing(
        val tMs: Long,
    ) : ScrubGestureState

    data class Brushing(
        val anchorMs: Long,
        val currentMs: Long,
    ) : ScrubGestureState

    /**
     * @property grabOffsetMs edge time minus the unclamped time under the Down point, so the edge moves by
     *   the finger's delta instead of jumping to the finger's absolute position.
     */
    data class DraggingHandle(
        val edge: HandleEdge,
        val otherEdgeMs: Long,
        val currentMs: Long,
        val grabOffsetMs: Long,
    ) : ScrubGestureState
}

/** Pointer events; x and y are in px within the plot. */
sealed interface ScrubGestureEvent {
    data class Down(
        val x: Float,
        val y: Float,
    ) : ScrubGestureEvent

    data class LongPress(
        val x: Float,
    ) : ScrubGestureEvent

    data class Move(
        val x: Float,
        val y: Float,
    ) : ScrubGestureEvent

    data class Up(
        val x: Float,
    ) : ScrubGestureEvent

    data object Cancel : ScrubGestureEvent
}

sealed interface SelectionCommit {
    data class Set(
        val range: TimeRange,
    ) : SelectionCommit

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
 * - Down within [handleHitPx] (a radius) of a selection edge inside the window is PressedOnHandle; otherwise it is
 *   Pressed. Neither consumes the Down. An edge outside the window has no drawn handle and cannot be grabbed.
 * - From either pressed state, crossing [slopPx] on either axis decides by the dominant axis: horizontal
 *   starts a brush (or, from PressedOnHandle, a handle drag that moves the edge by the finger's delta),
 *   vertical releases to the parent. LongPress starts scrubbing.
 * - Up after a brush commits the range, unless it is shorter than [minSelectionMs] (a no-op, not a clear).
 *   Up in either pressed state is a tap and clears an existing selection.
 */
class ScrubGestureReducer(
    val minSelectionMs: Long,
    val slopPx: Float,
    val handleHitPx: Float,
) {
    fun reduce(
        state: ScrubGestureState,
        event: ScrubGestureEvent,
        scale: TimeScale,
        selection: TimeRange?,
    ): ScrubGestureResult =
        when (event) {
            ScrubGestureEvent.Cancel -> {
                ScrubGestureResult(ScrubGestureState.Idle)
            }

            is ScrubGestureEvent.Down -> {
                down(event, scale, selection)
            }

            is ScrubGestureEvent.LongPress -> {
                longPress(state, event, scale)
            }

            is ScrubGestureEvent.Move -> {
                move(state, event, scale)
            }

            is ScrubGestureEvent.Up -> {
                up(
                    when (state) {
                        is ScrubGestureState.Brushing -> state.copy(currentMs = timeAt(event.x, scale))
                        is ScrubGestureState.DraggingHandle -> state.copy(currentMs = handleTimeAt(state, event.x, scale))
                        else -> state
                    },
                    scale,
                    selection,
                )
            }
        }

    private fun down(
        event: ScrubGestureEvent.Down,
        scale: TimeScale,
        selection: TimeRange?,
    ): ScrubGestureResult =
        ScrubGestureResult(
            selection?.let { nearestHandle(it, event, scale) } ?: ScrubGestureState.Pressed(event.x, event.y),
        )

    /** Only an edge inside the window has a drawn handle, so only such an edge can be grabbed. */
    private fun nearestHandle(
        selection: TimeRange,
        event: ScrubGestureEvent.Down,
        scale: TimeScale,
    ): ScrubGestureState.PressedOnHandle? {
        val startDistance = handleDistance(selection.startMs, event.x, scale)
        val endDistance = handleDistance(selection.endMs, event.x, scale)
        if (minOf(startDistance, endDistance) > handleHitPx) return null
        return if (startDistance <= endDistance) {
            ScrubGestureState.PressedOnHandle(HandleEdge.START, selection.endMs, selection.startMs, event.x, event.y)
        } else {
            ScrubGestureState.PressedOnHandle(HandleEdge.END, selection.startMs, selection.endMs, event.x, event.y)
        }
    }

    private fun handleDistance(
        edgeMs: Long,
        x: Float,
        scale: TimeScale,
    ): Float = if (edgeMs in scale.window) abs(x - scale.toX(edgeMs)) else Float.POSITIVE_INFINITY

    private fun longPress(
        state: ScrubGestureState,
        event: ScrubGestureEvent.LongPress,
        scale: TimeScale,
    ): ScrubGestureResult {
        val pressed =
            state == ScrubGestureState.Idle ||
                state is ScrubGestureState.Pressed ||
                state is ScrubGestureState.PressedOnHandle
        if (!pressed) return ScrubGestureResult(state)
        val t = timeAt(event.x, scale)
        return ScrubGestureResult(ScrubGestureState.Scrubbing(t), cursorMs = t, consume = true)
    }

    private fun move(
        state: ScrubGestureState,
        event: ScrubGestureEvent.Move,
        scale: TimeScale,
    ): ScrubGestureResult =
        when (state) {
            is ScrubGestureState.Pressed -> {
                movePressed(state, state.downX, state.downY, event) {
                    ScrubGestureState.Brushing(timeAt(state.downX, scale), timeAt(event.x, scale))
                }
            }

            is ScrubGestureState.PressedOnHandle -> {
                movePressed(state, state.downX, state.downY, event) {
                    val drag =
                        ScrubGestureState.DraggingHandle(
                            edge = state.edge,
                            otherEdgeMs = state.otherEdgeMs,
                            currentMs = state.edgeMs,
                            grabOffsetMs = state.edgeMs - scale.toTime(state.downX),
                        )
                    drag.copy(currentMs = handleTimeAt(drag, event.x, scale))
                }
            }

            is ScrubGestureState.Scrubbing -> {
                val t = timeAt(event.x, scale)
                ScrubGestureResult(ScrubGestureState.Scrubbing(t), cursorMs = t, consume = true)
            }

            is ScrubGestureState.Brushing -> {
                ScrubGestureResult(state.copy(currentMs = timeAt(event.x, scale)), consume = true)
            }

            is ScrubGestureState.DraggingHandle -> {
                ScrubGestureResult(state.copy(currentMs = handleTimeAt(state, event.x, scale)), consume = true)
            }

            ScrubGestureState.Idle -> {
                ScrubGestureResult(state)
            }
        }

    /** Slop decision shared by both pressed states: stay, release on vertical, or enter [horizontal]. */
    private inline fun movePressed(
        pressed: ScrubGestureState,
        downX: Float,
        downY: Float,
        event: ScrubGestureEvent.Move,
        horizontal: () -> ScrubGestureState,
    ): ScrubGestureResult {
        val dx = abs(event.x - downX)
        val dy = abs(event.y - downY)
        return when {
            dx <= slopPx && dy <= slopPx -> ScrubGestureResult(pressed)
            dy > dx -> ScrubGestureResult(ScrubGestureState.Idle, release = true)
            else -> ScrubGestureResult(horizontal(), consume = true)
        }
    }

    private fun up(
        state: ScrubGestureState,
        scale: TimeScale,
        selection: TimeRange?,
    ): ScrubGestureResult =
        when (state) {
            ScrubGestureState.Idle -> {
                ScrubGestureResult(state)
            }

            is ScrubGestureState.Pressed, is ScrubGestureState.PressedOnHandle -> {
                ScrubGestureResult(
                    ScrubGestureState.Idle,
                    commit = if (selection != null) SelectionCommit.Clear else null,
                )
            }

            is ScrubGestureState.Scrubbing -> {
                ScrubGestureResult(ScrubGestureState.Idle, consume = true)
            }

            is ScrubGestureState.Brushing -> {
                val long = abs(state.currentMs - state.anchorMs) >= minSelectionMs
                val range = if (long) normalizeSelection(state.anchorMs, state.currentMs, scale.window, minSelectionMs) else null
                ScrubGestureResult(ScrubGestureState.Idle, commit = range?.let { SelectionCommit.Set(it) }, consume = true)
            }

            is ScrubGestureState.DraggingHandle -> {
                // A too-short result widens from the fixed edge toward the dragged side, or the other way when
                // that would cross the window bound, so only the dragged edge moves. Only a window with no room
                // on either side leaves normalizeSelection to shift the fixed edge.
                val other = state.otherEdgeMs
                val after = other + minSelectionMs
                val before = other - minSelectionMs
                val afterFits = after <= scale.window.endMs
                val beforeFits = before >= scale.window.startMs
                val dragged =
                    if (abs(state.currentMs - other) >= minSelectionMs) {
                        state.currentMs
                    } else if (state.currentMs >= other) {
                        if (afterFits || !beforeFits) after else before
                    } else {
                        if (beforeFits || !afterFits) before else after
                    }
                val range = normalizeSelection(other, dragged, scale.window, minSelectionMs)
                ScrubGestureResult(ScrubGestureState.Idle, commit = range?.let { SelectionCommit.Set(it) }, consume = true)
            }
        }

    private fun timeAt(
        x: Float,
        scale: TimeScale,
    ): Long = scale.toTime(x).coerceIn(scale.window.startMs, scale.window.endMs)

    private fun handleTimeAt(
        state: ScrubGestureState.DraggingHandle,
        x: Float,
        scale: TimeScale,
    ): Long = (scale.toTime(x) + state.grabOffsetMs).coerceIn(scale.window.startMs, scale.window.endMs)
}
