package com.akane.inspiration.health

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.snapshot.LiveReading
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var deps: FakeHealthDeps

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        deps = FakeHealthDeps()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(): HealthViewModel = HealthViewModel(deps).also { runCurrent() }

    private fun TestScope.viewModel(readings: Flow<LiveReading>): HealthViewModel =
        HealthViewModel(
            object : HealthDependencies by deps {
                override fun liveReadings(periodMs: Long): Flow<LiveReading> = readings
            },
        ).also { runCurrent() }

    // ---- H13: visibility-scoped reads ------------------------------------------------------------

    @Test
    fun `visible but discharging collects nothing and holds no binding`() =
        runTest(dispatcher) {
            val vm = viewModel()

            vm.onIntent(HealthIntent.Visible)
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals("a discharging phone must not be read", 0, deps.liveCollections)
            assertEquals(0, deps.readingsRequested)
            assertEquals(0, deps.bindingsOpened)
            assertFalse(vm.state.value.charging)
            vm.onIntent(HealthIntent.Hidden)
        }

    @Test
    fun `collection starts when the plug state flips to charging while visible`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.onIntent(HealthIntent.Visible)
            runCurrent()
            assertEquals(0, deps.liveCollections)

            vm.onIntent(HealthIntent.PlugChanged(charging = true))
            runCurrent()
            advanceTimeBy(9_100)
            runCurrent()

            assertEquals(1, deps.liveCollections)
            assertEquals(CURVE_PERIOD_MS, deps.requestedPeriodMs)
            assertEquals(1, deps.bindingsOpened)
            assertEquals(0, deps.bindingsClosed)
            assertTrue("readings should have arrived", deps.readingsRequested >= 3)
            assertTrue(vm.state.value.charging)
            val curve = vm.state.value.curve
            assertTrue("a live curve should be drawn from the readings", curve is CurveUi.Ready)
            assertEquals(CurveSource.Live, (curve as CurveUi.Ready).source)
            vm.onIntent(HealthIntent.Hidden)
        }

    @Test
    fun `new live charge duration excludes the previous completed history charge`() =
        runTest(dispatcher) {
            deps.history =
                listOf(
                    event(NOW_MS - 5 * HOUR_MS, HistoryKind.PLUGGED),
                    event(NOW_MS - 4 * HOUR_MS, HistoryKind.UNPLUGGED),
                )
            val vm = viewModel()
            vm.onIntent(HealthIntent.Visible)
            runCurrent()
            vm.onIntent(HealthIntent.PlugChanged(true))
            runCurrent()
            advanceTimeBy(CURVE_PERIOD_MS)
            runCurrent()

            assertTrue(vm.state.value.curve is CurveUi.Ready)
            assertEquals(
                CURVE_PERIOD_MS,
                vm.state.value.analysis
                    ?.chargeMs,
            )
            vm.onIntent(HealthIntent.Hidden)
        }

    @Test
    fun `a second charging signal does not start a second collection`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.onIntent(HealthIntent.Visible)
            vm.onIntent(HealthIntent.PlugChanged(true))
            runCurrent()
            vm.onIntent(HealthIntent.PlugChanged(true))
            vm.onIntent(HealthIntent.Visible)
            runCurrent()

            assertEquals(1, deps.liveCollections)
            assertEquals(1, deps.bindingsOpened)
            vm.onIntent(HealthIntent.Hidden)
        }

    @Test
    fun `no reading is requested after hidden and the binding is closed`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.onIntent(HealthIntent.Visible)
            vm.onIntent(HealthIntent.PlugChanged(true))
            runCurrent()
            advanceTimeBy(9_100)
            runCurrent()
            val before = deps.readingsRequested
            assertTrue("the curve should have ticked while visible", before >= 3)

            vm.onIntent(HealthIntent.Hidden)
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()

            assertEquals("readings requested after Hidden", before, deps.readingsRequested)
            assertEquals(1, deps.bindingsOpened)
            assertEquals("the binding must be closed on Hidden", 1, deps.bindingsClosed)
            assertFalse(vm.state.value.charging)
        }

    @Test
    fun `unplugging while visible stops the readings and closes the binding`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.onIntent(HealthIntent.Visible)
            vm.onIntent(HealthIntent.PlugChanged(true))
            runCurrent()
            advanceTimeBy(6_100)
            runCurrent()
            val before = deps.readingsRequested

            vm.onIntent(HealthIntent.PlugChanged(false))
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(before, deps.readingsRequested)
            assertEquals(1, deps.bindingsClosed)
            assertFalse(vm.state.value.charging)
            vm.onIntent(HealthIntent.Hidden)
            assertEquals("Hidden after an unplug must not close twice", 1, deps.bindingsClosed)
        }

    @Test
    fun `a plug signal while hidden collects nothing`() =
        runTest(dispatcher) {
            val vm = viewModel()

            vm.onIntent(HealthIntent.PlugChanged(true))
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()

            assertEquals(0, deps.liveCollections)
            assertEquals(0, deps.bindingsOpened)
        }

    @Test
    fun `becoming visible while already charging starts collecting`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.onIntent(HealthIntent.PlugChanged(true))
            runCurrent()
            assertEquals(0, deps.liveCollections)

            vm.onIntent(HealthIntent.Visible)
            runCurrent()

            assertEquals(1, deps.liveCollections)
            assertEquals(1, deps.bindingsOpened)
            vm.onIntent(HealthIntent.Hidden)
            assertEquals(1, deps.bindingsClosed)
        }

    @Test
    fun `clearing the view model closes a held binding`() =
        runTest(dispatcher) {
            val store = ViewModelStore()
            val vm = ViewModelProvider(store, HealthViewModel.factory(deps))[HealthViewModel::class.java]
            runCurrent()
            vm.onIntent(HealthIntent.Visible)
            vm.onIntent(HealthIntent.PlugChanged(true))
            runCurrent()
            assertEquals(1, deps.bindingsOpened)

            store.clear()

            assertEquals(1, deps.bindingsClosed)
        }

    @Test
    fun `a failing live flow leaves the screen up`() =
        runTest(dispatcher) {
            val failing =
                object : HealthDependencies by deps {
                    override fun liveReadings(periodMs: Long): Flow<LiveReading> = flow { error("kernel read failed") }
                }
            val vm = HealthViewModel(failing)
            vm.onIntent(HealthIntent.Visible)
            vm.onIntent(HealthIntent.PlugChanged(true))
            runCurrent()

            assertEquals(HealthPhase.Ready, vm.state.value.phase)
            vm.onIntent(HealthIntent.Hidden)
        }

    // ---- observed charge boundaries --------------------------------------------------------------

    @Test
    fun `short unplug replug overrides stale open history and persisted points`() =
        runTest(dispatcher) {
            replayShortReplug(
                listOf(
                    event(NOW_MS, HistoryKind.PLUGGED),
                    event(NOW_MS, level = 40, tempDeciC = 410),
                    event(NOW_MS + 3 * SEC_MS, level = 41, tempDeciC = 410),
                ),
            )
        }

    @Test
    fun `short unplug replug splits persisted points without history`() =
        runTest(dispatcher) {
            replayShortReplug(emptyList())
        }

    @Test
    fun `short unplug replug splits persisted points when history read fails`() =
        runTest(dispatcher) {
            replayShortReplug(null)
        }

    private suspend fun TestScope.replayShortReplug(history: List<HistoryEvent>?) {
        deps.history = history
        val readings = MutableSharedFlow<LiveReading>()
        val vm = viewModel(readings)
        try {
            vm.onIntent(HealthIntent.Visible)
            vm.onIntent(HealthIntent.PlugChanged(true))
            runCurrent()
            readings.emit(liveReading(NOW_MS, levelPct = 40, tempDeciC = 410))
            deps.clocks.wall = NOW_MS + 3 * SEC_MS
            readings.emit(liveReading(deps.clocks.wall, levelPct = 41, tempDeciC = 410))
            runCurrent()
            assertEquals(
                3 * SEC_MS,
                vm.state.value.analysis
                    ?.hotObservedMs,
            )

            // The source persisted the old charge. Clearing only the live buffer cannot split these points.
            val oldSamples =
                listOf(
                    sample(NOW_MS, level = 40, tempDeciC = 410),
                    sample(NOW_MS + 3 * SEC_MS, level = 41, tempDeciC = 410),
                    sample(NOW_MS + 4 * SEC_MS, plugged = false),
                )
            deps.samples = oldSamples
            deps.clocks.wall = NOW_MS + 4 * SEC_MS
            vm.onIntent(HealthIntent.PlugChanged(false))
            runCurrent()
            assertEquals(CurveSource.Recorded, (vm.state.value.curve as CurveUi.Ready).source)
            assertEquals(
                4 * SEC_MS,
                vm.state.value.analysis
                    ?.chargeMs,
            )
            assertEquals(
                3 * SEC_MS,
                vm.state.value.analysis
                    ?.hotMs,
            )

            deps.clocks.wall = NOW_MS + 5 * SEC_MS
            vm.onIntent(HealthIntent.PlugChanged(false))
            assertEquals(
                "duplicate unplug must not extend the completed charge",
                4 * SEC_MS,
                vm.state.value.analysis
                    ?.chargeMs,
            )
            deps.clocks.wall = NOW_MS + 10 * SEC_MS
            vm.onIntent(HealthIntent.PlugChanged(true))
            runCurrent()
            assertEquals("old persisted points are outside the new charge", CurveUi.None, vm.state.value.curve)
            deps.clocks.wall = NOW_MS + 11 * SEC_MS
            vm.onIntent(HealthIntent.PlugChanged(true))
            deps.clocks.wall = NOW_MS + 12 * SEC_MS
            readings.emit(liveReading(deps.clocks.wall, levelPct = 42, tempDeciC = 410))
            deps.clocks.wall = NOW_MS + 15 * SEC_MS
            readings.emit(liveReading(deps.clocks.wall, levelPct = 43, tempDeciC = 410))
            runCurrent()

            // A subsequent load merges both old and new persisted points with the live buffer.
            deps.samples = oldSamples +
                listOf(
                    sample(NOW_MS + 12 * SEC_MS, level = 42, tempDeciC = 410),
                    sample(NOW_MS + 15 * SEC_MS, level = 43, tempDeciC = 410),
                )
            vm.onIntent(HealthIntent.Refresh)
            runCurrent()
            val curve = vm.state.value.curve as CurveUi.Ready
            assertEquals(42, curve.startLevelPct)
            assertEquals(43, curve.endLevelPct)
            assertTrue(curve.series.all { it.points.size == 2 })
            assertEquals(
                3 * SEC_MS,
                vm.state.value.analysis
                    ?.hotMs,
            )
            assertEquals(
                3 * SEC_MS,
                vm.state.value.analysis
                    ?.hotObservedMs,
            )
            assertEquals(
                "duration starts at the observed replug, not the first sample or duplicate broadcast",
                5 * SEC_MS,
                vm.state.value.analysis
                    ?.chargeMs,
            )
            assertEquals(2, deps.bindingsOpened)
            assertEquals(1, deps.bindingsClosed)
        } finally {
            vm.onIntent(HealthIntent.Hidden)
        }
        assertEquals(2, deps.bindingsClosed)
    }

    @Test
    fun `first sticky charging observation preserves the pre existing charge`() =
        runTest(dispatcher) {
            deps.history =
                listOf(
                    event(NOW_MS - 10 * SEC_MS, HistoryKind.PLUGGED),
                    event(NOW_MS - 8 * SEC_MS, level = 30),
                    event(NOW_MS - 5 * SEC_MS, level = 31),
                )
            deps.samples = listOf(sample(NOW_MS - 3 * SEC_MS, level = 32), sample(NOW_MS, level = 33))
            val readings = MutableSharedFlow<LiveReading>()
            val vm = viewModel(readings)
            try {
                vm.onIntent(HealthIntent.Visible)
                vm.onIntent(HealthIntent.PlugChanged(true))
                runCurrent()
                assertEquals(30, (vm.state.value.curve as CurveUi.Ready).startLevelPct)
                assertEquals(
                    10 * SEC_MS,
                    vm.state.value.analysis
                        ?.chargeMs,
                )

                deps.clocks.wall = NOW_MS + 2 * SEC_MS
                vm.onIntent(HealthIntent.PlugChanged(true))
                readings.emit(liveReading(deps.clocks.wall, levelPct = 34))
                runCurrent()
                assertEquals(30, (vm.state.value.curve as CurveUi.Ready).startLevelPct)
                assertEquals(
                    12 * SEC_MS,
                    vm.state.value.analysis
                        ?.chargeMs,
                )
            } finally {
                vm.onIntent(HealthIntent.Hidden)
            }
        }

    @Test
    fun `first sticky unplugged observation makes the next plug an observed boundary`() =
        runTest(dispatcher) {
            deps.history = listOf(event(NOW_MS - MIN_MS, HistoryKind.PLUGGED))
            deps.samples = listOf(sample(NOW_MS - 3 * SEC_MS, level = 40), sample(NOW_MS, level = 41))
            val readings = MutableSharedFlow<LiveReading>()
            val vm = viewModel(readings)
            try {
                vm.onIntent(HealthIntent.Visible)
                vm.onIntent(HealthIntent.PlugChanged(false))
                runCurrent()
                deps.clocks.wall = NOW_MS + 10 * SEC_MS
                vm.onIntent(HealthIntent.PlugChanged(true))
                runCurrent()
                assertEquals(CurveUi.None, vm.state.value.curve)
                readings.emit(liveReading(NOW_MS + 12 * SEC_MS, levelPct = 42))
                deps.clocks.wall = NOW_MS + 15 * SEC_MS
                readings.emit(liveReading(deps.clocks.wall, levelPct = 43))
                runCurrent()
                assertEquals(42, (vm.state.value.curve as CurveUi.Ready).startLevelPct)
                assertEquals(
                    5 * SEC_MS,
                    vm.state.value.analysis
                        ?.chargeMs,
                )
            } finally {
                vm.onIntent(HealthIntent.Hidden)
            }
        }

    @Test
    fun `completed charge stays closed when lagging history contains discharge levels`() =
        runTest(dispatcher) {
            deps.history = listOf(event(NOW_MS - SEC_MS, HistoryKind.PLUGGED))
            val readings = MutableSharedFlow<LiveReading>()
            val vm = viewModel(readings)
            try {
                vm.onIntent(HealthIntent.Visible)
                vm.onIntent(HealthIntent.PlugChanged(true))
                runCurrent()
                readings.emit(liveReading(NOW_MS, levelPct = 40, tempDeciC = 410))
                deps.clocks.wall = NOW_MS + 3 * SEC_MS
                readings.emit(liveReading(deps.clocks.wall, levelPct = 41, tempDeciC = 410))
                deps.clocks.wall = NOW_MS + 4 * SEC_MS
                vm.onIntent(HealthIntent.PlugChanged(false))
                runCurrent()
                deps.clocks.wall = NOW_MS + 8 * SEC_MS
                deps.history = deps.history.orEmpty() + event(NOW_MS + 6 * SEC_MS, level = 39, tempDeciC = 410)
                vm.onIntent(HealthIntent.Refresh)
                runCurrent()

                val curve = vm.state.value.curve as CurveUi.Ready
                assertEquals(CurveSource.Recorded, curve.source)
                assertEquals(40, curve.startLevelPct)
                assertEquals(41, curve.endLevelPct)
                assertEquals(
                    5 * SEC_MS,
                    vm.state.value.analysis
                        ?.chargeMs,
                )
                assertEquals(
                    3 * SEC_MS,
                    vm.state.value.analysis
                        ?.hotObservedMs,
                )
                assertEquals(1, deps.bindingsClosed)
            } finally {
                vm.onIntent(HealthIntent.Hidden)
            }
            assertEquals(1, deps.bindingsClosed)
        }

    @Test
    fun `hide show resets observed boundaries and accepts a fresh mid charge sticky state`() =
        runTest(dispatcher) {
            val readings = MutableSharedFlow<LiveReading>()
            val vm = viewModel(readings)
            try {
                vm.onIntent(HealthIntent.Visible)
                vm.onIntent(HealthIntent.PlugChanged(false))
                runCurrent()
                deps.clocks.wall = NOW_MS + 10 * SEC_MS
                vm.onIntent(HealthIntent.PlugChanged(true))
                runCurrent()
                readings.emit(liveReading(NOW_MS + 12 * SEC_MS, levelPct = 40))
                readings.emit(liveReading(NOW_MS + 15 * SEC_MS, levelPct = 41))
                deps.clocks.wall = NOW_MS + 16 * SEC_MS
                vm.onIntent(HealthIntent.PlugChanged(false))
                runCurrent()
                vm.onIntent(HealthIntent.Hidden)
                assertEquals(CurveUi.None, vm.state.value.curve)
                assertEquals(1, deps.bindingsClosed)

                deps.history =
                    listOf(
                        event(NOW_MS + 20 * SEC_MS, HistoryKind.PLUGGED),
                        event(NOW_MS + 22 * SEC_MS, level = 50),
                        event(NOW_MS + 25 * SEC_MS, level = 51),
                        event(NOW_MS + 28 * SEC_MS, level = 52),
                    )
                deps.clocks.wall = NOW_MS + 30 * SEC_MS
                vm.onIntent(HealthIntent.Visible)
                vm.onIntent(HealthIntent.PlugChanged(true))
                runCurrent()
                val curve = vm.state.value.curve as CurveUi.Ready
                assertEquals(CurveSource.History, curve.source)
                assertEquals(50, curve.startLevelPct)
                assertEquals(52, curve.endLevelPct)
                assertEquals(
                    10 * SEC_MS,
                    vm.state.value.analysis
                        ?.chargeMs,
                )
                assertEquals(2, deps.bindingsOpened)
            } finally {
                vm.onIntent(HealthIntent.Hidden)
            }
            assertEquals(2, deps.bindingsClosed)
        }

    // ---- loading, refresh and states --------------------------------------------------------------

    @Test
    fun `before the first load the screen is loading`() =
        runTest(dispatcher) {
            val vm = viewModel()
            assertEquals(HealthPhase.Loading, vm.state.value.phase)
            assertNull(vm.state.value.card)
        }

    @Test
    fun `visible loads, refreshes once and loads again`() =
        runTest(dispatcher) {
            val vm = viewModel()
            deps.healthSamples = listOf(healthSample(1_000, fcc = 4_000.0), healthSample(2_000, fcc = 3_900.0))

            vm.onIntent(HealthIntent.Visible)
            runCurrent()

            assertEquals(1, deps.refreshCalls)
            val state = vm.state.value
            assertEquals(HealthPhase.Ready, state.phase)
            assertEquals(4_300.0, state.card?.reported?.mah ?: 0.0, 0.0)
            assertTrue(state.wear is WearUi.Ready)
            assertFalse(state.refreshing)
            vm.onIntent(HealthIntent.Hidden)
        }

    @Test
    fun `nothing recorded and no kernel is the designed empty state`() =
        runTest(dispatcher) {
            deps.kernel = null
            val vm = viewModel()

            vm.onIntent(HealthIntent.Visible)
            runCurrent()

            assertEquals(HealthPhase.Empty, vm.state.value.phase)
            vm.onIntent(HealthIntent.Hidden)
        }

    @Test
    fun `every read failing is the error state and a retry recovers`() =
        runTest(dispatcher) {
            deps.kernelThrows = true
            deps.healthSamples = null
            deps.sessions = null
            deps.history = null
            deps.samples = null
            val vm = viewModel()

            vm.onIntent(HealthIntent.Visible)
            runCurrent()
            assertEquals(HealthPhase.Error, vm.state.value.phase)

            deps.kernelThrows = false
            deps.healthSamples = emptyList()
            deps.sessions = emptyList()
            deps.history = emptyList()
            deps.samples = emptyList()
            deps.clocks.wall += 2 * MIN_MS
            vm.onIntent(HealthIntent.Refresh)
            runCurrent()

            assertEquals(HealthPhase.Ready, vm.state.value.phase)
            vm.onIntent(HealthIntent.Hidden)
        }

    @Test
    fun `one failing read degrades its card and not the screen`() =
        runTest(dispatcher) {
            deps.healthSamples = null
            val vm = viewModel()

            vm.onIntent(HealthIntent.Visible)
            runCurrent()

            assertEquals(HealthPhase.Ready, vm.state.value.phase)
            assertTrue(vm.state.value.wear is WearUi.NotEnough)
            assertEquals(
                4_300.0,
                vm.state.value.card
                    ?.reported
                    ?.mah ?: 0.0,
                0.0,
            )
            vm.onIntent(HealthIntent.Hidden)
        }

    @Test
    fun `a failed pull to refresh shows a message, a failed automatic one does not`() =
        runTest(dispatcher) {
            deps.refreshResult = RefreshResult.Failed("deadline")
            val vm = viewModel()
            val effects = mutableListOf<HealthEffect>()
            backgroundScope.launch { vm.effects.collect { effects += it } }

            vm.onIntent(HealthIntent.Visible)
            runCurrent()
            assertTrue("an automatic refresh is silent", effects.isEmpty())

            vm.onIntent(HealthIntent.Refresh)
            runCurrent()
            assertEquals(listOf<HealthEffect>(HealthEffect.RefreshFailed), effects)
            assertFalse(vm.state.value.refreshing)
            vm.onIntent(HealthIntent.Hidden)
        }

    @Test
    fun `a pull to refresh during the automatic one shows progress and reports its failure`() =
        runTest(dispatcher) {
            deps.refreshResult = RefreshResult.Failed("deadline")
            val gate = CompletableDeferred<Unit>().also { deps.refreshGate = it }
            val vm = viewModel()
            val effects = mutableListOf<HealthEffect>()
            backgroundScope.launch { vm.effects.collect { effects += it } }

            vm.onIntent(HealthIntent.Visible)
            try {
                runCurrent()
                vm.onIntent(HealthIntent.Refresh)
                runCurrent()
                assertTrue(vm.state.value.refreshing)

                gate.complete(Unit)
                runCurrent()

                assertEquals("the running refresh is reused", 1, deps.refreshCalls)
                assertEquals(listOf<HealthEffect>(HealthEffect.RefreshFailed), effects)
                assertFalse(vm.state.value.refreshing)
            } finally {
                // Stops the live loop even when an assertion fails, so the test cannot spin forever.
                vm.onIntent(HealthIntent.Hidden)
            }
        }

    @Test
    fun `a debounced refresh is not an error`() =
        runTest(dispatcher) {
            deps.refreshResult = RefreshResult.Debounced(NOW_MS)
            val vm = viewModel()
            val effects = mutableListOf<HealthEffect>()
            backgroundScope.launch { vm.effects.collect { effects += it } }

            vm.onIntent(HealthIntent.Visible)
            vm.onIntent(HealthIntent.Refresh)
            runCurrent()

            assertTrue(effects.isEmpty())
            vm.onIntent(HealthIntent.Hidden)
        }

    // ---- data assembly ---------------------------------------------------------------------------

    @Test
    fun `background charging is drawn from history with its caption source`() =
        runTest(dispatcher) {
            val start = NOW_MS - 3 * HOUR_MS
            deps.history =
                buildList {
                    add(event(start, HistoryKind.PLUGGED, level = 20, tempDeciC = 300))
                    for (i in 1..8) {
                        add(
                            event(
                                start + i * 10 * MIN_MS,
                                level = 20 + i * 8,
                                tempDeciC = 380 + i * 10,
                                voltageMv =
                                    4_100 + i * 30,
                            ),
                        )
                    }
                    add(event(start + 90 * MIN_MS, HistoryKind.UNPLUGGED, level = 84))
                }
            val vm = viewModel()

            vm.onIntent(HealthIntent.Visible)
            runCurrent()

            val state = vm.state.value
            val curve = state.curve as CurveUi.Ready
            assertEquals(CurveSource.History, curve.source)
            assertEquals(listOf(CurveMetric.Voltage, CurveMetric.Temperature), curve.series.map { it.metric })
            assertNotNull(state.analysis)
            assertEquals(CurveSource.History, state.analysis?.source)
            assertTrue(state.analysis?.sparse ?: false)
            vm.onIntent(HealthIntent.Hidden)
        }

    @Test
    fun `persisted samples from an earlier visit are drawn as recorded`() =
        runTest(dispatcher) {
            val start = NOW_MS - 2 * HOUR_MS
            deps.history =
                listOf(
                    event(start - MIN_MS, HistoryKind.PLUGGED, level = 30),
                    event(start + 11 * 10 * SEC_MS, HistoryKind.UNPLUGGED, level = 45),
                )
            deps.samples = (0..10).map { sample(start + it * 10 * SEC_MS, level = 35 + it, tempDeciC = 410, voltageMv = 4_380) }
            val vm = viewModel()

            vm.onIntent(HealthIntent.Visible)
            runCurrent()

            val curve = vm.state.value.curve as CurveUi.Ready
            assertEquals(CurveSource.Recorded, curve.source)
            assertEquals(4, curve.series.size)
            val analysis = vm.state.value.analysis
            assertEquals(100 * SEC_MS, analysis?.hotMs)
            assertEquals(100 * SEC_MS, analysis?.highVoltageMs)
            vm.onIntent(HealthIntent.Hidden)
        }

    @Test
    fun `hidden clears the live buffer so a stale live curve is never shown on return`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.onIntent(HealthIntent.Visible)
            vm.onIntent(HealthIntent.PlugChanged(true))
            runCurrent()
            advanceTimeBy(9_100)
            runCurrent()
            assertTrue(vm.state.value.curve is CurveUi.Ready)

            vm.onIntent(HealthIntent.Hidden)
            runCurrent()

            assertEquals("nothing persisted in the fake, so nothing to draw", CurveUi.None, vm.state.value.curve)
        }
}
