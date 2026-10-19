package com.akane.inspiration.diagnostics.analysis

import com.akane.inspiration.diagnostics.story.StorySentence
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.diff.ReferenceResolver
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.SourceAvailability
import com.akane.inspiration.telemetry.store.HISTORY_SOURCE
import com.akane.inspiration.telemetry.store.SnapshotStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalAnalyzerTest {
    private val hour = 3_600_000L

    private fun store() =
        FakeSnapshotStore(
            snapshots =
                listOf(
                    lightSnapshot(wallMs = 1 * hour, level = 90),
                    lightSnapshot(wallMs = 5 * hour, level = 70),
                ),
            history =
                listOf(
                    historyEvent(1 * hour, HistoryKind.UNPLUGGED),
                    historyEvent(1 * hour, HistoryKind.SCREEN_OFF),
                    historyEvent(1 * hour, HistoryKind.LEVEL, level = 90),
                    historyEvent(5 * hour, HistoryKind.LEVEL, level = 70),
                ),
        )

    @Test
    fun `since unplugged runs resolve diff metrics rules and story over the covered interval`() =
        runTest {
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
    fun `one analysis loads the stored history once for resolution and metrics`() =
        runTest {
            val store = store()

            assertNotNull(IntervalAnalyzer(store, ruleEngine()).analyze(ReferenceKind.SinceUnplugged, nowWallMs = 6 * hour))

            assertEquals(1, store.historyReads)
        }

    @Test
    fun `returns null when the store has no snapshots`() =
        runTest {
            val analysis = IntervalAnalyzer(FakeSnapshotStore(), ruleEngine()).analyze(ReferenceKind.Last24h, nowWallMs = 6 * hour)

            assertNull(analysis)
        }

    @Test
    fun `returns null when the selected reference cannot be resolved`() =
        runTest {
            // Snapshots exist but no UNPLUGGED event or POWER_DISCONNECTED snapshot does.
            val store = FakeSnapshotStore(snapshots = listOf(lightSnapshot(wallMs = 1 * hour), lightSnapshot(wallMs = 2 * hour)))

            assertNull(IntervalAnalyzer(store, ruleEngine()).analyze(ReferenceKind.SinceUnplugged, nowWallMs = 3 * hour))
        }

    @Test
    fun `time slice analyzes only the requested boundaries`() =
        runTest {
            val analysis =
                IntervalAnalyzer(store(), ruleEngine())
                    .analyze(ReferenceKind.TimeSlice(fromWallMs = 1 * hour, toWallMs = 5 * hour), nowWallMs = 9 * hour)

            assertEquals(5 * hour, analysis!!.resolved.coveredToWallMs)
        }

    /** [stored] whose marker 1 is [snapshot], set at that snapshot's wall time. */
    private fun withMarker(
        stored: FakeSnapshotStore,
        snapshot: BatterySnapshot,
    ) = object : SnapshotStore by stored {
        override suspend fun marker(id: Long) = (snapshot.clock.wallMs to snapshot).takeIf { id == 1L }
    }

    @Test
    fun `a marker selected right after it was set is empty, not a zero-length analysis`() =
        runTest {
            // "Mark now" stores a FULL MARKER capture; until the next capture it is the newest snapshot too.
            val marked = lightSnapshot(wallMs = 5 * hour, level = 70, trigger = CaptureTrigger.MARKER).copy(kind = SnapshotKind.FULL)
            val stored = FakeSnapshotStore(snapshots = listOf(lightSnapshot(wallMs = 1 * hour, level = 90), marked))
            val analyzer = IntervalAnalyzer(withMarker(stored, marked), ruleEngine())

            assertNull(analyzer.analyze(ReferenceKind.CustomMarker(1), nowWallMs = 5 * hour))

            stored.snapshots += lightSnapshot(wallMs = 6 * hour, level = 68)
            val analysis = analyzer.analyze(ReferenceKind.CustomMarker(1), nowWallMs = 6 * hour)!!
            assertEquals(marked, analysis.resolved.ref)
            assertEquals(1 * hour, analysis.diff.durationMs)
        }

    @Test
    fun `a pair with no wall time between its snapshots is empty`() =
        runTest {
            // A marker from the previous boot and the first capture after the reboot share a wall-clock instant.
            val marked = lightSnapshot(wallMs = 5 * hour, level = 70, trigger = CaptureTrigger.MARKER)
            val afterReboot = lightSnapshot(wallMs = 5 * hour, elapsedMs = 60_000L, level = 70, trigger = CaptureTrigger.BOOT)
            val rebooted = afterReboot.copy(clock = afterReboot.clock.copy(bootCount = 2))
            val stored = FakeSnapshotStore(snapshots = listOf(rebooted, marked))
            val analyzer = IntervalAnalyzer(withMarker(stored, marked), ruleEngine())

            // The resolver keeps a cross-boot marker pair (the differ flags it), so only the analyzer's guard empties it.
            val resolved = ReferenceResolver(withMarker(stored, marked)).resolve(ReferenceKind.CustomMarker(1), 5 * hour)!!
            assertEquals(marked, resolved.ref)
            assertEquals(rebooted, resolved.now)
            assertEquals(resolved.coveredFromWallMs, resolved.coveredToWallMs)
            assertNull(analyzer.analyze(ReferenceKind.CustomMarker(1), nowWallMs = 5 * hour))
        }

    @Test
    fun `propagates store failures to the caller`() =
        runTest {
            val store = store().apply { failure = IllegalStateException("db closed") }
            val result = runCatching { IntervalAnalyzer(store, ruleEngine()).analyze(ReferenceKind.SinceUnplugged, nowWallMs = 6 * hour) }

            assertEquals("db closed", result.exceptionOrNull()?.message)
        }

    /** An app-open capture whose read stored the whole battery history (every page available). */
    private fun ingestedSnapshot(
        wallMs: Long,
        level: Int,
    ) = lightSnapshot(wallMs = wallMs, level = level).copy(
        kind = SnapshotKind.FULL,
        availability = mapOf(HISTORY_SOURCE to SourceAvailability(true, null)),
    )

    /**
     * Unplugged and screen off at 1 h (90%); history was last ingested at 3 h (88%). A screen-on broadcast
     * stores a LIGHT snapshot 6 h later, with no history ingest after 3 h.
     */
    private fun staleHistoryStore(end: BatterySnapshot) =
        FakeSnapshotStore(
            snapshots = listOf(ingestedSnapshot(1 * hour, level = 90), ingestedSnapshot(3 * hour, level = 88), end),
            history =
                listOf(
                    historyEvent(1 * hour, HistoryKind.UNPLUGGED, level = 90),
                    historyEvent(1 * hour, HistoryKind.SCREEN_OFF, level = 90),
                    historyEvent(1 * hour, HistoryKind.LEVEL, level = 90),
                    historyEvent(3 * hour, HistoryKind.LEVEL, level = 88),
                ),
        )

    @Test
    fun `time after the last history ingest is unknown so the story has no summary or diluted rate`() =
        runTest {
            val end = lightSnapshot(wallMs = 9 * hour, level = 60, trigger = CaptureTrigger.SCREEN_ON)
            val analysis =
                IntervalAnalyzer(staleHistoryStore(end), ruleEngine())
                    .analyze(ReferenceKind.SinceUnplugged, nowWallMs = 9 * hour)!!

            assertEquals(9 * hour, analysis.resolved.coveredToWallMs)
            assertEquals(2 * hour, analysis.metrics.screenOffMs)
            assertEquals(6 * hour, analysis.metrics.unknownMs)
            // 2% over the 2 known hours, not over all 8.
            assertEquals(1.0, analysis.metrics.screenOffPctPerHour!!, 1e-9)
            val sentences = analysis.story.sentences
            assertTrue(sentences.none { it is StorySentence.Summary })
            assertEquals(1.0, sentences.filterIsInstance<StorySentence.ScreenOffRate>().single().pctPerHour, 1e-9)
        }

    @Test
    fun `an endpoint that ingested history keeps the last state to the interval end`() =
        runTest {
            val analysis =
                IntervalAnalyzer(staleHistoryStore(ingestedSnapshot(9 * hour, level = 87)), ruleEngine())
                    .analyze(ReferenceKind.SinceUnplugged, nowWallMs = 9 * hour)!!

            assertEquals(8 * hour, analysis.metrics.screenOffMs)
            assertEquals(0L, analysis.metrics.unknownMs)
            assertTrue(analysis.story.sentences.any { it is StorySentence.Summary })
        }

    @Test
    fun `with no recorded history ingest only time up to the newest event is known`() =
        runTest {
            val store =
                staleHistoryStore(lightSnapshot(wallMs = 9 * hour, level = 60)).apply {
                    snapshots = snapshots.map { it.copy(availability = emptyMap()) }
                }
            val analysis = IntervalAnalyzer(store, ruleEngine()).analyze(ReferenceKind.SinceUnplugged, nowWallMs = 9 * hour)!!

            assertEquals(2 * hour, analysis.metrics.screenOffMs)
            assertEquals(6 * hour, analysis.metrics.unknownMs)
        }

    private suspend fun analyzeWithLightEndAfterIngest(tailMs: Long): IntervalAnalysis {
        val end = lightSnapshot(wallMs = 3 * hour + tailMs, level = 88, trigger = CaptureTrigger.SCREEN_ON)
        return IntervalAnalyzer(staleHistoryStore(end), ruleEngine())
            .analyze(ReferenceKind.SinceUnplugged, nowWallMs = 3 * hour + tailMs)!!
    }

    @Test
    fun `a screen broadcast 90 s after the last ingest keeps the summary`() =
        runTest {
            val analysis = analyzeWithLightEndAfterIngest(90_000L)

            assertEquals(0L, analysis.metrics.unknownMs)
            assertEquals(2 * hour + 90_000L, analysis.metrics.screenOffMs)
            assertTrue(analysis.story.sentences.any { it is StorySentence.Summary })
        }

    @Test
    fun `a 10 min tail past the last ingest is unknown in full and drops the summary`() =
        runTest {
            val analysis = analyzeWithLightEndAfterIngest(10 * 60_000L)

            assertEquals(10 * 60_000L, analysis.metrics.unknownMs)
            assertEquals(2 * hour, analysis.metrics.screenOffMs)
            assertTrue(analysis.story.sentences.none { it is StorySentence.Summary })
        }
}
