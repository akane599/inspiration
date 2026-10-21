package com.akane.inspiration.database.store

import androidx.test.core.app.ApplicationProvider
import com.akane.inspiration.database.HistoryDao
import com.akane.inspiration.database.InspirationDatabase
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.capacity.ChargeSegmentExtractor
import com.akane.inspiration.telemetry.capture.SnapshotCapturer
import com.akane.inspiration.telemetry.interval.IntervalMetricsCalculator
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.store.RETENTION_WINDOW_MS
import com.akane.inspiration.telemetry.store.pruneRetention
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the re-armed history replay after the 7 -> 8 migration (audit A03) does to rows that were stored by an earlier
 * build. Rows of one wall time, and runs of one stats session, are ordered by insertion id, so a replayed row can only
 * ever be appended: the real capturer over the real Room store, with the START dated as the earlier extractor dated it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryReplayOrderTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var databaseName: String
    private lateinit var database: InspirationDatabase
    private lateinit var store: RoomSnapshotStore

    @Before
    fun setUp() {
        databaseName = "history-replay-order-${System.nanoTime()}.db"
        database = InspirationDatabase.create(context, databaseName)
        store = RoomSnapshotStore(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `a START retention pruned under its wrong wall time is not appended after the rows written with it`() =
        runBlocking {
            val t0 = WALL_BASE_MS
            val clocks = MovableClocks(wallMs = t0)
            // Boot one shuts down; the device stays off for two hours. An earlier build dated boot two's START from boot
            // one's anchor, one history tick after the SHUTDOWN; the rows written with it carry the real boot time.
            val shutdown = historyEvent(2_000, t0, HistoryKind.SHUTDOWN)
            val misdatedStart = historyEvent(2_001, t0 + 1, HistoryKind.START)
            val bootWallMs = t0 + 2 * HOUR_MS
            val screenOn = historyEvent(2_001, bootWallMs, HistoryKind.SCREEN_ON)
            val level = historyEvent(2_001, bootWallMs, HistoryKind.LEVEL)
            ingest(clocks, historyPage(L1, shutdown, misdatedStart, screenOn, level))
            assertEquals(listOf(shutdown, misdatedStart, screenOn, level), stored())

            // The retention prune reaches the START's wrong wall time an hour before its companions' real one.
            clocks.wallMs = t0 + RETENTION_WINDOW_MS + HOUR_MS
            store.pruneRetention(clocks)
            assertEquals(listOf(screenOn, level), stored())

            // The upgrade re-arms the one-time replay, and the fixed extractor now dates the START by its own boot.
            reArmReplay()
            val datedStart = misdatedStart.copy(wallMs = bootWallMs)
            ingest(clocks, historyPage(L1, shutdown, datedStart, screenOn, level))

            assertEquals(
                "retention removed the START from a stored group; appended after its companions it would end the " +
                    "screen-on span they begin, so it stays out like any other pruned row",
                listOf(screenOn, level),
                stored(),
            )
            val tenMinutesOn = IntervalMetricsCalculator.compute(stored(), bootWallMs - HOUR_MS, bootWallMs + 10 * MINUTE_MS)
            assertEquals(10 * MINUTE_MS, tenMinutesOn.screenOnMs)

            // A wall-clock step relabels the session and re-pages it from the start: the companions are stored under
            // L1, the START arrives under L2. The match ignores the label, like the unique key does.
            clocks.wallMs -= MINUTE_MS
            ingest(clocks, historyPage(L2, shutdown, datedStart, screenOn, level, sessionChanged = true))
            assertEquals(listOf(screenOn, level), stored())
            assertEquals(L2 to 2_001L, store.historyCursor())
        }

    @Test
    fun `a START the migration dropped is restored, and a group the fixed build stored is re-paged without change`() =
        runBlocking {
            val t0 = WALL_BASE_MS
            val clocks = MovableClocks(wallMs = t0 + 2 * HOUR_MS + MINUTE_MS)
            val shutdown = historyEvent(2_000, t0, HistoryKind.SHUTDOWN)
            val loneStart = historyEvent(2_001, t0 + 1, HistoryKind.START)
            val bootWallMs = t0 + 2 * HOUR_MS
            // Rows that share only the START's wall time, or only its history time (another session's clock), are no
            // evidence that its group was stored: the match needs both.
            val sameWall = historyEvent(2_500, bootWallMs, HistoryKind.LEVEL)
            val sameHistoryTime = historyEvent(2_001, bootWallMs + HOUR_MS / 2, HistoryKind.LEVEL)
            // A later boot that the fixed build stored: START and the rows written with it share one wall time.
            val secondBootWallMs = bootWallMs + HOUR_MS
            val secondStart = historyEvent(3_001, secondBootWallMs, HistoryKind.START)
            val secondScreenOn = historyEvent(3_001, secondBootWallMs, HistoryKind.SCREEN_ON)
            ingest(clocks, historyPage(L1, shutdown, loneStart, sameWall, sameHistoryTime, secondStart, secondScreenOn))

            // Migration 7 -> 8: a START with no known-wall row at its history time is deleted, the replay re-armed.
            deleteStoredStartsAt(historyTimeMs = 2_001)
            reArmReplay()
            assertEquals(listOf(shutdown, sameWall, sameHistoryTime, secondStart, secondScreenOn), stored())
            val datedStart = loneStart.copy(wallMs = bootWallMs)
            ingest(clocks, historyPage(L1, shutdown, datedStart, sameWall, sameHistoryTime, secondStart, secondScreenOn))

            // Rows of one wall time read in id order, so the restored START follows the LEVEL that only shares its wall.
            assertEquals(
                "nothing stored shares the lone START's instant, so it is restored; the fixed group is deduplicated",
                listOf(shutdown, sameWall, datedStart, sameHistoryTime, secondStart, secondScreenOn),
                stored(),
            )
            assertEquals(6, database.historyDao().between(-1, Long.MAX_VALUE).size)
        }

    /**
     * A known limit of the A03 repair, pinned so a change to it is noticed: the replay stores a lone START under the
     * label the stats session carries now. When a wall-clock step relabelled the session after that boot, the START
     * joins the newer run and `ChargeSegmentExtractor` (which orders each run by history time) walks it ahead of
     * every event of that run, so a charge that spans the relabel is discarded. That can lose one estimate, for a
     * charge not yet evaluated at the replay (saved `charge_session` rows are kept); no wrong one is produced. Placing
     * the START in the older run would need an explicit-id insert below its next stored event, which the store does
     * not do: insertion order is the order of a session run.
     */
    @Test
    fun `a lone START replayed after a relabel joins the current run, which drops a charge spanning the relabel`() =
        runBlocking {
            val t0 = WALL_BASE_MS
            val clocks = MovableClocks(wallMs = t0 + 3 * HOUR_MS)
            val shutdown = historyEvent(2_000, t0, HistoryKind.SHUTDOWN)
            val misdatedStart = historyEvent(2_001, t0 + 1, HistoryKind.START)
            val bootWallMs = t0 + 2 * HOUR_MS
            val plugged = historyEvent(5_000, bootWallMs + 10 * MINUTE_MS, HistoryKind.PLUGGED).copy(levelPct = 30, plugType = 1)
            ingest(clocks, historyPage(L1, shutdown, misdatedStart, plugged))

            // A backward step relabels the continuing session; the re-page stores only the new event under L2.
            clocks.wallMs -= MINUTE_MS
            val unplugged = historyEvent(6_000, clocks.wallMs, HistoryKind.UNPLUGGED).copy(levelPct = 95)
            ingest(clocks, historyPage(L2, shutdown, misdatedStart, plugged, unplugged, sessionChanged = true))
            assertEquals(listOf(L1 to listOf(shutdown, misdatedStart, plugged), L2 to listOf(unplugged)), store.historySessions())
            assertEquals(1, ChargeSegmentExtractor.fromSessions(store.historySessions()).size)

            // Migration 7 -> 8 deletes the lone START and re-arms the replay; the START comes back dated by its boot.
            deleteStoredStartsAt(historyTimeMs = 2_001)
            reArmReplay()
            val datedStart = misdatedStart.copy(wallMs = bootWallMs)
            ingest(clocks, historyPage(L2, shutdown, datedStart, plugged, unplugged))

            assertEquals(
                listOf(L1 to listOf(shutdown, plugged), L2 to listOf(unplugged, datedStart)),
                store.historySessions(),
            )
            assertEquals(
                "the START heads the L2 run by history time and discards the charge that began in the L1 run",
                0,
                ChargeSegmentExtractor.fromSessions(store.historySessions()).size,
            )
        }

    private suspend fun ingest(
        clocks: MovableClocks,
        vararg pages: com.akane.inspiration.ipc.model.BatteryHistorySnapshot,
    ) {
        SnapshotCapturer(PagedHistorySource.scripted(pages.toList()), store, clocks)
            .ingestHistory(CaptureTrigger.APP_OPEN, INGEST_DEADLINE_MS)
    }

    private suspend fun stored(): List<HistoryEvent> = store.historyBetween(Long.MIN_VALUE, Long.MAX_VALUE)

    /** What migration 7 -> 8 does to re-arm the one-time replay. */
    private fun reArmReplay() {
        database.openHelper.writableDatabase.execSQL("DELETE FROM history_cursor WHERE id = ${HistoryDao.REWOUND_MARKER.id}")
    }

    /** What migration 7 -> 8 does to a START with no known-wall row at its history time. */
    private fun deleteStoredStartsAt(historyTimeMs: Long) {
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM history_event WHERE kind = '${HistoryKind.START}' AND historyTimeMs = $historyTimeMs",
        )
    }

    private companion object {
        const val L1 = 50_000L
        const val L2 = 49_000L // a backward step gives the continuing session a smaller label
        const val MINUTE_MS = 60_000L
    }
}
