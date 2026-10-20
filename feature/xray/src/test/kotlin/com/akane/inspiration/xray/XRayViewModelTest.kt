package com.akane.inspiration.xray

import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.ipc.BlockedReason
import com.akane.inspiration.ipc.ShizukuBackend
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.toolkit.ToolkitRefusal
import com.akane.inspiration.telemetry.toolkit.ToolkitWarning
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
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

/** A UID several packages share through `android:sharedUserId`, like the media providers' UID. */
private const val MEDIA_UID = 10_011

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

    private fun start(
        fake: FakeXRayDeps,
        ready: AnalysisState = readyOf(sampleDiff()),
    ) {
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
    fun `an analysis emission resolves labels but loads no icons`() {
        assertEquals(XRayStatus.READY, state.status)
        assertTrue(state.apps.all { it.label != null })
        assertEquals("icons load per composed row, not per emission", 0, deps.iconLoads)
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
    fun `the state counts the apps hidden for lack of a baseline from the report's skipped uids`() {
        assertEquals(0, state.hiddenApps)

        deps.analysis.value = readyOf(sampleDiff(), skippedInvalidUids = 3)
        assertEquals(3, state.hiddenApps)
        assertEquals(listOf(CHATTY_UID, MAPS_UID), state.apps.map { it.uid })

        deps.analysis.value = readyOf(sampleDiff(), resolvedOf(perUidAvailable = false), skippedInvalidUids = 3)
        assertEquals(0, state.hiddenApps)
    }

    @Test
    fun `loading, empty and failed analyses map to their own statuses`() {
        deps.analysis.value = AnalysisState.Empty(ReferenceKind.SinceUnplugged)
        assertEquals(XRayStatus.EMPTY, state.status)
        deps.analysis.value = AnalysisState.Failed("boom", ReferenceKind.SinceUnplugged)
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

        deps.analysis.value = readyOf(sampleDiff(), resolvedOf(1_000L, 2_000L), kind = slice)
        assertEquals(XRayStatus.READY, state.status)
        assertEquals(2_000L, state.slice?.coveredToMs)

        vm.onIntent(XRayIntent.ClearSlice)
        assertNull(state.slice)
    }

    @Test
    fun `a new selection shows loading, not the previous interval's apps, until its analysis arrives`() {
        assertEquals(XRayStatus.READY, state.status)

        // The session has not emitted Loading for the new selection yet: its state still answers the old one.
        deps.selection.select(ReferenceKind.Last24h)
        assertEquals(XRayStatus.LOADING, state.status)

        deps.analysis.value = readyOf(diffOf(uids = listOf(uidDelta(MAPS_UID, MAPS, 5.0))), kind = ReferenceKind.Last24h)
        assertEquals(XRayStatus.READY, state.status)
        assertEquals(listOf(MAPS_UID), state.apps.map { it.uid })
    }

    @Test
    fun `a failed capture is reported once and the last data stays`() =
        runTest {
            start(FakeXRayDeps().apply { refreshResult = RefreshResult.Failed("deadline") })

            assertEquals(XRayEffect.RefreshFailed, vm.effects.first())
            assertEquals(XRayStatus.READY, state.status)
            assertFalse(state.refreshing)
        }

    @Test
    fun `retry on a failed analysis recomputes even when the capture is debounced`() {
        start(FakeXRayDeps().apply { refreshResult = RefreshResult.Debounced(0L) }, AnalysisState.Failed("db closed", ReferenceKind.SinceUnplugged))
        assertEquals(XRayStatus.FAILED, state.status)
        assertEquals("the on-enter refresh does not recompute", 0, deps.recomputeCalls)

        deps.recomputeResult = readyOf(sampleDiff())
        vm.onIntent(XRayIntent.Capture)

        assertTrue(deps.recomputeCalls >= 1)
        assertEquals(2, deps.refreshCalls)
        assertEquals(XRayStatus.READY, state.status)
        assertFalse(state.refreshing)
    }

    @Test
    fun `a capture that brings no new data recomputes the analysis, a refreshed one does not`() {
        deps.refreshResult = RefreshResult.Debounced(0L)
        vm.onIntent(XRayIntent.Capture)
        assertEquals(1, deps.recomputeCalls)

        deps.refreshResult = RefreshResult.Failed("no shizuku")
        vm.onIntent(XRayIntent.Capture)
        assertEquals(2, deps.recomputeCalls)

        deps.refreshResult = RefreshResult.Refreshed(0L)
        vm.onIntent(XRayIntent.Capture)
        assertEquals("a capture's own tick recomputes; no extra request", 2, deps.recomputeCalls)
    }

    @Test
    fun `a capture joining the running refresh recomputes after it`() {
        val gate = CompletableDeferred<Unit>()
        start(
            FakeXRayDeps().apply {
                refreshGate = gate
                refreshResult = RefreshResult.Debounced(0L)
            },
        )

        vm.onIntent(XRayIntent.Capture)
        assertEquals(0, deps.recomputeCalls)
        gate.complete(Unit)

        assertEquals(1, deps.refreshCalls)
        assertEquals(1, deps.recomputeCalls)
    }

    /** The next effect, or null when none is queued (virtual time: the timeout costs nothing). */
    private suspend fun nextEffect(): XRayEffect? = withTimeoutOrNull(1_000L) { vm.effects.first() }

    @Test
    fun `a failure reported while the screen is hidden is not shown when it comes back`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            start(
                FakeXRayDeps().apply {
                    refreshGate = gate
                    refreshResult = RefreshResult.Failed("deadline")
                },
            )

            vm.onIntent(XRayIntent.Hidden)
            gate.complete(Unit) // the capture fails after the user left
            deps.refreshGate = null
            deps.refreshResult = RefreshResult.Refreshed(0L)
            vm.onIntent(XRayIntent.Visible)

            assertNull(nextEffect())
        }

    @Test
    fun `a failure nobody collected is dropped on hidden instead of replaying on return`() =
        runTest {
            start(FakeXRayDeps().apply { refreshResult = RefreshResult.Failed("deadline") }) // queued while visible

            vm.onIntent(XRayIntent.Hidden)
            deps.refreshResult = RefreshResult.Refreshed(0L)
            vm.onIntent(XRayIntent.Visible)

            assertNull(nextEffect())
        }

    @Test
    fun `hidden stops watching the analysis`() {
        vm.onIntent(XRayIntent.Hidden)

        deps.analysis.value = AnalysisState.Empty(ReferenceKind.SinceUnplugged)

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
    fun `an app uid reported without a package resolves its package, label and actions from the uid`() {
        val fake = FakeXRayDeps().apply { packagesByUid[CHATTY_UID] = listOf(CHATTY) }
        start(fake, readyOf(diffOf(uids = listOf(uidDelta(CHATTY_UID, null, 60.0)))))

        val row = state.apps.single()
        assertEquals(CHATTY, row.packageName)
        assertEquals("Label of $CHATTY", row.label)

        openChatty()
        assertTrue(detail.actions is ActionsState.Loaded)
    }

    @Test
    fun `an app uid reported under a process name is named, labelled and acted on as its package`() {
        // batterystats reports the UID's busiest process, e.g. "pkg:push"; the toolkit refuses such a name.
        start(FakeXRayDeps(), readyOf(diffOf(uids = listOf(uidDelta(CHATTY_UID, "$CHATTY:push", 60.0)))))

        val row = state.apps.single()
        assertEquals(CHATTY, row.packageName)
        assertEquals("Label of $CHATTY", row.label)

        openChatty()
        assertTrue(detail.actions is ActionsState.Loaded)
    }

    @Test
    fun `a shared uid is named by the package its reported process belongs to`() {
        val fake =
            FakeXRayDeps().apply {
                packagesByUid[CHATTY_UID] = listOf(CHATTY, "com.example.aardvark")
                packagesByUid[MAPS_UID] = listOf(MAPS, "com.example.aardvark.maps")
            }
        val uids = listOf(uidDelta(CHATTY_UID, "$CHATTY:push", 60.0), uidDelta(MAPS_UID, "$MAPS.persistent", 20.0))
        start(fake, readyOf(diffOf(uids = uids)))

        assertEquals(CHATTY, state.apps.first { it.uid == CHATTY_UID }.packageName)
        assertEquals(MAPS, state.apps.first { it.uid == MAPS_UID }.packageName)
        assertEquals("Label of $MAPS", state.apps.first { it.uid == MAPS_UID }.label)
        assertEquals("a named app is not labelled as shared", listOf(0, 0), state.apps.map { it.sharedBy })
    }

    @Test
    fun `a shared uid whose reported process names none of its apps is named and acted on as none of them`() {
        // The media UID on the redroid device: its drain is reported under the process android.process.media.
        val owners = listOf("com.android.providers.media", "com.android.providers.downloads", "com.android.mtp")
        val fake = FakeXRayDeps().apply { packagesByUid[MEDIA_UID] = owners }
        start(fake, readyOf(diffOf(uids = listOf(uidDelta(MEDIA_UID, "android.process.media", 40.0)))))

        val row = state.apps.single()
        assertNull("not the alphabetically first owner (com.android.mtp)", row.packageName)
        assertNull(row.label)
        assertEquals("the row says how many apps share the uid", 3, row.sharedBy)

        vm.onIntent(XRayIntent.OpenApp(MEDIA_UID))
        assertEquals(ActionsState.NoPackage, detail.actions)
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
        assertNull(state.confirmation)
        assertTrue("no read, plan or write for any owner", deps.device.calls.isEmpty())
    }

    @Test
    fun `a shared uid batterystats reports without a process is named and acted on as none of its apps`() {
        val fake = FakeXRayDeps().apply { packagesByUid[MEDIA_UID] = listOf("com.android.mtp", "com.android.providers.media") }
        start(fake, readyOf(diffOf(uids = listOf(uidDelta(MEDIA_UID, null, 40.0)))))

        assertNull(state.apps.single().packageName)
        assertEquals(2, state.apps.single().sharedBy)
        vm.onIntent(XRayIntent.OpenApp(MEDIA_UID))
        assertEquals(ActionsState.NoPackage, detail.actions)
    }

    @Test
    fun `a row is labelled as shared only when several apps run as its uid`() {
        start(FakeXRayDeps(), readyOf(diffOf(uids = listOf(uidDelta(10_003, "com.example.gone:remote", 5.0), uidDelta(1_000, null, 4.0)))))

        assertEquals(listOf(0, 0), state.apps.map { it.sharedBy })
    }

    @Test
    fun `a uid no installed package runs as has no package, label or actions, whatever was reported`() {
        start(FakeXRayDeps(), readyOf(diffOf(uids = listOf(uidDelta(10_003, "com.example.gone:remote", 5.0)))))

        val row = state.apps.single()
        assertNull(row.packageName)
        assertNull(row.label)

        vm.onIntent(XRayIntent.OpenApp(10_003))
        assertEquals(ActionsState.NoPackage, detail.actions)
        assertTrue(deps.device.calls.isEmpty())
    }

    @Test
    fun `a uid that resolves to no package keeps no label and no actions`() {
        start(FakeXRayDeps(), readyOf(diffOf(uids = listOf(uidDelta(1_000, null, 4.0)))))

        assertNull(state.apps.single().packageName)
        assertNull(state.apps.single().label)
    }

    @Test
    fun `well-known system uids have readable names, in every user, and app uids do not`() {
        assertEquals(R.string.xray_uid_root, systemUidLabelRes(0))
        assertEquals(R.string.xray_uid_system, systemUidLabelRes(1_000))
        assertEquals(R.string.xray_uid_phone, systemUidLabelRes(1_001))
        assertEquals(R.string.xray_uid_bluetooth, systemUidLabelRes(1_002))
        assertEquals(R.string.xray_uid_wifi, systemUidLabelRes(1_010))
        assertEquals(R.string.xray_uid_media, systemUidLabelRes(1_013))
        assertEquals(R.string.xray_uid_system, systemUidLabelRes(1_001_000))
        assertNull(systemUidLabelRes(CHATTY_UID))
        assertNull(systemUidLabelRes(1_999))
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
    fun `a plan still running when the screen is hidden never opens a confirmation`() {
        openChatty()
        val gate = CompletableDeferred<Unit>()
        deps.device.gate = gate

        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
        assertTrue(detail.busy)

        vm.onIntent(XRayIntent.Hidden)
        assertFalse(detail.busy)
        gate.complete(Unit) // the slow read answers after the user left
        deps.device.gate = null
        vm.onIntent(XRayIntent.Visible)
        vm.onIntent(XRayIntent.ConfirmPending)

        assertNull(state.confirmation)
        assertFalse(detail.busy)
        assertTrue(deps.device.writes.isEmpty())
    }

    @Test
    fun `a plan still running when the sheet is closed never opens a confirmation`() {
        openChatty()
        val gate = CompletableDeferred<Unit>()
        deps.device.gate = gate

        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
        vm.onIntent(XRayIntent.CloseApp)
        gate.complete(Unit)
        vm.onIntent(XRayIntent.ConfirmPending)

        assertNull(state.confirmation)
        assertNull(state.detail)
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
    fun `allowing an externally restricted app keeps an override that restores the original block`() {
        deps.device.runAnyInBackground = "ignore"
        openChatty()
        assertTrue(controls.restricted)
        vm.onIntent(XRayIntent.Act(ActionRequest.SetRestricted(false)))
        assertFalse(controls.restricted)
        val override = detail.overrides.single()
        assertTrue(override.restorable)
        vm.onIntent(XRayIntent.Act(ActionRequest.Restore(override.actionId)))
        vm.onIntent(XRayIntent.ConfirmPending)
        assertEquals("ignore", deps.device.runAnyInBackground)
        assertTrue(controls.restricted)
        assertTrue(detail.overrides.isEmpty())
    }

    @Test
    fun `a live default mode is shown restricted and allowing it writes allow`() {
        deps.device.runAnyInBackground = "default" // left by an older release; Android enforces it as restricted
        openChatty()
        assertTrue(controls.restricted)

        vm.onIntent(XRayIntent.Act(ActionRequest.SetRestricted(false)))

        assertEquals("allow", deps.device.runAnyInBackground)
        assertFalse(controls.restricted)
    }

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
    fun `a change that can no longer be read back stays listed for this app as unverified`() {
        openChatty()
        vm.onIntent(XRayIntent.Act(ActionRequest.SetRestricted(true)))
        vm.onIntent(XRayIntent.ConfirmPending)
        // The app can no longer be read at all (its updates were uninstalled: a pure system app).
        deps.device.refuseWith = ToolkitCode.DENY_SYSTEM

        vm.onIntent(XRayIntent.ReloadActions)

        assertEquals(ActionsState.Unavailable(Problem.Refused(ToolkitRefusal.PROTECTED_APP)), detail.actions)
        val override = detail.overrides.single()
        assertEquals(ToolkitRefusal.PROTECTED_APP, override.unverified)
        assertEquals("ignore", deps.device.runAnyInBackground)
    }

    /** Restricts Chatty from its sheet and returns the audit row of that change. */
    private fun restrictChatty(): Long {
        openChatty()
        vm.onIntent(XRayIntent.Act(ActionRequest.SetRestricted(true)))
        vm.onIntent(XRayIntent.ConfirmPending)
        assertEquals(ActionNotice.Applied(Change.Restricted), detail.notice)
        return detail.overrides.single().actionId
    }

    // B-06: the undo of our restriction is the one write a protected app takes; its sheet cannot read the app.
    @Test
    fun `a restriction of ours on an app that became protected is restored from its sheet`() {
        val id = restrictChatty()
        deps.device.protectedApp = true // it became the default SMS app, say
        vm.onIntent(XRayIntent.ReloadActions)
        assertEquals(ActionsState.Unavailable(Problem.Refused(ToolkitRefusal.PROTECTED_APP)), detail.actions)
        assertTrue("the override is offered with an enabled Restore", detail.overrides.single().restorable && !detail.busy)

        vm.onIntent(XRayIntent.Act(ActionRequest.Restore(id)))

        assertNull(state.confirmation)
        assertEquals(listOf("setAppOp", "setAppOp"), deps.device.writes)
        assertEquals("allow", deps.device.runAnyInBackground)
        assertEquals("the restore's own result replaces the old one", ActionNotice.Applied(Change.Restored), detail.notice)
        assertTrue(detail.overrides.isEmpty())
        assertEquals(ActionsState.Unavailable(Problem.Refused(ToolkitRefusal.PROTECTED_APP)), detail.actions)
        assertFalse(detail.busy)
    }

    @Test
    fun `a restore tapped while the controls are still loading runs and the controls show its read-back`() {
        val id = restrictChatty()
        val gate = CompletableDeferred<Unit>()
        deps.device.gate = gate
        vm.onIntent(XRayIntent.ReloadActions)
        assertEquals(ActionsState.Loading, detail.actions)

        vm.onIntent(XRayIntent.Act(ActionRequest.Restore(id)))
        assertTrue("the restore was taken", detail.busy)
        assertNull("the previous result is not left standing", detail.notice)
        deps.device.gate = null
        gate.complete(Unit)

        assertEquals("allow", deps.device.runAnyInBackground)
        assertEquals(ActionNotice.Applied(Change.Restored), detail.notice)
        assertFalse(controls.restricted)
        assertTrue(detail.overrides.isEmpty())
        assertFalse(detail.busy)
    }

    @Test
    fun `a restore tapped after the controls failed to read is answered, not dropped`() {
        val id = restrictChatty()
        deps.device.refuseWith = ToolkitCode.CALLER
        vm.onIntent(XRayIntent.ReloadActions)
        assertEquals(ActionsState.Unavailable(Problem.Refused(ToolkitRefusal.CALLER)), detail.actions)
        assertTrue(detail.overrides.single().restorable)

        vm.onIntent(XRayIntent.Act(ActionRequest.Restore(id)))

        assertEquals("the plan reads the app itself and says why it cannot", ActionNotice.Rejected(Problem.Refused(ToolkitRefusal.CALLER)), detail.notice)
        assertEquals(listOf("setAppOp"), deps.device.writes)
        assertFalse(detail.busy)
    }

    @Test
    fun `an app change is not taken while the controls are not shown`() {
        restrictChatty()
        deps.device.protectedApp = true
        vm.onIntent(XRayIntent.ReloadActions)

        vm.onIntent(XRayIntent.Act(ActionRequest.SetRestricted(false)))
        vm.onIntent(XRayIntent.Act(ActionRequest.SetBucket(StandbyBucket.WORKING_SET)))

        assertNull(state.confirmation)
        assertEquals(listOf("setAppOp"), deps.device.writes)
        assertFalse(detail.busy)
    }

    @Test
    fun `restoring an id that is not one of this app's changes is ignored`() {
        openChatty()

        vm.onIntent(XRayIntent.Act(ActionRequest.Restore(42L)))

        assertTrue(deps.device.writes.isEmpty())
        assertNull(detail.notice)
    }

    // ---- writes in flight (B-12) ------------------------------------------------------------

    /** Confirms a restriction of Chatty whose write then waits, under the toolkit lock, for its audit row. */
    private fun startHeldRestriction(): CompletableDeferred<Unit> {
        openChatty()
        vm.onIntent(XRayIntent.Act(ActionRequest.SetRestricted(true)))
        val insert = CompletableDeferred<Unit>()
        deps.device.store.insertGate = insert
        vm.onIntent(XRayIntent.ConfirmPending)
        assertTrue(detail.busy)
        assertTrue("precondition: nothing written yet", deps.device.writes.isEmpty())
        return insert
    }

    private fun release(insert: CompletableDeferred<Unit>) {
        deps.device.store.insertGate = null
        insert.complete(Unit)
    }

    @Test
    fun `a sheet reopened while its write runs shows it busy and takes no second write`() {
        val insert = startHeldRestriction()

        vm.onIntent(XRayIntent.CloseApp)
        openChatty()
        assertTrue("the controls were read again", detail.actions is ActionsState.Loaded)
        assertTrue("the reopened sheet shows the write still running", detail.busy)
        vm.onIntent(XRayIntent.Act(ActionRequest.SetBucket(StandbyBucket.WORKING_SET)))
        release(insert)

        assertEquals(listOf("setAppOp"), deps.device.writes)
        assertEquals("the write reports on the reopened sheet of its app", ActionNotice.Applied(Change.Restricted), detail.notice)
        assertTrue(controls.restricted)
        assertFalse(detail.busy)
    }

    @Test
    fun `another app's sheet is not held by a write in flight`() {
        val insert = startHeldRestriction()

        vm.onIntent(XRayIntent.OpenApp(MAPS_UID))

        assertFalse(detail.busy)
        release(insert)
        assertEquals(listOf("setAppOp"), deps.device.writes)
        assertNull("Chatty's result is not shown on Maps' sheet", detail.notice)
    }

    @Test
    fun `a controls read started during a write never replaces the write's own read-back`() {
        val insert = startHeldRestriction()
        val lateReply = deps.device.holdNextReply("getAppOps")
        // The screen comes back while the write runs: the sheet is read again, and that read answers late.
        vm.onIntent(XRayIntent.Hidden)
        vm.onIntent(XRayIntent.Visible)

        release(insert)
        assertTrue("precondition: the write's read-back is shown", controls.restricted)
        lateReply.complete(Unit)

        assertTrue("the read from before the write landed is not shown over it", controls.restricted)
        assertEquals(ActionNotice.Applied(Change.Restricted), detail.notice)
        assertFalse(detail.busy)
    }

    @Test
    fun `a controls read started before a restore never replaces the restore's read-back`() {
        val id = restrictChatty()
        val lateReply = deps.device.holdNextReply("getAppOps")
        vm.onIntent(XRayIntent.ReloadActions)
        assertEquals(ActionsState.Loading, detail.actions)

        vm.onIntent(XRayIntent.Act(ActionRequest.Restore(id)))
        assertEquals(ActionNotice.Applied(Change.Restored), detail.notice)
        assertFalse("precondition: the restore's read-back is shown", controls.restricted)
        lateReply.complete(Unit)

        assertFalse("the read from before the restore is not shown over it", controls.restricted)
        assertFalse(detail.busy)
    }

    // ---- Shizuku not ready ------------------------------------------------------------------

    @Test
    fun `without Shizuku the actions are blocked, no call is made and no action runs`() {
        start(FakeXRayDeps(shizuku = ShizukuState.PermissionRequired(ShizukuBackend.SUI)))

        openChatty()
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
        vm.onIntent(XRayIntent.Act(ActionRequest.SetRestricted(true)))

        assertEquals(ActionsState.Blocked(BlockedReason.PERMISSION_REQUIRED, ShizukuBackend.SUI), detail.actions)
        assertNull(state.confirmation)
        assertTrue(deps.device.calls.isEmpty())
        // the breakdown itself is still there
        assertEquals(CHATTY, detail.app.packageName)
        assertEquals(3, detail.app.components.size)
    }

    @Test
    fun `an open sheet unlocks when Shizuku becomes ready and locks when it goes away`() {
        start(FakeXRayDeps(shizuku = ShizukuState.NotRunning))
        openChatty()
        assertEquals("no binder, so no backend", ActionsState.Blocked(BlockedReason.NOT_RUNNING, backend = null), detail.actions)

        deps.shizukuState.value = ShizukuState.Ready(2000, 13)
        assertTrue(detail.actions is ActionsState.Loaded)

        deps.shizukuState.value = ShizukuState.PermissionDenied(ShizukuBackend.SHIZUKU)
        assertEquals(ActionsState.Blocked(BlockedReason.PERMISSION_DENIED, ShizukuBackend.SHIZUKU), detail.actions)
    }

    // SQ-29: a binder names its server; without one, the text names both Shizuku and Sui.
    @Test
    fun `the blocked text names the backend only when a binder is there`() {
        assertEquals(R.string.xray_blocked_title_sui, blockedTitleRes(ShizukuBackend.SUI))
        assertEquals(R.string.xray_blocked_title_shizuku, blockedTitleRes(ShizukuBackend.SHIZUKU))
        assertEquals(R.string.xray_blocked_title, blockedTitleRes(null))
        assertEquals(
            R.string.xray_blocked_permission_required_sui,
            blockedBodyRes(BlockedReason.PERMISSION_REQUIRED, ShizukuBackend.SUI),
        )
        assertEquals(
            R.string.xray_blocked_permission_denied_shizuku,
            blockedBodyRes(BlockedReason.PERMISSION_DENIED, ShizukuBackend.SHIZUKU),
        )
        assertEquals(R.string.xray_blocked_not_running, blockedBodyRes(BlockedReason.NOT_RUNNING, null))
        assertEquals(R.string.xray_blocked_not_installed, blockedBodyRes(BlockedReason.NOT_INSTALLED, null))
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
