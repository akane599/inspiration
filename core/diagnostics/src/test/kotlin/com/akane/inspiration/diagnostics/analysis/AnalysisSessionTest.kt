package com.akane.inspiration.diagnostics.analysis

import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.diff.ReferenceKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisSessionTest {
    private val hour = 3_600_000L

    private fun store() = FakeSnapshotStore(
        snapshots = listOf(lightSnapshot(wallMs = 1 * hour), lightSnapshot(wallMs = 5 * hour)),
        history = listOf(historyEvent(1 * hour, HistoryKind.UNPLUGGED)),
    )

    private class Harness(
        val store: FakeSnapshotStore,
        val selection: AnalysisSelection,
        val ticks: MutableSharedFlow<Long>,
        val session: AnalysisSession,
    )

    private fun TestScope.harness(store: FakeSnapshotStore = store()): Harness {
        val selection = AnalysisSelection()
        val ticks = MutableSharedFlow<Long>()
        val session = AnalysisSession(
            analyzer = IntervalAnalyzer(store, ruleEngine()),
            selection = selection,
            refreshTicks = ticks,
            scope = backgroundScope,
            nowWallMs = { 6 * hour },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        return Harness(store, selection, ticks, session)
    }

    @Test
    fun `selection defaults to since unplugged`() {
        assertEquals(ReferenceKind.SinceUnplugged, AnalysisSelection().selected.value)
    }

    @Test
    fun `starts Loading then becomes Ready with the analysis`() = runTest {
        val h = harness()
        assertEquals(AnalysisState.Loading, h.session.state.value)

        runCurrent()

        val ready = h.session.state.value as AnalysisState.Ready
        assertEquals(1 * hour, ready.analysis.resolved.coveredFromWallMs)
        assertTrue(h.store.historyReads > 0)
    }

    @Test
    fun `becomes Empty when the selected interval has no snapshot pair`() = runTest {
        val h = harness(FakeSnapshotStore())

        runCurrent()

        assertEquals(AnalysisState.Empty, h.session.state.value)
    }

    @Test
    fun `becomes Failed with the error message and recovers on the next tick`() = runTest {
        val broken = store().apply { failure = IllegalStateException("db closed") }
        val h = harness(broken)

        runCurrent()
        assertEquals(AnalysisState.Failed("db closed"), h.session.state.value)

        broken.failure = null
        h.ticks.emit(1)
        runCurrent()
        assertTrue(h.session.state.value is AnalysisState.Ready)
    }

    @Test
    fun `a selection change shows Loading then the new interval`() = runTest {
        val h = harness()
        runCurrent()
        val first = (h.session.state.value as AnalysisState.Ready).analysis

        val gate = CompletableDeferred<Unit>().also { h.store.gate = it }
        h.selection.select(ReferenceKind.TimeSlice(fromWallMs = 1 * hour, toWallMs = 3 * hour))
        runCurrent()
        assertEquals(AnalysisState.Loading, h.session.state.value)

        h.store.snapshots += lightSnapshot(wallMs = 3 * hour)
        gate.complete(Unit)
        runCurrent()

        val second = (h.session.state.value as AnalysisState.Ready).analysis
        assertEquals(5 * hour, first.resolved.coveredToWallMs)
        assertEquals(3 * hour, second.resolved.coveredToWallMs)
    }

    @Test
    fun `a refresh tick recomputes and keeps the previous state while it runs`() = runTest {
        val h = harness()
        runCurrent()
        val before = h.session.state.value as AnalysisState.Ready
        val reads = h.store.historyReads

        val gate = CompletableDeferred<Unit>().also { h.store.gate = it }
        h.ticks.emit(1)
        runCurrent()
        assertSame(before, h.session.state.value)

        h.store.snapshots += lightSnapshot(wallMs = 6 * hour)
        gate.complete(Unit)
        runCurrent()

        val after = h.session.state.value as AnalysisState.Ready
        assertEquals(6 * hour, after.analysis.resolved.coveredToWallMs)
        assertTrue(h.store.historyReads > reads)
    }

    @Test
    fun `a newer selection supersedes an in-flight computation`() = runTest {
        val h = harness()
        runCurrent()
        val gate = CompletableDeferred<Unit>().also { h.store.gate = it }

        h.selection.select(ReferenceKind.Last24h)
        runCurrent()
        h.selection.select(ReferenceKind.TimeSlice(fromWallMs = 1 * hour, toWallMs = 5 * hour))
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        val ready = h.session.state.value as AnalysisState.Ready
        assertEquals(1 * hour, ready.analysis.resolved.boundaryFromWallMs)
        assertEquals(5 * hour, ready.analysis.resolved.boundaryToWallMs)
    }
}
