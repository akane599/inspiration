package com.akane.inspiration.telemetry.capture

import com.akane.inspiration.ipc.model.BatteryHistorySnapshot
import com.akane.inspiration.ipc.model.BatteryUsageSnapshot
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.ipc.model.UidUsage
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.dump.DumpParse
import com.akane.inspiration.telemetry.dump.DumpSnapshot
import com.akane.inspiration.telemetry.dump.SystemTimers
import com.akane.inspiration.telemetry.dump.UidDump
import com.akane.inspiration.telemetry.dump.UidSensor
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.source.FullRead
import com.akane.inspiration.telemetry.source.LightRead
import com.akane.inspiration.telemetry.source.TelemetrySource
import com.akane.inspiration.telemetry.store.SnapshotStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotCapturerTest {
    @Test
    fun `full capture maps process states and uses uid components when device aggregate is absent`() = runTest {
        val store = FakeStore()
        val source = FakeSource(
            usage = BatteryUsageSnapshot(
                available = true,
                reason = null,
                statsStartMs = 10,
                statsEndMs = 20,
                dischargedPowerMah = 1.0,
                batteryTimeRemainingMs = 0,
                chargeTimeRemainingMs = 0,
                uids = listOf(UidUsage(12345, "pkg", 2.0, mapOf("cpu" to 1.5), mapOf("foreground" to 1.0, "cached" to 0.5))),
            ),
        )
        val capturer = SnapshotCapturer(source, store, TestClocks())

        capturer.captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = false)

        val saved = store.snapshots.single()
        assertEquals(1.0, saved.uids.single().fgMah!!, 0.0)
        assertEquals(0.5, saved.uids.single().cachedMah!!, 0.0)
        assertEquals(1.5, saved.deviceComponentsMah.getValue("cpu"), 0.0)
        assertEquals("device_from_uid_sum", saved.availability.getValue("usage").reason)
    }

    @Test
    fun `full capture carries dump uid sensors and system signal timers into the snapshot`() = runTest {
        val store = FakeStore()
        val sensors = listOf(UidSensor(-10_000, 900, 100, 2), UidSensor(7, 50, 0, 1))
        val dump = DumpSnapshot(
            reportVersion = 36,
            system = SystemTimers(
                startClockTimeMs = 10, totalRealtimeMs = 0, totalUptimeMs = 0, batteryRealtimeMs = 0,
                batteryUptimeMs = 0, screenOffRealtimeMs = 0, screenOffUptimeMs = 0, screenDozeMs = 0,
                screenOnMs = 0, interactiveMs = 0, deepDozeMs = 0, deepDozeCount = 0, lightDozeMs = 0,
                dischargeTotalMah = 0.0, dischargeScreenOffMah = 0.0, chargeTimeRemainingMs = null,
                dischargeTimeRemainingMs = null, signalStrengthMs = listOf(1, 2, 3, 4, 5),
                signalScanningMs = 6, mobileRadioActiveMs = 7,
            ),
            kernelWakelocks = emptyList(),
            wakeupReasons = emptyList(),
            uids = listOf(
                UidDump(12345, emptyList(), emptyList(), 0, 0, 0, 0, emptyMap(), sensors),
            ),
            cpuFrequenciesKhz = emptyList(),
            warnings = emptyList(),
        )
        val usage = BatteryUsageSnapshot(
            available = true,
            reason = null,
            statsStartMs = 10,
            statsEndMs = 20,
            dischargedPowerMah = 1.0,
            batteryTimeRemainingMs = 0,
            chargeTimeRemainingMs = 0,
            uids = listOf(UidUsage(12345, "pkg", 2.0, emptyMap(), emptyMap())),
        )
        val capturer = SnapshotCapturer(FakeSource(usage = usage, batteryStats = DumpParse.Ok(dump)), store, TestClocks())

        capturer.captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = false)

        val saved = store.snapshots.single()
        assertEquals(sensors, saved.uids.single().sensors)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), saved.system!!.signalStrengthMs)
        assertEquals(6L, saved.system.signalScanningMs)
        assertEquals(7L, saved.system.mobileRadioActiveMs)
    }

    @Test
    fun `full capture persists history and advances cursor`() = runTest {
        val store = FakeStore()
        val source = FakeSource(
            history = listOf(
                page(truncated = true, historyEvent(5)),
                page(truncated = false, historyEvent(9)),
            ),
        )
        val capturer = SnapshotCapturer(source, store, TestClocks())

        capturer.captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = true)
        capturer.captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = true)

        assertEquals(2, store.history.size)
        assertEquals(10L to 9L, store.cursor)
        assertEquals(10L to 9L, source.receivedCursors.last())
    }

    @Test
    fun `session restart reingestion stores only new history events`() = runTest {
        val old = historyEvent(5)
        val new = historyEvent(9)
        val store = FakeStore()
        val source = FakeSource(
            histories = listOf(
                listOf(page(truncated = false, old, statsStartMs = 10L)),
                listOf(page(truncated = false, old, new, statsStartMs = 20L, sessionChanged = true)),
            ),
        )
        val capturer = SnapshotCapturer(source, store, TestClocks())

        capturer.captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = true)
        capturer.captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = true)

        assertEquals(listOf(old, new), store.history)
        assertEquals(20L to 9L, store.cursor)
        assertEquals(10L to 5L, source.receivedCursors.last())
    }

    @Test
    fun `full capture deadline records miss`() = runTest {
        val store = FakeStore()
        val source = object : FakeSource() {
            override suspend fun full(trigger: CaptureTrigger, historyCursor: Pair<Long, Long>?): FullRead {
                delay(100)
                return super.full(trigger, historyCursor)
            }
        }

        val result = SnapshotCapturer(source, store, TestClocks()).captureFull(
            CaptureTrigger.MARKER,
            deadlineMs = 1,
            ingestHistory = false,
        )

        assertTrue(result is CaptureResult.Missed)
        assertEquals(CaptureTrigger.MARKER, store.misses.single().first)
    }

    @Test
    fun `history ingest persists history without saving a capture snapshot`() = runTest {
        val store = FakeStore()
        val capturer = SnapshotCapturer(
            FakeSource(history = listOf(page(truncated = false, historyEvent(9)))),
            store,
            TestClocks(),
        )

        capturer.ingestHistory(deadlineMs = 100)

        assertEquals(listOf(historyEvent(9)), store.history)
        assertTrue(store.snapshots.isEmpty())
    }

    private open class FakeSource(
        private val history: List<BatteryHistorySnapshot> = emptyList(),
        private val histories: List<List<BatteryHistorySnapshot>> = emptyList(),
        private val usage: BatteryUsageSnapshot = BatteryUsageSnapshot.unavailable("not_needed"),
        private val batteryStats: DumpParse<DumpSnapshot> = DumpParse.Unavailable("not_needed"),
    ) : TelemetrySource {
        val receivedCursors = mutableListOf<Pair<Long, Long>?>()
        private var fullReadCount = 0

        override suspend fun full(trigger: CaptureTrigger, historyCursor: Pair<Long, Long>?): FullRead {
            receivedCursors += historyCursor
            val pageSet = histories.getOrElse(fullReadCount++) { history }
            return FullRead(
                light = light(),
                usage = usage,
                batteryStats = batteryStats,
                power = DumpParse.Unavailable("not_needed"),
                alarm = DumpParse.Unavailable("not_needed"),
                kernel = null,
                cpuTimes = emptyList(),
                history = pageSet,
            )
        }

        override fun light(): LightRead = LightRead(
            clock = com.akane.inspiration.telemetry.snapshot.SnapshotClock(1, 2, 3, 4, null, null),
            levelPct = 70,
            plugged = false,
            status = "discharging",
            temperatureDeciC = 250,
            voltageMv = 4000,
            currentMa = -100,
            chargeCounterUah = 1_000_000,
        )
    }

    private class FakeStore : SnapshotStore {
        var cursor: Pair<Long, Long>? = null
        val history = mutableListOf<HistoryEvent>()
        private val historyKeys = mutableSetOf<Triple<Long, Int, Long>>()
        val misses = mutableListOf<Pair<CaptureTrigger, String>>()
        val snapshots = mutableListOf<BatterySnapshot>()

        override suspend fun insert(snapshot: BatterySnapshot): Long {
            snapshots += snapshot
            return snapshots.size.toLong()
        }
        override suspend fun byId(id: Long): BatterySnapshot? = null
        override suspend fun latest(kind: SnapshotKind?): BatterySnapshot? = null
        override suspend fun firstAtOrAfter(wallMs: Long, kind: SnapshotKind?, statsStartMs: Long?, bootCount: Int?): BatterySnapshot? = null
        override suspend fun lastAtOrBefore(wallMs: Long, kind: SnapshotKind?): BatterySnapshot? = null
        override suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot? = null
        override suspend fun insertHistory(events: List<HistoryEvent>, statsStartMs: Long) {
            events.forEach { event ->
                if (historyKeys.add(Triple(event.historyTimeMs, event.kind, event.wallMs))) {
                    history += event
                }
            }
        }
        override suspend fun historyBetween(fromWallMs: Long, toWallMs: Long): List<HistoryEvent> = history
        override suspend fun historyCursor(): Pair<Long, Long>? = cursor
        override suspend fun setHistoryCursor(statsStartMs: Long, lastHistoryTimeMs: Long) { cursor = statsStartMs to lastHistoryTimeMs }
        override suspend fun addMarker(snapshotId: Long, label: String, wallMs: Long): Long = 0
        override suspend fun marker(id: Long): Pair<Long, BatterySnapshot>? = null
        override suspend fun insertSamples(samples: List<LiveReading>) = Unit
        override suspend fun upsertChargeSession(statsStartMs: Long, segment: com.akane.inspiration.telemetry.capacity.ChargeSegment, verdict: com.akane.inspiration.telemetry.capacity.SegmentVerdict) = Unit
        override suspend fun acceptedEstimatesChronological(limit: Int): List<Double> = emptyList()
        override suspend fun recordMiss(wallMs: Long, trigger: CaptureTrigger, reason: String) { misses += trigger to reason }
        override suspend fun prune(nowWallMs: Long) = Unit
    }

    private class TestClocks : Clocks {
        override fun wallMs(): Long = 1L
        override fun elapsedRealtimeMs(): Long = 2L
        override fun uptimeMs(): Long = 3L
        override fun bootCount(): Int = 4
    }

    private fun page(
        truncated: Boolean,
        vararg events: HistoryEvent,
        statsStartMs: Long = 10L,
        sessionChanged: Boolean = false,
    ) = BatteryHistorySnapshot(
        available = true,
        reason = null,
        statsStartMs = statsStartMs,
        sessionChanged = sessionChanged,
        events = events.toList(),
        truncated = truncated,
    )

    private fun historyEvent(time: Long) = HistoryEvent(time, time, HistoryKind.LEVEL, 70, 1_000_000, 250, 4000, 0)
}
