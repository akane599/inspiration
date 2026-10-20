package com.akane.inspiration.database.store

import androidx.test.core.app.ApplicationProvider
import com.akane.inspiration.database.HistoryWatermarkEntity
import com.akane.inspiration.database.InspirationDatabase
import com.akane.inspiration.ipc.model.BatteryHistorySnapshot
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.telemetry.capture.SnapshotCapturer
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.store.RETENTION_WINDOW_MS
import com.akane.inspiration.telemetry.store.pruneRetention
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How the prune and the page write move the stored watermark, and the history clock restart guard (G1-G3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryWatermarkStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var databaseName: String
    private lateinit var database: InspirationDatabase
    private lateinit var store: RoomSnapshotStore

    @Before
    fun setUp() {
        databaseName = "history-watermark-store-${System.nanoTime()}.db"
        database = InspirationDatabase.create(context, databaseName)
        store = RoomSnapshotStore(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `a prune on a database without a watermark creates it with W at the history cutoff and H unknown`() =
        runBlocking {
            assertNull(watermark())

            store.prune(NOW_MS)

            assertEquals(HistoryWatermarkEntity(seenThroughHistoryTimeMs = Long.MIN_VALUE, prunedBeforeWallMs = CUTOFF_MS), watermark())
        }

    @Test
    fun `a later prune at a lower now leaves W unchanged`() =
        runBlocking {
            store.prune(NOW_MS)
            store.prune(NOW_MS - DAY_MS)

            assertEquals(CUTOFF_MS, watermark()?.prunedBeforeWallMs)
        }

    @Test
    fun `a prune never touches H`() =
        runBlocking {
            database.historyDao().writeWatermark(HistoryWatermarkEntity(seenThroughHistoryTimeMs = 500L, prunedBeforeWallMs = 10L))

            store.prune(NOW_MS)
            assertEquals(HistoryWatermarkEntity(seenThroughHistoryTimeMs = 500L, prunedBeforeWallMs = CUTOFF_MS), watermark())
            store.prune(NOW_MS - DAY_MS)
            assertEquals(HistoryWatermarkEntity(seenThroughHistoryTimeMs = 500L, prunedBeforeWallMs = CUTOFF_MS), watermark())
        }

    @Test
    fun `the first page on a database without a watermark keeps what the cutoff keeps and creates the row`() =
        runBlocking {
            val below = historyEvent(100, CUTOFF_MS - 1)
            val atCutoff = historyEvent(200, CUTOFF_MS)
            val above = historyEvent(300, CUTOFF_MS + 1)

            val kept = store.persistHistoryPage(L1, listOf(below, atCutoff, above), truncated = false, cutoffWallMs = CUTOFF_MS)

            assertEquals(2, kept)
            assertEquals(listOf(atCutoff, above), store.historyBetween(Long.MIN_VALUE, Long.MAX_VALUE))
            assertEquals(L1 to 300L, store.historyCursor())
            assertEquals(HistoryWatermarkEntity(seenThroughHistoryTimeMs = 300L, prunedBeforeWallMs = CUTOFF_MS), watermark())
        }

    @Test
    fun `a relabel's cursor overwrite leaves H where it was`() =
        runBlocking {
            store.persistHistoryPage(L1, listOf(historyEvent(100, NOW_MS), historyEvent(300, NOW_MS + 1)), false, CUTOFF_MS)

            store.persistHistoryPage(L2, listOf(historyEvent(100, NOW_MS)), truncated = true, cutoffWallMs = CUTOFF_MS)

            assertEquals(L2 to 100L, store.historyCursor())
            assertEquals(300L, watermark()?.seenThroughHistoryTimeMs)
        }

    @Test
    fun `G1 - after a prune at an inflated now, a complete page from a restarted history clock is stored and lowers H`() =
        runBlocking {
            val restart = inflatedRestart(truncated = false)

            assertEquals(restart.events, stored())
            assertEquals(HistoryWatermarkEntity(seenThroughHistoryTimeMs = 20L, prunedBeforeWallMs = NOW_MS + 30 * DAY_MS), watermark())
        }

    @Test
    fun `G2 - the same restart page, truncated, stores nothing and keeps H`() =
        runBlocking {
            inflatedRestart(truncated = true)

            assertEquals(emptyList<HistoryEvent>(), stored())
            assertEquals(1_000L, watermark()?.seenThroughHistoryTimeMs)
        }

    @Test
    fun `G3 - a complete relabel re-page that reaches H does not fire the guard and keeps a pruned row out`() =
        runBlocking {
            val clocks = MovableClocks(wallMs = NOW_MS)
            val pruned = historyEvent(100, NOW_MS - 2 * HOUR_MS)
            val kept = historyEvent(1_000, NOW_MS - HOUR_MS)
            ingest(clocks, historyPage(L1, pruned, kept))
            clocks.wallMs = NOW_MS + RETENTION_WINDOW_MS - 90 * MINUTE_MS
            store.pruneRetention(clocks)

            clocks.wallMs = NOW_MS
            ingest(clocks, historyPage(L2, pruned, kept, sessionChanged = true))

            assertEquals(listOf(kept), stored())
            assertEquals(1_000L, watermark()?.seenThroughHistoryTimeMs)
        }

    /** H at 1 000, W inflated 30 days past real time by a prune at a jumped clock, then a restart page at real time. */
    private suspend fun inflatedRestart(truncated: Boolean): BatteryHistorySnapshot {
        val clocks = MovableClocks(wallMs = NOW_MS)
        ingest(clocks, historyPage(L1, historyEvent(1_000, NOW_MS - HOUR_MS)))
        clocks.wallMs = NOW_MS + 60 * DAY_MS
        store.pruneRetention(clocks)
        assertEquals(emptyList<HistoryEvent>(), stored())

        clocks.wallMs = NOW_MS + HOUR_MS
        val page =
            historyPage(
                L2,
                historyEvent(10, NOW_MS + 30 * MINUTE_MS),
                historyEvent(20, NOW_MS + HOUR_MS),
                truncated = truncated,
                sessionChanged = true,
            )
        ingest(clocks, page)
        return page
    }

    private suspend fun ingest(
        clocks: MovableClocks,
        page: BatteryHistorySnapshot,
    ) {
        SnapshotCapturer(PagedHistorySource.scripted(listOf(page)), store, clocks).ingestHistory(CaptureTrigger.APP_OPEN, INGEST_DEADLINE_MS)
    }

    private suspend fun watermark(): HistoryWatermarkEntity? = database.historyDao().watermark()

    private suspend fun stored(): List<HistoryEvent> = store.historyBetween(Long.MIN_VALUE, Long.MAX_VALUE)

    private companion object {
        const val L1 = 50_000L
        const val L2 = 49_000L
        const val MINUTE_MS = 60_000L
        const val NOW_MS = WALL_BASE_MS
        const val CUTOFF_MS = NOW_MS - RETENTION_WINDOW_MS
    }
}
