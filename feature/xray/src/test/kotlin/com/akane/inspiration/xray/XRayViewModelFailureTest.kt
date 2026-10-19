package com.akane.inspiration.xray

import com.akane.inspiration.ipc.toolkit.StandbyBucket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val CHATTY_UID = 10_002

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
    fun `a write whose audit row cannot be stored says it failed instead of crashing`() {
        vm.onIntent(XRayIntent.OpenApp(CHATTY_UID))
        store.insertFailure = diskFull()

        vm.onIntent(XRayIntent.Act(ActionRequest.SetBucket(StandbyBucket.WORKING_SET)))

        assertNothingEscaped()
        assertFalse("the failed action must end busy", detail.busy)
        assertEquals(failed, detail.notice)
        assertTrue("the controls show the read-back state again", detail.actions is ActionsState.Loaded)
        assertTrue("nothing was written without its audit row", deps.device.writes.isEmpty())
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
