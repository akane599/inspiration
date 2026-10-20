package com.akane.inspiration.database.store

import androidx.test.core.app.ApplicationProvider
import com.akane.inspiration.database.HistoryCursorEntity
import com.akane.inspiration.database.InspirationDatabase
import com.akane.inspiration.ipc.model.BatteryHistorySnapshot
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.capture.SnapshotCapturer
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.dump.DumpParse
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.source.FullRead
import com.akane.inspiration.telemetry.source.LightRead
import com.akane.inspiration.telemetry.source.TelemetrySource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * The history cursor never moves backwards. The app-open refresh, a marker and the charge job all ingest history
 * through the one SnapshotCapturer, and each reads the cursor when it starts: an ingest that started first and
 * finished last must not write its older cursor over a newer one, or the next ingest re-pages stored history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryCursorTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var databaseName: String
    private lateinit var database: InspirationDatabase
    private lateinit var store: RoomSnapshotStore

    @Before
    fun setUp() {
        databaseName = "history-cursor-${System.nanoTime()}.db"
        database = InspirationDatabase.create(context, databaseName)
        store = RoomSnapshotStore(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `an overlapping slower history ingest does not move the cursor backwards`() =
        runBlocking {
            val source = GatedHistorySource()
            val capturer = SnapshotCapturer(source, store, FixedClocks)

            // A (e.g. the app-open refresh) reads history up to 100 and then stalls in a slow dump/binder call.
            val slow = launch(Dispatchers.Default) { capturer.ingestHistory(CaptureTrigger.APP_OPEN, deadlineMs = 5_000L) }
            withTimeout(5_000L) { source.firstEntered.await() }
            // B (e.g. the charge job) starts while A is in flight; history has grown to 200 by now.
            val fast = launch(Dispatchers.Default) { capturer.ingestHistory(CaptureTrigger.CHARGE_JOB, deadlineMs = 5_000L) }
            withTimeoutOrNull(500L) { fast.join() }
            source.releaseFirst.complete(Unit)
            withTimeout(5_000L) {
                slow.join()
                fast.join()
            }

            assertEquals(
                "both ingests finished and history up to 200 is stored, so the cursor must be at 200",
                STATS_START_MS to 200L,
                store.historyCursor(),
            )
            assertTrue(
                "both reads were stored",
                store.historyBetween(0L, Long.MAX_VALUE).map { it.historyTimeMs }.containsAll(listOf(100L, 200L)),
            )
        }

    @Test
    fun `an older position in the same stats session does not replace the cursor`() =
        runBlocking {
            store.setHistoryCursor(statsStartMs = STATS_START_MS, lastHistoryTimeMs = 200L)
            store.setHistoryCursor(statsStartMs = STATS_START_MS, lastHistoryTimeMs = 100L)

            assertEquals(STATS_START_MS to 200L, store.historyCursor())
        }

    @Test
    fun `a new stats session restarts the cursor even at an earlier history time`() =
        runBlocking {
            store.setHistoryCursor(statsStartMs = STATS_START_MS, lastHistoryTimeMs = 200L)
            store.setHistoryCursor(statsStartMs = STATS_START_MS + 1_000L, lastHistoryTimeMs = 5L)

            assertEquals(STATS_START_MS + 1_000L to 5L, store.historyCursor())
        }

    @Test
    fun `a cursor an earlier build wrote is rewound to its session start once`() =
        runBlocking {
            // An earlier build wrote the cursor; nothing of this build has run on the database yet.
            database.historyDao().upsertCursor(HistoryCursorEntity(statsStartMs = STATS_START_MS, lastHistoryTimeMs = 200L))

            assertEquals("the label is kept, the position goes back to 0", STATS_START_MS to 0L, store.historyCursor())
            store.setHistoryCursor(statsStartMs = STATS_START_MS, lastHistoryTimeMs = 150L)
            assertEquals("the re-ingest moves it forward from 0", STATS_START_MS to 150L, store.historyCursor())
            assertEquals("and it is not rewound again", STATS_START_MS to 150L, store.historyCursor())

            // A store opened later on the same database (the next process) does not rewind either.
            assertEquals(STATS_START_MS to 150L, RoomSnapshotStore(database).historyCursor())
        }

    @Test
    fun `a database whose first cursor this build wrote is never rewound`() =
        runBlocking {
            assertEquals(null, store.historyCursor())
            store.setHistoryCursor(statsStartMs = STATS_START_MS, lastHistoryTimeMs = 200L)

            assertEquals(STATS_START_MS to 200L, store.historyCursor())
            assertEquals(STATS_START_MS to 200L, store.historyCursor())
        }

    @Test
    fun `the rewind re-ingests what an earlier build skipped, once, and not what the retention dropped`() =
        runBlocking {
            val nowMs = WALL_BASE_MS + 40L * DAY_MS
            // What an earlier build stored: a level and the RESET, but not the screen state the RESET restates.
            val level = HistoryEvent(100L, nowMs - 2 * HOUR_MS, HistoryKind.LEVEL, 90, -1L, 250, 4_000, 1)
            val reset = HistoryEvent(200L, nowMs - HOUR_MS, HistoryKind.RESET, 100, -1L, 250, 4_000, 0)
            store.insertHistory(listOf(level, reset), STATS_START_MS)
            database.historyDao().upsertCursor(HistoryCursorEntity(statsStartMs = STATS_START_MS, lastHistoryTimeMs = 200L))
            // The session as this build's walker reads it, from an event the retention already pruned.
            val pruned = HistoryEvent(50L, nowMs - 31L * DAY_MS, HistoryKind.LEVEL, 40, -1L, 250, 4_000, 1)
            val restated = HistoryEvent(200L, nowMs - HOUR_MS, HistoryKind.SCREEN_ON, 100, -1L, 250, 4_000, 0)
            val source = SessionSource(listOf(pruned, level, reset, restated))
            val capturer = SnapshotCapturer(source, store, NowClocks(nowMs))

            capturer.ingestHistory(CaptureTrigger.APP_OPEN, deadlineMs = 5_000L)
            capturer.ingestHistory(CaptureTrigger.APP_OPEN, deadlineMs = 5_000L)

            assertEquals(listOf(STATS_START_MS to 0L, STATS_START_MS to 200L), source.receivedCursors)
            assertEquals(listOf(level, reset, restated), store.historyBetween(0L, Long.MAX_VALUE))
            assertEquals(STATS_START_MS to 200L, store.historyCursor())
        }

    /** One stats session's history; each read returns what follows the cursor's position. */
    private class SessionSource(
        private val events: List<HistoryEvent>,
    ) : TelemetrySource {
        val receivedCursors = mutableListOf<Pair<Long, Long>?>()

        override suspend fun full(
            trigger: CaptureTrigger,
            historyCursor: Pair<Long, Long>?,
        ): FullRead {
            receivedCursors += historyCursor
            val after = historyCursor?.second ?: 0L
            val page = events.filter { it.historyTimeMs > after }
            return FullRead(
                light = GatedHistorySource().light(),
                usage = null,
                batteryStats = DumpParse.Unavailable("not_needed"),
                alarm = DumpParse.Unavailable("not_needed"),
                kernel = null,
                cpuTimes = emptyList(),
                history = listOf(BatteryHistorySnapshot(true, null, STATS_START_MS, false, page, truncated = false)),
            )
        }

        override fun light(): LightRead = GatedHistorySource().light()
    }

    private class NowClocks(
        private val nowMs: Long,
    ) : Clocks {
        override fun wallMs(): Long = nowMs

        override fun elapsedRealtimeMs(): Long = 2L

        override fun uptimeMs(): Long = 3L

        override fun bootCount(): Int = 0
    }

    /** The first call sees history up to 100 and blocks until released; any later call sees up to 200. */
    private class GatedHistorySource : TelemetrySource {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        private val calls = AtomicInteger()

        override suspend fun full(
            trigger: CaptureTrigger,
            historyCursor: Pair<Long, Long>?,
        ): FullRead {
            val first = calls.getAndIncrement() == 0
            val after = historyCursor?.second ?: 0L
            val head = if (first) 100L else 200L
            val events = listOf(after + 1L, head).distinct().filter { it > after }.map(::levelEvent)
            if (first) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
            return FullRead(
                light = light(),
                usage = null,
                batteryStats = DumpParse.Unavailable("not_needed"),
                alarm = DumpParse.Unavailable("not_needed"),
                kernel = null,
                cpuTimes = emptyList(),
                history = listOf(BatteryHistorySnapshot(true, null, STATS_START_MS, false, events, truncated = false)),
            )
        }

        override fun light(): LightRead =
            LightRead(
                clock = SnapshotClock(1L, 2L, 3L, 4, null, null),
                levelPct = 60,
                plugged = false,
                status = "discharging",
                temperatureDeciC = 250,
                voltageMv = 4_000,
                currentMa = -100,
                chargeCounterUah = 1_000_000L,
            )

        private fun levelEvent(historyTimeMs: Long) =
            HistoryEvent(historyTimeMs, WALL_BASE_MS + historyTimeMs, HistoryKind.LEVEL, 60, -1L, 250, 4_000, 0)
    }

    private object FixedClocks : Clocks {
        override fun wallMs(): Long = 1L

        override fun elapsedRealtimeMs(): Long = 2L

        override fun uptimeMs(): Long = 3L

        override fun bootCount(): Int = 4
    }

    private companion object {
        const val STATS_START_MS = 50_000L
        const val WALL_BASE_MS = 1_790_000_000_000L
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24L * HOUR_MS
    }
}
