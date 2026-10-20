package com.akane.inspiration.xray

import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.diff.ReferenceKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val SUITE_UID = 10_050
private const val PROFILE_SUITE_UID = 1_010_050 // Android user 10, same app id as the owner installation.

/** The lowest package name of the shared UID: what PackageManager's fallback (`minOrNull`) answers. */
private const val SUITE_A = "com.example.suite.a"

/** The sibling package batterystats names as the UID's highest drain in a later capture. */
private const val SUITE_B = "com.example.suite.b"

/**
 * One UID can change package between analyses: batterystats first reports a shared UID without a package, so
 * the PackageManager fallback names [SUITE_A], and a later capture names [SUITE_B] (`packageChanged` stays false
 * because one side is null). A privileged write must never be retargeted: a missing UID/package closes the
 * sheet and its confirmation; a write already running still finishes against the package it was pinned to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XRayViewModelTargetTest {
    private lateinit var deps: FakeXRayDeps
    private lateinit var vm: XRayViewModel

    private val state get() = vm.state.value
    private val detail get() = state.detail!!
    private val rows get() = deps.device.store.rows

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        deps = FakeXRayDeps()
        deps.packagesByUid[SUITE_UID] = listOf(SUITE_A, SUITE_B)
        deps.analysis.value = suiteAnalysis(packageName = null, totalMah = 30.0)
        vm = XRayViewModel(deps)
        vm.onIntent(XRayIntent.Visible)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun suiteAnalysis(
        packageName: String?,
        totalMah: Double,
    ) = readyOf(diffOf(uids = listOf(uidDelta(SUITE_UID, packageName, totalMah, mapOf("CPU" to totalMah)))))

    /** A capture finishes: the same UID is now attributed to its sibling package. */
    private fun renameToSibling() {
        deps.analysis.value = suiteAnalysis(packageName = SUITE_B, totalMah = 32.0)
    }

    private fun openSuite() {
        vm.onIntent(XRayIntent.OpenApp(SUITE_UID))
        assertEquals("precondition: the sheet is for the fallback package", SUITE_A, detail.app.packageName)
    }

    private fun addProfileInstallation() {
        deps.packagesByUid[PROFILE_SUITE_UID] = listOf(SUITE_A, SUITE_B)
        deps.analysis.value =
            readyOf(
                diffOf(
                    uids =
                        listOf(
                            uidDelta(SUITE_UID, SUITE_A, 30.0, mapOf("CPU" to 30.0)),
                            uidDelta(PROFILE_SUITE_UID, SUITE_A, 60.0, mapOf("CPU" to 60.0)),
                        ),
                ),
            )
    }

    private fun clearToolkitCalls() {
        deps.device.calls.clear()
        deps.device.writes.clear()
        deps.device.store.auditReads = 0
    }

    private fun assertNoToolkitCalls() {
        assertEquals("no owner controls, planning, read-back or writes", emptyList<String>(), deps.device.calls)
        assertEquals(emptyList<String>(), deps.device.writes)
        assertEquals("no override subscription, restore lookup or audit planning", 0, deps.device.store.auditReads)
    }

    /** Inject an unsafe retained UI state without broadening any production API for these defense-in-depth tests. */
    @Suppress("UNCHECKED_CAST")
    private fun replaceDetail(value: AppDetail) {
        val field = XRayViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        val mutableState = field.get(vm) as MutableStateFlow<XRayState>
        mutableState.value = state.copy(detail = value)
    }

    /** Exercise the last boundary independently of the public intent and pending-confirmation guards. */
    private suspend fun executeDirectly(
        request: ActionRequest,
        confirmed: Boolean,
    ) = suspendCoroutine<Unit> { continuation ->
        val method =
            XRayViewModel::class.java
                .getDeclaredMethod(
                    "executeContained",
                    Int::class.javaPrimitiveType,
                    ActionRequest::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    Continuation::class.java,
                ).apply { isAccessible = true }
        val result = method.invoke(vm, PROFILE_SUITE_UID, request, SUITE_A, confirmed, continuation)
        if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
    }

    @Test
    fun `a dual-installed profile keeps its analytics but never reads owner controls or subscribes to overrides`() {
        addProfileInstallation()

        vm.onIntent(XRayIntent.OpenApp(PROFILE_SUITE_UID))
        vm.onIntent(XRayIntent.ReloadActions)
        deps.shizukuState.value = ShizukuState.NotRunning
        deps.shizukuState.value = ShizukuState.Ready(2000, 13)

        assertEquals(2, state.apps.size)
        assertEquals(PROFILE_SUITE_UID, detail.app.uid)
        assertEquals(SUITE_A, detail.app.packageName)
        assertEquals("Label of $SUITE_A", detail.app.label)
        assertEquals(60.0, detail.app.totalMah, 0.0)
        assertEquals(listOf(ComponentRow("CPU", 60.0, 100.0)), detail.app.components)
        assertEquals(ActionsState.UnsupportedUser, detail.actions)
        assertTrue(detail.overrides.isEmpty())
        assertNoToolkitCalls()
    }

    @Test
    fun `direct profile intents cannot plan or restore even with stale owner controls and an owner override id`() {
        openSuite()
        vm.onIntent(XRayIntent.Act(ActionRequest.SetRestricted(true)))
        vm.onIntent(XRayIntent.ConfirmPending)
        val ownerDetail = detail
        val overrideId = ownerDetail.overrides.single().actionId
        val ownerRows = rows.toList()
        addProfileInstallation()
        vm.onIntent(XRayIntent.OpenApp(PROFILE_SUITE_UID))
        clearToolkitCalls()

        val requests =
            listOf(
                ActionRequest.SetRestricted(true),
                ActionRequest.SetRestricted(false),
                ActionRequest.SetBucket(StandbyBucket.WORKING_SET),
                ActionRequest.SetBucket(StandbyBucket.RESTRICTED),
                ActionRequest.ForceStop,
                ActionRequest.Restore(overrideId),
            )
        requests.forEach { request ->
            replaceDetail(detail.copy(actions = ownerDetail.actions, overrides = ownerDetail.overrides))
            vm.onIntent(XRayIntent.Act(request))
            vm.onIntent(XRayIntent.ConfirmPending)
            assertNull(state.confirmation)
            assertFalse(detail.busy)
            assertNoToolkitCalls()
        }
        assertEquals("not even an audit row may be written for a profile action", ownerRows, rows)
    }

    @Test
    fun `confirmation checks the immutable pending uid rather than the currently displayed owner sheet`() {
        openSuite()
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
        assertNotNull(state.confirmation)
        // A profile pin is no longer publicly reachable. Seed one to prove confirmation also checks its own pin,
        // not only the current (owner-user) detail or the public Act boundary.
        val field = XRayViewModel::class.java.getDeclaredField("pending").apply { isAccessible = true }
        val constructor =
            field
                .get(vm)
                .javaClass
                .getDeclaredConstructor(
                    ActionRequest::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                ).apply { isAccessible = true }
        field.set(vm, constructor.newInstance(ActionRequest.ForceStop, PROFILE_SUITE_UID, SUITE_A))
        clearToolkitCalls()

        vm.onIntent(XRayIntent.ConfirmPending)
        vm.onIntent(XRayIntent.ConfirmPending)

        assertNull(state.confirmation)
        assertNull(field.get(vm))
        assertEquals(SUITE_UID, detail.app.uid)
        assertFalse(detail.busy)
        assertTrue(rows.isEmpty())
        assertNoToolkitCalls()
    }

    @Test
    fun `execution independently rejects every profile write and restore before failure read-back`() =
        runTest {
            openSuite()
            vm.onIntent(XRayIntent.Act(ActionRequest.SetRestricted(true)))
            vm.onIntent(XRayIntent.ConfirmPending)
            val ownerDetail = detail
            val overrideId = ownerDetail.overrides.single().actionId
            val ownerRows = rows.toList()
            addProfileInstallation()
            vm.onIntent(XRayIntent.OpenApp(PROFILE_SUITE_UID))
            clearToolkitCalls()
            // If a write accidentally reaches ToolkitActions, force the failure-containment read-back path too.
            deps.device.store.insertFailure = IllegalStateException("audit unavailable")

            val requests =
                listOf(
                    ActionRequest.SetRestricted(false),
                    ActionRequest.SetBucket(StandbyBucket.WORKING_SET),
                    ActionRequest.ForceStop,
                    ActionRequest.Restore(overrideId),
                )
            for (request in requests) {
                for (confirmed in listOf(false, true)) {
                    replaceDetail(detail.copy(actions = ownerDetail.actions, overrides = ownerDetail.overrides, busy = true))
                    executeDirectly(request, confirmed)
                    assertEquals(ActionsState.UnsupportedUser, detail.actions)
                    assertTrue(detail.overrides.isEmpty())
                    assertFalse(detail.busy)
                    assertNoToolkitCalls()
                }
            }
            assertEquals(ownerRows, rows)
        }

    @Test
    fun `opening the profile drops an owner confirmation without retargeting it to the dual-installed package`() {
        openSuite()
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
        assertNotNull(state.confirmation)
        addProfileInstallation()
        clearToolkitCalls()

        vm.onIntent(XRayIntent.OpenApp(PROFILE_SUITE_UID))
        vm.onIntent(XRayIntent.ConfirmPending)

        assertEquals(ActionsState.UnsupportedUser, detail.actions)
        assertNull(state.confirmation)
        assertTrue(rows.isEmpty())
        assertNoToolkitCalls()
    }

    @Test
    fun `owner actions and restoring their override still work when the same package has a profile installation`() {
        addProfileInstallation()
        openSuite()
        vm.onIntent(XRayIntent.Act(ActionRequest.SetRestricted(true)))
        vm.onIntent(XRayIntent.ConfirmPending)
        val overrideId = detail.overrides.single().actionId
        vm.onIntent(XRayIntent.Act(ActionRequest.Restore(overrideId)))
        vm.onIntent(XRayIntent.ConfirmPending)
        assertEquals(ActionNotice.Applied(Change.Restored), detail.notice)
        assertTrue(detail.overrides.isEmpty())
        vm.onIntent(XRayIntent.Act(ActionRequest.SetBucket(StandbyBucket.WORKING_SET)))
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
        vm.onIntent(XRayIntent.ConfirmPending)

        assertEquals(listOf("setAppOp", "setAppOp", "setStandbyBucket", "forceStop"), deps.device.writes)
        assertEquals(listOf(SUITE_A, SUITE_A, SUITE_A, SUITE_A), rows.map { it.pkg })
        assertEquals(ActionNotice.Applied(Change.ForceStopped), detail.notice)
        assertTrue(detail.actions is ActionsState.Loaded)
        assertFalse(detail.busy)
    }

    @Test
    fun `a missing pinned package cancels its confirmation without retargeting to the uid's sibling`() {
        openSuite()
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
        val confirmation = state.confirmation
        assertNotNull("precondition: force stop asks first", confirmation)
        assertEquals("precondition: the dialog names $SUITE_A", "Label of $SUITE_A", confirmation?.target)

        renameToSibling() // while the dialog is open
        vm.onIntent(XRayIntent.ConfirmPending)

        assertNull("the new measured list no longer contains the dialog's target", state.detail)
        assertNull(state.confirmation)
        assertTrue("neither the removed package nor its sibling may be written", rows.isEmpty())
    }

    @Test
    fun `a changed package closes the sheet and only explicitly reopening can act on the new package`() {
        openSuite()

        renameToSibling()

        assertEquals("the list follows the analysis", SUITE_B, state.apps.single().packageName)
        assertNull("no old package or energy survives on the detail sheet", state.detail)
        vm.onIntent(XRayIntent.Act(ActionRequest.SetBucket(StandbyBucket.WORKING_SET)))
        assertTrue(rows.isEmpty())

        vm.onIntent(XRayIntent.OpenApp(SUITE_UID))
        assertEquals(SUITE_B, detail.app.packageName)
        assertEquals("Label of $SUITE_B", detail.app.label)
        assertTrue(detail.actions is ActionsState.Loaded)
        vm.onIntent(XRayIntent.Act(ActionRequest.SetBucket(StandbyBucket.WORKING_SET)))

        assertEquals(ActionNotice.Applied(Change.BucketSet(StandbyBucket.WORKING_SET)), detail.notice)
        assertEquals(listOf(SUITE_B), rows.map { it.pkg })
    }

    @Test
    fun `non-ready analyses close detail and confirmation and reject stale open and action intents`() {
        val states =
            listOf(
                AnalysisState.Loading to XRayStatus.LOADING,
                AnalysisState.Empty(ReferenceKind.SinceUnplugged) to XRayStatus.EMPTY,
                AnalysisState.Failed("read failed", ReferenceKind.SinceUnplugged) to XRayStatus.FAILED,
            )
        for ((analysis, expectedStatus) in states) {
            deps.analysis.value = suiteAnalysis(packageName = SUITE_A, totalMah = 30.0)
            openSuite()
            vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
            assertNotNull(state.confirmation)

            deps.analysis.value = analysis

            assertEquals(expectedStatus, state.status)
            assertNull(state.detail)
            assertNull(state.confirmation)
            assertTrue("the old list deliberately remains cached but is not actionable", state.apps.isNotEmpty())
            clearToolkitCalls()
            vm.onIntent(XRayIntent.OpenApp(SUITE_UID))
            vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
            vm.onIntent(XRayIntent.ConfirmPending)
            vm.onIntent(XRayIntent.ReloadActions)
            assertNull(state.detail)
            assertTrue(rows.isEmpty())
            assertNoToolkitCalls()
        }
    }

    @Test
    fun `a selection awaiting its tagged analysis closes the old sheet and pending confirmation`() {
        openSuite()
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))

        deps.selection.select(ReferenceKind.Last24h)
        vm.onIntent(XRayIntent.ConfirmPending)
        vm.onIntent(XRayIntent.OpenApp(SUITE_UID))

        assertEquals(XRayStatus.LOADING, state.status)
        assertNull(state.detail)
        assertNull(state.confirmation)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `same-kind automatic power-boundary refresh closes detail when no measured UID package row survives`() {
        val absentRows =
            listOf(
                diffOf(),
                diffOf(uids = listOf(uidDelta(SUITE_UID, SUITE_A, 0.0))),
                diffOf(uids = listOf(uidDelta(SUITE_UID, SUITE_A, 30.0, valid = false))),
                diffOf(uids = listOf(uidDelta(SUITE_UID, SUITE_A, 30.0, packageChanged = true))),
                diffOf(uids = listOf(uidDelta(SUITE_UID, SUITE_A, 30.0)), batteryStats = false),
                diffOf(uids = listOf(uidDelta(10_001, MAPS, 20.0))),
            ).map { readyOf(it, resolved = resolvedOf(HOUR_MS, 2 * HOUR_MS)) } +
                readyOf(
                    diffOf(uids = listOf(uidDelta(SUITE_UID, SUITE_A, 30.0))),
                    resolved = resolvedOf(HOUR_MS, 2 * HOUR_MS, perUidAvailable = false),
                )
        for (refreshed in absentRows) {
            deps.analysis.value = suiteAnalysis(packageName = SUITE_A, totalMah = 30.0)
            openSuite()
            vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))

            deps.analysis.value = refreshed

            assertEquals(XRayStatus.READY, state.status)
            assertNull(state.detail)
            assertNull(state.confirmation)
            assertFalse(state.apps.any { it.uid == SUITE_UID })
            clearToolkitCalls()
            vm.onIntent(XRayIntent.OpenApp(SUITE_UID))
            vm.onIntent(XRayIntent.ConfirmPending)
            assertNull(state.detail)
            assertTrue(rows.isEmpty())
            assertNoToolkitCalls()
        }
    }

    @Test
    fun `a failed analysis received while hidden closes the retained detail when visible again`() {
        openSuite()
        vm.onIntent(XRayIntent.Hidden)
        deps.analysis.value = AnalysisState.Failed("new interval unavailable", ReferenceKind.SinceUnplugged)

        vm.onIntent(XRayIntent.Visible)
        vm.onIntent(XRayIntent.OpenApp(SUITE_UID))

        assertEquals(XRayStatus.FAILED, state.status)
        assertNull(state.detail)
        assertNull(state.confirmation)
    }

    @Test
    fun `a package lookup that no longer resolves closes the old actionable sheet`() {
        openSuite()
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
        deps.packagesByUid[SUITE_UID] = emptyList()

        deps.analysis.value = suiteAnalysis(packageName = SUITE_A, totalMah = 31.0)
        vm.onIntent(XRayIntent.ConfirmPending)

        assertNull(state.detail)
        assertNull(state.confirmation)
        assertNull(state.apps.single().packageName)
        assertTrue(rows.isEmpty())
        vm.onIntent(XRayIntent.OpenApp(SUITE_UID))
        assertEquals(ActionsState.NoPackage, detail.actions)
    }

    @Test
    fun `an invalidated measurement cancels in-flight planning before a confirmation or write can appear`() {
        for (next in listOf(AnalysisState.Loading, readyOf(diffOf()))) {
            deps.analysis.value = suiteAnalysis(packageName = SUITE_A, totalMah = 30.0)
            openSuite()
            val gate = CompletableDeferred<Unit>()
            deps.device.gate = gate
            vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
            assertTrue(detail.busy)

            deps.analysis.value = next
            gate.complete(Unit)
            deps.device.gate = null
            vm.onIntent(XRayIntent.ConfirmPending)

            assertNull(state.detail)
            assertNull(state.confirmation)
            assertTrue(rows.isEmpty())
            assertTrue(deps.device.writes.isEmpty())
        }
    }

    @Test
    fun `a write already running may finish after a measurement disappears but cannot reopen detail`() {
        openSuite()
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
        val gate = CompletableDeferred<Unit>()
        deps.device.gate = gate
        vm.onIntent(XRayIntent.ConfirmPending)
        assertTrue(detail.busy)

        deps.analysis.value = readyOf(diffOf(), resolved = resolvedOf(HOUR_MS, 2 * HOUR_MS))
        assertNull(state.detail)
        gate.complete(Unit)

        assertEquals(listOf(SUITE_A), rows.map { it.pkg })
        assertEquals(listOf("forceStop"), deps.device.writes)
        assertNull(state.detail)
        assertNull(state.confirmation)
    }

    @Test
    fun `a newer matching row refreshes numbers and preserves the confirmation's pinned target`() {
        openSuite()
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
        val confirmation = state.confirmation

        deps.analysis.value = suiteAnalysis(packageName = SUITE_A, totalMah = 45.0)

        assertEquals(SUITE_A, detail.app.packageName)
        assertEquals(45.0, detail.app.totalMah, 0.0)
        assertEquals(confirmation, state.confirmation)
        vm.onIntent(XRayIntent.ConfirmPending)
        assertEquals(listOf(SUITE_A), rows.map { it.pkg })
        assertEquals(ActionNotice.Applied(Change.ForceStopped), detail.notice)
    }

    @Test
    fun `a write still running when the uid's sheet is reopened for its new package never reports on that sheet`() {
        openSuite()
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
        val binder = CompletableDeferred<Unit>()
        deps.device.gate = binder // the write's Binder round trip is slow
        vm.onIntent(XRayIntent.ConfirmPending)

        renameToSibling()
        assertNull("the automatic refresh closes the missing UID/package", state.detail)
        vm.onIntent(XRayIntent.OpenApp(SUITE_UID))
        assertEquals("precondition: the reopened sheet is for the new package", SUITE_B, detail.app.packageName)
        binder.complete(Unit)

        assertNull("the force stop of $SUITE_A was reported on the sheet of $SUITE_B", detail.notice)
        assertFalse(detail.busy)
        assertTrue(detail.actions is ActionsState.Loaded)
        assertEquals("the write still finished against the app it was confirmed for", listOf(SUITE_A), rows.map { it.pkg })
    }
}
