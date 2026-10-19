package com.akane.inspiration.xray

import com.akane.inspiration.ipc.toolkit.StandbyBucket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val SUITE_UID = 10_050

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
        deps.packagesByUid[SUITE_UID] = SUITE_A
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
