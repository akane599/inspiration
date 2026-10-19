package com.akane.inspiration.sleeplab

import com.akane.inspiration.designsystem.chart.model.HypnogramStage
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.analysis.SegmentKind
import com.akane.inspiration.diagnostics.analysis.Timeline
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.dump.UidAlarm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
class SleepLabViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var deps: FakeSleepLabDeps

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        deps = FakeSleepLabDeps()
        deps.appLabels.uidPackages += mapOf(10_001 to listOf("com.chat"), 10_002 to listOf("com.mail"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(): SleepLabViewModel = SleepLabViewModel(deps).also { runCurrent() }

    private fun TestScope.visible(vm: SleepLabViewModel) {
        vm.onIntent(SleepLabIntent.Visible)
        runCurrent()
    }

    private val night = Timeline(
        segments = listOf(
            segment(SegmentKind.SCREEN_ON, NOW_MS - 10 * HOUR_MS, NOW_MS - 8 * HOUR_MS),
            segment(SegmentKind.LIGHT_IDLE, NOW_MS - 8 * HOUR_MS, NOW_MS - 7 * HOUR_MS),
            segment(SegmentKind.DEEP_IDLE, NOW_MS - 7 * HOUR_MS, NOW_MS - 2 * HOUR_MS),
        ),
        levelPoints = emptyList(),
        temperaturePoints = emptyList(),
        wakePulsesMs = listOf(NOW_MS - 4 * HOUR_MS),
        from = NOW_MS - 10 * HOUR_MS,
        to = NOW_MS,
    )

    private val withWakelocks = intervalAnalysis(
        diff = snapshotDiff(
            uids = listOf(
                uidDelta(10_001, "com.chat", wakelocks = listOf(wakelock("push", 90_000, 2)), alarms = listOf(UidAlarm("t", 3))),
                uidDelta(10_002, "com.mail", wakelocks = listOf(wakelock("fetch", 5_000, 9))),
            ),
        ),
    )

    @Test
    fun `nothing is shaped and nothing is read before the screen is visible`() = runTest(dispatcher) {
        deps.analysis.value = ready(withWakelocks)
        val vm = viewModel()
        runCurrent()

        assertEquals(SleepLabState.Initial, vm.state.value)
        assertEquals(0, deps.refreshCalls)
        assertTrue(deps.timelineCalls.isEmpty())
        assertTrue("no label lookup while hidden", deps.appLabels.looked.isEmpty())
    }

    @Test
    fun `rows without a reported package resolve the owner from the uid's packages`() = runTest(dispatcher) {
        deps.appLabels.uidPackages[10_003] = listOf("com.news")
        deps.appLabels.labels["com.news"] = "News"
        deps.analysis.value = ready(
            intervalAnalysis(
                diff = snapshotDiff(
                    uids = listOf(
                        uidDelta(10_003, packageName = null, wakelocks = listOf(wakelock("sync", 7_000, 1)), alarms = listOf(UidAlarm("t", 2))),
                        uidDelta(1_000, packageName = null, wakelocks = listOf(wakelock("sys", 1_000, 1))),
                    ),
                ),
            ),
        )
        val vm = viewModel()

        visible(vm)

        val analysis = vm.state.value.analysis as AnalysisUi.Ready
        val wake = (analysis.appWakelocks as ListUi.Rows).rows.first { it.uid == 10_003 }
        assertEquals("com.news", wake.packageName)
        assertEquals("News", wake.appLabel)
        val alarm = (analysis.alarms as ListUi.Rows).rows.single()
        assertEquals("News", alarm.appLabel)
        val unowned = (analysis.appWakelocks as ListUi.Rows).rows.first { it.uid == 1_000 }
        assertEquals(null, unowned.appLabel)
    }

    private fun newsAnalysis(reported: String?, coveredTo: Long = NOW_MS) = intervalAnalysis(
        resolved = resolved(coveredTo = coveredTo),
        diff = snapshotDiff(
            uids = listOf(uidDelta(10_003, reported, wakelocks = listOf(wakelock("sync", 7_000, 1)), alarms = listOf(UidAlarm("t", 2)))),
        ),
    )

    @Test
    fun `a uid reported under a process name is shown and labelled as its package`() = runTest(dispatcher) {
        deps.appLabels.uidPackages[10_003] = listOf("com.news")
        deps.appLabels.labels["com.news"] = "News"
        deps.analysis.value = ready(newsAnalysis(reported = "com.news:sync"))
        val vm = viewModel()

        visible(vm)

        val analysis = vm.state.value.analysis as AnalysisUi.Ready
        val wake = (analysis.appWakelocks as ListUi.Rows).rows.single()
        assertEquals("com.news", wake.packageName)
        assertEquals("News", wake.appLabel)
        assertEquals("News", (analysis.alarms as ListUi.Rows).rows.single().appLabel)
        assertEquals("labels are read for the package, never the process name", listOf("com.news"), deps.appLabels.looked.distinct())
    }

    @Test
    fun `a uid no installed package runs as is shown by its uid, whatever was reported`() = runTest(dispatcher) {
        deps.analysis.value = ready(newsAnalysis(reported = "com.news:sync"))
        val vm = viewModel()

        visible(vm)

        val wake = ((vm.state.value.analysis as AnalysisUi.Ready).appWakelocks as ListUi.Rows).rows.single()
        assertNull(wake.packageName)
        assertNull(wake.appLabel)
        assertTrue(deps.appLabels.looked.isEmpty())
    }

    @Test
    fun `a uid lookup that failed is asked again on the next analysis instead of being remembered`() = runTest(dispatcher) {
        deps.appLabels.uidFailure = IllegalStateException("package manager died")
        deps.analysis.value = ready(newsAnalysis(reported = null))
        val vm = viewModel()
        visible(vm)
        val first = ((vm.state.value.analysis as AnalysisUi.Ready).appWakelocks as ListUi.Rows).rows.single()
        assertNull("a failed lookup degrades the row, not the screen", first.appLabel)

        deps.appLabels.uidFailure = null
        deps.appLabels.uidPackages[10_003] = listOf("com.news")
        deps.appLabels.labels["com.news"] = "News"
        // A refresh produced a newer analysis (a different object; an equal one would be conflated away).
        deps.analysis.value = ready(newsAnalysis(reported = null, coveredTo = NOW_MS - MIN_MS))
        runCurrent()

        val second = ((vm.state.value.analysis as AnalysisUi.Ready).appWakelocks as ListUi.Rows).rows.single()
        assertEquals("News", second.appLabel)
        assertEquals(listOf(10_003, 10_003), deps.appLabels.uidLooked)
    }

    @Test
    fun `visible refreshes once and shapes the ready analysis with owner labels`() = runTest(dispatcher) {
        deps.appLabels.labels["com.chat"] = "Chat"
        deps.analysis.value = ready(withWakelocks)
        val vm = viewModel()

        visible(vm)

        assertEquals("becoming visible refreshes once", 1, deps.refreshCalls)
        val analysis = vm.state.value.analysis as AnalysisUi.Ready
        val rows = (analysis.appWakelocks as ListUi.Rows).rows
        assertEquals(listOf("push", "fetch"), rows.map { it.name })
        assertEquals("Chat", rows[0].appLabel)
        assertEquals("com.mail", rows[1].appLabel)
        assertEquals(listOf("com.chat", "com.mail"), deps.appLabels.looked.distinct().sorted())
    }

    @Test
    fun `the hypnogram is read for the covered range of the interval and mapped to screen-off stages`() = runTest(dispatcher) {
        deps.timelineResult = night
        val covered = resolved(boundaryFrom = NOW_MS - 12 * HOUR_MS, coveredFrom = NOW_MS - 10 * HOUR_MS, coveredTo = NOW_MS)
        deps.analysis.value = ready(intervalAnalysis(resolved = covered))
        val vm = viewModel()

        visible(vm)

        assertEquals(listOf((NOW_MS - 10 * HOUR_MS) to NOW_MS), deps.timelineCalls)
        val hypnogram = vm.state.value.hypnogram as HypnogramUi.Ready
        assertEquals(listOf(HypnogramStage.LIGHT_IDLE, HypnogramStage.DEEP_IDLE), hypnogram.data.segments.map { it.stage })
        assertEquals(listOf(NOW_MS - 4 * HOUR_MS), hypnogram.data.wakePulsesMs.toList())
    }

    @Test
    fun `an unavailable timeline leaves the hypnogram failed, and a later one recovers it`() = runTest(dispatcher) {
        deps.timelineResult = null
        deps.analysis.value = ready(intervalAnalysis())
        val vm = viewModel()
        visible(vm)
        assertEquals(HypnogramUi.Failed, vm.state.value.hypnogram)
        assertTrue("the tabs do not depend on the timeline", vm.state.value.analysis is AnalysisUi.Ready)

        deps.timelineResult = night
        // A refresh produced a newer analysis (a different object; an equal one would be conflated away).
        deps.analysis.value = ready(intervalAnalysis(resolved = resolved(coveredTo = NOW_MS - MIN_MS)))
        runCurrent()

        assertTrue(vm.state.value.hypnogram is HypnogramUi.Ready)
    }

    @Test
    fun `a refresh whose timeline read fails keeps the hypnogram already shown`() = runTest(dispatcher) {
        deps.timelineResult = night
        deps.analysis.value = ready(intervalAnalysis())
        val vm = viewModel()
        visible(vm)
        val shown = vm.state.value.hypnogram
        assertTrue(shown is HypnogramUi.Ready)

        deps.timelineResult = null
        deps.analysis.value = ready(withWakelocks)
        runCurrent()

        assertEquals(shown, vm.state.value.hypnogram)
        assertEquals(2, (vm.state.value.analysis as AnalysisUi.Ready).let { (it.appWakelocks as ListUi.Rows).rows.size })
    }

    @Test
    fun `loading empty and failed analyses become designed states and clear the hypnogram`() = runTest(dispatcher) {
        deps.timelineResult = night
        deps.analysis.value = ready(intervalAnalysis())
        val vm = viewModel()
        visible(vm)
        assertTrue(vm.state.value.hypnogram is HypnogramUi.Ready)

        deps.analysis.value = AnalysisState.Loading
        runCurrent()
        assertEquals(AnalysisUi.Loading, vm.state.value.analysis)
        assertEquals("another interval's hypnogram must not linger", HypnogramUi.Loading, vm.state.value.hypnogram)

        deps.analysis.value = AnalysisState.Empty
        runCurrent()
        assertEquals(AnalysisUi.Empty, vm.state.value.analysis)

        deps.analysis.value = AnalysisState.Failed("boom")
        runCurrent()
        assertEquals(AnalysisUi.Error, vm.state.value.analysis)
    }

    @Test
    fun `without per-app snapshots the state carries the needs-full-snapshots lists`() = runTest(dispatcher) {
        deps.analysis.value = ready(intervalAnalysis(resolved = resolved(perUidAvailable = false), diff = withWakelocks.diff))
        val vm = viewModel()

        visible(vm)

        val analysis = vm.state.value.analysis as AnalysisUi.Ready
        assertEquals(ListUi.NeedsFullSnapshots, analysis.appWakelocks)
        assertEquals(ListUi.NeedsFullSnapshots, analysis.alarms)
        assertTrue("labels are not looked up for lists that are not shown", deps.appLabels.looked.isEmpty())
    }

    @Test
    fun `hidden stops shaping, and visible again shows the newest analysis`() = runTest(dispatcher) {
        deps.analysis.value = ready(intervalAnalysis())
        val vm = viewModel()
        visible(vm)
        val calls = deps.timelineCalls.size

        vm.onIntent(SleepLabIntent.Hidden)
        runCurrent()
        deps.analysis.value = ready(withWakelocks)
        runCurrent()

        assertEquals("no timeline read while hidden", calls, deps.timelineCalls.size)
        assertTrue("the old analysis stays until the screen is visible", (vm.state.value.analysis as AnalysisUi.Ready).let { (it.appWakelocks as ListUi.Rows).rows.isEmpty() })

        visible(vm)

        assertEquals(2, ((vm.state.value.analysis as AnalysisUi.Ready).appWakelocks as ListUi.Rows).rows.size)
        assertEquals(calls + 1, deps.timelineCalls.size)
    }

    @Test
    fun `a time slice selection produces the banner and clearing it goes through the selection`() = runTest(dispatcher) {
        val from = NOW_MS - 6 * HOUR_MS
        val to = NOW_MS - 2 * HOUR_MS
        deps.selection.select(ReferenceKind.TimeSlice(from, to))
        deps.analysis.value = ready(
            intervalAnalysis(resolved(boundaryFrom = from, boundaryTo = to, coveredFrom = from + 9 * MIN_MS, coveredTo = to)),
            kind = ReferenceKind.TimeSlice(from, to),
        )
        val vm = viewModel()

        visible(vm)

        val slice = vm.state.value.slice
        assertNotNull(slice)
        assertEquals(from, slice!!.requested.startMs)
        assertEquals("the banner gets the covered range and words the gap itself", from + 9 * MIN_MS, slice.covered!!.startMs)

        vm.onIntent(SleepLabIntent.ClearSlice)
        runCurrent()

        assertEquals(ReferenceKind.SinceUnplugged, deps.selection.selected.value)
        assertNull("the banner leaves with the slice", vm.state.value.slice)
    }

    @Test
    fun `a new selection shows loading, not the previous interval's lists or hypnogram, until its analysis arrives`() = runTest(dispatcher) {
        deps.timelineResult = night
        deps.analysis.value = ready(withWakelocks)
        val vm = viewModel()
        visible(vm)
        assertTrue(vm.state.value.analysis is AnalysisUi.Ready)
        val reads = deps.timelineCalls.size

        // The session has not emitted Loading for the new selection yet: its state still answers the old one.
        deps.selection.select(ReferenceKind.Last24h)
        runCurrent()
        assertEquals(AnalysisUi.Loading, vm.state.value.analysis)
        assertEquals(HypnogramUi.Loading, vm.state.value.hypnogram)
        assertEquals("nothing is read for the old interval", reads, deps.timelineCalls.size)

        deps.analysis.value = ready(intervalAnalysis(), kind = ReferenceKind.Last24h)
        runCurrent()
        assertTrue(vm.state.value.analysis is AnalysisUi.Ready)
        assertTrue(vm.state.value.hypnogram is HypnogramUi.Ready)
    }

    @Test
    fun `select tab changes only the tab`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onIntent(SleepLabIntent.SelectTab(SleepTab.Alarms))
        runCurrent()

        assertEquals(SleepTab.Alarms, vm.state.value.tab)
        assertEquals(SleepLabState.Initial.copy(tab = SleepTab.Alarms), vm.state.value)
    }

    @Test
    fun `a user refresh that fails raises one effect and a quiet one does not`() = runTest(dispatcher) {
        deps.refreshResult = RefreshResult.Failed("no shizuku")
        val vm = viewModel()
        val effects = mutableListOf<SleepLabEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }

        visible(vm)
        assertTrue("the start-up refresh fails quietly", effects.isEmpty())

        vm.onIntent(SleepLabIntent.Refresh)
        runCurrent()

        assertEquals(listOf<SleepLabEffect>(SleepLabEffect.RefreshFailed), effects)
        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun `a pull to refresh during the automatic one shows progress and reports its failure`() = runTest(dispatcher) {
        deps.refreshResult = RefreshResult.Failed("deadline")
        val gate = CompletableDeferred<Unit>().also { deps.refreshGate = it }
        val vm = viewModel()
        val effects = mutableListOf<SleepLabEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }

        visible(vm)
        vm.onIntent(SleepLabIntent.Refresh)
        runCurrent()
        assertTrue(vm.state.value.refreshing)

        gate.complete(Unit)
        runCurrent()

        assertEquals("the running refresh is reused", 1, deps.refreshCalls)
        assertEquals(listOf<SleepLabEffect>(SleepLabEffect.RefreshFailed), effects)
        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun `a debounced or successful user refresh raises no effect and ends the spinner`() = runTest(dispatcher) {
        deps.refreshResult = RefreshResult.Debounced(NOW_MS)
        val vm = viewModel()
        val effects = mutableListOf<SleepLabEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }

        vm.onIntent(SleepLabIntent.Refresh)
        runCurrent()

        assertTrue(effects.isEmpty())
        assertEquals(1, deps.refreshCalls)
        assertFalse(vm.state.value.refreshing)
    }
}
