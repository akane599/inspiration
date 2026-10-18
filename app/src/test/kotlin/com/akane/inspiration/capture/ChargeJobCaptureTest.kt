package com.akane.inspiration.capture

import com.akane.inspiration.ipc.model.BatteryHistorySnapshot
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.capture.SnapshotCapturer
import com.akane.inspiration.telemetry.dump.DumpParse
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.source.FullRead
import com.akane.inspiration.telemetry.source.LightRead
import com.akane.inspiration.telemetry.source.TelemetrySource
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.Marker
import com.akane.inspiration.telemetry.store.SnapshotStore
import com.akane.inspiration.telemetry.store.TelemetrySample
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** runChargeJobCapture: one full read per run, and the PLUGGED lookup never loads the whole history. */
class ChargeJobCaptureTest {
    @Test
    fun `a due capture saves the ingest read instead of reading batterystats again`() = runTest {
        val source = CountingSource(history = listOf(event(5_000, HistoryKind.PLUGGED)))
        val store = FakeStore()

        runChargeJobCapture(SnapshotCapturer(source, store, FixedClocks), store, FixedClocks, DEADLINE_MS, analyze = {})

        assertEquals(1, source.fullReads)
        assertEquals(listOf(SnapshotKind.FULL to CaptureTrigger.CHARGE_JOB), store.saved())
        assertEquals(listOf(event(5_000, HistoryKind.PLUGGED)), store.history)
        assertEquals(0, store.wholeHistoryLoads)
    }

    @Test
    fun `a skipped run still reads once and saves nothing`() = runTest {
        val source = CountingSource(history = listOf(event(5_000, HistoryKind.PLUGGED)))
        // The last charge job already ran after this charge session's PLUGGED event, in this boot.
        val store = FakeStore(snapshots = mutableListOf(snapshot(CaptureTrigger.CHARGE_JOB, wallMs = 6_000)))
        var analyzed = false

        runChargeJobCapture(
            SnapshotCapturer(source, store, FixedClocks),
            store,
            FixedClocks,
            DEADLINE_MS,
            analyze = { analyzed = true },
        )

        assertEquals(1, source.fullReads)
        assertEquals(listOf(SnapshotKind.FULL to CaptureTrigger.CHARGE_JOB), store.saved())
        assertEquals(false, analyzed)
        assertEquals(0, store.wholeHistoryLoads)
    }

    @Test
    fun `a newer PLUGGED event from the per-kind lookup makes the run capture`() = runTest {
        val source = CountingSource(history = listOf(event(5_000, HistoryKind.PLUGGED), event(5_500, HistoryKind.LEVEL)))
        val store = FakeStore(snapshots = mutableListOf(snapshot(CaptureTrigger.CHARGE_JOB, wallMs = 4_000)))

        runChargeJobCapture(SnapshotCapturer(source, store, FixedClocks), store, FixedClocks, DEADLINE_MS, analyze = {})

        assertEquals(1, source.fullReads)
        assertEquals(2, store.saved().size)
        assertEquals(1, store.perKindLookups)
        assertEquals(0, store.wholeHistoryLoads)
    }

    private class CountingSource(private val history: List<HistoryEvent>) : TelemetrySource {
        var fullReads = 0

        override suspend fun full(trigger: CaptureTrigger, historyCursor: Pair<Long, Long>?): FullRead {
            fullReads++
            return FullRead(
                light = light(),
                usage = null,
                batteryStats = DumpParse.Unavailable("not_needed"),
                power = DumpParse.Unavailable("not_needed"),
                alarm = DumpParse.Unavailable("not_needed"),
                kernel = null,
                cpuTimes = emptyList(),
                history = listOf(BatteryHistorySnapshot(true, null, 10L, false, history, false)),
            )
        }

        override fun light(): LightRead = LightRead(
            clock = SnapshotClock(7_000, 2, 3, BOOT, null, null),
            levelPct = 70,
            plugged = true,
            status = "charging",
            temperatureDeciC = 250,
            voltageMv = 4_000,
            currentMa = 1_000,
            chargeCounterUah = 1_000_000,
        )
    }

    private object FixedClocks : Clocks {
        override fun wallMs(): Long = 7_000L
        override fun elapsedRealtimeMs(): Long = 2L
        override fun uptimeMs(): Long = 3L
        override fun bootCount(): Int = BOOT
    }

    private class FakeStore(private val snapshots: MutableList<BatterySnapshot> = mutableListOf()) : SnapshotStore {
        val history = mutableListOf<HistoryEvent>()
        var wholeHistoryLoads = 0
        var perKindLookups = 0
        private var cursor: Pair<Long, Long>? = null

        fun saved() = snapshots.map { it.kind to it.trigger }

        override suspend fun insert(snapshot: BatterySnapshot): Long {
            snapshots += snapshot
            return snapshots.size.toLong()
        }

        override suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot? =
            snapshots.filter { it.trigger == trigger }.maxByOrNull { it.clock.wallMs }

        override suspend fun insertHistory(events: List<HistoryEvent>, statsStartMs: Long) {
            history += events
        }

        override suspend fun historyBetween(fromWallMs: Long, toWallMs: Long): List<HistoryEvent> {
            wholeHistoryLoads++
            return history.filter { it.wallMs in fromWallMs..toWallMs }
        }

        override suspend fun lastHistoryOfEachKindBefore(wallMs: Long): List<HistoryEvent> {
            perKindLookups++
            return history.filter { it.wallMs < wallMs }
                .groupBy { it.kind }
                .values
                .map { events -> events.maxBy { it.wallMs } }
                .sortedBy { it.wallMs }
        }

        override suspend fun historyCursor(): Pair<Long, Long>? = cursor
        override suspend fun setHistoryCursor(statsStartMs: Long, lastHistoryTimeMs: Long) {
            cursor = statsStartMs to lastHistoryTimeMs
        }

        override suspend fun recordMiss(wallMs: Long, trigger: CaptureTrigger, reason: String) = Unit
        override suspend fun byId(id: Long): BatterySnapshot? = null
        override suspend fun latest(kind: SnapshotKind?): BatterySnapshot? = null
        override suspend fun firstAtOrAfter(wallMs: Long, kind: SnapshotKind?, statsStartMs: Long?, bootCount: Int?): BatterySnapshot? = null
        override suspend fun lastAtOrBefore(wallMs: Long, kind: SnapshotKind?): BatterySnapshot? = null
        override suspend fun snapshotsBetween(fromWallMs: Long, toWallMs: Long): List<BatterySnapshot> = emptyList()
        override suspend fun addMarker(snapshotId: Long, label: String, wallMs: Long): Long = 0
        override suspend fun marker(id: Long): Pair<Long, BatterySnapshot>? = null
        override suspend fun markers(): List<Marker> = emptyList()
        override suspend fun insertSamples(samples: List<LiveReading>) = Unit
        override suspend fun samplesBetween(from: Long, to: Long): List<TelemetrySample> = emptyList()
        override suspend fun upsertChargeSession(statsStartMs: Long, segment: ChargeSegment, verdict: SegmentVerdict) = Unit
        override suspend fun chargeSessions(): List<ChargeSessionRecord> = emptyList()
        override suspend fun acceptedEstimatesChronological(limit: Int): List<Double> = emptyList()
        override suspend fun healthSamples(): List<HealthSample> = emptyList()
        override suspend fun insertHealthSample(sample: HealthSample) = Unit
        override suspend fun insertToolkitAction(record: ToolkitActionRecord): Long = 0
        override suspend fun updateToolkitAction(record: ToolkitActionRecord) = Unit
        override suspend fun toolkitActions(limit: Int): List<ToolkitActionRecord> = emptyList()
        override suspend fun pendingToolkitActions(): List<ToolkitActionRecord> = emptyList()
        override suspend fun prune(nowWallMs: Long) = Unit
    }

    private companion object {
        const val BOOT = 4
        const val DEADLINE_MS = 10_000L

        fun event(wallMs: Long, kind: Int) = HistoryEvent(wallMs, wallMs, kind, 70, 1_000_000, 250, 4_000, 0)

        fun snapshot(trigger: CaptureTrigger, wallMs: Long) = BatterySnapshot(
            id = 0L,
            kind = SnapshotKind.FULL,
            trigger = trigger,
            clock = SnapshotClock(wallMs, 2, 3, BOOT, null, null),
            battery = com.akane.inspiration.telemetry.snapshot.BatteryState(70, true, "charging", 250, 4_000, 1_000, 1_000_000, null),
            system = null,
            uids = emptyList(),
            deviceComponentsMah = emptyMap(),
            kernelWakelocks = emptyList(),
            wakeupReasons = emptyList(),
            cpuFreq = emptyList(),
            availability = emptyMap(),
            truncatedUids = 0,
        )
    }
}
