package com.akane.inspiration.xray

import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.toolkit.ToolkitRefusal
import com.akane.inspiration.telemetry.toolkit.ToolkitWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val CHATTY_UID = 10_002
private const val MAPS_UID = 10_001

/**
 * The ViewModel against a real [com.akane.inspiration.telemetry.toolkit.ToolkitActions] that talks to a fake
 * device, so plan, confirmation, execute and read-back are the production ones; only the service is faked.
 * Main is an unconfined test dispatcher, so work runs to its next suspension point inside each call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XRayViewModelTest {
    private lateinit var deps: FakeXRayDeps
    private lateinit var vm: XRayViewModel

    private val state get() = vm.state.value
    private val detail get() = state.detail!!
    private val controls get() = (detail.actions as ActionsState.Loaded).controls

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        start(FakeXRayDeps())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun start(fake: FakeXRayDeps, ready: AnalysisState = readyOf(sampleDiff())) {
        deps = fake
        deps.analysis.value = ready
        vm = XRayViewModel(deps)
        vm.onIntent(XRayIntent.Visible)
    }

    private fun openChatty() = vm.onIntent(XRayIntent.OpenApp(CHATTY_UID))

    // ---- analysis ---------------------------------------------------------------------------

    @Test
    fun `visible asks for one capture and shows the ranked apps with their labels`() {
        assertEquals(1, deps.refreshCalls)
        assertEquals(XRayStatus.READY, state.status)
        assertFalse(state.refreshing)
        assertEquals(listOf(CHATTY_UID, MAPS_UID), state.apps.map { it.uid })
        assertEquals("Label of $CHATTY", state.apps.first().label)
        assertEquals("SCREEN", state.components.first().key)
    }

    @Test
    fun `without per-app data the state says so and lists no apps while components stay`() {
        start(FakeXRayDeps(), readyOf(sampleDiff(), resolvedOf(perUidAvailable = false)))

        assertEquals(XRayStatus.READY, state.status)
        assertFalse(state.perUidAvailable)
        assertTrue(state.apps.isEmpty())
        assertTrue(state.components.isNotEmpty())
    }

    @Test
    fun `loading, empty and failed analyses map to their own statuses`() {
        deps.analysis.value = AnalysisState.Empty
        assertEquals(XRayStatus.EMPTY, state.status)
        deps.analysis.value = AnalysisState.Failed("boom")
        assertEquals(XRayStatus.FAILED, state.status)
        deps.analysis.value = AnalysisState.Loading
        assertEquals(XRayStatus.LOADING, state.status)
    }

    @Test
    fun `a time slice shows loading until the analysis for that slice arrives`() {
        val slice = ReferenceKind.TimeSlice(1_000L, 2_000L)

        deps.selection.select(slice)
        assertEquals(XRayStatus.LOADING, state.status)
        assertEquals(1_000L, state.slice?.requestedFromMs)

        deps.analysis.value = readyOf(sampleDiff(), resolvedOf(1_000L, 2_000L))
        assertEquals(XRayStatus.READY, state.status)
        assertEquals(2_000L, state.slice?.coveredToMs)

        vm.onIntent(XRayIntent.ClearSlice)
        assertNull(state.slice)
    }

    @Test
    fun `a failed capture is reported once and the last data stays`() = runTest {
        start(FakeXRayDeps().apply { refreshResult = RefreshResult.Failed("deadline") })

        assertEquals(XRayEffect.RefreshFailed, vm.effects.first())
        assertEquals(XRayStatus.READY, state.status)
        assertFalse(state.refreshing)
    }

    @Test
    fun `hidden stops watching the analysis`() {
        vm.onIntent(XRayIntent.Hidden)

        deps.analysis.value = AnalysisState.Empty

        assertEquals(XRayStatus.READY, state.status)
    }

    @Test
    fun `selecting a tab changes only the tab`() {
        vm.onIntent(XRayIntent.SelectTab(XRayTab.APPS))

        assertEquals(XRayTab.APPS, state.tab)
        assertEquals(XRayStatus.READY, state.status)
    }

    // ---- detail and read-back ---------------------------------------------------------------

    @Test
    fun `opening an app reads its settings back from the system`() {
        deps.device.runAnyInBackground = "ignore"
        deps.device.bucket = StandbyBucket.RARE.rawValue

        openChatty()

        assertEquals(CHATTY, detail.app.packageName)
        assertEquals(AppControls(restricted = true, restrictionEditable = true, bucket = StandbyBucket.RARE, bucketRaw = 40), controls)
    }

    @Test
    fun `an app that is not in the list cannot be opened`() {
        vm.onIntent(XRayIntent.OpenApp(99_999))

        assertNull(state.detail)
        assertTrue(deps.device.calls.isEmpty())
    }

    @Test
    fun `an entry without a package has no quick actions and nothing is called`() {
        start(FakeXRayDeps(), readyOf(diffOf(uids = listOf(uidDelta(1_000, null, 4.0)))))

        vm.onIntent(XRayIntent.OpenApp(1_000))
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))

        assertEquals(ActionsState.NoPackage, detail.actions)
        assertTrue(deps.device.calls.isEmpty())
    }

    @Test
    fun `reload shows what the system reports now, not what was last asked`() {
        openChatty()
        assertFalse(controls.restricted)

        deps.device.runAnyInBackground = "ignore" // changed outside the app
        vm.onIntent(XRayIntent.ReloadActions)

        assertTrue(controls.restricted)
    }

    @Test
    fun `a harmless action runs at once and the controls show the read-back state`() {
        openChatty()

        vm.onIntent(XRayIntent.Act(ActionRequest.SetBucket(StandbyBucket.WORKING_SET)))

        assertNull(state.confirmation)
        assertEquals(listOf("setStandbyBucket"), deps.device.writes)
        assertEquals(StandbyBucket.WORKING_SET, controls.bucket)
        assertEquals(ActionNotice.Applied(Change.BucketSet(StandbyBucket.WORKING_SET)), detail.notice)
        assertFalse(detail.busy)
    }

    // ---- plan, confirm, execute -------------------------------------------------------------

    @Test
    fun `restricting background use asks first, writes nothing until confirmed, then reads back`() {
        openChatty()

        vm.onIntent(XRayIntent.Act(ActionRequest.SetRestricted(true)))

        val confirmation = state.confirmation!!
        assertEquals(ActionRequest.SetRestricted(true), confirmation.request)
        assertEquals("Label of $CHATTY", confirmation.target)
        assertEquals(listOf(ToolkitWarning.RUN_ANY_IN_BACKGROUND_IGNORE), confirmation.warnings.toList())
        assertTrue(deps.device.writes.isEmpty())
        assertFalse(controls.restricted)

        vm.onIntent(XRayIntent.ConfirmPending)

        assertNull(state.confirmation)
        assertEquals(listOf("setAppOp"), deps.device.writes)
        assertEquals("ignore", deps.device.runAnyInBackground)
        assertTrue(controls.restricted)
        assertEquals(ActionNotice.Applied(Change.Restricted), detail.notice)
        assertFalse(detail.busy)
    }

    @Test
    fun `dismissing the confirmation changes nothing`() {
        openChatty()
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
        assertEquals(listOf(ToolkitWarning.FORCE_STOP), state.confirmation!!.warnings.toList())

        vm.onIntent(XRayIntent.DismissConfirmation)
        vm.onIntent(XRayIntent.ConfirmPending)

        assertNull(state.confirmation)
        assertTrue(deps.device.writes.isEmpty())
    }

    @Test
    fun `force stop runs only after the confirmation`() {
        openChatty()
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
        assertTrue(deps.device.writes.isEmpty())

        vm.onIntent(XRayIntent.ConfirmPending)

        assertEquals(listOf("forceStop"), deps.device.writes)
        assertEquals(ActionNotice.Applied(Change.ForceStopped), detail.notice)
    }

    @Test
    fun `a refused plan shows the reason and nothing is written`() {
        openChatty()
        deps.device.refuseWith = ToolkitCode.DENY_SYSTEM

        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))

        assertNull(state.confirmation)
        assertTrue(deps.device.writes.isEmpty())
        assertEquals(ActionNotice.Rejected(Problem.Refused(ToolkitRefusal.PROTECTED_APP)), detail.notice)
        assertFalse(detail.busy)
    }

    @Test
    fun `a write the service refuses shows the reason and the controls keep the system state`() {
        openChatty()
        deps.device.refuseWritesWith = ToolkitCode.DENY_PROTECTED

        vm.onIntent(XRayIntent.Act(ActionRequest.SetBucket(StandbyBucket.FREQUENT)))

        assertEquals(listOf("setStandbyBucket"), deps.device.writes)
        assertEquals(ActionNotice.Rejected(Problem.Refused(ToolkitRefusal.PROTECTED_APP)), detail.notice)
        assertEquals(StandbyBucket.ACTIVE, controls.bucket)
        assertFalse(detail.busy)
    }

    @Test
    fun `hidden drops a pending confirmation so it cannot be accepted later`() {
        openChatty()
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
        assertNotNull(state.confirmation)

        vm.onIntent(XRayIntent.Hidden)
        vm.onIntent(XRayIntent.ConfirmPending)

        assertNull(state.confirmation)
        assertTrue(deps.device.writes.isEmpty())
    }

    @Test
    fun `an action while another confirmation is open is ignored`() {
        openChatty()
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))

        vm.onIntent(XRayIntent.Act(ActionRequest.SetBucket(StandbyBucket.RARE)))

        assertEquals(ActionRequest.ForceStop, state.confirmation!!.request)
        assertTrue(deps.device.writes.isEmpty())
    }

    // ---- restore ----------------------------------------------------------------------------

    @Test
    fun `a change made here is listed for this app and can be restored`() {
        openChatty()
        vm.onIntent(XRayIntent.Act(ActionRequest.SetRestricted(true)))
        vm.onIntent(XRayIntent.ConfirmPending)
        val override = detail.overrides.single()
        assertTrue(override.restorable)

        vm.onIntent(XRayIntent.Act(ActionRequest.Restore(override.actionId)))

        assertEquals("allow", deps.device.runAnyInBackground)
        assertFalse(controls.restricted)
        assertTrue(detail.overrides.isEmpty())
        assertEquals(ActionNotice.Applied(Change.Restored), detail.notice)
    }

    @Test
    fun `restoring an id that is not one of this app's changes is ignored`() {
        openChatty()

        vm.onIntent(XRayIntent.Act(ActionRequest.Restore(42L)))

        assertTrue(deps.device.writes.isEmpty())
        assertNull(detail.notice)
    }

    // ---- Shizuku not ready ------------------------------------------------------------------

    @Test
    fun `without Shizuku the actions are blocked, no call is made and no action runs`() {
        start(FakeXRayDeps(shizuku = ShizukuState.PermissionRequired))

        openChatty()
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
        vm.onIntent(XRayIntent.Act(ActionRequest.SetRestricted(true)))

        assertEquals(ActionsState.Blocked(BlockedReason.PERMISSION_REQUIRED), detail.actions)
        assertNull(state.confirmation)
        assertTrue(deps.device.calls.isEmpty())
        // the breakdown itself is still there
        assertEquals(CHATTY, detail.app.packageName)
        assertEquals(3, detail.app.components.size)
    }

    @Test
    fun `each Shizuku state that is not ready has its own blocked reason`() {
        assertEquals(BlockedReason.NOT_INSTALLED, ShizukuState.NotInstalled.blockedReason())
        assertEquals(BlockedReason.NOT_RUNNING, ShizukuState.NotRunning.blockedReason())
        assertEquals(BlockedReason.PERMISSION_DENIED, ShizukuState.PermissionDenied.blockedReason())
        assertNull(ShizukuState.Ready(2000, 13).blockedReason())
    }

    @Test
    fun `an open sheet unlocks when Shizuku becomes ready and locks when it goes away`() {
        start(FakeXRayDeps(shizuku = ShizukuState.NotRunning))
        openChatty()
        assertEquals(ActionsState.Blocked(BlockedReason.NOT_RUNNING), detail.actions)

        deps.shizukuState.value = ShizukuState.Ready(2000, 13)
        assertTrue(detail.actions is ActionsState.Loaded)

        deps.shizukuState.value = ShizukuState.PermissionDenied
        assertEquals(ActionsState.Blocked(BlockedReason.PERMISSION_DENIED), detail.actions)
    }

    @Test
    fun `a service that cannot answer shows a retry state instead of controls`() {
        deps.device.refuseWith = ToolkitCode.CALLER

        openChatty()

        assertEquals(ActionsState.Unavailable(Problem.Refused(ToolkitRefusal.CALLER)), detail.actions)
    }

    @Test
    fun `closing the sheet clears the detail and any confirmation`() {
        openChatty()
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))

        vm.onIntent(XRayIntent.CloseApp)

        assertNull(state.detail)
        assertNull(state.confirmation)
    }
}
