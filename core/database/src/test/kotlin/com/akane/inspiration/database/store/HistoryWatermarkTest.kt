package com.akane.inspiration.database.store

import androidx.test.core.app.ApplicationProvider
import com.akane.inspiration.database.InspirationDatabase
import com.akane.inspiration.database.SnapshotEntity
import com.akane.inspiration.database.createExportedSchema
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.capture.SnapshotCapturer
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.store.RETENTION_WINDOW_MS
import com.akane.inspiration.telemetry.store.pruneRetention
import com.akane.inspiration.telemetry.source.TelemetrySource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A re-page never brings back a row a cutoff already removed (docs/plans/prune-watermark.md, scenarios 1-7): the real
 * capturer over the real Room store, with the clock stepped, jumped and corrected around ingests and prunes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryWatermarkTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var databaseName: String
    private lateinit var database: InspirationDatabase
    private lateinit var store: RoomSnapshotStore

    @Before
    fun setUp() {
        databaseName = "history-watermark-${System.nanoTime()}.db"
        database = InspirationDatabase.create(context, databaseName)
        store = RoomSnapshotStore(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `scenario 1 - a backward step after a prune does not bring the pruned row back on a relabel re-page`() =
        runBlocking {
            val t0 = WALL_BASE_MS
            val clocks = MovableClocks(wallMs = t0)
            val pruned = historyEvent(100, t0 - HOUR_MS)
            val kept = historyEvent(200, t0)
            ingest(clocks, historyPage(L1, pruned, kept))
            clocks.wallMs = t0 + RETENTION_WINDOW_MS - 30 * MINUTE_MS
            store.pruneRetention(clocks)
            assertEquals(listOf(kept), stored())

            clocks.wallMs -= DAY_MS // a manual backward set: the cutoff drops below the pruned row
            val new = historyEvent(300, clocks.wallMs)
            ingest(clocks, historyPage(L2, pruned, kept, new, sessionChanged = true))

            assertEquals(listOf(kept, new), stored())
        }

    @Test
    fun `scenario 2 - a prune at a jumped now, then the clock corrected - new events are stored, pruned ones not`() =
        runBlocking {
            val realNowMs = WALL_BASE_MS
            val clocks = MovableClocks(wallMs = realNowMs)
            val a = historyEvent(100, realNowMs - 10 * DAY_MS)
            val b = historyEvent(200, realNowMs - HOUR_MS)
            ingest(clocks, historyPage(L1, a, b))
            // A forward jump after a reboot (no snapshot of this boot, so no boot frame): the prune trusts the wall clock.
            clocks.wallMs = realNowMs + 60 * DAY_MS
            store.pruneRetention(clocks)
            assertEquals(emptyList<HistoryEvent>(), stored())

            clocks.wallMs = realNowMs + HOUR_MS // corrected
            val c = historyEvent(300, realNowMs + 30 * MINUTE_MS)
            val d = historyEvent(400, realNowMs + HOUR_MS)
            ingest(clocks, historyPage(L2, a, b, c, d, sessionChanged = true))

            assertEquals("new positions are judged by the current cutoff alone", listOf(c, d), stored())
        }

    @Test
    fun `scenario 3 - an early clock pinning the boot frame does not bring pruned rows back after the correction`() =
        runBlocking {
            val realNowMs = WALL_BASE_MS
            val clocks = MovableClocks(wallMs = realNowMs - 5 * DAY_MS)
            val pruned = historyEvent(100, realNowMs - 33 * DAY_MS)
            val kept = historyEvent(200, realNowMs - 5 * DAY_MS)
            ingest(clocks, historyPage(L1, pruned, kept))
            clocks.wallMs = realNowMs
            store.pruneRetention(clocks)
            assertEquals(listOf(kept), stored())

            // Reboot with the clock at its floor, and a snapshot taken before network time pins this boot's frame.
            val floorMs = realNowMs - 200 * DAY_MS
            clocks.bootCount = 2
            database.snapshotDao().insert(
                SnapshotEntity(kind = "LIGHT", trigger = "BOOT", wallMs = floorMs, elapsedRealtimeMs = 10_000L, uptimeMs = 10_000L, bootCount = 2),
            )
            clocks.wallMs = realNowMs + HOUR_MS // NTP corrects the clock; retentionNowMs stays in the floor frame
            clocks.elapsedMs = HOUR_MS
            val new = historyEvent(300, realNowMs + 30 * MINUTE_MS)
            ingest(clocks, historyPage(L2, pruned, kept, new, sessionChanged = true))

            assertEquals(listOf(kept, new), stored())
        }

    @Test
    fun `scenario 4 - an event an ingest skipped does not come back under a later lower cutoff`() =
        runBlocking {
            val pruneNowMs = WALL_BASE_MS
            val clocks = MovableClocks(wallMs = pruneNowMs)
            store.pruneRetention(clocks)
            clocks.wallMs = pruneNowMs + 2 * DAY_MS
            val skipped = historyEvent(100, pruneNowMs - 29 * DAY_MS) // above the prune's cutoff, below the ingest's
            val kept = historyEvent(200, pruneNowMs)
            ingest(clocks, historyPage(L1, skipped, kept))
            assertEquals(listOf(kept), stored())

            clocks.wallMs = pruneNowMs // stepped back: the cutoff drops below the skipped event
            ingest(clocks, historyPage(L2, skipped, kept, sessionChanged = true))

            assertEquals(listOf(kept), stored())
        }

    @Test
    fun `scenario 5 - the SQ-6 rewind through the v6 to v7 migration adds the restated rows once and no band event`() =
        runBlocking {
            val nowMs = WALL_BASE_MS + 40 * DAY_MS
            val level = historyEvent(100, nowMs - 2 * HOUR_MS)
            val reset = historyEvent(200, nowMs - HOUR_MS, HistoryKind.RESET)
            database.close()
            context.deleteDatabase(databaseName)
            // What an earlier build stored: a level and the RESET, not the screen state the RESET restates; its cursor
            // was never rewound (no marker row).
            context.openOrCreateDatabase(databaseName, 0, null).use { old ->
                createExportedSchema(old, version = 6)
                for (event in listOf(level, reset)) {
                    old.execSQL(
                        "INSERT INTO history_event (statsStartMs, historyTimeMs, wallMs, kind, levelPct, chargeUah, " +
                            "temperatureDeciC, voltageMv, plugType) VALUES ($L1, ${event.historyTimeMs}, ${event.wallMs}, " +
                            "'${event.kind}', 60, -1, 250, 4000, '0')",
                    )
                }
                old.execSQL("INSERT INTO history_cursor (id, statsStartMs, lastHistoryTimeMs) VALUES (1, $L1, 200)")
                old.version = 6
            }
            database = InspirationDatabase.create(context, databaseName)
            store = RoomSnapshotStore(database)
            // The session as this build's walker reads it: a band event at an old position, inside the retention but
            // below every stored wall time, and the RESET's restated screen state at the RESET's position and wall.
            val band = historyEvent(50, nowMs - 3 * HOUR_MS)
            val restated = historyEvent(200, nowMs - HOUR_MS, HistoryKind.SCREEN_ON)
            val session = listOf(band, level, reset, restated)
            val source =
                PagedHistorySource { cursor ->
                    listOf(historyPage(L1, *session.filter { it.historyTimeMs > (cursor?.second ?: 0L) }.toTypedArray()))
                }

            ingest(MovableClocks(wallMs = nowMs), source)
            ingest(MovableClocks(wallMs = nowMs), source)

            assertEquals(listOf(L1 to 0L, L1 to 200L), source.receivedCursors)
            assertEquals(listOf(level, reset, restated), stored())
        }

    @Test
    fun `scenario 6 - a restarted history clock on a complete page is stored`() =
        runBlocking {
            val nowMs = WALL_BASE_MS
            val clocks = MovableClocks(wallMs = nowMs)
            val before = historyEvent(1_000, nowMs - HOUR_MS)
            ingest(clocks, historyPage(L1, before))

            clocks.wallMs = nowMs + MINUTE_MS
            val x = historyEvent(10, nowMs)
            val y = historyEvent(20, nowMs + MINUTE_MS)
            ingest(clocks, historyPage(L2, x, y, sessionChanged = true))

            assertEquals(listOf(before, x, y), stored())
        }

    @Test
    fun `scenario 7 - a partial re-page that lowered the cursor below what was seen keeps those positions old`() =
        runBlocking {
            val t0 = WALL_BASE_MS
            val clocks = MovableClocks(wallMs = t0)
            val e1 = historyEvent(100, t0 - HOUR_MS)
            val e2 = historyEvent(200, t0 - 30 * MINUTE_MS)
            val e3 = historyEvent(300, t0)
            ingest(clocks, historyPage(L1, e1, e2, e3))
            clocks.wallMs = t0 + RETENTION_WINDOW_MS - 15 * MINUTE_MS
            store.pruneRetention(clocks)
            assertEquals(listOf(e3), stored())

            clocks.wallMs = t0 + 29 * DAY_MS // stepped back
            // Capture A reads only the relabel's first page before its deadline: the cursor falls to 100.
            ingest(clocks, historyPage(L2, e1, truncated = true, sessionChanged = true))
            assertEquals(L2 to 100L, store.historyCursor())
            // Capture B continues from it.
            val e4 = historyEvent(400, clocks.wallMs)
            ingest(clocks, historyPage(L2, e2, e3, e4))

            assertEquals(listOf(e3, e4), stored())
        }

    private suspend fun ingest(
        clocks: MovableClocks,
        vararg pages: com.akane.inspiration.ipc.model.BatteryHistorySnapshot,
    ) = ingest(clocks, PagedHistorySource.scripted(pages.toList()))

    private suspend fun ingest(
        clocks: MovableClocks,
        source: TelemetrySource,
    ) {
        SnapshotCapturer(source, store, clocks).ingestHistory(CaptureTrigger.APP_OPEN, INGEST_DEADLINE_MS)
    }

    private suspend fun stored(): List<HistoryEvent> = store.historyBetween(Long.MIN_VALUE, Long.MAX_VALUE)

    private companion object {
        const val L1 = 50_000L
        const val L2 = 49_000L // a backward step gives the continuing session a smaller label
        const val MINUTE_MS = 60_000L
    }
}
