package com.akane.inspiration.xray

import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.toolkit.StandbyBucket
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
 * because one side is null). A privileged write must still go to the app the user opened and the dialog named:
 * the confirmation is pinned to its target when it is planned, and an open sheet never changes package.
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
    fun `a confirmed force stop hits only the package the dialog named, even after a new analysis renames the uid`() {
        openSuite()
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
        val confirmation = state.confirmation
        assertNotNull("precondition: force stop asks first", confirmation)
        assertEquals("precondition: the dialog names $SUITE_A", "Label of $SUITE_A", confirmation?.target)

        renameToSibling() // while the dialog is open
        vm.onIntent(XRayIntent.ConfirmPending)

        assertTrue("a confirmed write went to a package the dialog never named: $rows", rows.none { it.pkg == SUITE_B })
        assertEquals("the confirmed force stop ran against $SUITE_A", listOf(SUITE_A), rows.map { it.pkg })
        assertEquals(ActionNotice.Applied(Change.ForceStopped), detail.notice)
    }

    @Test
    fun `an open sheet keeps its package when a new analysis renames its uid, so a tap writes to that package`() {
        openSuite()

        renameToSibling()

        assertEquals("the list follows the analysis", SUITE_B, state.apps.single().packageName)
        assertEquals("the open sheet keeps the app it was opened for", SUITE_A, detail.app.packageName)
        assertEquals("Label of $SUITE_A", detail.app.label)
        assertTrue(detail.actions is ActionsState.Loaded)

        vm.onIntent(XRayIntent.Act(ActionRequest.SetBucket(StandbyBucket.WORKING_SET))) // needs no confirmation

        assertEquals(ActionNotice.Applied(Change.BucketSet(StandbyBucket.WORKING_SET)), detail.notice)
        assertTrue("the controls were read for $SUITE_A, but the write went elsewhere: $rows", rows.none { it.pkg == SUITE_B })
        assertEquals(listOf(SUITE_A), rows.map { it.pkg })
    }

    @Test
    fun `a newer analysis that names the same package still refreshes the open sheet's numbers`() {
        openSuite()

        deps.analysis.value = suiteAnalysis(packageName = SUITE_A, totalMah = 45.0)

        assertEquals(SUITE_A, detail.app.packageName)
        assertEquals(45.0, detail.app.totalMah, 0.0)
    }

    @Test
    fun `a write still running when the uid's sheet is reopened for its new package never reports on that sheet`() {
        openSuite()
        vm.onIntent(XRayIntent.Act(ActionRequest.ForceStop))
        val binder = CompletableDeferred<Unit>()
        deps.device.gate = binder // the write's Binder round trip is slow
        vm.onIntent(XRayIntent.ConfirmPending)
        vm.onIntent(XRayIntent.CloseApp)

        renameToSibling()
        vm.onIntent(XRayIntent.OpenApp(SUITE_UID))
        assertEquals("precondition: the reopened sheet is for the new package", SUITE_B, detail.app.packageName)
        binder.complete(Unit)

        assertNull("the force stop of $SUITE_A was reported on the sheet of $SUITE_B", detail.notice)
        assertFalse(detail.busy)
        assertTrue(detail.actions is ActionsState.Loaded)
        assertEquals("the write still finished against the app it was confirmed for", listOf(SUITE_A), rows.map { it.pkg })
    }
}
