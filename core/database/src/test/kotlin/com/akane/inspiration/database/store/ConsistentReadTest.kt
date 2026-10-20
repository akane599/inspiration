package com.akane.inspiration.database.store

import androidx.test.core.app.ApplicationProvider
import com.akane.inspiration.database.InspirationDatabase
import com.akane.inspiration.database.MaintenanceDao
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.interval.intervalHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [RoomSnapshotStore.readConsistently] holds the analysis reads (the newest-of-kind seek, then the interval history) in
 * one transaction: a history insert and a prune from another coroutine, started between them, land after the last.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConsistentReadTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "consistent-read-${System.nanoTime()}.db"
    private lateinit var database: InspirationDatabase
    private lateinit var store: RoomSnapshotStore

    /** Writers elsewhere in the app: an independent scope, never a child of the reading coroutine. */
    private val writers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var historyTimeMs = 0L

    @Before
    fun setUp() {
        database = InspirationDatabase.create(context, databaseName)
        store = RoomSnapshotStore(database)
    }

    @After
    fun tearDown() {
        writers.cancel()
        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun event(
        wallMs: Long,
        kind: Int,
        level: Int,
    ) = HistoryEvent(historyTimeMs++, wallMs, kind, level, -1L, 300, 4_000, 0)

    @Test
    fun `a store and prune started between the analysis reads lands after them`() =
        runBlocking {
            store.insertHistory(
                listOf(
                    event(1 * HOUR, HistoryKind.SCREEN_OFF, 90),
                    event(1 * HOUR, HistoryKind.LEVEL, 90),
                    event(3 * HOUR, HistoryKind.LEVEL, 80),
                    event(5 * HOUR, HistoryKind.LEVEL, 70),
                ),
                statsStartMs = 1L,
            )
            val seedsBefore = store.newestHistoryOfEachKindBefore(3 * HOUR)
            val intervalBefore = store.intervalHistory(3 * HOUR, 6 * HOUR)

            val write =
                writers.launch(start = CoroutineStart.LAZY) {
                    store.insertHistory(listOf(event(4 * HOUR, HistoryKind.LEVEL, 10)), statsStartMs = 1L)
                    // Deletes the history before 2 h: the SCREEN_OFF and LEVEL the interval opens in.
                    store.prune(MaintenanceDao.RETENTION_MS + 2 * HOUR)
                }
            val (seeds, landedBetween, interval) =
                store.readConsistently {
                    val seeds = store.newestHistoryOfEachKindBefore(3 * HOUR)
                    write.start()
                    // Long enough for the write to finish when nothing holds it back.
                    val landed = withTimeoutOrNull(WRITE_WAIT_MS) { write.join() } != null
                    Triple(seeds, landed, store.intervalHistory(3 * HOUR, 6 * HOUR))
                }
            write.join()

            assertFalse("the write landed between the reads", landedBetween)
            assertEquals(seedsBefore, seeds)
            assertEquals(intervalBefore, interval)
            // Not lost: it landed once the reads were done, and it changes what they would read.
            val intervalAfter = store.intervalHistory(3 * HOUR, 6 * HOUR)
            assertNotEquals(intervalBefore, intervalAfter)
            assertEquals(listOf(3, 4, 5).map { it * HOUR }, store.historyBetween(0, Long.MAX_VALUE).map { it.wallMs })
        }

    private companion object {
        const val HOUR = 3_600_000L
        const val WRITE_WAIT_MS = 1_000L
    }
}
