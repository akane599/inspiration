package com.akane.inspiration.designsystem.chart.scrub

import androidx.compose.runtime.State
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.akane.inspiration.designsystem.chart.math.ScrubGestureEvent
import com.akane.inspiration.designsystem.chart.math.ScrubGestureEvent.Cancel
import com.akane.inspiration.designsystem.chart.math.ScrubGestureEvent.Down
import com.akane.inspiration.designsystem.chart.math.ScrubGestureEvent.LongPress
import com.akane.inspiration.designsystem.chart.math.ScrubGestureEvent.Move
import com.akane.inspiration.designsystem.chart.math.ScrubGestureEvent.Up
import com.akane.inspiration.designsystem.chart.math.ScrubGestureReducer
import com.akane.inspiration.designsystem.chart.math.TimeScale
import com.akane.inspiration.designsystem.chart.model.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gesture glue around the pure reducer: what one gesture leaves in the draw-phase state and reports through the
 * callbacks, including when it ends by cancellation instead of an Up. Window 0..100 000 ms over 1000 px (100 ms per
 * px), slop 10 px, minimum selection 5000 ms, handle hit radius 24 px.
 */
class ScrubGestureDriverTest {
    private class Holder<T>(
        override var value: T,
    ) : State<T>

    private class CountingHaptics : HapticFeedback {
        var count = 0

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            count += 1
        }
    }

    private val window = TimeRange.of(0L, 100_000L)
    private val state = ScrubInteractionState(initialDim = 0f)
    private val selection = Holder<TimeRange?>(null)
    private val haptics = CountingHaptics()
    private val selectionChanges = mutableListOf<TimeRange?>()
    private val scrubs = mutableListOf<Long?>()

    private val inputs =
        ScrubGestureInputs(
            window = Holder(window),
            selection = selection,
            minSelectionMs = Holder(5_000L),
            gutters = Holder(Gutters(0f, 0f)),
            onSelectionChange = Holder { range: TimeRange? -> selectionChanges += range },
            onScrub = Holder { t: Long? -> scrubs += t },
            haptic = Holder(haptics),
        )

    private fun driver() =
        ScrubGestureDriver(
            reducer = ScrubGestureReducer(minSelectionMs = 5_000L, slopPx = 10f, handleHitPx = 24f),
            scale = TimeScale(window, 1000f),
            state = state,
            inputs = inputs,
        )

    private fun ScrubGestureDriver.run(vararg events: ScrubGestureEvent) = events.forEach { dispatch(it) }

    private fun assertNoPreview() {
        assertEquals(NO_TIME, state.previewStartMs.longValue)
        assertEquals(NO_TIME, state.previewEndMs.longValue)
    }

    @Test
    fun aGestureMarksItselfActiveUntilItFinishes() {
        val driver = driver()
        assertTrue(state.gestureActive)
        driver.run(Down(400f, 50f), Up(400f))
        driver.finish()
        assertFalse(state.gestureActive)
    }

    @Test
    fun aBrushShowsItsPreviewAtFullDimWhileItMoves() {
        driver().run(Down(200f, 50f), Move(400f, 50f))
        assertEquals(20_000L, state.previewStartMs.longValue)
        assertEquals(40_000L, state.previewEndMs.longValue)
        assertEquals(1f, state.dimProgress.floatValue)
    }

    @Test
    fun aCancelledBrushLeavesNoPreviewNoCommitAndRewindsTheDim() {
        val driver = driver()
        driver.run(Down(200f, 50f), Move(400f, 50f), Cancel)
        assertNoPreview()
        driver.finish()
        assertNoPreview()
        assertEquals(emptyList<TimeRange?>(), selectionChanges)
        assertEquals(0, state.commitCount.intValue)
        assertEquals(0f, state.dimProgress.floatValue)
    }

    @Test
    fun aBrushEndedByCoroutineCancellationStillClearsItsPreview() {
        // No Cancel event: the pointerInput coroutine is cancelled mid-brush and only finish() runs.
        val driver = driver()
        driver.run(Down(200f, 50f), Move(400f, 50f))
        driver.finish()
        assertNoPreview()
        assertEquals(0f, state.dimProgress.floatValue)
        assertFalse(state.gestureActive)
    }

    @Test
    fun aCommittedBrushKeepsItsPreviewUntilTheSelectionHasDrawn() {
        val driver = driver()
        driver.run(Down(200f, 50f), Move(400f, 50f), Up(450f))
        driver.finish()
        assertEquals(listOf<TimeRange?>(TimeRange.of(20_000L, 45_000L)), selectionChanges)
        assertEquals(1, state.commitCount.intValue)
        // The preview is not cleared here: the caller's selection takes over from it without a frame of nothing.
        assertEquals(20_000L, state.previewStartMs.longValue)
        assertTrue(state.previewEndMs.longValue != NO_TIME)
        assertEquals(1f, state.dimProgress.floatValue)
    }

    @Test
    fun aHandleDragThatIsCancelledLeavesTheSelectionAlone() {
        selection.value = TimeRange.of(20_000L, 60_000L)
        val driver = driver()
        driver.run(Down(600f, 50f), Move(700f, 50f))
        assertEquals(20_000L, state.previewStartMs.longValue)
        assertEquals(70_000L, state.previewEndMs.longValue)
        driver.finish()
        assertNoPreview()
        assertEquals(emptyList<TimeRange?>(), selectionChanges)
        // A selection is still on screen, so the dim is not rewound.
        assertEquals(1f, state.dimProgress.floatValue)
    }

    @Test
    fun aTapClearsTheSelection() {
        selection.value = TimeRange.of(20_000L, 60_000L)
        driver().run(Down(400f, 50f), Up(400f))
        assertEquals(listOf<TimeRange?>(null), selectionChanges)
    }

    @Test
    fun aScrubBuzzesOnceReportsItsCursorAndFinishClearsIt() {
        val driver = driver()
        driver.run(Down(300f, 50f), LongPress(300f), Move(500f, 50f))
        assertEquals(1, haptics.count)
        assertEquals(50_000L, state.cursorMs.longValue)
        assertEquals(listOf<Long?>(30_000L, 50_000L), scrubs)
        driver.finish()
        assertEquals(NO_TIME, state.cursorMs.longValue)
        assertEquals(listOf<Long?>(30_000L, 50_000L, null), scrubs)
    }

    @Test
    fun aScrubEndedByUpReportsTheClearedCursorOnlyOnce() {
        val driver = driver()
        driver.run(Down(300f, 50f), LongPress(300f), Up(300f))
        driver.finish()
        assertEquals(listOf<Long?>(30_000L, null), scrubs)
        assertEquals(NO_TIME, state.cursorMs.longValue)
        assertEquals(emptyList<TimeRange?>(), selectionChanges)
    }

    @Test
    fun theLoopWaitsForALongPressOnlyWhileThePointerIsUnderTheSlop() {
        val driver = driver()
        assertFalse(driver.awaitsLongPress)
        driver.dispatch(Down(200f, 50f))
        assertTrue(driver.awaitsLongPress)
        driver.dispatch(Move(205f, 50f))
        assertTrue(driver.awaitsLongPress)
        driver.dispatch(Move(400f, 50f))
        assertFalse(driver.awaitsLongPress)
    }

    @Test
    fun aDownOnAHandleAlsoWaitsForALongPress() {
        selection.value = TimeRange.of(20_000L, 60_000L)
        val driver = driver()
        driver.dispatch(Down(600f, 50f))
        assertTrue(driver.awaitsLongPress)
    }

    @Test
    fun theLongPressTimeoutRunsFromTheDownNotFromTheLastMove() {
        assertEquals(500L, longPressRemainingMs(longPressTimeoutMs = 500L, downUptimeMs = 1_000L, lastUptimeMs = 1_000L))
        assertEquals(200L, longPressRemainingMs(longPressTimeoutMs = 500L, downUptimeMs = 1_000L, lastUptimeMs = 1_300L))
        assertEquals(0L, longPressRemainingMs(longPressTimeoutMs = 500L, downUptimeMs = 1_000L, lastUptimeMs = 1_500L))
        assertEquals(0L, longPressRemainingMs(longPressTimeoutMs = 500L, downUptimeMs = 1_000L, lastUptimeMs = 9_000L))
    }

    @Test
    fun aConsumedChangeCancelsWhateverThePointerDid() {
        assertEquals(Cancel, pointerChangeEvent(consumed = true, pressed = true, x = 10f, y = 20f))
        assertEquals(Cancel, pointerChangeEvent(consumed = true, pressed = false, x = 10f, y = 20f))
        assertEquals(Move(10f, 20f), pointerChangeEvent(consumed = false, pressed = true, x = 10f, y = 20f))
        assertEquals(Up(10f), pointerChangeEvent(consumed = false, pressed = false, x = 10f, y = 20f))
    }
}
