package com.akane.inspiration.database.store

import androidx.test.core.app.ApplicationProvider
import com.akane.inspiration.database.InspirationDatabase
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.diff.ReferenceResolver
import com.akane.inspiration.telemetry.interval.IntervalMetricsCalculator
import com.akane.inspiration.telemetry.interval.intervalHistory
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Audit R-15: an analysis reads the interval's own history plus seeds ([intervalHistory]) and the resolver reads only
 * its anchors, yet both must give exactly what the whole stored history gave, over the real SQL ordering: multi-day
 * histories with rows before the interval, same-wall ties (same kind too), RESET/START/SHUTDOWN boundaries, rows
 * stored out of wall order and rows of unknown wall time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IntervalHistoryEquivalenceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var databaseName: String
    private lateinit var database: InspirationDatabase
    private lateinit var store: RoomSnapshotStore
    private var historyTimeMs = 0L

    @Before
    fun setUp() {
        databaseName = "interval-history-${System.nanoTime()}.db"
        database = InspirationDatabase.create(context, databaseName)
        store = RoomSnapshotStore(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    /** A fresh, empty database for the next generated history. */
    private fun reopen() {
        tearDown()
        setUp()
    }

    /** What the analyzer read before R-15: every stored event with `0 <= wallMs <= toWallMs`, oldest first. */
    private suspend fun wholeHistory(toWallMs: Long): List<HistoryEvent> =
        store.historyBetween(0, toWallMs).filter { it.wallMs in 0..toWallMs }.sortedBy(HistoryEvent::wallMs)

    private fun event(
        wallMs: Long,
        kind: Int,
        level: Int = 50,
        plugType: Int = 0,
    ) = HistoryEvent(historyTimeMs++, wallMs, kind, level, -1L, 300, 4_000, plugType)

    private fun snapshot(wallMs: Long) =
        BatterySnapshot(
            id = 0L,
            kind = SnapshotKind.LIGHT,
            trigger = CaptureTrigger.APP_OPEN,
            clock = SnapshotClock(wallMs, wallMs - BOOT_WALL_MS, wallMs - BOOT_WALL_MS, 1, 10L, null),
            battery = BatteryState(60, false, "discharging", 300, 4_000, -200, 1_000_000L, null),
            system = null,
            uids = emptyList(),
            deviceComponentsMah = emptyMap(),
            kernelWakelocks = emptyList(),
            wakeupReasons = emptyList(),
            cpuFreq = emptyList(),
            availability = emptyMap(),
            truncatedUids = 0,
        )

    /** Three days of events on a 5-minute grid, so many share a wall time; one in eight kinds is a boundary. */
    private fun randomHistory(random: Random): List<HistoryEvent> {
        val kinds =
            listOf(HistoryKind.LEVEL, HistoryKind.LEVEL, HistoryKind.LEVEL, HistoryKind.SCREEN_ON, HistoryKind.SCREEN_OFF) +
                listOf(HistoryKind.SCREEN_ON, HistoryKind.SCREEN_OFF, HistoryKind.PLUGGED, HistoryKind.UNPLUGGED) +
                listOf(HistoryKind.DEEP_IDLE_ON, HistoryKind.DEEP_IDLE_OFF, HistoryKind.LIGHT_IDLE_ON) +
                listOf(HistoryKind.RESET, HistoryKind.START, HistoryKind.SHUTDOWN)
        var wallMs = BOOT_WALL_MS
        var level = 90
        return List(900) {
            wallMs += random.nextLong(0, 3) * 5 * MINUTE_MS
            val kind = kinds.random(random)
            level = (level + random.nextInt(-2, 2)).coerceIn(1, 100)
            val plugType = if (kind == HistoryKind.RESET || kind == HistoryKind.START) random.nextInt(0, 2) else 0
            event(if (random.nextInt(40) == 0) -1L else wallMs, kind, level, plugType)
        }
    }

    private suspend fun storeOutOfOrder(events: List<HistoryEvent>) {
        // A later page stored first: ids then disagree with wall order across the split.
        val split = events.size / 3
        store.insertHistory(events.drop(split), statsStartMs = 2L)
        store.insertHistory(events.take(split), statsStartMs = 1L)
    }

    private suspend fun assertMetricsMatch(
        fromWallMs: Long,
        toWallMs: Long,
        nowWallMs: Long,
        horizonWallMs: Long,
    ) {
        val expected = IntervalMetricsCalculator.compute(wholeHistory(nowWallMs), fromWallMs, toWallMs, horizonWallMs)
        val actual =
            IntervalMetricsCalculator.compute(
                store.intervalHistory(fromWallMs, minOf(nowWallMs, toWallMs)),
                fromWallMs,
                toWallMs,
                horizonWallMs,
            )
        assertEquals("from=$fromWallMs to=$toWallMs now=$nowWallMs", expected, actual)
    }

    @Test
    fun `seeded interval history computes the metrics of the whole history over random multi-day histories`() =
        runBlocking {
            for (seed in 1..4) {
                reopen()
                val random = Random(seed)
                val events = randomHistory(random)
                storeOutOfOrder(events)
                val walls = events.map { it.wallMs }.filter { it >= 0 }
                val first = walls.min()
                val last = walls.max()
                repeat(60) {
                    // Half the starts sit exactly on an event's wall time, the rest anywhere (before the history too).
                    val from = if (random.nextBoolean()) walls.random(random) else random.nextLong(first - HOUR_MS, last + HOUR_MS)
                    val to = random.nextLong(from, last + 2 * HOUR_MS)
                    val now = if (random.nextBoolean()) to else random.nextLong(from, last + 2 * HOUR_MS)
                    val horizon = if (random.nextBoolean()) to else random.nextLong(from - HOUR_MS, to + 1)
                    assertMetricsMatch(from, to, now, horizon)
                }
            }
        }

    @Test
    fun `seeded interval history handles same-kind ties, resets and a shutdown before the interval`() =
        runBlocking {
            val day = BOOT_WALL_MS
            val history =
                listOf(
                    event(day, HistoryKind.START, level = 95, plugType = 1),
                    event(day + HOUR_MS, HistoryKind.UNPLUGGED, level = 95),
                    event(day + 2 * HOUR_MS, HistoryKind.LEVEL, level = 90),
                    // Same wall, same kinds: the stored order decides (ON last), and the SQL's per-kind row is arbitrary.
                    event(day + 3 * HOUR_MS, HistoryKind.SCREEN_ON, level = 89),
                    event(day + 3 * HOUR_MS, HistoryKind.SCREEN_OFF, level = 88),
                    event(day + 3 * HOUR_MS, HistoryKind.SCREEN_ON, level = 87),
                    event(day + 3 * HOUR_MS, HistoryKind.LEVEL, level = 86),
                    // A reset restating the screen at its instant, then a day of discharge.
                    event(day + DAY_MS, HistoryKind.RESET, level = 80, plugType = 0),
                    event(day + DAY_MS, HistoryKind.SCREEN_OFF, level = 80),
                    event(day + DAY_MS + HOUR_MS, HistoryKind.LEVEL, level = 75),
                    // A shutdown after the last level: the next event, of any kind, sets the drop baseline.
                    event(day + DAY_MS + 2 * HOUR_MS, HistoryKind.SHUTDOWN, level = 74),
                    event(day + DAY_MS + 3 * HOUR_MS, HistoryKind.SCREEN_ON, level = 70),
                    event(day + DAY_MS + 4 * HOUR_MS, HistoryKind.SCREEN_OFF, level = 68),
                    event(day + DAY_MS + 5 * HOUR_MS, HistoryKind.SCREEN_ON, level = 66),
                    event(day + DAY_MS + 6 * HOUR_MS, HistoryKind.LEVEL, level = 60),
                    event(day + 2 * DAY_MS, HistoryKind.START, level = 60, plugType = 0),
                    event(day + 2 * DAY_MS + HOUR_MS, HistoryKind.LEVEL, level = 55),
                )
            storeOutOfOrder(history)

            val end = day + 2 * DAY_MS + 2 * HOUR_MS
            for (from in listOf(day + 3 * HOUR_MS, day + 3 * HOUR_MS + 1, day + DAY_MS + 30 * MINUTE_MS, day + DAY_MS + 5 * HOUR_MS + 1)) {
                assertMetricsMatch(from, end, end, end)
            }
            // The shutdown case matters: the first event after it (70) sets the drop baseline. The newest event of each
            // kind alone would take it from the SCREEN_OFF (68) and report 8 points, not 10.
            val from = day + DAY_MS + 5 * HOUR_MS + 1
            val afterShutdown = IntervalMetricsCalculator.compute(store.intervalHistory(from, end), from, end)
            assertEquals(10, afterShutdown.levelUsedPct)
        }

    @Test
    fun `targeted anchors resolve the same reference as the whole history`() =
        runBlocking {
            var resolvedCount = 0
            for (seed in 1..3) {
                reopen()
                val random = Random(100 + seed)
                val events = randomHistory(random)
                storeOutOfOrder(events)
                val walls = events.map { it.wallMs }.filter { it >= 0 }
                // Snapshots every half hour, and one just after every SCREEN_ON so screen-off periods can close.
                val snapshotWalls =
                    (walls.min()..walls.max() step 30 * MINUTE_MS) +
                        events.filter { it.kind == HistoryKind.SCREEN_ON && it.wallMs >= 0 }.map { it.wallMs + 1_000L }
                snapshotWalls.distinct().forEach { store.insert(snapshot(it)) }
                val resolver = ReferenceResolver(store)
                repeat(40) {
                    val now = random.nextLong(walls.min(), walls.max() + HOUR_MS)
                    val whole = wholeHistory(now)
                    for (kind in listOf(ReferenceKind.SinceUnplugged, ReferenceKind.ScreenOnSession, ReferenceKind.SinceScreenOff)) {
                        val expected = resolver.resolve(kind, now, whole)
                        assertEquals("$kind at $now", expected, resolver.resolve(kind, now))
                        if (expected != null) resolvedCount++
                    }
                }
            }
            assertTrue("the comparison must not be vacuous: $resolvedCount", resolvedCount > 100)
        }

    @Test
    fun `targeted screen-off anchor keeps the stored order of a same-wall pair`() =
        runBlocking {
            val t = BOOT_WALL_MS + HOUR_MS
            // ON then OFF at one wall time: that OFF opens a period that has not ended, so the newest completed one is
            // the earlier OFF -> ON; with a newer OFF stored after the ON, the pair still ends at the tied ON.
            storeOutOfOrder(
                listOf(
                    event(t - 20 * MINUTE_MS, HistoryKind.SCREEN_OFF),
                    event(t, HistoryKind.SCREEN_ON),
                    event(t, HistoryKind.SCREEN_OFF),
                    event(t + 10 * MINUTE_MS, HistoryKind.LEVEL),
                ),
            )
            listOf(t - 30 * MINUTE_MS, t - 15 * MINUTE_MS, t + 1_000L, t + 20 * MINUTE_MS).forEach { store.insert(snapshot(it)) }
            val now = t + 30 * MINUTE_MS
            val resolver = ReferenceResolver(store)
            val expected = resolver.resolve(ReferenceKind.SinceScreenOff, now, wholeHistory(now))

            assertEquals(t - 20 * MINUTE_MS, expected!!.boundaryFromWallMs)
            assertEquals(expected, resolver.resolve(ReferenceKind.SinceScreenOff, now))
        }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
        const val DAY_MS = 24 * HOUR_MS
        const val BOOT_WALL_MS = 1_790_000_000_000L
    }
}
