package com.akane.inspiration.diagnostics.analysis

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.store.SnapshotStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One analysis reads its snapshot pair, the history horizon and the interval's history from one store state: a capture
 * that stores history and prunes, landing between those reads, is seen by all of them or by none.
 */
class IntervalAnalyzerConsistencyTest {
    private val hour = 3_600_000L

    private val history =
        listOf(
            historyEvent(1 * hour, HistoryKind.UNPLUGGED, level = 90),
            historyEvent(1 * hour, HistoryKind.SCREEN_OFF, level = 90),
            historyEvent(1 * hour, HistoryKind.LEVEL, level = 90),
            historyEvent(3 * hour, HistoryKind.LEVEL, level = 80),
            historyEvent(4 * hour, HistoryKind.SCREEN_ON, level = 80),
            historyEvent(5 * hour, HistoryKind.LEVEL, level = 70),
        )

    /** A store write from elsewhere: a newer history page stored, then the oldest events pruned. */
    private fun FakeSnapshotStore.storeAndPrune() {
        history = history.filter { it.wallMs > 1 * hour } + historyEvent(2 * hour, HistoryKind.LEVEL, level = 40)
    }

    /**
     * [stored] seen through a store whose [readConsistently] isolates like the Room transaction: a write that arrives
     * while a consistent read is open lands when it closes. The write arrives at the first history range or horizon
     * read, after the resolver's snapshot and newest-of-kind reads and before the rest of the interval's history.
     */
    private class InterleavingStore(
        private val stored: FakeSnapshotStore,
        private val write: FakeSnapshotStore.() -> Unit,
    ) : SnapshotStore by stored {
        private var depth = 0
        private var pending: (() -> Unit)? = null
        var wrote = false
        var readsOutside = 0

        override suspend fun <R> readConsistently(block: suspend () -> R): R {
            depth++
            try {
                return block()
            } finally {
                depth--
                if (depth == 0) pending?.invoke().also { pending = null }
            }
        }

        private fun interleave() {
            if (depth == 0) readsOutside++
            if (wrote) return
            wrote = true
            val apply = { stored.write() }
            if (depth > 0) pending = apply else apply()
        }

        override suspend fun historyHorizonWallMs(): Long? {
            interleave()
            return stored.historyHorizonWallMs()
        }

        override suspend fun historyBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ): List<HistoryEvent> {
            interleave()
            return stored.historyBetween(fromWallMs, toWallMs)
        }
    }

    @Test
    fun `a store and prune between the analysis reads leaves the analysis on the state it started from`() =
        runTest {
            fun stored() = FakeSnapshotStore(listOf(lightSnapshot(1 * hour, level = 90), lightSnapshot(5 * hour, level = 70)), history)
            val before = IntervalAnalyzer(stored(), ruleEngine()).analyze(ReferenceKind.SinceUnplugged, 6 * hour)!!
            val afterStore = stored().apply { storeAndPrune() }
            val after = IntervalAnalyzer(afterStore, ruleEngine()).analyze(ReferenceKind.SinceUnplugged, 6 * hour)
            // The write does change what the history measures, so a mixed view would show.
            assertNotEquals(before.metrics, after?.metrics)

            val interleaved = stored()
            val store = InterleavingStore(interleaved) { storeAndPrune() }
            val analysis = IntervalAnalyzer(store, ruleEngine()).analyze(ReferenceKind.SinceUnplugged, 6 * hour)!!

            assertTrue("the write happened during the analysis", store.wrote)
            assertEquals(before.resolved, analysis.resolved)
            assertEquals(before.metrics, analysis.metrics)
            assertEquals(before.report, analysis.report)
            assertEquals("history reads outside one consistent read", 0, store.readsOutside)
            // The write was deferred, not lost.
            assertEquals(afterStore.history, interleaved.history)
        }

    @Test
    fun `a store and prune between the history only reads leaves them on one state`() =
        runTest {
            // No capture brackets the screen-off period: only the history times it.
            val before = IntervalAnalyzer(FakeSnapshotStore(history = history), ruleEngine()).historyOnly(ReferenceKind.SinceScreenOff, 6 * hour)!!
            val afterStore = FakeSnapshotStore(history = history).apply { storeAndPrune() }
            val after = IntervalAnalyzer(afterStore, ruleEngine()).historyOnly(ReferenceKind.SinceScreenOff, 6 * hour)
            assertNotEquals(before, after)

            val interleaved = FakeSnapshotStore(history = history)
            val store = InterleavingStore(interleaved) { storeAndPrune() }
            val metrics = IntervalAnalyzer(store, ruleEngine()).historyOnly(ReferenceKind.SinceScreenOff, 6 * hour)

            assertTrue("the write happened during the analysis", store.wrote)
            assertEquals(before, metrics)
            assertEquals("history reads outside one consistent read", 0, store.readsOutside)
            assertEquals(afterStore.history, interleaved.history)
        }
}
