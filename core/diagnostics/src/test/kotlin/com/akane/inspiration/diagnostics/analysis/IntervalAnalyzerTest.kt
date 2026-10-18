package com.akane.inspiration.diagnostics.analysis

import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.diff.ReferenceKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalAnalyzerTest {
    private val hour = 3_600_000L

    private fun store() = FakeSnapshotStore(
        snapshots = listOf(
            lightSnapshot(wallMs = 1 * hour, level = 90),
            lightSnapshot(wallMs = 5 * hour, level = 70),
        ),
        history = listOf(
            historyEvent(1 * hour, HistoryKind.UNPLUGGED),
            historyEvent(1 * hour, HistoryKind.SCREEN_OFF),
            historyEvent(1 * hour, HistoryKind.LEVEL, level = 90),
            historyEvent(5 * hour, HistoryKind.LEVEL, level = 70),
        ),
    )

    @Test
    fun `since unplugged runs resolve diff metrics rules and story over the covered interval`() = runTest {
        val analysis = IntervalAnalyzer(store(), ruleEngine()).analyze(ReferenceKind.SinceUnplugged, nowWallMs = 6 * hour)

        assertNotNull(analysis)
        analysis!!
        assertEquals(1 * hour, analysis.resolved.coveredFromWallMs)
        assertEquals(5 * hour, analysis.resolved.coveredToWallMs)
        assertEquals(4 * hour, analysis.diff.durationMs)
        assertEquals(analysis.resolved.coveredFromWallMs, analysis.metrics.fromWallMs)
        assertEquals(analysis.resolved.coveredToWallMs, analysis.metrics.toWallMs)
        assertEquals(20, analysis.metrics.levelUsedPct)
        // LIGHT endpoints carry no per-UID data, so UID-based rules are not evaluable.
        assertFalse(analysis.resolved.perUidAvailable)
        assertTrue(analysis.report.insights.isEmpty())
        assertNotNull(analysis.story)
    }

    @Test
    fun `one analysis loads the stored history once for resolution and metrics`() = runTest {
        val store = store()

        assertNotNull(IntervalAnalyzer(store, ruleEngine()).analyze(ReferenceKind.SinceUnplugged, nowWallMs = 6 * hour))

        assertEquals(1, store.historyReads)
    }

    @Test
    fun `returns null when the store has no snapshots`() = runTest {
        val analysis = IntervalAnalyzer(FakeSnapshotStore(), ruleEngine()).analyze(ReferenceKind.Last24h, nowWallMs = 6 * hour)

        assertNull(analysis)
    }

    @Test
    fun `returns null when the selected reference cannot be resolved`() = runTest {
        // Snapshots exist but no UNPLUGGED event or POWER_DISCONNECTED snapshot does.
        val store = FakeSnapshotStore(snapshots = listOf(lightSnapshot(wallMs = 1 * hour), lightSnapshot(wallMs = 2 * hour)))

        assertNull(IntervalAnalyzer(store, ruleEngine()).analyze(ReferenceKind.SinceUnplugged, nowWallMs = 3 * hour))
    }

    @Test
    fun `time slice analyzes only the requested boundaries`() = runTest {
        val analysis = IntervalAnalyzer(store(), ruleEngine())
            .analyze(ReferenceKind.TimeSlice(fromWallMs = 1 * hour, toWallMs = 5 * hour), nowWallMs = 9 * hour)

        assertEquals(5 * hour, analysis!!.resolved.coveredToWallMs)
    }

    @Test
    fun `propagates store failures to the caller`() = runTest {
        val store = store().apply { failure = IllegalStateException("db closed") }
        val result = runCatching { IntervalAnalyzer(store, ruleEngine()).analyze(ReferenceKind.SinceUnplugged, nowWallMs = 6 * hour) }

        assertEquals("db closed", result.exceptionOrNull()?.message)
    }
}
