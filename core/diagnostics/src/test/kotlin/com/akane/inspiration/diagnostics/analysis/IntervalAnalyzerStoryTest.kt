package com.akane.inspiration.diagnostics.analysis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.akane.inspiration.diagnostics.story.AndroidStoryWriter
import com.akane.inspiration.diagnostics.story.StorySentence
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.snapshot.CpuFreqTime
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.store.SnapshotStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Stored coverage is not proof of a live interval, including when a cached story ages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IntervalAnalyzerStoryTest {
    private val hour = 3_600_000L
    private val writer = AndroidStoryWriter(ApplicationProvider.getApplicationContext<Context>())
    private val tr = Locale.forLanguageTag("tr")

    private fun storedSnapshot(
        wallMs: Long,
        level: Int,
        energyMah: Double,
    ) = lightSnapshot(wallMs = wallMs, level = level).let { snapshot ->
        snapshot.copy(
            kind = SnapshotKind.FULL,
            clock = snapshot.clock.copy(statsStartMs = 0L, statsDurationMs = wallMs),
            battery = snapshot.battery.copy(dischargedMah = energyMah),
            deviceComponentsMah = mapOf("cpu" to energyMah),
            cpuFreq = listOf(CpuFreqTime(policy = 0, freqKhz = 1_000_000L, timeMs = wallMs / 100)),
        )
    }

    /** Unplugged/screen-off at 01:00, 90%; 70% by 04:00, then screen-on at 05:00 without another drop. */
    private fun store() =
        FakeSnapshotStore(
            snapshots =
                listOf(
                    storedSnapshot(wallMs = 1 * hour, level = 90, energyMah = 10.0),
                    storedSnapshot(wallMs = 5 * hour, level = 70, energyMah = 30.0),
                ),
            history =
                listOf(
                    historyEvent(1 * hour, HistoryKind.UNPLUGGED, level = 90),
                    historyEvent(1 * hour, HistoryKind.SCREEN_OFF, level = 90),
                    historyEvent(1 * hour, HistoryKind.LEVEL, level = 90),
                    // A simultaneous SCREEN_ON/LEVEL attributes the drop to the new screen state.
                    historyEvent(4 * hour, HistoryKind.LEVEL, level = 70),
                    historyEvent(5 * hour, HistoryKind.SCREEN_ON, level = 70),
                ),
        )

    private suspend fun analyze(
        kind: ReferenceKind,
        nowWallMs: Long,
    ): IntervalAnalysis {
        val stored = store()
        if (kind == ReferenceKind.ScreenOnSession) {
            stored.history = stored.history.filterNot {
                it.kind == HistoryKind.SCREEN_OFF || it.kind == HistoryKind.SCREEN_ON
            } + historyEvent(1 * hour, HistoryKind.SCREEN_ON, level = 90)
        }
        val source =
            object : SnapshotStore by stored {
                override suspend fun marker(id: Long) = (hour to stored.snapshots.first()).takeIf { id == 1L }
            }
        return checkNotNull(IntervalAnalyzer(source, ruleEngine()).analyze(kind, nowWallMs)) {
            "$kind resolves no interval"
        }
    }

    private suspend fun render(
        kind: ReferenceKind,
        nowWallMs: Long,
        locale: Locale,
    ): String = writer.render(analyze(kind, nowWallMs).story, locale)

    private fun assertStartsWith(
        expected: String,
        actual: String,
    ) = assertTrue("expected \"$actual\" to start with \"$expected\"", actual.startsWith(expected))

    private fun assertNeutralStory(analysis: IntervalAnalysis) {
        val summary =
            analysis.story.sentences
                .filterIsInstance<StorySentence.Summary>()
                .single()
        assertEquals(4.0, summary.hours, 0.0)
        assertEquals(20, summary.levelUsedPct)
        assertFalse(summary.endsNow)
        assertStartsWith(
            "Over these 4 hours you used 20% of your battery.",
            writer.render(analysis.story, Locale.ENGLISH),
        )
        assertStartsWith("Bu 4 saatte pilin %20 kadarını kullandın.", writer.render(analysis.story, tr))
    }

    @Test
    fun `a past time slice is not described as the last hours`() =
        runTest {
            // The slice 01:00-05:00 is analysed at 24:00, 19 hours after it ended.
            val slice = ReferenceKind.TimeSlice(fromWallMs = 1 * hour, toWallMs = 5 * hour)

            assertNeutralStory(analyze(slice, 24 * hour))
        }

    @Test
    fun `a completed screen-off is not described as the last hours`() =
        runTest {
            // The screen-off 01:00-05:00 is the last completed one; it is analysed an hour after it ended.
            assertNeutralStory(analyze(ReferenceKind.SinceScreenOff, 6 * hour))
        }

    @Test
    fun `a stored endpoint matching analysis time still uses interval wording`() =
        runTest {
            assertNeutralStory(analyze(ReferenceKind.SinceUnplugged, 5 * hour))
        }

    @Test
    fun `open-ended references falling back to old coverage keep facts but not last hours wording`() =
        runTest {
            val references =
                listOf(
                    ReferenceKind.SinceUnplugged,
                    ReferenceKind.ScreenOnSession,
                    ReferenceKind.Last24h,
                    ReferenceKind.CustomMarker(markerId = 1L),
                )
            for (kind in references) {
                val analysis = analyze(kind, 24 * hour)

                assertEquals(24 * hour, analysis.resolved.boundaryToWallMs)
                assertEquals(1 * hour, analysis.resolved.coveredFromWallMs)
                assertEquals(5 * hour, analysis.resolved.coveredToWallMs)
                assertTrue(analysis.resolved.gapMs >= 19 * hour)
                assertTrue(analysis.resolved.perUidAvailable)
                assertEquals(4 * hour, analysis.diff.durationMs)
                assertTrue(analysis.diff.validity.batteryStats)
                assertTrue(analysis.diff.validity.kernelCounters)
                assertEquals(20.0, checkNotNull(analysis.diff.dischargedMah), 0.0)
                assertEquals(20.0, checkNotNull(analysis.diff.deviceComponentsMah["cpu"]), 0.0)
                assertEquals(
                    4 * hour / 100,
                    analysis.diff.cpuFreqDelta
                        .single()
                        .timeMs,
                )
                assertEquals(20, analysis.metrics.levelUsedPct)
                val rate =
                    if (kind == ReferenceKind.ScreenOnSession) {
                        assertEquals(4 * hour, analysis.metrics.screenOnMs)
                        analysis.metrics.screenOnPctPerHour
                    } else {
                        assertEquals(4 * hour, analysis.metrics.screenOffMs)
                        analysis.metrics.screenOffPctPerHour
                    }
                assertEquals(5.0, checkNotNull(rate), 0.0)
                assertNeutralStory(analysis)
            }
        }

    @Test
    fun `a cached story stays truthful after its endpoint ages without changing measured facts`() =
        runTest {
            val captured = analyze(ReferenceKind.SinceUnplugged, 5 * hour)
            val later = analyze(ReferenceKind.SinceUnplugged, 24 * hour)

            assertEquals(captured.diff, later.diff)
            assertEquals(captured.metrics, later.metrics)
            assertEquals(captured.story, later.story)
            // Rendering the retained result must be truthful too, not just a later recomputation.
            assertNeutralStory(captured)
            assertNeutralStory(later)
            assertEquals(
                render(ReferenceKind.SinceUnplugged, 24 * hour, Locale.ENGLISH),
                writer.render(captured.story, Locale.ENGLISH),
            )
            assertEquals(
                render(ReferenceKind.SinceUnplugged, 24 * hour, tr),
                writer.render(captured.story, tr),
            )
        }
}
