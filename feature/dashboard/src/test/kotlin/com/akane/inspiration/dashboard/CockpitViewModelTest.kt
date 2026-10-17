package com.akane.inspiration.dashboard

import com.akane.inspiration.designsystem.component.PulseStatus
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.store.Marker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

@OptIn(ExperimentalCoroutinesApi::class)
class CockpitViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var deps: FakeCockpitDeps

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        deps = FakeCockpitDeps()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(): CockpitViewModel = CockpitViewModel(deps).also { runCurrent() }

    @Test
    fun `visible starts the live gauge at 1500 ms and holds one binding`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onIntent(CockpitIntent.Visible)
        runCurrent()
        vm.onIntent(CockpitIntent.Visible)
        runCurrent()

        assertEquals(1_500L, deps.requestedPeriodMs)
        assertEquals("a second Visible must not start a second collection", 1, deps.liveCollections)
        assertEquals(1, deps.bindingsOpened)
        assertEquals(0, deps.bindingsClosed)
        assertNotNull(vm.state.value.live)
        assertEquals(-301, vm.state.value.live?.currentMa)
        vm.onIntent(CockpitIntent.Hidden)
    }

    @Test
    fun `no reading is requested after hidden and the binding is closed`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onIntent(CockpitIntent.Visible)
        runCurrent()
        advanceTimeBy(3_100)
        runCurrent()
        val before = deps.readingsRequested
        assertTrue("the gauge should have ticked while visible", before >= 3)

        vm.onIntent(CockpitIntent.Hidden)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals("readings requested after Hidden", before, deps.readingsRequested)
        assertEquals(1, deps.bindingsOpened)
        assertEquals("the binding must be closed on Hidden", 1, deps.bindingsClosed)
        assertNull("a stale reading must not survive Hidden", vm.state.value.live)
    }

    @Test
    fun `hidden twice closes the binding once and visible again reads and holds again`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onIntent(CockpitIntent.Visible)
        runCurrent()
        vm.onIntent(CockpitIntent.Hidden)
        vm.onIntent(CockpitIntent.Hidden)
        runCurrent()
        assertEquals(1, deps.bindingsClosed)

        vm.onIntent(CockpitIntent.Visible)
        runCurrent()

        assertEquals(2, deps.liveCollections)
        assertEquals(2, deps.bindingsOpened)
        assertEquals(1, deps.bindingsClosed)
        vm.onIntent(CockpitIntent.Hidden)
        assertEquals(2, deps.bindingsClosed)
    }

    @Test
    fun `visible refreshes quietly and loads the last 24 hours of timeline`() = runTest(dispatcher) {
        deps.refreshResult = RefreshResult.Failed("no shizuku")
        val vm = viewModel()
        val effects = mutableListOf<CockpitEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }

        vm.onIntent(CockpitIntent.Visible)
        runCurrent()

        assertEquals(1, deps.refreshCalls)
        assertEquals(NOW_MS - 24 * HOUR_MS to NOW_MS, deps.timelineCalls.first())
        assertTrue(vm.state.value.chart is ChartUi.Ready)
        assertTrue("a failed automatic refresh stays silent", effects.isEmpty())
        vm.onIntent(CockpitIntent.Hidden)
    }

    @Test
    fun `a refreshed capture reloads the timeline and a debounced one does not`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onIntent(CockpitIntent.Visible)
        runCurrent()
        assertEquals("timeline before and after the refresh", 2, deps.timelineCalls.size)

        deps.refreshResult = RefreshResult.Debounced(NOW_MS)
        vm.onIntent(CockpitIntent.Refresh)
        runCurrent()

        assertEquals(2, deps.refreshCalls)
        assertEquals("debounced: only the pre-refresh read", 3, deps.timelineCalls.size)
        assertFalse(vm.state.value.refreshing)
        vm.onIntent(CockpitIntent.Hidden)
    }

    @Test
    fun `a failed pull to refresh tells the user once`() = runTest(dispatcher) {
        deps.refreshResult = RefreshResult.Failed("deadline")
        val vm = viewModel()
        val effects = mutableListOf<CockpitEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }

        vm.onIntent(CockpitIntent.Refresh)
        runCurrent()

        assertEquals(listOf<CockpitEffect>(CockpitEffect.RefreshFailed), effects)
        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun `a timeline that cannot load becomes a failed chart and a later success replaces it`() = runTest(dispatcher) {
        deps.timelineResult = null
        val vm = viewModel()
        vm.onIntent(CockpitIntent.Refresh)
        runCurrent()
        assertEquals(ChartUi.Failed, vm.state.value.chart)

        deps.timelineResult = emptyTimeline()
        vm.onIntent(CockpitIntent.Refresh)
        runCurrent()
        assertTrue(vm.state.value.chart is ChartUi.Ready)
    }

    @Test
    fun `committing a brush selects the time slice with ordered ends and shows the banner data`() = runTest(dispatcher) {
        val vm = viewModel()
        val from = NOW_MS - 5 * HOUR_MS
        val to = NOW_MS - 3 * HOUR_MS

        vm.onIntent(CockpitIntent.CommitBrush(fromMs = to, toMs = from))
        runCurrent()

        assertEquals(ReferenceKind.TimeSlice(from, to), deps.selection.selected.value)
        assertEquals(ReferenceUi.TimeSlice, vm.state.value.reference)
        val slice = vm.state.value.slice
        assertNotNull(slice)
        assertEquals(from, slice?.requested?.startMs)
        assertEquals(to, slice?.requested?.endMs)
        assertNull("covered range is unknown until the analysis answers this slice", slice?.covered)
    }

    @Test
    fun `clearing the slice returns to since unplugged`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onIntent(CockpitIntent.CommitBrush(NOW_MS - 5 * HOUR_MS, NOW_MS - 3 * HOUR_MS))
        runCurrent()

        vm.onIntent(CockpitIntent.ClearSlice)
        runCurrent()

        assertEquals(ReferenceKind.SinceUnplugged, deps.selection.selected.value)
        assertNull(vm.state.value.slice)
    }

    @Test
    fun `picking a reference from the pill selects it`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onIntent(CockpitIntent.SelectReference(ReferenceChoice.Last24h))
        runCurrent()
        assertEquals(ReferenceKind.Last24h, deps.selection.selected.value)
        assertEquals(ReferenceUi.Last24h, vm.state.value.reference)

        vm.onIntent(CockpitIntent.SelectReference(ReferenceChoice.SinceScreenOff))
        runCurrent()
        assertEquals(ReferenceKind.SinceScreenOff, deps.selection.selected.value)
    }

    @Test
    fun `the banner covers the slice the analysis answers and warns past five minutes of gap`() = runTest(dispatcher) {
        val vm = viewModel()
        val from = NOW_MS - 5 * HOUR_MS
        val to = NOW_MS - 3 * HOUR_MS
        vm.onIntent(CockpitIntent.CommitBrush(from, to))
        deps.analysis.value = AnalysisState.Ready(
            intervalAnalysis(resolved(boundaryFrom = from, boundaryTo = to, coveredFrom = from + 12 * MIN_MS, coveredTo = to)),
        )
        runCurrent()

        val slice = vm.state.value.slice
        assertEquals(from + 12 * MIN_MS, slice?.covered?.startMs)
        assertEquals(to, slice?.covered?.endMs)
        assertEquals(12 * MIN_MS, slice?.gapMs)
    }

    @Test
    fun `a gap of five minutes or less is not reported and an analysis for another range is ignored`() = runTest(dispatcher) {
        val vm = viewModel()
        val from = NOW_MS - 5 * HOUR_MS
        val to = NOW_MS - 3 * HOUR_MS
        vm.onIntent(CockpitIntent.CommitBrush(from, to))
        deps.analysis.value = AnalysisState.Ready(
            intervalAnalysis(resolved(boundaryFrom = from, boundaryTo = to, coveredFrom = from + 5 * MIN_MS, coveredTo = to)),
        )
        runCurrent()
        assertNull(vm.state.value.slice?.gapMs)

        deps.analysis.value = AnalysisState.Ready(intervalAnalysis(resolved(boundaryFrom = from - HOUR_MS, boundaryTo = to)))
        runCurrent()
        assertNull("analysis of a previous selection", vm.state.value.slice?.covered)
    }

    @Test
    fun `the pill's custom marker opens the sheet listing markers newest first`() = runTest(dispatcher) {
        deps.markerList = listOf(
            Marker(1, 1, "before gaming", NOW_MS - 5 * HOUR_MS),
            Marker(2, 2, "after dinner", NOW_MS - HOUR_MS),
        )
        val vm = viewModel()

        vm.onIntent(CockpitIntent.OpenMarkers)
        assertTrue(vm.state.value.markerSheet?.loading == true)
        runCurrent()

        val sheet = vm.state.value.markerSheet
        assertEquals(false, sheet?.loading)
        assertEquals(listOf("after dinner", "before gaming"), sheet?.markers?.map { it.label })
    }

    @Test
    fun `mark now creates a marker with the typed label and lists it`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onIntent(CockpitIntent.OpenMarkers)
        runCurrent()

        vm.onIntent(CockpitIntent.MarkNow("before the update"))
        runCurrent()

        assertEquals(listOf("before the update"), deps.createdLabels)
        val sheet = vm.state.value.markerSheet
        assertEquals(false, sheet?.creating)
        assertEquals(false, sheet?.failed)
        assertEquals(listOf("before the update"), sheet?.markers?.map { it.label })
    }

    @Test
    fun `a failed capture leaves the sheet open with an error and no new marker`() = runTest(dispatcher) {
        deps.createMarkerResult = null
        val vm = viewModel()
        vm.onIntent(CockpitIntent.OpenMarkers)
        runCurrent()

        vm.onIntent(CockpitIntent.MarkNow("x"))
        runCurrent()

        val sheet = vm.state.value.markerSheet
        assertEquals(true, sheet?.failed)
        assertEquals(false, sheet?.creating)
        assertTrue(sheet?.markers?.isEmpty() == true)
    }

    @Test
    fun `picking a marker makes it the reference and closes the sheet`() = runTest(dispatcher) {
        deps.markerList = listOf(Marker(7, 7, "m", NOW_MS))
        val vm = viewModel()
        vm.onIntent(CockpitIntent.OpenMarkers)
        runCurrent()

        vm.onIntent(CockpitIntent.SelectMarker(7))
        runCurrent()

        assertEquals(ReferenceKind.CustomMarker(7), deps.selection.selected.value)
        assertEquals(ReferenceUi.CustomMarker, vm.state.value.reference)
        assertNull(vm.state.value.markerSheet)
    }

    @Test
    fun `dismissing the sheet closes it`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onIntent(CockpitIntent.OpenMarkers)
        runCurrent()

        vm.onIntent(CockpitIntent.DismissMarkers)

        assertNull(vm.state.value.markerSheet)
    }

    @Test
    fun `shizuku state drives the pulse and grant asks the app for permission`() = runTest(dispatcher) {
        val vm = viewModel()
        assertEquals(PulseStatus.ACTIVE, vm.state.value.shizuku.status)
        assertEquals(2000, vm.state.value.shizuku.uid)
        assertFalse(vm.state.value.shizuku.canGrant)

        deps.shizukuState.value = ShizukuState.PermissionRequired
        runCurrent()
        assertEquals(PulseStatus.CONNECTING, vm.state.value.shizuku.status)
        assertTrue(vm.state.value.shizuku.canGrant)

        vm.onIntent(CockpitIntent.GrantShizuku)
        assertEquals(1, deps.permissionRequests)

        deps.shizukuState.value = ShizukuState.NotRunning
        runCurrent()
        assertEquals(PulseStatus.BLOCKED, vm.state.value.shizuku.status)
        assertFalse(vm.state.value.shizuku.canGrant)
    }

    @Test
    fun `analysis states map to designed ui states and ready carries the metrics`() = runTest(dispatcher) {
        val vm = viewModel()
        assertEquals(AnalysisUi.Loading, vm.state.value.analysis)

        deps.analysis.value = AnalysisState.Empty
        runCurrent()
        assertEquals(AnalysisUi.Empty, vm.state.value.analysis)

        deps.analysis.value = AnalysisState.Failed("boom")
        runCurrent()
        assertEquals(AnalysisUi.Error, vm.state.value.analysis)

        deps.analysis.value = AnalysisState.Ready(intervalAnalysis())
        runCurrent()
        val ready = vm.state.value.analysis as AnalysisUi.Ready
        assertEquals(TestStory, ready.story)
        assertEquals(8.2, ready.metrics.activePctPerHour)
    }

    @Test
    fun `time left falls back to a fresh system estimate`() = runTest(dispatcher) {
        // The fake readings carry a negative current but no charge counter, so only the system estimate can answer.
        deps.analysis.value = AnalysisState.Ready(
            intervalAnalysis(resolved(coveredTo = NOW_MS - 2 * MIN_MS, nowSystem = systemTimers(5 * HOUR_MS))),
        )
        val vm = viewModel()

        vm.onIntent(CockpitIntent.Visible)
        runCurrent()

        assertEquals(5 * HOUR_MS, vm.state.value.live?.remainingMs)
        vm.onIntent(CockpitIntent.Hidden)
    }

    @Test
    fun `time left ignores a system estimate older than ten minutes`() = runTest(dispatcher) {
        deps.analysis.value = AnalysisState.Ready(
            intervalAnalysis(resolved(coveredTo = NOW_MS - 20 * MIN_MS, nowSystem = systemTimers(5 * HOUR_MS))),
        )
        val vm = viewModel()

        vm.onIntent(CockpitIntent.Visible)
        runCurrent()

        assertNull(vm.state.value.live?.remainingMs)
        assertNotNull(vm.state.value.live)
        vm.onIntent(CockpitIntent.Hidden)
    }
}
