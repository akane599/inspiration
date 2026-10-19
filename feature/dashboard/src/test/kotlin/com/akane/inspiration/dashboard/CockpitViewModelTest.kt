package com.akane.inspiration.dashboard

import com.akane.inspiration.designsystem.chart.model.LaneKind
import com.akane.inspiration.designsystem.chart.model.LaneSegment
import com.akane.inspiration.designsystem.component.GaugeChargeState
import com.akane.inspiration.designsystem.component.PulseStatus
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.analysis.SegmentKind
import com.akane.inspiration.diagnostics.analysis.TimelineSegment
import com.akane.inspiration.diagnostics.story.BatteryStory
import com.akane.inspiration.diagnostics.story.StorySentence
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.store.Marker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
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
import org.junit.Assert.assertSame
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

    private fun TestScope.viewModel(): CockpitViewModel = CockpitViewModel(deps, shapingDispatcher = dispatcher).also { runCurrent() }

    /**
     * Runs [block] during a visit: the analysis, selection and Shizuku state reach the screen only while it is visible.
     * Hidden stops the live loop even when an assertion fails, so the test cannot spin forever.
     */
    private inline fun TestScope.visit(
        vm: CockpitViewModel,
        block: () -> Unit,
    ) {
        vm.onIntent(CockpitIntent.Visible)
        try {
            runCurrent()
            block()
        } finally {
            vm.onIntent(CockpitIntent.Hidden)
        }
    }

    @Test
    fun `visible starts the live gauge at 1500 ms and holds one binding`() =
        runTest(dispatcher) {
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
            assertEquals(
                -301,
                vm.state.value.live
                    ?.currentMa,
            )
            vm.onIntent(CockpitIntent.Hidden)
        }

    @Test
    fun `no reading is requested after hidden and the binding is closed`() =
        runTest(dispatcher) {
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
    fun `hidden twice closes the binding once and visible again reads and holds again`() =
        runTest(dispatcher) {
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
    fun `a source that stops emitting returns the gauge to dashes`() =
        runTest(dispatcher) {
            deps.liveFlow =
                flow {
                    emit(liveReading())
                    awaitCancellation()
                }
            val vm = viewModel()
            vm.onIntent(CockpitIntent.Visible)
            try {
                runCurrent()
                assertNotNull(vm.state.value.live)

                advanceTimeBy(LIVE_STALE_MS - 1)
                runCurrent()
                assertNotNull("still within the staleness window", vm.state.value.live)

                advanceTimeBy(2)
                runCurrent()
                assertNull("a reading with no successor is not live", vm.state.value.live)
            } finally {
                vm.onIntent(CockpitIntent.Hidden)
            }
        }

    @Test
    fun `a single missed reading does not flash dashes`() =
        runTest(dispatcher) {
            deps.liveFlow =
                flow {
                    emit(liveReading(currentMa = -300))
                    // One read failed (and took the bind timeout): the next success comes two periods plus 5 s later.
                    delay(2 * LIVE_PERIOD_MS + 5_000)
                    emit(liveReading(currentMa = -310))
                    awaitCancellation()
                }
            val vm = viewModel()
            vm.onIntent(CockpitIntent.Visible)
            try {
                runCurrent()
                repeat(10) {
                    advanceTimeBy(800)
                    runCurrent()
                    assertNotNull("dashes at ${(it + 1) * 800} ms", vm.state.value.live)
                }
                assertEquals(
                    -310,
                    vm.state.value.live
                        ?.currentMa,
                )
            } finally {
                vm.onIntent(CockpitIntent.Hidden)
            }
        }

    @Test
    fun `a drain hovering at the deadband on a charger keeps its label and its time left`() =
        runTest(dispatcher) {
            // Each status's readings arrive a period apart: past the 50 mA deadband, back within 10 mA of it, then out.
            fun readings(status: String) =
                listOf(-50, -45, -35).map { liveReading(currentMa = it, plugged = true, chargeCounterUah = 3_000_000, status = status) }
            deps.liveFlow =
                flow {
                    (readings("charging") + readings("full")).forEach {
                        emit(it)
                        delay(LIVE_PERIOD_MS)
                    }
                    awaitCancellation()
                }
            val vm = viewModel()
            vm.onIntent(CockpitIntent.Visible)
            try {
                runCurrent()
                val seen = mutableListOf(vm.state.value.live)
                repeat(5) {
                    advanceTimeBy(LIVE_PERIOD_MS)
                    runCurrent()
                    seen += vm.state.value.live
                }

                assertEquals(
                    listOf(GaugeChargeState.PluggedNotCharging, GaugeChargeState.PluggedNotCharging, GaugeChargeState.Charging),
                    seen.take(3).map { it?.chargeState },
                )
                // 3,000,000 uAh at 50 mA is 60 h, at 45 mA 66 h 40 min; at 35 mA a held battery has no time to empty.
                assertEquals(listOf(60 * HOUR_MS, 66 * HOUR_MS + 40 * MIN_MS, null), seen.drop(3).map { it?.remainingMs })
            } finally {
                vm.onIntent(CockpitIntent.Hidden)
            }
        }

    @Test
    fun `a failing live source returns the gauge to dashes without taking the screen down`() =
        runTest(dispatcher) {
            val failure = CompletableDeferred<Unit>()
            deps.liveFlow =
                flow {
                    emit(liveReading())
                    failure.await()
                    throw IllegalStateException("binder died")
                }
            val vm = viewModel()
            vm.onIntent(CockpitIntent.Visible)
            try {
                runCurrent()
                assertNotNull(vm.state.value.live)

                failure.complete(Unit)
                runCurrent()

                assertNull(vm.state.value.live)
            } finally {
                vm.onIntent(CockpitIntent.Hidden)
            }
        }

    @Test
    fun `shizuku leaving ready clears the gauge`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.onIntent(CockpitIntent.Visible)
            try {
                runCurrent()
                assertNotNull(vm.state.value.live)

                deps.shizukuState.value = ShizukuState.NotRunning
                runCurrent()
                assertNull(vm.state.value.live)

                // A read that finishes after Shizuku went away is not shown as live either.
                advanceTimeBy(LIVE_PERIOD_MS)
                runCurrent()
                assertNull(vm.state.value.live)
            } finally {
                vm.onIntent(CockpitIntent.Hidden)
            }
        }

    @Test
    fun `visible refreshes quietly and loads the last 24 hours of timeline`() =
        runTest(dispatcher) {
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

    /** The store's view of a screen-on stretch that began half an hour ago, with its lane closed at [endMs]. */
    private fun screenOnUntil(endMs: Long) =
        emptyTimeline().copy(segments = listOf(TimelineSegment(SegmentKind.SCREEN_ON, NOW_MS - 30 * MIN_MS, endMs)))

    private fun CockpitState.screenOnLane(): List<LaneSegment>? =
        (chart as? ChartUi.Ready)
            ?.data
            ?.lanes
            ?.single { it.kind == LaneKind.SCREEN_ON }
            ?.segments

    @Test
    fun `an app-open refresh in flight on enter reloads the chart once its history is stored`() =
        runTest(dispatcher) {
            // The app-open capture (MainActivity.onStart) holds the coordinator when the Cockpit becomes visible:
            // the store still has the previous horizon, so the screen-on lane ends there.
            val previousHorizon = NOW_MS - 20 * MIN_MS
            deps.timelineResult = screenOnUntil(previousHorizon)
            val appOpenCapture = CompletableDeferred<Unit>().also { deps.refreshGate = it }
            val vm = viewModel()
            vm.onIntent(CockpitIntent.Visible)
            try {
                runCurrent()
                assertEquals(listOf(LaneSegment(NOW_MS - 30 * MIN_MS, previousHorizon)), vm.state.value.screenOnLane())

                // The capture commits its history ingest and the coordinator records its tick. The screen's own
                // refresh, queued behind it, then comes back Debounced: no capture of its own.
                deps.timelineResult = screenOnUntil(NOW_MS)
                deps.lastRefreshWallMs.value = NOW_MS
                deps.refreshResult = RefreshResult.Debounced(NOW_MS)
                appOpenCapture.complete(Unit)
                runCurrent()

                assertEquals(
                    "the lane runs to the newly stored horizon",
                    listOf(LaneSegment(NOW_MS - 30 * MIN_MS, NOW_MS)),
                    vm.state.value.screenOnLane(),
                )
                assertEquals(1, deps.refreshCalls)
            } finally {
                vm.onIntent(CockpitIntent.Hidden)
            }
        }

    @Test
    fun `a refresh this screen did not ask for reloads the chart while visible, and none is read while hidden`() =
        runTest(dispatcher) {
            // The on-enter refresh fails (no binder yet); Shizuku then becomes ready and the container re-runs it.
            deps.refreshResult = RefreshResult.Failed("shizuku not ready")
            val vm = viewModel()
            vm.onIntent(CockpitIntent.Visible)
            try {
                runCurrent()
                val reads = deps.timelineCalls.size

                deps.lastRefreshWallMs.value = NOW_MS
                runCurrent()
                assertEquals(reads + 1, deps.timelineCalls.size)

                vm.onIntent(CockpitIntent.Hidden)
                deps.lastRefreshWallMs.value = NOW_MS + MIN_MS
                runCurrent()
                assertEquals("a hidden screen reads nothing", reads + 1, deps.timelineCalls.size)
            } finally {
                vm.onIntent(CockpitIntent.Hidden)
            }
        }

    @Test
    fun `a pull re-reads the chart at once and its capture reloads it again once stored`() =
        runTest(dispatcher) {
            deps.refreshResult = RefreshResult.Debounced(NOW_MS)
            val vm = viewModel()
            vm.onIntent(CockpitIntent.Visible)
            try {
                runCurrent()
                assertEquals("one read on enter; a debounced refresh stored nothing", 1, deps.timelineCalls.size)

                deps.clocks.wall = NOW_MS + 2 * MIN_MS
                deps.refreshResult = RefreshResult.Refreshed(deps.clocks.wall)
                vm.onIntent(CockpitIntent.Refresh)
                runCurrent()

                assertEquals(2, deps.refreshCalls)
                assertEquals("the pull's own read, then the stored capture's", 3, deps.timelineCalls.size)
                assertFalse(vm.state.value.refreshing)
            } finally {
                vm.onIntent(CockpitIntent.Hidden)
            }
        }

    @Test
    fun `a marker's capture reloads the chart through its store tick`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.onIntent(CockpitIntent.Visible)
            try {
                runCurrent()
                vm.onIntent(CockpitIntent.OpenMarkers)
                runCurrent()
                val reads = deps.timelineCalls.size

                vm.onIntent(CockpitIntent.MarkNow("charger swap"))
                runCurrent()

                assertEquals("the marker's FULL capture ingested history", reads + 1, deps.timelineCalls.size)
            } finally {
                vm.onIntent(CockpitIntent.Hidden)
            }
        }

    @Test
    fun `a failed pull to refresh tells the user once`() =
        runTest(dispatcher) {
            deps.refreshResult = RefreshResult.Failed("deadline")
            val vm = viewModel()
            val effects = mutableListOf<CockpitEffect>()
            backgroundScope.launch { vm.effects.collect { effects += it } }
            vm.onIntent(CockpitIntent.Visible)
            try {
                runCurrent()
                assertTrue("an automatic refresh is silent", effects.isEmpty())

                vm.onIntent(CockpitIntent.Refresh)
                runCurrent()

                assertEquals(listOf<CockpitEffect>(CockpitEffect.RefreshFailed), effects)
                assertFalse(vm.state.value.refreshing)
            } finally {
                vm.onIntent(CockpitIntent.Hidden)
            }
        }

    @Test
    fun `a pulled refresh failing after the user left is not shown on the next visit`() =
        runTest(dispatcher) {
            deps.refreshResult = RefreshResult.Failed("deadline")
            val gate = CompletableDeferred<Unit>().also { deps.refreshGate = it }
            val vm = viewModel()
            vm.onIntent(CockpitIntent.Visible)
            try {
                runCurrent()
                vm.onIntent(CockpitIntent.Refresh)
                runCurrent()
                assertTrue(vm.state.value.refreshing)

                vm.onIntent(CockpitIntent.Hidden)
                assertFalse("leaving the visit ends its progress immediately", vm.state.value.refreshing)
                gate.complete(Unit)
                runCurrent()

                deps.refreshGate = null
                deps.refreshResult = RefreshResult.Refreshed(NOW_MS)
                vm.onIntent(CockpitIntent.Visible)
                val effects = mutableListOf<CockpitEffect>()
                backgroundScope.launch { vm.effects.collect { effects += it } }
                runCurrent()

                assertEquals("the shared capture was not replaced", 2, deps.refreshCalls)
                assertTrue("the previous visit's failure must not be replayed", effects.isEmpty())
            } finally {
                vm.onIntent(CockpitIntent.Hidden)
            }
        }

    @Test
    fun `hidden drains a failure buffered while no collector was there`() =
        runTest(dispatcher) {
            deps.refreshResult = RefreshResult.Failed("deadline")
            val vm = viewModel()
            vm.onIntent(CockpitIntent.Visible)
            try {
                runCurrent()
                vm.onIntent(CockpitIntent.Refresh)
                runCurrent()

                vm.onIntent(CockpitIntent.Hidden)
                deps.refreshResult = RefreshResult.Refreshed(NOW_MS)
                vm.onIntent(CockpitIntent.Visible)
                val effects = mutableListOf<CockpitEffect>()
                backgroundScope.launch { vm.effects.collect { effects += it } }
                runCurrent()

                assertTrue("a new collector cannot receive the old buffered notification", effects.isEmpty())
            } finally {
                vm.onIntent(CockpitIntent.Hidden)
            }
        }

    @Test
    fun `a refresh intent while hidden cannot opt a later visit into failure reporting`() =
        runTest(dispatcher) {
            deps.refreshResult = RefreshResult.Failed("deadline")
            val gate = CompletableDeferred<Unit>().also { deps.refreshGate = it }
            val vm = viewModel()
            vm.onIntent(CockpitIntent.Visible)
            try {
                runCurrent()
                vm.onIntent(CockpitIntent.Hidden)
                vm.onIntent(CockpitIntent.Refresh)
                runCurrent()
                assertFalse(vm.state.value.refreshing)

                vm.onIntent(CockpitIntent.Visible)
                val effects = mutableListOf<CockpitEffect>()
                backgroundScope.launch { vm.effects.collect { effects += it } }
                gate.complete(Unit)
                runCurrent()

                assertEquals(1, deps.refreshCalls)
                assertTrue(effects.isEmpty())
            } finally {
                vm.onIntent(CockpitIntent.Hidden)
            }
        }

    @Test
    fun `retry on a failed analysis recomputes even when the refresh is debounced`() =
        runTest(dispatcher) {
            deps.analysis.value = AnalysisState.Failed("db closed")
            deps.refreshResult = RefreshResult.Debounced(NOW_MS)
            val gate = CompletableDeferred<Unit>().also { deps.refreshGate = it }
            val vm = viewModel()

            visit(vm) {
                assertEquals(AnalysisUi.Error, vm.state.value.analysis)
                deps.recomputeResult = ready()
                vm.onIntent(CockpitIntent.Refresh)
                runCurrent()
                assertTrue("a failed analysis is recomputed at once, not after the capture", deps.recomputeCalls >= 1)
                assertTrue(vm.state.value.analysis is AnalysisUi.Ready)

                gate.complete(Unit)
                runCurrent()
                assertEquals("the pull joined the on-enter refresh", 1, deps.refreshCalls)
                assertFalse(vm.state.value.refreshing)
            }
        }

    @Test
    fun `the shared analysis is observed only while the screen is visible`() =
        runTest(dispatcher) {
            deps.analysis.value = ready()
            val vm = viewModel()
            assertEquals("constructing the screen must not start the session", 0, deps.analysis.subscriptionCount.value)

            vm.onIntent(CockpitIntent.Visible)
            runCurrent()
            assertEquals(1, deps.analysis.subscriptionCount.value)

            vm.onIntent(CockpitIntent.Hidden)
            runCurrent()
            assertEquals(
                "a hidden screen must not keep the session computing, or a returning one cannot restart it",
                0,
                deps.analysis.subscriptionCount.value,
            )

            // What the session computes while nothing shows it is shown on the next visit.
            deps.analysis.value = ready(intervalAnalysis(metrics = intervalMetrics(screenOnPctPerHour = 5.0)))
            runCurrent()
            visit(vm) {
                assertEquals(1, deps.analysis.subscriptionCount.value)
                assertEquals(5.0, (vm.state.value.analysis as AnalysisUi.Ready).metrics.activePctPerHour)
            }
        }

    @Test
    fun `a debounced or failed user refresh recomputes the analysis and an automatic one does not`() =
        runTest(dispatcher) {
            deps.analysis.value = ready()
            deps.refreshResult = RefreshResult.Debounced(NOW_MS)
            val vm = viewModel()
            vm.onIntent(CockpitIntent.Visible)
            try {
                runCurrent()
                // The visit's own subscription restarts the session, which recomputes on it: no explicit request.
                assertEquals(1, deps.analysis.subscriptionCount.value)
                assertEquals("the on-enter refresh asks for no recompute", 0, deps.recomputeCalls)

                vm.onIntent(CockpitIntent.Refresh)
                runCurrent()
                assertEquals(1, deps.recomputeCalls)

                deps.refreshResult = RefreshResult.Failed("no shizuku")
                vm.onIntent(CockpitIntent.Refresh)
                runCurrent()
                assertEquals(2, deps.recomputeCalls)

                deps.refreshResult = RefreshResult.Refreshed(NOW_MS)
                vm.onIntent(CockpitIntent.Refresh)
                runCurrent()
                assertEquals("a capture's own tick recomputes; no extra request", 2, deps.recomputeCalls)
            } finally {
                vm.onIntent(CockpitIntent.Hidden)
            }
        }

    @Test
    fun `a pull to refresh during the automatic one shows progress and reports its failure`() =
        runTest(dispatcher) {
            deps.refreshResult = RefreshResult.Failed("deadline")
            val gate = CompletableDeferred<Unit>().also { deps.refreshGate = it }
            val vm = viewModel()
            val effects = mutableListOf<CockpitEffect>()
            backgroundScope.launch { vm.effects.collect { effects += it } }

            vm.onIntent(CockpitIntent.Visible)
            try {
                runCurrent()
                vm.onIntent(CockpitIntent.Refresh)
                runCurrent()
                assertTrue(vm.state.value.refreshing)

                gate.complete(Unit)
                runCurrent()

                assertEquals("the running refresh is reused", 1, deps.refreshCalls)
                assertEquals(listOf<CockpitEffect>(CockpitEffect.RefreshFailed), effects)
                assertFalse(vm.state.value.refreshing)
            } finally {
                // Stops the live loop even when an assertion fails, so the test cannot spin forever.
                vm.onIntent(CockpitIntent.Hidden)
            }
        }

    @Test
    fun `a timeline that cannot load becomes a failed chart and a later success replaces it`() =
        runTest(dispatcher) {
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
    fun `the chart's timeline is built and shaped on the shaping dispatcher, not the main thread`() =
        runTest(dispatcher) {
            val shaping = StandardTestDispatcher(testScheduler, name = "shaping")
            val vm = CockpitViewModel(deps, shapingDispatcher = shaping).also { runCurrent() }

            vm.onIntent(CockpitIntent.Refresh)
            runCurrent()

            assertSame(shaping, deps.timelineDispatcher)
            assertTrue(vm.state.value.chart is ChartUi.Ready)
        }

    @Test
    fun `committing a brush selects the time slice with ordered ends and shows the banner data`() =
        runTest(dispatcher) {
            val vm = viewModel()
            val from = NOW_MS - 5 * HOUR_MS
            val to = NOW_MS - 3 * HOUR_MS

            visit(vm) {
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
        }

    @Test
    fun `clearing the slice returns to since unplugged`() =
        runTest(dispatcher) {
            val vm = viewModel()
            visit(vm) {
                vm.onIntent(CockpitIntent.CommitBrush(NOW_MS - 5 * HOUR_MS, NOW_MS - 3 * HOUR_MS))
                runCurrent()
                assertNotNull(vm.state.value.slice)

                vm.onIntent(CockpitIntent.ClearSlice)
                runCurrent()

                assertEquals(ReferenceKind.SinceUnplugged, deps.selection.selected.value)
                assertNull(vm.state.value.slice)
            }
        }

    @Test
    fun `picking a reference from the pill selects it`() =
        runTest(dispatcher) {
            val vm = viewModel()

            visit(vm) {
                vm.onIntent(CockpitIntent.SelectReference(ReferenceChoice.Last24h))
                runCurrent()
                assertEquals(ReferenceKind.Last24h, deps.selection.selected.value)
                assertEquals(ReferenceUi.Last24h, vm.state.value.reference)

                vm.onIntent(CockpitIntent.SelectReference(ReferenceChoice.SinceScreenOff))
                runCurrent()
                assertEquals(ReferenceKind.SinceScreenOff, deps.selection.selected.value)
            }
        }

    @Test
    fun `the banner covers the slice the analysis answers`() =
        runTest(dispatcher) {
            val vm = viewModel()
            val from = NOW_MS - 5 * HOUR_MS
            val to = NOW_MS - 3 * HOUR_MS
            visit(vm) {
                vm.onIntent(CockpitIntent.CommitBrush(from, to))
                deps.analysis.value =
                    ready(
                        intervalAnalysis(resolved(boundaryFrom = from, boundaryTo = to, coveredFrom = from + 12 * MIN_MS, coveredTo = to)),
                        kind = ReferenceKind.TimeSlice(from, to),
                    )
                runCurrent()

                val slice = vm.state.value.slice
                assertEquals(from + 12 * MIN_MS, slice?.covered?.startMs)
                assertEquals(to, slice?.covered?.endMs)
            }
        }

    @Test
    fun `an analysis of the previous selection is neither shown nor used by the banner`() =
        runTest(dispatcher) {
            val vm = viewModel()
            val from = NOW_MS - 5 * HOUR_MS
            val to = NOW_MS - 3 * HOUR_MS
            visit(vm) {
                vm.onIntent(CockpitIntent.CommitBrush(from, to))
                runCurrent()

                val previous = ReferenceKind.TimeSlice(from - HOUR_MS, to)
                deps.analysis.value = ready(intervalAnalysis(resolved(boundaryFrom = from - HOUR_MS, boundaryTo = to)), kind = previous)
                runCurrent()
                assertNull(
                    "analysis of a previous selection",
                    vm.state.value.slice
                        ?.covered,
                )
                assertEquals(AnalysisUi.Loading, vm.state.value.analysis)
            }
        }

    @Test
    fun `a new brush shows loading, not the previous slice's story, until its analysis arrives`() =
        runTest(dispatcher) {
            val vm = viewModel()
            val sliceA = ReferenceKind.TimeSlice(NOW_MS - 8 * HOUR_MS, NOW_MS - 6 * HOUR_MS)
            val storyA = BatteryStory(listOf(StorySentence.Summary(hours = 2.0, levelUsedPct = 9)))
            visit(vm) {
                vm.onIntent(CockpitIntent.CommitBrush(sliceA.fromWallMs, sliceA.toWallMs))
                deps.analysis.value =
                    ready(
                        intervalAnalysis(resolved(boundaryFrom = sliceA.fromWallMs, boundaryTo = sliceA.toWallMs)).copy(story = storyA),
                        kind = sliceA,
                    )
                runCurrent()
                assertEquals(storyA, (vm.state.value.analysis as? AnalysisUi.Ready)?.story)

                // The user brushes slice B. The session has not emitted Loading yet: its state still answers slice A.
                val sliceB = ReferenceKind.TimeSlice(NOW_MS - 3 * HOUR_MS, NOW_MS - HOUR_MS)
                vm.onIntent(CockpitIntent.CommitBrush(sliceB.fromWallMs, sliceB.toWallMs))
                runCurrent()
                assertEquals(sliceB, deps.selection.selected.value)
                assertEquals(AnalysisUi.Loading, vm.state.value.analysis)

                deps.analysis.value =
                    ready(intervalAnalysis(resolved(boundaryFrom = sliceB.fromWallMs, boundaryTo = sliceB.toWallMs)), kind = sliceB)
                runCurrent()
                assertEquals(TestStory, (vm.state.value.analysis as? AnalysisUi.Ready)?.story)
            }
        }

    @Test
    fun `a new reference shows loading, not the previous interval's story, until its analysis arrives`() =
        runTest(dispatcher) {
            deps.analysis.value = ready()
            val vm = viewModel()

            visit(vm) {
                assertTrue(vm.state.value.analysis is AnalysisUi.Ready)
                vm.onIntent(CockpitIntent.SelectReference(ReferenceChoice.Last24h))
                runCurrent()
                assertEquals(ReferenceUi.Last24h, vm.state.value.reference)
                assertEquals(AnalysisUi.Loading, vm.state.value.analysis)

                deps.analysis.value = ready(kind = ReferenceKind.Last24h)
                runCurrent()
                assertTrue(vm.state.value.analysis is AnalysisUi.Ready)
            }
        }

    @Test
    fun `the pill's custom marker opens the sheet listing markers newest first`() =
        runTest(dispatcher) {
            deps.markerList =
                listOf(
                    Marker(1, 1, "before gaming", NOW_MS - 5 * HOUR_MS),
                    Marker(2, 2, "after dinner", NOW_MS - HOUR_MS),
                )
            val vm = viewModel()

            vm.onIntent(CockpitIntent.OpenMarkers)
            assertTrue(
                vm.state.value.markerSheet
                    ?.loading == true,
            )
            runCurrent()

            val sheet = vm.state.value.markerSheet
            assertEquals(false, sheet?.loading)
            assertEquals(listOf("after dinner", "before gaming"), sheet?.markers?.map { it.label })
        }

    @Test
    fun `mark now creates a marker with the typed label and lists it`() =
        runTest(dispatcher) {
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
    fun `a failed capture leaves the sheet open with an error and no new marker`() =
        runTest(dispatcher) {
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
    fun `picking a marker makes it the reference and closes the sheet`() =
        runTest(dispatcher) {
            deps.markerList = listOf(Marker(7, 7, "m", NOW_MS))
            val vm = viewModel()
            visit(vm) {
                vm.onIntent(CockpitIntent.OpenMarkers)
                runCurrent()

                vm.onIntent(CockpitIntent.SelectMarker(7))
                runCurrent()

                assertEquals(ReferenceKind.CustomMarker(7), deps.selection.selected.value)
                assertEquals(ReferenceUi.CustomMarker, vm.state.value.reference)
                assertNull(vm.state.value.markerSheet)
            }
        }

    @Test
    fun `dismissing the sheet closes it`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.onIntent(CockpitIntent.OpenMarkers)
            runCurrent()

            vm.onIntent(CockpitIntent.DismissMarkers)

            assertNull(vm.state.value.markerSheet)
        }

    @Test
    fun `shizuku state drives the pulse and grant asks the app for permission`() =
        runTest(dispatcher) {
            val vm = viewModel()

            visit(vm) {
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
        }

    @Test
    fun `analysis states map to designed ui states and ready carries the metrics`() =
        runTest(dispatcher) {
            val vm = viewModel()
            assertEquals(AnalysisUi.Loading, vm.state.value.analysis)

            visit(vm) {
                deps.analysis.value = AnalysisState.Empty
                runCurrent()
                assertEquals(AnalysisUi.Empty, vm.state.value.analysis)

                deps.analysis.value = AnalysisState.Failed("boom")
                runCurrent()
                assertEquals(AnalysisUi.Error, vm.state.value.analysis)

                deps.analysis.value = ready()
                runCurrent()
                val ready = vm.state.value.analysis as AnalysisUi.Ready
                assertEquals(TestStory, ready.story)
                assertEquals(8.2, ready.metrics.activePctPerHour)
            }
        }

    @Test
    fun `time left falls back to a fresh system estimate`() =
        runTest(dispatcher) {
            // The fake readings carry a negative current but no charge counter, so only the system estimate can answer.
            deps.analysis.value =
                ready(
                    intervalAnalysis(resolved(coveredTo = NOW_MS - 2 * MIN_MS, nowSystem = systemTimers(5 * HOUR_MS))),
                )
            val vm = viewModel()

            vm.onIntent(CockpitIntent.Visible)
            runCurrent()

            assertEquals(
                5 * HOUR_MS,
                vm.state.value.live
                    ?.remainingMs,
            )
            vm.onIntent(CockpitIntent.Hidden)
        }

    @Test
    fun `time left ignores a system estimate older than ten minutes`() =
        runTest(dispatcher) {
            deps.analysis.value =
                ready(
                    intervalAnalysis(resolved(coveredTo = NOW_MS - 20 * MIN_MS, nowSystem = systemTimers(5 * HOUR_MS))),
                )
            val vm = viewModel()

            vm.onIntent(CockpitIntent.Visible)
            runCurrent()

            assertNull(
                vm.state.value.live
                    ?.remainingMs,
            )
            assertNotNull(vm.state.value.live)
            vm.onIntent(CockpitIntent.Hidden)
        }
}
