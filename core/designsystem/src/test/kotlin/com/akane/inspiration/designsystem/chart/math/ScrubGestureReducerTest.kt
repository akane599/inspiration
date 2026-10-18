package com.akane.inspiration.designsystem.chart.math

import com.akane.inspiration.designsystem.chart.math.ScrubGestureEvent.Cancel
import com.akane.inspiration.designsystem.chart.math.ScrubGestureEvent.Down
import com.akane.inspiration.designsystem.chart.math.ScrubGestureEvent.LongPress
import com.akane.inspiration.designsystem.chart.math.ScrubGestureEvent.Move
import com.akane.inspiration.designsystem.chart.math.ScrubGestureEvent.Up
import com.akane.inspiration.designsystem.chart.model.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Window 0..100 000 ms over 1000 px: 100 ms per px. Slop 10 px, minimum selection 5000 ms (50 px), handle hit 48 px. */
class ScrubGestureReducerTest {
    private val window = TimeRange.of(0L, 100_000L)
    private val scale = TimeScale(window, 1000f)
    private val reducer = ScrubGestureReducer(minSelectionMs = 5_000L, slopPx = 10f, handleHitPx = 48f)
    private val selection = TimeRange.of(20_000L, 60_000L) // x = 200..600

    private fun step(state: ScrubGestureState, event: ScrubGestureEvent, sel: TimeRange? = null) =
        reducer.reduce(state, event, scale, sel)

    /** Runs the events in order, returning the final result. */
    private fun run(sel: TimeRange?, vararg events: ScrubGestureEvent): ScrubGestureResult {
        var result = ScrubGestureResult(ScrubGestureState.Idle)
        for (e in events) result = step(result.state, e, sel)
        return result
    }

    @Test
    fun releasePositionCompletesTheBrushEvenWhenNoMoveWasDeliveredThere() {
        val result = run(null, Down(200f, 50f), Move(400f, 50f), Up(450f))
        assertEquals(SelectionCommit.Set(TimeRange.of(20_000, 45_000)), result.commit)
    }

    @Test
    fun releaseCanCrossTheMinimumBrushSize() {
        val result = run(null, Down(200f, 50f), Move(240f, 50f), Up(260f))
        assertEquals(SelectionCommit.Set(TimeRange.of(20_000, 26_000)), result.commit)
    }

    @Test
    fun cleanTapClearsExistingSelection() {
        val r = run(selection, Down(400f, 50f), Up(400f))
        assertEquals(SelectionCommit.Clear, r.commit)
        assertEquals(ScrubGestureState.Idle, r.state)
    }

    @Test
    fun tapWithoutSelectionDoesNothing() {
        val r = run(null, Down(400f, 50f), Up(400f))
        assertNull(r.commit)
        assertEquals(ScrubGestureState.Idle, r.state)
    }

    @Test
    fun wobbleWithinSlopStaysPressedAndStillCountsAsTap() {
        val down = step(ScrubGestureState.Idle, Down(400f, 50f), selection)
        val moved = step(down.state, Move(405f, 54f), selection)
        assertEquals(down.state, moved.state)
        assertFalse(moved.consume)
        assertFalse(moved.release)
        assertNull(moved.commit)
        assertEquals(SelectionCommit.Clear, step(moved.state, Up(405f), selection).commit)
    }

    @Test
    fun shortDragUnderMinSelectionIsNoOpNotClear() {
        // 30 px = 3000 ms < 5000 ms minimum, but past the 10 px slop.
        val r = run(selection, Down(400f, 50f), Move(430f, 50f), Up(430f))
        assertNull(r.commit)
        assertEquals(ScrubGestureState.Idle, r.state)
    }

    @Test
    fun horizontalMovePastSlopEntersBrushingAndConsumes() {
        val down = step(ScrubGestureState.Idle, Down(200f, 50f))
        val r = step(down.state, Move(400f, 52f))
        assertEquals(ScrubGestureState.Brushing(20_000L, 40_000L), r.state)
        assertTrue(r.consume)
        assertFalse(r.release)
        assertNull(r.commit)
    }

    @Test
    fun upAfterBrushCommitsRange() {
        val r = run(null, Down(200f, 50f), Move(400f, 50f), Up(400f))
        assertEquals(SelectionCommit.Set(TimeRange.of(20_000L, 40_000L)), r.commit)
        assertEquals(ScrubGestureState.Idle, r.state)
    }

    @Test
    fun brushRightToLeftIsOrderedAndClampedToWindow() {
        val rtl = run(null, Down(400f, 50f), Move(200f, 50f), Up(200f))
        assertEquals(SelectionCommit.Set(TimeRange.of(20_000L, 40_000L)), rtl.commit)
        val past = run(null, Down(800f, 50f), Move(1500f, 50f), Up(1500f))
        assertEquals(SelectionCommit.Set(TimeRange.of(80_000L, 100_000L)), past.commit)
    }

    @Test
    fun brushLiveStateFollowsMoves() {
        val r = run(null, Down(200f, 50f), Move(300f, 50f), Move(450f, 60f))
        assertEquals(ScrubGestureState.Brushing(20_000L, 45_000L), r.state)
        assertTrue(r.consume)
    }

    @Test
    fun mostlyVerticalMoveReleasesToParent() {
        val down = step(ScrubGestureState.Idle, Down(500f, 50f))
        val r = step(down.state, Move(502f, 90f))
        assertTrue(r.release)
        assertFalse(r.consume)
        assertEquals(ScrubGestureState.Idle, r.state)
        // The released gesture stays inert until the next Down.
        val after = step(r.state, Move(700f, 90f))
        assertEquals(ScrubGestureState.Idle, after.state)
        assertFalse(after.consume)
        assertNull(step(after.state, Up(700f), selection).commit)
    }

    @Test
    fun longPressEntersScrubbingAndEveryMoveIsConsumed() {
        val down = step(ScrubGestureState.Idle, Down(300f, 50f))
        val lp = step(down.state, LongPress(300f))
        assertEquals(ScrubGestureState.Scrubbing(30_000L), lp.state)
        assertEquals(30_000L, lp.cursorMs)
        assertTrue(lp.consume)
        val small = step(lp.state, Move(302f, 51f))
        assertTrue(small.consume)
        assertEquals(30_200L, small.cursorMs)
        val vertical = step(small.state, Move(600f, 300f))
        assertTrue(vertical.consume)
        assertFalse(vertical.release)
        assertEquals(60_000L, vertical.cursorMs)
        assertEquals(100_000L, step(vertical.state, Move(5000f, 0f)).cursorMs) // clamped to the window
    }

    @Test
    fun upAfterScrubClearsCursorAndKeepsSelection() {
        val r = run(selection, Down(300f, 50f), LongPress(300f), Move(350f, 50f), Up(350f))
        assertEquals(ScrubGestureState.Idle, r.state)
        assertNull(r.cursorMs)
        assertNull(r.commit)
    }

    @Test
    fun downNearStartEdgeStartsHandleDragAndUpCommitsAdjustedRange() {
        val down = step(ScrubGestureState.Idle, Down(205f, 50f), selection)
        assertEquals(ScrubGestureState.DraggingHandle(HandleEdge.START, 60_000L, 20_000L), down.state)
        assertTrue(down.consume)
        val moved = step(down.state, Move(100f, 55f), selection)
        assertEquals(ScrubGestureState.DraggingHandle(HandleEdge.START, 60_000L, 10_000L), moved.state)
        assertTrue(moved.consume)
        val up = step(moved.state, Up(100f), selection)
        assertEquals(SelectionCommit.Set(TimeRange.of(10_000L, 60_000L)), up.commit)
        assertEquals(ScrubGestureState.Idle, up.state)
    }

    @Test
    fun downNearEndEdgeAdjustsEndAndCrossingSwapsEnds() {
        val down = step(ScrubGestureState.Idle, Down(640f, 50f), selection) // 40 px from 600
        assertEquals(ScrubGestureState.DraggingHandle(HandleEdge.END, 20_000L, 60_000L), down.state)
        val widened = run(selection, Down(595f, 50f), Move(800f, 50f), Up(800f))
        assertEquals(SelectionCommit.Set(TimeRange.of(20_000L, 80_000L)), widened.commit)
        val crossed = run(selection, Down(195f, 50f), Move(800f, 50f), Up(800f))
        assertEquals(SelectionCommit.Set(TimeRange.of(60_000L, 80_000L)), crossed.commit)
    }

    @Test
    fun handleDragBelowMinimumIsWidenedToMinimum() {
        val r = run(selection, Down(200f, 50f), Move(590f, 50f), Up(590f))
        assertEquals(SelectionCommit.Set(TimeRange.of(55_000L, 60_000L)), r.commit)
    }

    @Test
    fun downFarFromHandlesStartsPressedNotHandleDrag() {
        val r = step(ScrubGestureState.Idle, Down(400f, 50f), selection)
        assertEquals(ScrubGestureState.Pressed(400f, 50f), r.state)
        assertFalse(r.consume)
        // No selection: even a Down exactly where a handle would be is just Pressed.
        assertEquals(ScrubGestureState.Pressed(200f, 50f), step(ScrubGestureState.Idle, Down(200f, 50f), null).state)
    }

    @Test
    fun cancelResetsFromEveryStateWithoutCommit() {
        val states = listOf(
            ScrubGestureState.Pressed(1f, 2f),
            ScrubGestureState.Scrubbing(10L),
            ScrubGestureState.Brushing(10L, 20L),
            ScrubGestureState.DraggingHandle(HandleEdge.END, 10L, 20L),
        )
        for (s in states) {
            val r = step(s, Cancel, selection)
            assertEquals(ScrubGestureState.Idle, r.state)
            assertNull(r.commit)
            assertNull(r.cursorMs)
            assertFalse(r.consume)
        }
    }
}
