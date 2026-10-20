package com.akane.inspiration.toolkit

import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.toolkit.BgDexoptStatus
import com.akane.inspiration.telemetry.toolkit.ToolkitUnavailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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

/**
 * A Room error inside `ToolkitActions` (a full disk while the write-ahead PENDING row is inserted, an audit log
 * that cannot be read) is a RuntimeException that propagates out of the call. On Android an exception nothing
 * catches in a `viewModelScope` launch reaches the thread's default handler and kills the process, so every
 * Toolkit launch must contain it: the screen says the action failed, ends `busy` and stays usable.
 *
 * The thread's uncaught-exception handlers are replaced by a recorder, so a launch that lets an exception escape
 * fails the test instead of passing silently.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolkitViewModelFailureTest {
    private val scheduler = TestCoroutineScheduler()
    private val toolkitScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private lateinit var deps: FakeToolkitDeps
    private lateinit var vm: ToolkitViewModel

    private val uncaught = mutableListOf<Throwable>()
    private val recorder = Thread.UncaughtExceptionHandler { _, e -> uncaught += e }
    private var previousDefault: Thread.UncaughtExceptionHandler? = null
    private var previousOwn: Thread.UncaughtExceptionHandler? = null

    private val state get() = vm.state.value
    private val device get() = deps.device
    private val detail get() = state.detail!!

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        previousDefault = Thread.getDefaultUncaughtExceptionHandler()
        previousOwn = Thread.currentThread().uncaughtExceptionHandler
        Thread.setDefaultUncaughtExceptionHandler(recorder)
        Thread.currentThread().uncaughtExceptionHandler = recorder
    }

    @After
    fun tearDown() {
        Thread.currentThread().uncaughtExceptionHandler = previousOwn
        Thread.setDefaultUncaughtExceptionHandler(previousDefault)
        toolkitScope.cancel()
        Dispatchers.resetMain()
    }

    private fun start() {
        deps = FakeToolkitDeps(scope = toolkitScope)
        vm = ToolkitViewModel(deps)
        vm.onIntent(ToolkitIntent.Visible)
    }

    private fun act(request: ToolkitRequest) = vm.onIntent(ToolkitIntent.Act(request))

    private fun confirm() = vm.onIntent(ToolkitIntent.ConfirmPending)

    /** The next audit insert fails like Room does on a full disk (`SQLiteFullException` is a RuntimeException). */
    private fun failInserts() {
        device.store.insertGate = CompletableDeferred<Unit>().apply { completeExceptionally(diskFull()) }
    }

    private fun diskFull() = RuntimeException("SQLiteFullException: database or disk is full (code 13)")

    private fun assertNothingEscaped() = assertTrue("an exception reached the thread's handler: $uncaught", uncaught.isEmpty())

    @Test
    fun `a write whose write-ahead audit row cannot be stored says it failed instead of crashing`() {
        start()
        vm.onIntent(ToolkitIntent.OpenApp(CHATTY))
        assertEquals("precondition: the sheet is open", CHATTY, detail.app.packageName)
        failInserts()

        act(ToolkitRequest.SetBucket(StandbyBucket.RARE))

        assertNothingEscaped()
        assertFalse("the failed action must end busy", state.busy)
        // Nothing was sent (the row is stored before the call), so "the command failed" is true.
        assertEquals(ToolkitNotice.Rejected(Problem.Failed(code = null)), detail.notice)
        assertTrue("the controls show the read-back state again", detail.controls is ControlsState.Loaded)
        assertTrue("nothing was written without its audit row", device.writes.isEmpty())
    }

    @Test
    fun `a write sent before its audit row could be settled says it could not be confirmed`() {
        start()
        vm.onIntent(ToolkitIntent.OpenApp(CHATTY))
        device.store.updateFailure = diskFull()

        act(ToolkitRequest.SetBucket(StandbyBucket.RARE))

        assertNothingEscaped()
        assertFalse(state.busy)
        // R-05: the device changed, so this is "sent, but couldn't be confirmed", never "the command failed".
        assertEquals(listOf("setStandbyBucket"), device.writes)
        assertEquals(ToolkitNotice.Rejected(Problem.Unavailable(ToolkitUnavailable.UNVERIFIED)), detail.notice)
        assertTrue("the controls show the read-back state", detail.controls is ControlsState.Loaded)
    }

    @Test
    fun `a stale refusal whose audit row cannot be settled is still asked again`() {
        start()
        device.dozeStaleOnce = true
        device.store.updateFailure = diskFull()

        act(ToolkitRequest.ApplyDoze(DozeProfile.BALANCED))

        assertNothingEscaped()
        assertTrue("the user decides again with the value there now", state.confirmation?.stale == true)
        assertEquals("idle_to=1", (state.doze.load as DozeLoad.Loaded).info.raw)
        assertFalse(state.busy)
    }

    @Test
    fun `a confirmed write that throws says it failed and the screen takes the next action`() {
        start()
        vm.onIntent(ToolkitIntent.OpenApp(CHATTY))
        act(ToolkitRequest.ForceStop)
        failInserts()

        confirm()

        assertNothingEscaped()
        assertFalse(state.busy)
        assertEquals(ToolkitNotice.Rejected(Problem.Failed(code = null)), detail.notice)

        device.store.insertGate = null
        act(ToolkitRequest.SetBucket(StandbyBucket.WORKING_SET))
        assertEquals(ToolkitNotice.Applied(Change.BucketSet(StandbyBucket.WORKING_SET)), detail.notice)
    }

    @Test
    fun `a restore whose plan cannot read the audit log says it failed`() {
        start()
        vm.onIntent(ToolkitIntent.OpenApp(CHATTY))
        act(ToolkitRequest.SetAppOp(AppOp.RUN_ANY_IN_BACKGROUND, ignored = true))
        confirm()
        vm.onIntent(ToolkitIntent.CloseApp)
        val override = state.overrides.single()
        device.store.readFailure = diskFull()

        act(ToolkitRequest.Restore(override.actionId))

        assertNothingEscaped()
        assertFalse(state.busy)
        assertEquals(ToolkitNotice.Rejected(Problem.Failed(code = null)), state.overridesNotice)
    }

    @Test
    fun `a reset all that throws frees the screen and says it failed`() {
        start()
        vm.onIntent(ToolkitIntent.OpenApp(MAPS))
        act(ToolkitRequest.SetBucket(StandbyBucket.RARE))
        vm.onIntent(ToolkitIntent.CloseApp)
        act(ToolkitRequest.ResetAll)
        device.store.readFailure = diskFull() // the overrides to reset cannot be read: nothing is attempted

        confirm()

        assertNothingEscaped()
        assertFalse(state.busy)
        assertNull(state.resetReport)
        assertEquals(ToolkitNotice.Rejected(Problem.Failed(code = null)), state.overridesNotice)
    }

    @Test
    fun `a reset all item whose audit row cannot be stored is reported not restored, the others restored`() {
        start()
        vm.onIntent(ToolkitIntent.OpenApp(MAPS))
        act(ToolkitRequest.SetBucket(StandbyBucket.RARE))
        vm.onIntent(ToolkitIntent.OpenApp(CHATTY))
        act(ToolkitRequest.SetBucket(StandbyBucket.RARE))
        vm.onIntent(ToolkitIntent.CloseApp)
        act(ToolkitRequest.ResetAll)
        // The first item restores; the write-ahead row of every later one cannot be stored.
        var inserts = 0
        device.store.beforeInsert = { if (inserts++ > 0) throw diskFull() }
        val sent = device.writes.size

        confirm()

        assertNothingEscaped()
        assertFalse(state.busy)
        val results = state.resetReport!!.lines.map { it.result }
        assertEquals(2, results.size)
        assertTrue("the item restored before the failure is reported restored: $results", ResetResult.Restored in results)
        assertTrue(ResetResult.NotRestored(Problem.Failed(code = null)) in results)
        assertEquals("only the item whose row was stored was written", 1, device.writes.size - sent)
    }

    @Test
    fun `a dexopt start whose audit row cannot be stored says it failed`() {
        start()
        assertEquals("precondition: no job runs", BgDexoptStatus.Idle, state.dexopt.status)
        failInserts()

        vm.onIntent(ToolkitIntent.StartDexopt)

        assertNothingEscaped()
        assertFalse(state.dexopt.busy)
        assertEquals(ToolkitNotice.Rejected(Problem.Failed(code = null)), state.dexopt.notice)
        assertEquals(0, device.holds)
    }

    @Test
    fun `an audit log that cannot be read leaves the rest of the screen working`() {
        start()
        device.store.readFailure = diskFull()

        vm.onIntent(ToolkitIntent.Reload) // every read starts again, now against the failing log

        assertNothingEscaped()
        assertEquals(ToolkitPhase.READY, state.status)
        assertEquals(4, state.apps.total)
        vm.onIntent(ToolkitIntent.OpenApp(CHATTY))
        assertTrue("an app's settings are read from the system, not the log", detail.controls is ControlsState.Loaded)

        act(ToolkitRequest.SetBucket(StandbyBucket.WORKING_SET)) // a write needs the log

        assertNothingEscaped()
        assertFalse(state.busy)
        assertEquals(ToolkitNotice.Rejected(Problem.Failed(code = null)), detail.notice)
    }
}
