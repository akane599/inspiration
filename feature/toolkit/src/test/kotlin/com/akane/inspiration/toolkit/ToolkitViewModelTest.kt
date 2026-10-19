package com.akane.inspiration.toolkit

import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.app.AppEntry
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import com.akane.inspiration.telemetry.toolkit.BgDexoptCancelReason
import com.akane.inspiration.telemetry.toolkit.BgDexoptStatus
import com.akane.inspiration.telemetry.toolkit.ToolkitAuditLog
import com.akane.inspiration.telemetry.toolkit.ToolkitRefusal
import com.akane.inspiration.telemetry.toolkit.ToolkitUnavailable
import com.akane.inspiration.telemetry.toolkit.ToolkitWarning
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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
import java.util.Locale
import com.akane.inspiration.ipc.model.BgDexoptStatus as ServiceDexopt

/**
 * The ViewModel against a real [com.akane.inspiration.telemetry.toolkit.ToolkitActions] that talks to a fake
 * device, so plan, confirmation, execute, read-back and the audit log are the production ones; only the
 * service is faked. Main is an unconfined test dispatcher on [scheduler], so work runs to its next suspension
 * point inside each call and time only moves when a test moves it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolkitViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val toolkitScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private lateinit var deps: FakeToolkitDeps
    private lateinit var vm: ToolkitViewModel

    private val state get() = vm.state.value
    private val device get() = deps.device
    private val detail get() = state.detail!!
    private val controls get() = (detail.controls as ControlsState.Loaded).controls
    private val dozeInfo get() = (state.doze.load as DozeLoad.Loaded).info

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        toolkitScope.cancel()
        Dispatchers.resetMain()
    }

    private fun start(
        shizuku: ShizukuState = ShizukuState.Ready(2000, 13),
        locale: Locale = Locale.ENGLISH,
        prepare: FakeToolkitDeps.() -> Unit = {},
    ) {
        deps = FakeToolkitDeps(shizuku = shizuku, scope = toolkitScope).apply(prepare)
        vm = ToolkitViewModel(deps, locale = { locale })
        vm.onIntent(ToolkitIntent.Visible)
    }

    private fun act(request: ToolkitRequest) = vm.onIntent(ToolkitIntent.Act(request))

    private fun open(pkg: String) = vm.onIntent(ToolkitIntent.OpenApp(pkg))

    private fun confirm() = vm.onIntent(ToolkitIntent.ConfirmPending)

    /** Restricts [pkg]'s background activity through the screen: open, ask, confirm, close. */
    private fun restrict(pkg: String) {
        open(pkg)
        act(ToolkitRequest.SetAppOp(AppOp.RUN_ANY_IN_BACKGROUND, ignored = true))
        confirm()
        vm.onIntent(ToolkitIntent.CloseApp)
    }

    // ---- blocked and loading ----------------------------------------------------------------

    @Test
    fun `without permission the screen is blocked, offers Grant and reads nothing until Ready`() {
        start(shizuku = ShizukuState.PermissionRequired)

        assertEquals(ToolkitPhase.BLOCKED, state.status)
        assertEquals(BlockedReason.PERMISSION_REQUIRED, state.blocked)
        assertTrue(device.calls.isEmpty())
        assertEquals(DozeLoad.Loading, state.doze.load)

        vm.onIntent(ToolkitIntent.Grant)
        assertEquals(1, deps.grantRequests)

        deps.shizukuState.value = ShizukuState.Ready(2000, 13)
        assertEquals(ToolkitPhase.READY, state.status)
        assertNull(state.blocked)
        assertTrue(state.doze.load is DozeLoad.Loaded)
        assertEquals(4, state.apps.total)
    }

    @Test
    fun `Grant only asks while the permission is still undecided`() {
        start(shizuku = ShizukuState.NotRunning)

        vm.onIntent(ToolkitIntent.Grant)

        assertEquals(BlockedReason.NOT_RUNNING, state.blocked)
        assertEquals(0, deps.grantRequests)
    }

    @Test
    fun `a Shizuku server too old for the service blocks the screen and offers no Grant`() {
        start(shizuku = ShizukuState.UnsupportedServer(12))

        vm.onIntent(ToolkitIntent.Grant)

        assertEquals(ToolkitPhase.BLOCKED, state.status)
        assertEquals(BlockedReason.UNSUPPORTED_SERVER, state.blocked)
        assertFalse(BlockedReason.UNSUPPORTED_SERVER.canGrant)
        assertEquals(0, deps.grantRequests)
        assertTrue(device.calls.isEmpty())
    }

    @Test
    fun `Shizuku going away blocks the screen and closes an open app and its confirmation`() {
        start()
        open(CHATTY)
        act(ToolkitRequest.ForceStop)
        assertNotNull(state.confirmation)

        deps.shizukuState.value = ShizukuState.NotRunning

        assertEquals(ToolkitPhase.BLOCKED, state.status)
        assertNull(state.detail)
        assertNull(state.confirmation)
        confirm()
        assertTrue(device.writes.isEmpty())
    }

    @Test
    fun `inflight retry cannot overwrite blocked access state`() {
        start { appsFailure = true }
        val gate = CompletableDeferred<Unit>()
        device.gate = gate
        vm.onIntent(ToolkitIntent.Reload)
        deps.shizukuState.value = ShizukuState.NotRunning
        device.serviceUp = false
        gate.complete(Unit)
        assertEquals(ToolkitPhase.BLOCKED, state.status)
        assertEquals(BlockedReason.NOT_RUNNING, state.blocked)
    }

    @Test
    fun `an unreachable service shows the error state and Reload recovers`() {
        start { device.serviceUp = false }

        assertEquals(ToolkitPhase.FAILED, state.status)
        assertEquals(DozeLoad.Unavailable(Problem.Unavailable(ToolkitUnavailable.NO_SERVICE)), state.doze.load)

        device.serviceUp = true
        vm.onIntent(ToolkitIntent.Reload)

        assertEquals(ToolkitPhase.READY, state.status)
        assertTrue(state.doze.load is DozeLoad.Loaded)
        assertEquals(4, state.apps.total)
    }

    @Test
    fun `a package manager failure fails only the app list`() {
        start { appsFailure = true }

        assertEquals(ToolkitPhase.READY, state.status)
        assertEquals(AppsPhase.FAILED, state.apps.phase)
        assertTrue(state.doze.load is DozeLoad.Loaded)
    }

    @Test
    fun `no apps is an empty list, not an error`() {
        start { apps = emptyList() }

        assertEquals(AppsPhase.READY, state.apps.phase)
        assertTrue(state.apps.items.isEmpty())
        assertEquals(0, state.apps.total)
    }

    @Test
    fun `hidden clears a confirmation that was waiting`() {
        start()
        open(CHATTY)
        act(ToolkitRequest.ForceStop)
        assertNotNull(state.confirmation)

        vm.onIntent(ToolkitIntent.Hidden)

        assertNull(state.confirmation)
        confirm()
        assertTrue(device.writes.isEmpty())
    }

    @Test
    fun `a confirmation that arrives after Hidden is dropped, not shown when the screen comes back`() {
        start()
        open(CHATTY)
        val gate = CompletableDeferred<Unit>()
        device.gate = gate
        act(ToolkitRequest.ForceStop) // the plan is read from the device and is still in flight
        assertTrue(state.busy)

        vm.onIntent(ToolkitIntent.Hidden)
        gate.complete(Unit) // the plan comes back needing confirmation, after the screen is gone
        vm.onIntent(ToolkitIntent.Visible)

        assertNull(state.confirmation)
        assertFalse(state.busy)
        confirm()
        assertTrue(device.writes.isEmpty())
    }

    @Test
    fun `switching apps invalidates a suspended non-confirming plan`() {
        start()
        open(CHATTY)
        val gate = CompletableDeferred<Unit>()
        device.gate = gate
        act(ToolkitRequest.SetBucket(StandbyBucket.WORKING_SET))
        assertTrue(state.busy)

        vm.onIntent(ToolkitIntent.CloseApp)
        open(MAPS)
        gate.complete(Unit)

        assertEquals(MAPS, detail.app.packageName)
        assertFalse(state.busy)
        assertNull(state.confirmation)
        assertTrue(device.writes.isEmpty())
        assertEquals(StandbyBucket.ACTIVE.rawValue, device.app(CHATTY).bucket)
        assertEquals(StandbyBucket.ACTIVE.rawValue, device.app(MAPS).bucket)
    }

    @Test
    fun `opening another sheet drops a suspended confirming plan`() {
        start()
        open(CHATTY)
        val gate = CompletableDeferred<Unit>()
        device.gate = gate
        act(ToolkitRequest.ForceStop)

        open(MAPS)
        gate.complete(Unit)
        confirm()

        assertNull(state.confirmation)
        assertTrue(device.writes.isEmpty())
    }

    // ---- doze -------------------------------------------------------------------------------

    @Test
    fun `balanced is planned, applied and shown from the read-back`() {
        start()
        assertEquals(DozeProfile.STANDARD, dozeInfo.profile)

        act(ToolkitRequest.ApplyDoze(DozeProfile.BALANCED))

        assertEquals(DozeProfile.BALANCED.constants, device.dozeRaw)
        assertEquals(DozeProfile.BALANCED, dozeInfo.profile)
        assertEquals(600_000L, dozeInfo.effective.first { it.key == "inactive_to" }.ms)
        assertEquals(ToolkitNotice.Applied(Change.DozeApplied(DozeProfile.BALANCED)), state.doze.notice)
        assertFalse(state.busy)
    }

    @Test
    fun `an app tapped while a doze write runs waits and the write reports on its card`() {
        start()
        val gate = CompletableDeferred<Unit>()
        device.store.insertGate = gate

        act(ToolkitRequest.ApplyDoze(DozeProfile.BALANCED))
        assertTrue(state.busy)
        assertEquals(DozeProfile.STANDARD, dozeInfo.profile)

        // N-16: opening the app would hide the write's result; the list waits for it instead.
        open(CHATTY)
        gate.complete(Unit)

        assertEquals(DozeProfile.BALANCED.constants, device.dozeRaw)
        assertEquals(DozeProfile.BALANCED, dozeInfo.profile)
        assertEquals(ToolkitNotice.Applied(Change.DozeApplied(DozeProfile.BALANCED)), state.doze.notice)
        assertNull(state.detail)
        assertNull(state.confirmation)
        assertFalse(state.busy)

        open(CHATTY)
        assertEquals(CHATTY, detail.app.packageName)
    }

    @Test
    fun `an app tapped while a doze plan is pending does not drop the apply`() {
        start()
        val gate = CompletableDeferred<Unit>()
        device.gate = gate

        act(ToolkitRequest.ApplyDoze(DozeProfile.BALANCED))
        assertTrue(state.busy)
        open(CHATTY)
        gate.complete(Unit)

        assertEquals(DozeProfile.BALANCED.constants, device.dozeRaw)
        assertEquals(ToolkitNotice.Applied(Change.DozeApplied(DozeProfile.BALANCED)), state.doze.notice)
        assertNull(state.detail)
        assertFalse(state.busy)
    }

    @Test
    fun `an app tapped while a restore plan is pending does not drop the restore`() {
        start()
        restrict(CHATTY)
        val override = state.overrides.single()
        val gate = CompletableDeferred<Unit>()
        device.gate = gate

        act(ToolkitRequest.Restore(override.actionId))
        assertTrue(state.busy)
        open(MAPS)
        gate.complete(Unit)

        assertEquals("allow", device.app(CHATTY).ops[AppOp.RUN_ANY_IN_BACKGROUND])
        assertTrue(state.overrides.isEmpty())
        assertEquals(ToolkitNotice.Applied(Change.Restored), state.overridesNotice)
        assertNull(state.detail)
        assertFalse(state.busy)
    }

    @Test
    fun `standard over a custom value asks first and writes nothing until confirmed`() {
        start { device.dozeRaw = "idle_to=1" }
        assertNull(dozeInfo.profile)

        act(ToolkitRequest.ApplyDoze(DozeProfile.STANDARD))

        assertEquals(listOf(ToolkitWarning.DOZE_OVER_CUSTOM), state.confirmation?.warnings)
        assertTrue(device.writes.isEmpty())

        vm.onIntent(ToolkitIntent.DismissConfirmation)
        assertNull(state.confirmation)
        assertTrue(device.writes.isEmpty())

        act(ToolkitRequest.ApplyDoze(DozeProfile.STANDARD))
        confirm()

        assertEquals(listOf("applyDozeProfile"), device.writes)
        assertNull(device.dozeRaw)
        assertEquals(DozeProfile.STANDARD, dozeInfo.profile)
        assertTrue(state.doze.notice is ToolkitNotice.Applied)
    }

    @Test
    fun `standard after balanced over a custom value asks first, is listed and reset all brings the custom value back`() {
        start { device.dozeRaw = "idle_to=1" }
        act(ToolkitRequest.ApplyDoze(DozeProfile.BALANCED))
        assertEquals(DozeProfile.BALANCED.constants, device.dozeRaw)

        act(ToolkitRequest.ApplyDoze(DozeProfile.STANDARD))
        assertEquals(listOf(ToolkitWarning.DOZE_OVER_CUSTOM), state.confirmation?.warnings)
        confirm()

        assertNull(device.dozeRaw)
        assertEquals(OverrideWhat.Doze(DozeProfile.STANDARD), state.overrides.single().what)

        act(ToolkitRequest.ResetAll)
        confirm()

        assertEquals("idle_to=1", device.dozeRaw)
        assertTrue(state.overrides.isEmpty())
    }

    @Test
    fun `a doze value changed while the confirmation was open is asked about again, not overwritten`() {
        start { device.dozeRaw = "idle_to=1" }
        act(ToolkitRequest.ApplyDoze(DozeProfile.STANDARD))
        assertEquals(listOf(ToolkitWarning.DOZE_OVER_CUSTOM), state.confirmation?.warnings)

        device.dozeRaw = "idle_to=2" // changed elsewhere while the user was deciding
        confirm()

        assertTrue("nothing may be written over the new value: ${device.writes}", device.writes.isEmpty())
        assertEquals("idle_to=2", device.dozeRaw)
        assertEquals("idle_to=2", dozeInfo.raw)
        val again = state.confirmation
        assertNotNull(again)
        assertTrue(again!!.stale)
        assertFalse(state.busy)

        confirm()

        assertEquals(listOf("applyDozeProfile"), device.writes)
        assertNull(device.dozeRaw)
    }

    @Test
    fun `a doze value changed before the plan is shown and asked about with the current value`() {
        start()
        device.dozeRaw = "idle_to=3" // the screen still shows Standard

        act(ToolkitRequest.ApplyDoze(DozeProfile.BALANCED))

        assertTrue(device.writes.isEmpty())
        assertEquals("idle_to=3", dozeInfo.raw)
        assertTrue(state.confirmation!!.stale)
        confirm()
        assertEquals(DozeProfile.BALANCED.constants, device.dozeRaw)
    }

    @Test
    fun `a stale refusal re-reads the setting and asks again with the new value`() {
        start { device.dozeStaleOnce = true }

        act(ToolkitRequest.ApplyDoze(DozeProfile.BALANCED))

        val confirmation = state.confirmation
        assertNotNull(confirmation)
        assertTrue(confirmation!!.stale)
        assertEquals("idle_to=1", dozeInfo.raw)
        assertNull(state.doze.notice)
        assertFalse(state.busy)
        assertEquals(1, device.writes.size)

        confirm()

        assertEquals(2, device.writes.size)
        assertEquals(DozeProfile.BALANCED, dozeInfo.profile)
        assertTrue(state.doze.notice is ToolkitNotice.Applied)
    }

    @Test
    fun `a doze apply is not asked or written when the re-read after a stale plan fails`() {
        start { device.dozeRaw = "idle_to=1" }
        device.dozeRaw = null // reset elsewhere; the screen still shows the custom value
        // The plan's read finds the change; the re-read that would show it fails (F-18).
        device.failDozeRead = device.count("getDozeState") + 2

        act(ToolkitRequest.ApplyDoze(DozeProfile.BALANCED))

        assertTrue(state.doze.load is DozeLoad.Unavailable)
        assertNull("no confirmation against a value the screen does not show", state.confirmation)
        assertEquals(ToolkitNotice.Rejected(Problem.Refused(ToolkitRefusal.STALE)), state.doze.notice)
        assertFalse(state.busy)
        confirm()
        assertTrue("nothing may be written: ${device.writes}", device.writes.isEmpty())
        assertNull(device.dozeRaw)
    }

    @Test
    fun `a doze confirmation is not carried out once the doze card is no longer loaded`() {
        start { device.dozeRaw = "idle_to=1" }
        act(ToolkitRequest.ApplyDoze(DozeProfile.STANDARD))
        assertEquals(listOf(ToolkitWarning.DOZE_OVER_CUSTOM), state.confirmation?.warnings)
        // a reload whose doze read fails: the card can no longer show the value the user is deciding against
        device.failDozeRead = device.count("getDozeState") + 1
        vm.onIntent(ToolkitIntent.Reload)
        assertTrue(state.doze.load is DozeLoad.Unavailable)
        assertNotNull(state.confirmation)

        confirm()

        assertTrue("nothing may be written: ${device.writes}", device.writes.isEmpty())
        assertEquals("idle_to=1", device.dozeRaw)
        assertNull(state.confirmation)
        assertEquals(ToolkitNotice.Rejected(Problem.Refused(ToolkitRefusal.STALE)), state.doze.notice)
        assertFalse(state.busy)
    }

    @Test
    fun `a profile the system does not take is rolled back and explained`() {
        start { device.dozeNotEffective = true }

        act(ToolkitRequest.ApplyDoze(DozeProfile.AGGRESSIVE_NIGHT))

        assertEquals(ToolkitNotice.Rejected(Problem.Unavailable(ToolkitUnavailable.NOT_EFFECTIVE)), state.doze.notice)
        assertEquals(DozeProfile.STANDARD, dozeInfo.profile)
        assertNull(device.dozeRaw)
    }

    @Test
    fun `an applied profile is an override and restoring it puts the old value back`() {
        start()
        act(ToolkitRequest.ApplyDoze(DozeProfile.AGGRESSIVE_NIGHT))

        val override = state.overrides.single()
        assertEquals(OverrideWhat.Doze(DozeProfile.AGGRESSIVE_NIGHT), override.what)
        assertNull(override.label)

        act(ToolkitRequest.Restore(override.actionId))

        assertNull(device.dozeRaw)
        assertEquals(DozeProfile.STANDARD, dozeInfo.profile)
        assertTrue(state.overrides.isEmpty())
        assertEquals(ToolkitNotice.Applied(Change.Restored), state.overridesNotice)
    }

    // ---- app picker ---------------------------------------------------------------------------

    @Test
    fun `the picker lists restricted apps first and searches label and package`() {
        start()
        // The picker fixture includes Mail, but only an active override makes it restricted.
        restrict(MAIL)

        assertEquals(listOf(MAIL, CALC, CHATTY, MAPS), state.apps.items.map { it.packageName })
        assertTrue(
            state.apps.items
                .first()
                .restricted,
        )

        vm.onIntent(ToolkitIntent.SetQuery("MAI"))
        assertEquals(listOf(MAIL), state.apps.items.map { it.packageName })
        assertEquals(4, state.apps.total)

        vm.onIntent(ToolkitIntent.SetQuery("example.ch"))
        assertEquals(listOf(CHATTY), state.apps.items.map { it.packageName })

        vm.onIntent(ToolkitIntent.SetQuery("nothing like it"))
        assertTrue(state.apps.items.isEmpty())

        vm.onIntent(ToolkitIntent.SetQuery(""))
        assertEquals(4, state.apps.items.size)
    }

    @Test
    fun `restricted flags and unlaunchable restricted apps come from the screen's own override read`() {
        start()
        restrict(MAIL)
        // installedApps() lists launchable packages only and does not read overrides itself (F-19): Mail has no
        // launcher, and a stale flag on an entry is not trusted.
        deps.apps =
            DEFAULT_APPS
                .filter { it.launchable }
                .map { it.copy(restricted = it.packageName == CHATTY) }
        vm.onIntent(ToolkitIntent.Reload)

        assertEquals(listOf(MAIL, CALC, CHATTY, MAPS), state.apps.items.map { it.packageName })
        assertEquals(
            listOf(MAIL),
            state.apps.items
                .filter { it.restricted }
                .map { it.packageName },
        )
        assertEquals(4, state.apps.total)
        assertEquals(
            "Mail",
            state.apps.items
                .first()
                .label,
        )

        // Restoring the override drops the unlaunchable app from the picker again.
        act(ToolkitRequest.Restore(state.overrides.single().actionId))

        assertEquals(listOf(CALC, CHATTY, MAPS), state.apps.items.map { it.packageName })
        assertTrue(state.apps.items.none { it.restricted })
    }

    @Test
    fun `an app sheet left loading by Hidden offers a retry when the next visit fails to load`() {
        start()
        val gate = CompletableDeferred<Unit>()
        device.gate = gate
        open(CHATTY)
        assertEquals(ControlsState.Loading, detail.controls)

        // Hidden cancels the read; the next visit cannot reach the service, so load() stops at the doze read (F-20).
        vm.onIntent(ToolkitIntent.Hidden)
        device.gate = null
        gate.complete(Unit)
        device.serviceUp = false
        vm.onIntent(ToolkitIntent.Visible)

        assertEquals(ToolkitPhase.FAILED, state.status)
        assertTrue(detail.controls is ControlsState.Unavailable)

        device.serviceUp = true
        vm.onIntent(ToolkitIntent.ReloadApp)

        assertTrue(detail.controls is ControlsState.Loaded)
    }

    @Test
    fun `an app sheet left loading by Hidden is read again when the next visit loads`() {
        start()
        val gate = CompletableDeferred<Unit>()
        device.gate = gate
        open(CHATTY)
        vm.onIntent(ToolkitIntent.Hidden)
        device.gate = null
        gate.complete(Unit)

        vm.onIntent(ToolkitIntent.Visible)

        assertEquals(ToolkitPhase.READY, state.status)
        assertTrue(detail.controls is ControlsState.Loaded)
    }

    @Test
    fun `an entry without a label gets one from the label source`() {
        start { apps = listOf(AppEntry("com.example.nolabel", "", launchable = true, restricted = false)) }

        assertEquals(
            "Label of com.example.nolabel",
            state.apps.items
                .single()
                .label,
        )
    }

    @Test
    fun `only an app of the list can be opened`() {
        start()

        open("com.example.not.in.the.list")
        assertNull(state.detail)

        act(ToolkitRequest.SetAppOp(AppOp.RUN_ANY_IN_BACKGROUND, ignored = true))
        assertTrue(device.writes.isEmpty())

        open(CHATTY)
        assertEquals(CHATTY, detail.app.packageName)
    }

    @Test
    fun `opening an app reads every switch and the bucket back from the system`() {
        start {
            device.app(CHATTY).ops[AppOp.WAKE_LOCK] = "ignore"
            device.app(CHATTY).bucket = StandbyBucket.RARE.rawValue
        }

        open(CHATTY)

        assertEquals(listOf(false, false, true), controls.ops.map { it.ignored })
        assertTrue(controls.ops.all { it.editable })
        assertEquals(StandbyBucket.RARE, controls.bucket)
    }

    @Test
    fun `a switch moves only after the system confirms the change`() {
        start()
        open(CHATTY)

        act(ToolkitRequest.SetAppOp(AppOp.RUN_ANY_IN_BACKGROUND, ignored = true))

        assertEquals(listOf(ToolkitWarning.RUN_ANY_IN_BACKGROUND_IGNORE), state.confirmation?.warnings)
        assertFalse(controls.ops.first { it.op == AppOp.RUN_ANY_IN_BACKGROUND }.ignored)
        assertTrue(device.writes.isEmpty())

        confirm()

        assertTrue(controls.ops.first { it.op == AppOp.RUN_ANY_IN_BACKGROUND }.ignored)
        assertEquals("ignore", device.app(CHATTY).ops[AppOp.RUN_ANY_IN_BACKGROUND])
        assertEquals(ToolkitNotice.Applied(Change.AppOpSet(AppOp.RUN_ANY_IN_BACKGROUND, true)), detail.notice)
    }

    @Test
    fun `a refused write leaves the switch where the system has it`() {
        start { device.refuseWritesFor += CHATTY }
        open(CHATTY)

        act(ToolkitRequest.SetAppOp(AppOp.RUN_IN_BACKGROUND, ignored = true))

        assertFalse(controls.ops.first { it.op == AppOp.RUN_IN_BACKGROUND }.ignored)
        assertEquals(ToolkitNotice.Rejected(Problem.Refused(ToolkitRefusal.PROTECTED_APP)), detail.notice)
        assertFalse(state.busy)
    }

    @Test
    fun `a protected app shows why its controls are unavailable`() {
        start { device.protectedPackages += MAPS }

        open(MAPS)

        assertEquals(ControlsState.Unavailable(Problem.Refused(ToolkitRefusal.PROTECTED_APP)), detail.controls)
    }

    @Test
    fun `a restriction on an app that became protected stays listed and reset all lifts it`() =
        runTest(scheduler) {
            start()
            restrict(MAPS)
            // Maps becomes the default keyboard; the screen is left and opened again.
            device.protectedPackages += MAPS
            vm.onIntent(ToolkitIntent.Hidden)
            vm.onIntent(ToolkitIntent.Visible)

            assertEquals(listOf("Maps"), state.overrides.map { it.label })
            assertNull(state.overrides.single().unverified)
            assertTrue(
                state.apps.items
                    .first { it.packageName == MAPS }
                    .restricted,
            )

            act(ToolkitRequest.ResetAll)
            confirm()

            assertEquals(
                ResetResult.Restored,
                state.resetReport!!
                    .lines
                    .single()
                    .result,
            )
            assertEquals("allow", device.app(MAPS).ops[AppOp.RUN_ANY_IN_BACKGROUND])
            assertTrue(state.overrides.isEmpty())
        }

    @Test
    fun `ignoring wakelocks needs a warned confirmation and nothing is written before it`() {
        start()
        open(CHATTY)

        act(ToolkitRequest.SetAppOp(AppOp.WAKE_LOCK, ignored = true))

        assertTrue(ToolkitWarning.WAKE_LOCK_IGNORE in state.confirmation!!.warnings)
        assertEquals("Chatty", state.confirmation?.target)
        assertTrue(device.writes.isEmpty())

        vm.onIntent(ToolkitIntent.DismissConfirmation)
        assertTrue(device.writes.isEmpty())
        assertFalse(controls.ops.first { it.op == AppOp.WAKE_LOCK }.ignored)

        act(ToolkitRequest.SetAppOp(AppOp.WAKE_LOCK, ignored = true))
        confirm()

        assertTrue(controls.ops.first { it.op == AppOp.WAKE_LOCK }.ignored)
        assertEquals(listOf("setAppOp"), device.writes)
    }

    @Test
    fun `the restricted bucket is confirmed and the others are not`() {
        start()
        open(CHATTY)

        act(ToolkitRequest.SetBucket(StandbyBucket.RARE))
        assertNull(state.confirmation)
        assertEquals(StandbyBucket.RARE, controls.bucket)

        act(ToolkitRequest.SetBucket(StandbyBucket.RESTRICTED))
        assertEquals(listOf(ToolkitWarning.RESTRICTED_BUCKET), state.confirmation?.warnings)
        assertEquals(StandbyBucket.RARE, controls.bucket)

        confirm()
        assertEquals(StandbyBucket.RESTRICTED, controls.bucket)
    }

    @Test
    fun `force stop is confirmed before it runs`() {
        start()
        open(CHATTY)

        act(ToolkitRequest.ForceStop)

        assertEquals(listOf(ToolkitWarning.FORCE_STOP), state.confirmation?.warnings)
        assertTrue(device.writes.isEmpty())

        confirm()

        assertEquals(listOf("forceStop"), device.writes)
        assertEquals(ToolkitNotice.Applied(Change.ForceStopped), detail.notice)
    }

    // ---- overrides, restore, history ---------------------------------------------------------

    @Test
    fun `allowing a preexisting restriction is shown as allowed and restore returns ignore`() {
        start { device.app(CHATTY).ops[AppOp.RUN_ANY_IN_BACKGROUND] = "ignore" }
        open(CHATTY)
        act(ToolkitRequest.SetAppOp(AppOp.RUN_ANY_IN_BACKGROUND, ignored = false))
        val override = state.overrides.single()
        assertEquals(OverrideWhat.AppOpIgnored(AppOp.RUN_ANY_IN_BACKGROUND, false), override.what)
        assertEquals("allow", device.app(CHATTY).ops[AppOp.RUN_ANY_IN_BACKGROUND])
        act(ToolkitRequest.Restore(override.actionId))
        confirm()
        assertEquals("ignore", device.app(CHATTY).ops[AppOp.RUN_ANY_IN_BACKGROUND])
    }

    @Test
    fun `a live default mode is shown restricted and allowing it writes allow`() {
        // An older release's "allow" left `default`, which Android still enforces as restricted (F-01).
        start { device.app(CHATTY).ops[AppOp.RUN_ANY_IN_BACKGROUND] = "default" }
        open(CHATTY)
        val op = controls.ops.first { it.op == AppOp.RUN_ANY_IN_BACKGROUND }
        assertTrue(op.ignored)
        assertTrue(op.editable)

        act(ToolkitRequest.SetAppOp(AppOp.RUN_ANY_IN_BACKGROUND, ignored = false))

        assertEquals("allow", device.app(CHATTY).ops[AppOp.RUN_ANY_IN_BACKGROUND])
        assertFalse(controls.ops.first { it.op == AppOp.RUN_ANY_IN_BACKGROUND }.ignored)
    }

    @Test
    fun `an app an older release restored to default is flagged restricted and reset all writes allow`() {
        // 0.2.2: restrict, then its Restore wrote `default`, which Android still enforces as restricted (F-01).
        start {
            val restrict =
                device.store.rows.size + 1L
            device.store.rows +=
                ToolkitActionRecord(
                    id = restrict,
                    startedAt = 100,
                    endedAt = 101,
                    kind = ToolkitAuditLog.KIND_APP_OP,
                    pkg = MAIL,
                    op = AppOp.RUN_ANY_IN_BACKGROUND.argv,
                    param = "ignore",
                    status = ToolkitActionStatus.RESTORED,
                    code = 0,
                    beforeRaw = "default",
                    afterRaw = "ignore",
                )
            device.store.rows +=
                ToolkitActionRecord(
                    id = restrict + 1,
                    startedAt = 200,
                    endedAt = 201,
                    kind = ToolkitAuditLog.KIND_APP_OP,
                    pkg = MAIL,
                    op = AppOp.RUN_ANY_IN_BACKGROUND.argv,
                    param = "default",
                    status = ToolkitActionStatus.DONE,
                    code = 0,
                    beforeRaw = "ignore",
                    afterRaw = "default",
                    restoresId = restrict,
                )
            device.app(MAIL).ops[AppOp.RUN_ANY_IN_BACKGROUND] = "default"
        }

        assertEquals(
            listOf(MAIL),
            state.apps.items
                .filter { it.restricted }
                .map { it.packageName },
        )
        assertEquals(
            MAIL,
            state.apps.items
                .first()
                .packageName,
        )
        val override = state.overrides.single()
        assertEquals(OverrideWhat.AppOpIgnored(AppOp.RUN_ANY_IN_BACKGROUND), override.what)
        assertTrue(override.restorable)

        act(ToolkitRequest.ResetAll)
        confirm()

        assertEquals("allow", device.app(MAIL).ops[AppOp.RUN_ANY_IN_BACKGROUND])
        assertTrue(state.overrides.isEmpty())
        assertTrue(state.apps.items.none { it.restricted })
    }

    @Test
    fun `a restriction is listed as an override and restore puts the old value back`() {
        start()
        restrict(CHATTY)

        val override = state.overrides.single()
        assertEquals("Chatty", override.label)
        assertEquals(OverrideWhat.AppOpIgnored(AppOp.RUN_ANY_IN_BACKGROUND), override.what)
        assertTrue(override.restorable)

        act(ToolkitRequest.Restore(override.actionId))

        assertEquals("allow", device.app(CHATTY).ops[AppOp.RUN_ANY_IN_BACKGROUND])
        assertTrue(state.overrides.isEmpty())
        assertEquals(ToolkitNotice.Applied(Change.Restored), state.overridesNotice)
        assertEquals(
            ToolkitActionStatus.RESTORED,
            device.store.rows
                .first { it.id == override.actionId }
                .status,
        )
    }

    @Test
    fun `the picker sorts labels by the locale's collation, as the installed list does`() {
        // Case-insensitive code-point order would put `Çay` and `Ölçer` after `Zil` (N-20).
        val labels = listOf("Zil", "Çay", "Dergi", "Ölçer", "arşiv")
        start(locale = Locale.forLanguageTag("tr")) {
            apps = labels.mapIndexed { i, label -> AppEntry("com.example.app$i", label, launchable = true, restricted = false) }
        }

        assertEquals(listOf("arşiv", "Çay", "Dergi", "Ölçer", "Zil"), state.apps.items.map { it.label })
    }

    @Test
    fun `restriction changes update picker badges ordering and active search`() {
        start()
        restrict(MAPS)
        assertEquals(
            MAPS,
            state.apps.items
                .first()
                .packageName,
        )
        assertTrue(
            state.apps.items
                .first()
                .restricted,
        )

        vm.onIntent(ToolkitIntent.SetQuery("Maps"))
        val override = state.overrides.single()
        act(ToolkitRequest.Restore(override.actionId))
        assertEquals(listOf(MAPS), state.apps.items.map { it.packageName })
        assertFalse(
            state.apps.items
                .single()
                .restricted,
        )

        vm.onIntent(ToolkitIntent.SetQuery(""))
        assertEquals(
            CALC,
            state.apps.items
                .first()
                .packageName,
        )
        assertFalse(
            state.apps.items
                .first { it.packageName == MAPS }
                .restricted,
        )
    }

    @Test
    fun `only a shown override can be restored`() {
        start()
        restrict(CHATTY)
        val writes = device.writes.size

        act(ToolkitRequest.Restore(9_999L))

        assertEquals(writes, device.writes.size)
        assertEquals(1, state.overrides.size)
    }

    @Test
    fun `switching a restriction back by hand drops it from the overrides`() {
        start()
        restrict(CHATTY)
        open(CHATTY)

        act(ToolkitRequest.SetAppOp(AppOp.RUN_ANY_IN_BACKGROUND, ignored = false))

        assertFalse(controls.ops.first { it.op == AppOp.RUN_ANY_IN_BACKGROUND }.ignored)
        assertTrue(state.overrides.isEmpty())
    }

    @Test
    fun `history lists the newest action first with its decoded meaning and app label`() {
        start()
        act(ToolkitRequest.ApplyDoze(DozeProfile.BALANCED))
        restrict(CHATTY)

        val rows = state.history
        assertEquals(HistoryWhat.AppOpSet(AppOp.RUN_ANY_IN_BACKGROUND, ignored = true), rows[0].what)
        assertEquals("Chatty", rows[0].target)
        assertEquals(ToolkitActionStatus.DONE, rows[0].status)
        assertEquals(HistoryWhat.DozeApplied(DozeProfile.BALANCED), rows[1].what)
        assertNull(rows[1].target)
    }

    // ---- reset all ------------------------------------------------------------------------------

    @Test
    fun `reset all asks first and reports every item, including the one that failed`() =
        runTest(scheduler) {
            start()
            restrict(CHATTY)
            open(MAPS)
            act(ToolkitRequest.SetBucket(StandbyBucket.RARE))
            vm.onIntent(ToolkitIntent.CloseApp)
            assertEquals(2, state.overrides.size)
            // Maps can no longer be read or written at all (its updates were uninstalled: a pure system app).
            device.systemPackages += MAPS
            val writes = device.writes.size

            act(ToolkitRequest.ResetAll)

            assertEquals(2, state.confirmation?.count)
            assertEquals(writes, device.writes.size)

            confirm()

            val report = state.resetReport
            assertNotNull(report)
            assertEquals(2, report!!.lines.size)
            assertEquals(1, report.restored)
            assertEquals(1, report.failed)
            assertEquals(ResetResult.Restored, report.lines.first { it.item.label == "Chatty" }.result)
            assertEquals(
                ResetResult.NotRestored(Problem.Refused(ToolkitRefusal.PROTECTED_APP)),
                report.lines.first { it.item.label == "Maps" }.result,
            )
            assertEquals(ToolkitEffect.ResetFinished(restored = 1, total = 2), vm.effects.first())
            // It may still be in effect, so it stays listed, marked as unverified rather than dropped (N-06).
            assertEquals(listOf("Maps"), state.overrides.map { it.label })
            assertEquals(ToolkitRefusal.PROTECTED_APP, state.overrides.single().unverified)
            assertEquals(StandbyBucket.RARE.rawValue, device.app(MAPS).bucket)
            assertFalse(state.busy)

            vm.onIntent(ToolkitIntent.DismissResetReport)
            assertNull(state.resetReport)
        }

    @Test
    fun `reset all does not restrict again an app the user switched back by hand`() {
        start()
        restrict(CHATTY)
        open(CHATTY)
        act(ToolkitRequest.SetAppOp(AppOp.RUN_ANY_IN_BACKGROUND, ignored = false))
        vm.onIntent(ToolkitIntent.CloseApp)
        act(ToolkitRequest.ApplyDoze(DozeProfile.BALANCED))

        act(ToolkitRequest.ResetAll)
        confirm()

        assertEquals(1, state.resetReport?.lines?.size)
        assertEquals(
            OverrideWhat.Doze(DozeProfile.BALANCED),
            state.resetReport
                ?.lines
                ?.single()
                ?.item
                ?.what,
        )
        assertEquals("allow", device.app(CHATTY).ops[AppOp.RUN_ANY_IN_BACKGROUND])
        assertNull(device.dozeRaw)
    }

    @Test
    fun `reset all with nothing active does nothing`() {
        start()

        act(ToolkitRequest.ResetAll)

        assertNull(state.confirmation)
        assertNull(state.resetReport)
    }

    @Test
    fun `reset all puts a bucket back to its original, not to the value the newest change replaced`() {
        start()
        open(MAPS)
        act(ToolkitRequest.SetBucket(StandbyBucket.RARE))
        act(ToolkitRequest.SetBucket(StandbyBucket.RESTRICTED))
        confirm()
        vm.onIntent(ToolkitIntent.CloseApp)
        assertEquals(StandbyBucket.RESTRICTED.rawValue, device.app(MAPS).bucket)
        assertEquals(1, state.overrides.size)

        act(ToolkitRequest.ResetAll)
        confirm()

        assertEquals(StandbyBucket.ACTIVE.rawValue, device.app(MAPS).bucket)
        assertEquals(
            ResetResult.Restored,
            state.resetReport
                ?.lines
                ?.single()
                ?.result,
        )
        assertTrue(state.overrides.isEmpty())
    }

    @Test
    fun `a bucket moved and moved back leaves no override to restore`() {
        start()
        open(MAPS)
        act(ToolkitRequest.SetBucket(StandbyBucket.RARE))
        act(ToolkitRequest.SetBucket(StandbyBucket.ACTIVE))
        vm.onIntent(ToolkitIntent.CloseApp)

        assertTrue(state.overrides.isEmpty())
        act(ToolkitRequest.ResetAll)
        assertNull(state.confirmation)
    }

    @Test
    fun `a reset that finishes while the screen is hidden raises no effect and none replays on return`() =
        runTest(scheduler) {
            start()
            restrict(CHATTY)
            act(ToolkitRequest.ResetAll)
            val gate = CompletableDeferred<Unit>()
            device.gate = gate
            confirm()

            vm.onIntent(ToolkitIntent.Hidden)
            gate.complete(Unit)
            vm.onIntent(ToolkitIntent.Visible)

            assertEquals(1, state.resetReport?.restored)
            assertNull(withTimeoutOrNull(1_000) { vm.effects.first() })
        }

    @Test
    fun `an effect nobody collected before Hidden is not replayed on the next visit`() =
        runTest(scheduler) {
            start()
            restrict(CHATTY)
            act(ToolkitRequest.ResetAll)
            confirm() // the effect is queued; no collector is running

            vm.onIntent(ToolkitIntent.Hidden)
            vm.onIntent(ToolkitIntent.Visible)

            assertNull(withTimeoutOrNull(1_000) { vm.effects.first() })
        }

    // ---- bg-dexopt ------------------------------------------------------------------------------

    @Test
    fun `dexopt start is refused while unplugged and says why`() {
        start { device.plugged = false }
        assertEquals(BgDexoptStatus.Idle, state.dexopt.status)

        vm.onIntent(ToolkitIntent.StartDexopt)

        assertEquals(ToolkitNotice.Rejected(Problem.Refused(ToolkitRefusal.NOT_PLUGGED)), state.dexopt.notice)
        assertEquals(BgDexoptStatus.Idle, state.dexopt.status)
        assertFalse(state.dexopt.busy)
        assertEquals(0, device.holds)
    }

    @Test
    fun `a started job shows as running and the screen takes no binding hold of its own`() {
        start()

        vm.onIntent(ToolkitIntent.StartDexopt)

        assertEquals(ToolkitNotice.Applied(Change.DexoptStarted), state.dexopt.notice)
        assertTrue(state.dexopt.status is BgDexoptStatus.Running)
        assertEquals(0, deps.screenHolds)
        assertEquals(1, device.holds)

        vm.onIntent(ToolkitIntent.StartDexopt)
        assertEquals(1, device.writes.count { it == "startBgDexopt" })
    }

    @Test
    fun `a fresh service that reports unknown is idle when no job of ours was started`() {
        start {
            device.dexopt = ServiceDexopt(BgDexoptState.UNKNOWN, 0, 0, 0, 0)
        }
        assertEquals(BgDexoptStatus.Idle, state.dexopt.status)

        vm.onIntent(ToolkitIntent.CancelDexopt)
        assertEquals(0, device.writes.count { it == "cancelBgDexopt" })

        vm.onIntent(ToolkitIntent.StartDexopt)
        assertEquals(1, device.writes.count { it == "startBgDexopt" })
    }

    @Test
    fun `an unknown job may still be running, offers cancel and cannot be started over`() {
        start {
            device.dexopt =
                ServiceDexopt(
                    BgDexoptState.UNKNOWN,
                    0,
                    0,
                    0,
                    0,
                )
            // A job we started earlier whose outcome was never confirmed.
            device.store.rows +=
                ToolkitActionRecord(
                    id = 1L,
                    startedAt = 1L,
                    kind = "dexopt",
                    op = "bg_dexopt",
                    status = ToolkitActionStatus.PENDING,
                )
        }
        assertEquals(BgDexoptStatus.Unknown, state.dexopt.status)

        vm.onIntent(ToolkitIntent.StartDexopt)
        assertEquals(0, device.writes.count { it == "startBgDexopt" })

        vm.onIntent(ToolkitIntent.CancelDexopt)

        assertEquals(1, device.writes.count { it == "cancelBgDexopt" })
        assertEquals(ToolkitNotice.Applied(Change.DexoptCancelled), state.dexopt.notice)
        val status = state.dexopt.status
        assertTrue(status is BgDexoptStatus.Cancelled && status.reason == BgDexoptCancelReason.USER)
    }

    @Test
    fun `on an API level that cannot cancel dexopt the card says so and neither start nor cancel is sent`() {
        start { device.dexoptUnsupported = true }
        assertEquals(BgDexoptStatus.Unsupported, state.dexopt.status)

        vm.onIntent(ToolkitIntent.StartDexopt)
        vm.onIntent(ToolkitIntent.CancelDexopt)

        assertEquals(0, device.writes.count { it == "startBgDexopt" || it == "cancelBgDexopt" })
        assertNull(state.dexopt.notice)
        assertFalse(state.dexopt.busy)
        assertEquals(0, device.holds)
    }

    @Test
    fun `cancel is ignored while nothing runs`() {
        start()

        vm.onIntent(ToolkitIntent.CancelDexopt)

        assertEquals(0, device.writes.count { it == "cancelBgDexopt" })
    }

    @Test
    fun `the dexopt status is read only while the screen is visible`() {
        start()
        vm.onIntent(ToolkitIntent.StartDexopt)
        val afterStart = device.count("getBgDexoptStatus")

        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()
        val whileVisible = device.count("getBgDexoptStatus")
        assertTrue("running status is polled while visible", whileVisible > afterStart)

        vm.onIntent(ToolkitIntent.Hidden)
        scheduler.advanceTimeBy(30_000)
        scheduler.runCurrent()

        assertEquals(whileVisible, device.count("getBgDexoptStatus"))
    }

    // ---- status notification ----------------------------------------------------------------

    @Test
    fun `the status notification switch follows the setting and a toggle goes to the dependencies`() {
        start()
        assertFalse(state.statusNotification.enabled)

        vm.onIntent(ToolkitIntent.SetStatusNotification(true))

        assertEquals(listOf(true), deps.statusNotificationRequests)
        assertTrue(state.statusNotification.enabled)

        vm.onIntent(ToolkitIntent.SetStatusNotification(false))

        assertEquals(listOf(true, false), deps.statusNotificationRequests)
        assertFalse(state.statusNotification.enabled)
    }

    @Test
    fun `the status notification card is there whatever Shizuku says`() {
        start(shizuku = ShizukuState.NotRunning) { statusNotificationEnabled.value = true }

        assertEquals(ToolkitPhase.BLOCKED, state.status)
        assertTrue(state.statusNotification.enabled)
    }

    @Test
    fun `the blocked flag is read again on every Visible`() {
        start { notificationsBlocked = true }
        assertTrue(state.statusNotification.blocked)

        vm.onIntent(ToolkitIntent.Hidden)
        deps.notificationsBlocked = false // the user allowed it in system settings
        vm.onIntent(ToolkitIntent.Visible)

        assertFalse(state.statusNotification.blocked)
        assertEquals(2, deps.notificationsBlockedReads)
    }

    @Test
    fun `the status notification setting is followed only while visible`() {
        start()
        vm.onIntent(ToolkitIntent.Hidden)

        deps.statusNotificationEnabled.value = true
        assertFalse(state.statusNotification.enabled)

        vm.onIntent(ToolkitIntent.Visible)
        assertTrue(state.statusNotification.enabled)
    }

    @Test
    fun `Allow in settings emits the open-settings effect`() =
        runTest {
            start { notificationsBlocked = true }

            vm.onIntent(ToolkitIntent.OpenNotificationSettings)

            assertEquals(ToolkitEffect.OpenNotificationSettings, vm.effects.first())
        }
}
