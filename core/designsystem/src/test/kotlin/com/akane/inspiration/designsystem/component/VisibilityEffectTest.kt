package com.akane.inspiration.designsystem.component

import android.app.Activity
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
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
 * A screen's Visible/Hidden follow the user, not the activity instance: a real recreation
 * ([ActivityController.recreate] marks the activity as changing configurations, then pauses, stops, saves its state,
 * destroys it and builds a new one from that state, as rotation does) must reach neither callback, while a real stop,
 * a return and leaving the composition still do. The Routes' Activity-scoped ViewModels rely on this (SQ-47).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VisibilityEffectTest {
    private lateinit var controller: ActivityController<HostActivity>

    @Before
    fun setUp() {
        events.clear()
        content = { VisibilityEffect(key = Unit, onVisible = { events += VISIBLE }, onHidden = { events += HIDDEN }) }
        controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        idle()
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
        // Drain what the teardown posted: Compose's main dispatcher outlives the test, and a dispatch left pending
        // when Robolectric clears the queue stalls every coroutine in the classes that run after this one (SQ-66).
        idle()
    }

    @Test
    fun `a configuration change is not leaving, so neither hidden nor a second visible runs`() {
        controller.recreate()
        idle()

        assertEquals(listOf(VISIBLE), events)
    }

    @Test
    fun `a real stop after a recreation still hides and a return shows again`() {
        controller.recreate()
        idle()
        controller.pause().stop()
        idle()
        controller.start().resume()
        idle()

        assertEquals(listOf(VISIBLE, HIDDEN, VISIBLE), events)
    }

    @Test
    fun `a recreation while stopped carries no skip, so the return shows`() {
        controller.pause().stop()
        idle()
        // Robolectric brings the new instance up to resumed and back down to stopped, a genuine start and stop.
        controller.recreate()
        idle()
        controller.start().resume()
        idle()

        assertEquals(VISIBLE, events.last())
        assertEquals("visible and hidden alternate", events.indices.map { if (it % 2 == 0) VISIBLE else HIDDEN }, events)
    }

    @Test
    fun `leaving the composition hides`() {
        // A tab switch takes the route out of the composition while the activity stays started.
        // Detaching the ComposeView disposes its composition (the default composition strategy).
        val activity = controller.get()
        activity.setContentView(View(activity))
        idle()

        assertEquals(listOf(VISIBLE, HIDDEN), events)
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    /**
     * The owners a ComponentActivity provides, in its order: ON_STOP before the instance state is saved (API 28+),
     * and the saved state restored in onCreate. androidx.activity is not on this module's compile classpath.
     */
    class HostActivity : Activity(), LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        override fun onCreate(savedInstanceState: Bundle?) {
            savedState.performRestore(savedInstanceState)
            super.onCreate(savedInstanceState)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            val host = this
            setContentView(
                ComposeView(this).apply {
                    setViewTreeLifecycleOwner(host)
                    setViewTreeSavedStateRegistryOwner(host)
                    setContent { content() }
                },
            )
        }

        override fun onStart() {
            super.onStart()
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        override fun onResume() {
            super.onResume()
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        override fun onPause() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            super.onPause()
        }

        override fun onStop() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            super.onStop()
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            savedState.performSave(outState)
        }

        override fun onDestroy() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            super.onDestroy()
        }
    }

    private companion object {
        const val VISIBLE = "visible"
        const val HIDDEN = "hidden"
        val events = mutableListOf<String>()
        var content: @Composable () -> Unit = {}
    }
}
