package com.akane.inspiration.designsystem.chart.scrub

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.akane.inspiration.designsystem.chart.model.TimeRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The chart's gesture loop, end to end: real [MotionEvent]s go through the Android view into Compose, so the pointer
 * passes and consumption are Compose's own. The plot is 200 dp wide over a 100 s window; the selection, 10-20 s, is
 * far from where the tests tap (75%, 75 s), so a tap is never an edge grab.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScrubGesturesUiTest {
    private class Holder<T>(
        override val value: T,
    ) : State<T>

    private object NoHaptics : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
    }

    private lateinit var controller: ActivityController<Activity>
    private lateinit var view: ComposeView
    private val selectionChanges = mutableListOf<TimeRange?>()

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
        // Drain what the teardown posted: Compose's main dispatcher outlives the test, and a dispatch left pending
        // when Robolectric clears the queue stalls every coroutine in the classes that run after this one (SQ-66).
        idle(Duration.ofMillis(100))
    }

    @Test
    fun `a tap on the plot clears its selection`() {
        show { Plot(Modifier.size(PLOT_WIDTH_DP.dp, 100.dp)) }

        tap(xDp = TAP_X_DP, yDp = 50f)

        assertEquals("a plain tap still clears, as before", listOf<TimeRange?>(null), selectionChanges)
    }

    @Test
    fun `a tap on the plot in a resting scroll still clears its selection`() {
        show {
            Column(Modifier.size(PLOT_WIDTH_DP.dp, 400.dp).verticalScroll(ScrollState(0))) {
                Plot(Modifier.fillMaxWidth().height(4_000.dp))
            }
        }

        tap(xDp = TAP_X_DP, yDp = 200f)

        assertEquals(listOf<TimeRange?>(null), selectionChanges)
    }

    @Test
    fun `tapping the plot to stop a scroll in flight keeps the selection`() {
        val scroll = ScrollState(0)
        show {
            Column(Modifier.size(PLOT_WIDTH_DP.dp, 400.dp).verticalScroll(scroll)) {
                Plot(Modifier.fillMaxWidth().height(4_000.dp))
            }
        }
        // A scroll still moving, as after a fling, so the scroll node takes the press that stops it on its way down.
        // It is held in flight on a frame clock the test owns: a real fling's decay can't be caught mid-flight under
        // Robolectric, and Compose's UI dispatcher doesn't reliably run here once an earlier test in the JVM used it.
        val clock = BroadcastFrameClock()
        val inFlight =
            CoroutineScope(Dispatchers.Unconfined + clock).launch {
                scroll.animateScrollTo(3_000, tween(durationMillis = 60_000, easing = LinearEasing))
            }
        try {
            assertTrue("the scroll is in flight when the tap lands", scroll.isScrollInProgress)

            tap(xDp = TAP_X_DP, yDp = 200f)

            assertEquals("the tap only stopped the scroll", emptyList<TimeRange?>(), selectionChanges)
        } finally {
            inFlight.cancel()
        }
    }

    @Test
    fun `a press an ancestor consumed on its way down is not a tap on the plot`() {
        show {
            // What a moving scroll does with the press that stops it, and what the obscured-touch guard does.
            Box(Modifier.pointerInput(Unit) { consumeDownsOnTheWayDown() }) {
                Plot(Modifier.size(PLOT_WIDTH_DP.dp, 100.dp))
            }
        }

        tap(xDp = TAP_X_DP, yDp = 50f)

        assertEquals("the selection is kept", emptyList<TimeRange?>(), selectionChanges)
    }

    /** The plot's gesture loop alone, with a selection to clear. */
    @Composable
    private fun Plot(modifier: Modifier) {
        val state = remember { ScrubInteractionState(initialDim = 1f) }
        val inputs =
            remember {
                ScrubGestureInputs(
                    window = Holder(TimeRange.of(0L, 100_000L)),
                    selection = Holder(TimeRange.of(10_000L, 20_000L)),
                    minSelectionMs = Holder(5_000L),
                    gutters = Holder(Gutters(0f, 0f)),
                    onSelectionChange = Holder { range: TimeRange? -> selectionChanges += range },
                    onScrub = Holder { _: Long? -> },
                    haptic = Holder(NoHaptics),
                )
            }
        Box(modifier.scrubGestures(state, inputs))
    }

    private suspend fun PointerInputScope.consumeDownsOnTheWayDown() {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { if (it.changedToDown()) it.consume() }
            }
        }
    }

    private fun show(content: @Composable () -> Unit) {
        val owner = ResumedOwner()
        view =
            ComposeView(controller.get()).apply {
                // A plain Activity doesn't provide the owners ComposeView needs (androidx.activity isn't a dependency here).
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent(content)
            }
        controller.get().setContentView(view)
        idle(Duration.ofMillis(100))
        check(view.width > 0 && view.height > 0) { "the plot was not laid out" }
    }

    private fun tap(
        xDp: Float,
        yDp: Float,
    ) {
        val downTime = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, downTime, xDp, yDp)
        idle(Duration.ofMillis(TAP_HOLD_MS))
        dispatch(MotionEvent.ACTION_UP, downTime, xDp, yDp)
        idle(Duration.ofMillis(100))
    }

    private fun dispatch(
        action: Int,
        downTime: Long,
        xDp: Float,
        yDp: Float,
    ) {
        val density = view.resources.displayMetrics.density
        val properties =
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        val coords =
            MotionEvent.PointerCoords().apply {
                x = xDp * density
                y = yDp * density
                pressure = 1f
                size = 1f
            }
        val event =
            MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun idle(duration: Duration) {
        shadowOf(Looper.getMainLooper()).idleFor(duration)
    }

    private class ResumedOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)

        init {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    }

    private companion object {
        const val PLOT_WIDTH_DP = 200
        const val TAP_X_DP = 150f

        /** Well under the long-press timeout, so the press is a tap. */
        const val TAP_HOLD_MS = 50L
    }
}
