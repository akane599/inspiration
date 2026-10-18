package com.akane.inspiration.capture

import com.akane.inspiration.ipc.model.HistoryEvent
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** RuntimeCaptureReceiver's chain: BroadcastCaptureRunner guard -> SnapshotCapturer.captureFor(plan). */
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastFullCaptureTest {
    private val plan = CapturePlan.Full(CaptureTrigger.POWER_DISCONNECTED, CapturePolicy.BROADCAST_FULL_DEADLINE_MS)

    @Test
    fun `a full read past its deadline records the miss and falls back to a light snapshot`() = runTest {
        val store = FakeStore(insertMs = 50)
        var finished = 0
        BroadcastCaptureRunner { throw it }.start(
            scope = this,
            deadlineMs = CapturePolicy.broadcastGuardMs(plan),
            capture = { SnapshotCapturer(FakeSource(readMs = 2_100), store, FixedClocks).captureFor(plan) },
            finish = { finished++ },
        )
        advanceUntilIdle()

        assertEquals(listOf(CaptureTrigger.POWER_DISCONNECTED), store.misses)
        assertEquals(listOf(SnapshotKind.LIGHT to CaptureTrigger.POWER_DISCONNECTED), store.saved())
        assertEquals(1, finished)
    }

    @Test
    fun `a full read that finishes just inside its deadline is still persisted`() = runTest {
        val store = FakeStore(insertMs = 50)
        BroadcastCaptureRunner { throw it }.start(
            scope = this,
            deadlineMs = CapturePolicy.broadcastGuardMs(plan),
            capture = { SnapshotCapturer(FakeSource(readMs = 1_990), store, FixedClocks).captureFor(plan) },
            finish = {},
        )
        advanceUntilIdle()

        assertEquals(emptyList<CaptureTrigger>(), store.misses)
        assertEquals(listOf(SnapshotKind.FULL to CaptureTrigger.POWER_DISCONNECTED), store.saved())
    }

    @Test
    fun `light plans keep the plain broadcast guard`() {
        assertEquals(
            CapturePolicy.BROADCAST_FULL_DEADLINE_MS,
            CapturePolicy.broadcastGuardMs(CapturePlan.Light(CaptureTrigger.SCREEN_ON)),
        )
    }

    private class FakeSource(private val readMs: Long) : TelemetrySource {
        override suspend fun full(trigger: CaptureTrigger, historyCursor: Pair<Long, Long>?): FullRead {
            delay(readMs)
            return FullRead(
                light = light(),
                usage = null,
                batteryStats = DumpParse.Unavailable("not_needed"),
                power = DumpParse.Unavailable("not_needed"),
                alarm = DumpParse.Unavailable("not_needed"),
                kernel = null,
                cpuTimes = emptyList(),
                history = emptyList(),
            )
        }

        override fun light(): LightRead = LightRead(
            clock = SnapshotClock(1, 2, 3, 4, null, null),
            levelPct = 70,
            plugged = false,
            status = "discharging",
            temperatureDeciC = 250,
            voltageMv = 4_000,
            currentMa = -100,
            chargeCounterUah = 1_000_000,
        )
    }

    private object FixedClocks : Clocks {
        override fun wallMs(): Long = 1L
        override fun elapsedRealtimeMs(): Long = 2L
        override fun uptimeMs(): Long = 3L
        override fun bootCount(): Int = 4
    }

    /** Writes take [insertMs] of virtual time, like a cancellable Room suspend call. */
    private class FakeStore(private val insertMs: Long) : SnapshotStore {
        val misses = mutableListOf<CaptureTrigger>()
        private val snapshots = mutableListOf<BatterySnapshot>()

        fun saved() = snapshots.map { it.kind to it.trigger }

        override suspend fun insert(snapshot: BatterySnapshot): Long {
            delay(insertMs)
            snapshots += snapshot
            return snapshots.size.toLong()
        }

        override suspend fun recordMiss(wallMs: Long, trigger: CaptureTrigger, reason: String) {
            delay(insertMs)
            misses += trigger
        }

        override suspend fun byId(id: Long): BatterySnapshot? = null
        override suspend fun latest(kind: SnapshotKind?): BatterySnapshot? = null
        override suspend fun firstAtOrAfter(wallMs: Long, kind: SnapshotKind?, statsStartMs: Long?, bootCount: Int?): BatterySnapshot? = null
        override suspend fun lastAtOrBefore(wallMs: Long, kind: SnapshotKind?): BatterySnapshot? = null
        override suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot? = null
        override suspend fun snapshotsBetween(fromWallMs: Long, toWallMs: Long): List<BatterySnapshot> = emptyList()
        override suspend fun insertHistory(events: List<HistoryEvent>, statsStartMs: Long) = Unit
        override suspend fun historyBetween(fromWallMs: Long, toWallMs: Long): List<HistoryEvent> = emptyList()
        override suspend fun historyCursor(): Pair<Long, Long>? = null
        override suspend fun setHistoryCursor(statsStartMs: Long, lastHistoryTimeMs: Long) = Unit
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
}
