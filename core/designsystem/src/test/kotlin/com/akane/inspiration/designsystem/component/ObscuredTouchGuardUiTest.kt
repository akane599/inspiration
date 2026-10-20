package com.akane.inspiration.designsystem.component

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The guard's wiring, end to end: a real [MotionEvent] goes through the Android view into Compose, so this
 * fails if the guard stops reading FLAG_WINDOW_IS_OBSCURED from the event or stops consuming it on the way
 * down ([androidx.compose.ui.input.pointer.PointerEventPass.Initial]), before the child sees it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ObscuredTouchGuardUiTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var view: ComposeView
    private var clicks = 0
    private var notices = 0

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val owner = ResumedOwner()
        view = ComposeView(controller.get()).apply {
            // A plain Activity doesn't provide the owners ComposeView needs (androidx.activity isn't a dependency here).
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                CompositionLocalProvider(LocalObscuredTouchNotice provides { notices++ }) {
                    Box(modifier = Modifier.size(100.dp).filterTouchesWhenObscured()) {
                        Box(modifier = Modifier.fillMaxSize().clickable { clicks++ })
                    }
                }
            }
        }
        controller.get().setContentView(view)
        idle()
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
    }

    @Test
    fun `an obscured tap does not reach the guarded control and notifies once`() {
        tap(flags = MotionEvent.FLAG_WINDOW_IS_OBSCURED)

        assertEquals(0, clicks)
        assertEquals(1, notices)
    }

    @Test
    fun `an uncovered tap reaches the guarded control without a notice`() {
        tap(flags = 0)

        assertEquals(1, clicks)
        assertEquals(0, notices)
    }

    @Test
    fun `a partially obscured tap still goes through`() {
        tap(flags = MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)

        assertEquals(1, clicks)
        assertEquals(0, notices)
    }

    private fun tap(flags: Int) {
        val downTime = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, downTime, downTime, flags)
        dispatch(MotionEvent.ACTION_UP, downTime, downTime + 50, flags)
        idle()
    }

    private fun dispatch(action: Int, downTime: Long, eventTime: Long, flags: Int) {
        check(view.width > 0 && view.height > 0) { "the guarded content was not laid out" }
        // The middle of the 100 dp guarded box at the view's top start.
        val x = 50 * view.resources.displayMetrics.density
        val y = x
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }
        val event = MotionEvent.obtain(
            downTime, eventTime, action, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags,
        )
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
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
}
