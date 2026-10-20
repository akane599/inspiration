package com.akane.inspiration.xray

import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.toolkit.ToolkitUnavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val CHATTY_UID = 10_002
private const val MAPS_UID = 10_001

/**
 * A Room error inside `ToolkitActions` (a full disk while the write-ahead PENDING row is inserted, an audit log
 * that cannot be read) is a RuntimeException that propagates out of the call. On Android an exception nothing
 * catches in a `viewModelScope` launch reaches the thread's default handler and kills the process, so every
 * X-Ray launch must contain it: the sheet says the action failed, ends `busy` and stays usable.
 *
 * The thread's uncaught-exception handlers are replaced by a recorder, so a launch that lets an exception escape
 * fails the test instead of passing silently.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XRayViewModelFailureTest {
    private lateinit var deps: FakeXRayDeps
    private lateinit var vm: XRayViewModel

    private val uncaught = mutableListOf<Throwable>()
    private val recorder = Thread.UncaughtExceptionHandler { _, e -> uncaught += e }
    private var previousDefault: Thread.UncaughtExceptionHandler? = null
    private var previousOwn: Thread.UncaughtExceptionHandler? = null

    private val state get() = vm.state.value
    private val detail get() = state.detail!!
    private val store get() = deps.device.store

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        previousDefault = Thread.getDefaultUncaughtExceptionHandler()
        previousOwn = Thread.currentThread().uncaughtExceptionHandler
        Thread.setDefaultUncaughtExceptionHandler(recorder)
        Thread.currentThread().uncaughtExceptionHandler = recorder
        deps = FakeXRayDeps()
        deps.analysis.value = readyOf(sampleDiff())
        vm = XRayViewModel(deps)
        vm.onIntent(XRayIntent.Visible)
    }

    @After
    fun tearDown() {
        Thread.currentThread().uncaughtExceptionHandler = previousOwn
        Thread.setDefaultUncaughtExceptionHandler(previousDefault)
        Dispatchers.resetMain()
    }

    private fun diskFull() = RuntimeException("SQLiteFullException: database or disk is full (code 13)")

    private fun assertNothingEscaped() = assertTrue("an exception reached the thread's handler: $uncaught", uncaught.isEmpty())

    private val failed = ActionNotice.Rejected(Problem.Failed(code = null))

    @Test
    fun `an owner lookup that throws leaves that row unnamed instead of crashing the screen`() {
        deps.uidFailures[CHATTY_UID] = SecurityException("package manager refused the uid lookup")
        deps.labelFailures[MAPS] = IllegalStateException("package manager died")
        // A newer analysis (an equal one would be conflated away) is shaped with the failing package manager.
        deps.analysis.value = readyOf(sampleDiff(), resolvedOf(toMs = 2 * HOUR_MS))

        assertNothingEscaped()
        assertEquals(XRayStatus.READY, state.status)
        val chatty = state.apps.first { it.uid == CHATTY_UID }
        assertNull("never the reported name, which the actions would act on", chatty.packageName)
        assertNull(chatty.label)
        val maps = state.apps.first { it.uid == MAPS_UID }
        assertEquals("a label that cannot be read keeps the owner", MAPS, maps.packageName)
        assertNull(maps.label)

        vm.onIntent(XRayIntent.OpenApp(CHATTY_UID))
        assertEquals(ActionsState.NoPackage, detail.actions)
        assertNothingEscaped()
    }

    @Test
    fun `a write whose write-ahead audit row cannot be stored says it failed instead of crashing`() {
        vm.onIntent(XRayIntent.OpenApp(CHATTY_UID))
        store.insertFailure = diskFull()

        vm.onIntent(XRayIntent.Act(ActionRequest.SetBucket(StandbyBucket.WORKING_SET)))

        assertNothingEscaped()
        assertFalse("the failed action must end busy", detail.busy)
        // Nothing was sent (the row is stored before the call), so "the command failed" is true.
        assertEquals(failed, detail.notice)
        assertTrue("the controls show the read-back state again", detail.actions is ActionsState.Loaded)
        assertTrue("nothing was written without its audit row", deps.device.writes.isEmpty())
    }

    @Test
    fun `a write sent before its audit row could be settled says it could not be confirmed`() {
        vm.onIntent(XRayIntent.OpenApp(CHATTY_UID))
        store.updateFailure = diskFull()

        vm.onIntent(XRayIntent.Act(ActionRequest.SetBucket(StandbyBucket.WORKING_SET)))

        assertNothingEscaped()
        assertFalse(detail.busy)
        // R-05: the device changed, so this is "sent, but couldn't be confirmed", never "the command failed".
        assertEquals(listOf("setStandbyBucket"), deps.device.writes)
        assertEquals(ActionNotice.Rejected(Problem.Unavailable(ToolkitUnavailable.UNVERIFIED)), detail.notice)
    }

    @Test
    fun `a confirmed write that throws says it failed and the sheet takes the next action`() {
        vm.onIntent(XRayIntent.OpenApp(CHATTY_UID))
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
        store.insertFailure = diskFull()

        vm.onIntent(XRayIntent.ConfirmPending)

        assertNothingEscaped()
        assertFalse(detail.busy)
        assertEquals(failed, detail.notice)

        store.insertFailure = null
        vm.onIntent(XRayIntent.Act(ActionRequest.SetBucket(StandbyBucket.WORKING_SET)))
        assertEquals(ActionNotice.Applied(Change.BucketSet(StandbyBucket.WORKING_SET)), detail.notice)
    }

    @Test
    fun `a restore whose plan cannot read the audit log says it failed`() {
        vm.onIntent(XRayIntent.OpenApp(CHATTY_UID))
        vm.onIntent(XRayIntent.Act(ActionRequest.SetRestricted(true)))
        vm.onIntent(XRayIntent.ConfirmPending)
        val override = detail.overrides.single()
        store.readFailure = diskFull()

        vm.onIntent(XRayIntent.Act(ActionRequest.Restore(override.actionId)))

        assertNothingEscaped()
        assertFalse(detail.busy)
        assertEquals(failed, detail.notice)
        assertEquals("ignore", deps.device.runAnyInBackground)
    }

    @Test
    fun `an audit log that cannot be read keeps the controls and fails only the write that needs it`() {
        store.readFailure = diskFull()

        vm.onIntent(XRayIntent.OpenApp(CHATTY_UID))

        assertNothingEscaped()
        assertTrue("an app's settings are read from the system, not the log", detail.actions is ActionsState.Loaded)
        assertTrue(detail.overrides.isEmpty())

        vm.onIntent(XRayIntent.Act(ActionRequest.SetBucket(StandbyBucket.WORKING_SET)))

        assertNothingEscaped()
        assertFalse(detail.busy)
        assertEquals(failed, detail.notice)
    }
}
