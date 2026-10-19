package com.akane.inspiration.diagnostics.analysis

import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.diff.ReferenceKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisSessionTest {
    private val hour = 3_600_000L

    private fun store() =
        FakeSnapshotStore(
            snapshots = listOf(lightSnapshot(wallMs = 1 * hour), lightSnapshot(wallMs = 5 * hour)),
            history = listOf(historyEvent(1 * hour, HistoryKind.UNPLUGGED)),
        )

    private class Harness(
        val store: FakeSnapshotStore,
        val selection: AnalysisSelection,
        val ticks: MutableSharedFlow<Long>,
        val session: AnalysisSession,
    )

    /** [subscribe] starts a collector, since the session only computes while its state is observed. */
    private fun TestScope.harness(
        store: FakeSnapshotStore = store(),
        subscribe: Boolean = true,
    ): Harness {
        val selection = AnalysisSelection()
        val ticks = MutableSharedFlow<Long>()
        val session =
            AnalysisSession(
                analyzer = IntervalAnalyzer(store, ruleEngine()),
                selection = selection,
                refreshTicks = ticks,
                scope = backgroundScope,
                nowWallMs = { 6 * hour },
                dispatcher = StandardTestDispatcher(testScheduler),
            )
        if (subscribe) backgroundScope.launch { session.state.collect {} }
        return Harness(store, selection, ticks, session)
    }

    @Test
    fun `selection defaults to since unplugged`() {
        assertEquals(ReferenceKind.SinceUnplugged, AnalysisSelection().selected.value)
    }

    @Test
    fun `starts Loading then becomes Ready with the analysis`() =
        runTest {
            val h = harness()
            assertEquals(AnalysisState.Loading, h.session.state.value)

            runCurrent()

            val ready = h.session.state.value as AnalysisState.Ready
            assertEquals(1 * hour, ready.analysis.resolved.coveredFromWallMs)
            assertTrue(h.store.historyReads > 0)
        }

    @Test
    fun `a ready state names the selection it answers, and is loading for any other`() =
        runTest {
            val h = harness()
            runCurrent()
            val first = h.session.state.value as AnalysisState.Ready
            assertEquals(ReferenceKind.SinceUnplugged, first.kind)
            assertSame(first, first.forSelection(ReferenceKind.SinceUnplugged))
            assertEquals(AnalysisState.Loading, first.forSelection(ReferenceKind.Last24h))
            assertEquals(AnalysisState.Empty, AnalysisState.Empty.forSelection(ReferenceKind.Last24h))

            val slice = ReferenceKind.TimeSlice(fromWallMs = 1 * hour, toWallMs = 5 * hour)
            h.selection.select(slice)
            runCurrent()

            val second = h.session.state.value as AnalysisState.Ready
            assertEquals(slice, second.kind)
            assertEquals(AnalysisState.Loading, second.forSelection(ReferenceKind.TimeSlice(fromWallMs = 2 * hour, toWallMs = 5 * hour)))
        }

    @Test
    fun `becomes Empty when the selected interval has no snapshot pair`() =
        runTest {
            val h = harness(FakeSnapshotStore())

            runCurrent()

            assertEquals(AnalysisState.Empty, h.session.state.value)
        }

    @Test
    fun `becomes Failed with the error message and recovers on the next tick`() =
        runTest {
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
    fun `recompute re-runs a failed analysis without a refresh tick and keeps the state while it runs`() =
        runTest {
            val broken = store().apply { failure = IllegalStateException("db closed") }
            val h = harness(broken)
            runCurrent()
            val failed = h.session.state.value
            assertEquals(AnalysisState.Failed("db closed"), failed)

            broken.failure = null
            val gate = CompletableDeferred<Unit>().also { broken.gate = it }
            h.session.recompute()
            runCurrent()
            assertSame("a recompute keeps the shown state, never Loading", failed, h.session.state.value)

            gate.complete(Unit)
            runCurrent()
            assertTrue(h.session.state.value is AnalysisState.Ready)
        }

    @Test
    fun `recompute keeps a ready state until the fresh one replaces it`() =
        runTest {
            val h = harness()
            runCurrent()
            val before = h.session.state.value as AnalysisState.Ready

            val gate = CompletableDeferred<Unit>().also { h.store.gate = it }
            h.session.recompute()
            runCurrent()
            assertSame(before, h.session.state.value)

            h.store.snapshots += lightSnapshot(wallMs = 6 * hour)
            gate.complete(Unit)
            runCurrent()
            assertEquals(6 * hour, (h.session.state.value as AnalysisState.Ready).analysis.resolved.coveredToWallMs)
        }

    @Test
    fun `a selection change shows Loading then the new interval`() =
        runTest {
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
    fun `a refresh tick recomputes and keeps the previous state while it runs`() =
        runTest {
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
    fun `a newer selection supersedes an in-flight computation`() =
        runTest {
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

    @Test
    fun `computes only while the state is observed and keeps the last state in between`() =
        runTest {
            val h = harness(subscribe = false)
            runCurrent()
            assertEquals(AnalysisState.Loading, h.session.state.value)
            assertEquals(0, h.store.historyReads)

            val first = backgroundScope.launch { h.session.state.collect {} }
            runCurrent()
            val ready = h.session.state.value as AnalysisState.Ready
            val reads = h.store.historyReads
            assertTrue(reads > 0)

            first.cancel()
            runCurrent()
            h.ticks.emit(1)
            runCurrent()
            assertEquals(reads, h.store.historyReads)
            assertSame(ready, h.session.state.value)

            // A returning observer sees the kept state at once, not Loading, while the fresh compute runs.
            val gate = CompletableDeferred<Unit>().also { h.store.gate = it }
            backgroundScope.launch { h.session.state.collect {} }
            runCurrent()
            assertSame(ready, h.session.state.value)
            gate.complete(Unit)
            runCurrent()
            assertTrue(h.store.historyReads > reads)
            assertTrue(h.session.state.value is AnalysisState.Ready)
        }

    @Test
    fun `a finished result for the old selection never replaces Loading for the new one`() =
        runTest {
            // Sweep where B's selection lands relative to A's completion, so the case where A's result is
            // already on its way to the state while B's Loading is written is covered deterministically.
            repeat(40) { yields ->
                val h = harness(store().apply { snapshots += lightSnapshot(wallMs = 3 * hour) })
                runCurrent()

                val gateA = CompletableDeferred<Unit>().also { h.store.gate = it }
                h.selection.select(ReferenceKind.Last24h)
                runCurrent()
                assertEquals(AnalysisState.Loading, h.session.state.value)

                gateA.complete(Unit)
                backgroundScope.launch {
                    repeat(yields) { yield() }
                    // B's own read stays blocked, so Loading must hold whatever A did.
                    h.store.gate = CompletableDeferred()
                    h.selection.select(ReferenceKind.TimeSlice(fromWallMs = 1 * hour, toWallMs = 3 * hour))
                }
                runCurrent()

                assertEquals("after $yields yields", AnalysisState.Loading, h.session.state.value)
            }
        }

    @Test
    fun `selection changes on real threads always land on Loading before the new result`() =
        runBlocking {
            val store = store().apply { snapshots += lightSnapshot(wallMs = 3 * hour) }
            val selection = AnalysisSelection()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val session =
                    AnalysisSession(
                        analyzer = IntervalAnalyzer(store, ruleEngine()),
                        selection = selection,
                        refreshTicks = MutableSharedFlow(),
                        scope = scope,
                        nowWallMs = { 6 * hour },
                        dispatcher = Dispatchers.IO,
                    )
                scope.launch { session.state.collect {} }
                val slices = listOf(3 * hour, 5 * hour).map { ReferenceKind.TimeSlice(fromWallMs = 1 * hour, toWallMs = it) }
                repeat(200) { i ->
                    val target = slices[i % 2]
                    val gate = CompletableDeferred<Unit>()
                    // The previous selection is computing (ungated) right as the new one is chosen.
                    store.gate = null
                    selection.select(slices[(i + 1) % 2])
                    store.gate = gate
                    selection.select(target)
                    // While the target's read is blocked the state must settle on Loading; a stale Ready written
                    // after it would stay until the gate opens and time this out.
                    withTimeout(5_000) { session.state.awaitSettledLoading() }
                    gate.complete(Unit)
                    withTimeout(5_000) {
                        session.state.first { (it as? AnalysisState.Ready)?.analysis?.resolved?.boundaryToWallMs == target.toWallMs }
                    }
                }
            } finally {
                scope.cancel()
            }
        }

    private suspend fun StateFlow<AnalysisState>.awaitSettledLoading() {
        while (true) {
            if (value == AnalysisState.Loading) {
                delay(5)
                if (value == AnalysisState.Loading) return
            } else {
                delay(1)
            }
        }
    }
}
