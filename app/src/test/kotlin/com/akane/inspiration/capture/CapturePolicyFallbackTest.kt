package com.akane.inspiration.capture

import android.content.Intent
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The light row a FULL plan falls back to carries the clock of the broadcast, not of the moment the full read gave up:
 * a SCREEN_OFF row stamped seconds late would land after the SCREEN_ON row of a quick unlock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapturePolicyFallbackTest {
    @Test
    fun `a screen-off fallback after a missed bind keeps the broadcast clock, ahead of a quicker screen-on row`() = runTest {
        val store = FakeStore()
        val capturer = SnapshotCapturer(VirtualTimeSource(this, readMs = CapturePolicy.BROADCAST_BIND_FULL_DEADLINE_MS + 500), store, FixedClocks)
        val offPlan = CapturePolicy.forBroadcast(Intent.ACTION_SCREEN_OFF, serviceWarm = false, shizukuReady = true)!!
        // The screen-off row is not stored yet, so the screen-on broadcast gets a LIGHT plan.
        val onPlan = CapturePolicy.forBroadcast(Intent.ACTION_SCREEN_ON, serviceWarm = false, shizukuReady = true, screenOffForMs = null)!!

        launch { capturer.captureFor(offPlan) }
        advanceTimeBy(1_000)
        launch { capturer.captureFor(onPlan) }
        advanceUntilIdle()

        assertEquals(listOf(CaptureTrigger.SCREEN_OFF), store.misses)
        val off = store.snapshots.single { it.trigger == CaptureTrigger.SCREEN_OFF }
        val on = store.snapshots.single { it.trigger == CaptureTrigger.SCREEN_ON }
        assertEquals(SnapshotKind.LIGHT, off.kind)
        assertEquals("fallback row clock is the clock at the start of the capture", 0L, off.clock.elapsedRealtimeMs)
        assertEquals(1_000L, on.clock.elapsedRealtimeMs)
    }

    @Test
    fun `a fallback after the receiver guard cancels the capture keeps the broadcast clock`() = runTest {
        val store = FakeStore()
        val plan = CapturePlan.Full(CaptureTrigger.POWER_DISCONNECTED, CapturePolicy.BROADCAST_FULL_DEADLINE_MS)
        val capturer = SnapshotCapturer(VirtualTimeSource(this, readMs = 60_000L), store, FixedClocks)

        val capture = launch { capturer.captureFor(plan) }
        advanceTimeBy(1_500)
        capture.cancel()
        advanceUntilIdle()

        assertEquals(listOf(CaptureTrigger.POWER_DISCONNECTED), store.misses)
        val row = store.snapshots.single()
        assertEquals(SnapshotKind.LIGHT, row.kind)
        assertEquals("fallback row clock is the clock at the start of the capture", 0L, row.clock.elapsedRealtimeMs)
    }

    @Test
    fun `a full read in time stores its own reading`() = runTest {
        val store = FakeStore()
        val plan = CapturePlan.Full(CaptureTrigger.SCREEN_OFF, CapturePolicy.BROADCAST_FULL_DEADLINE_MS)
        val capturer = SnapshotCapturer(VirtualTimeSource(this, readMs = 500L), store, FixedClocks)

        capturer.captureFor(plan)

        val row = store.snapshots.single()
        assertEquals(SnapshotKind.FULL, row.kind)
        assertEquals(500L, row.clock.elapsedRealtimeMs)
    }

    /** Each light reading is stamped with the test's virtual time; a full read takes [readMs] of it. */
    private class VirtualTimeSource(
        private val scope: TestScope,
        private val readMs: Long,
    ) : TelemetrySource {
        override suspend fun full(trigger: CaptureTrigger, historyCursor: Pair<Long, Long>?): FullRead {
            delay(readMs)
            return FullRead(
                light = light(),
                usage = null,
                batteryStats = DumpParse.Unavailable("not_needed"),
                alarm = DumpParse.Unavailable("not_needed"),
                kernel = null,
                cpuTimes = emptyList(),
                history = emptyList(),
            )
        }

        override fun light(): LightRead {
            val nowMs = scope.testScheduler.currentTime
            return LightRead(
                clock = SnapshotClock(1_000_000L + nowMs, nowMs, nowMs, 4, null, null),
                levelPct = 70,
                plugged = false,
                status = "discharging",
                temperatureDeciC = 250,
                voltageMv = 4_000,
                currentMa = -100,
                chargeCounterUah = 1_000_000,
            )
        }
    }

    private object FixedClocks : Clocks {
        override fun wallMs(): Long = 1L
        override fun elapsedRealtimeMs(): Long = 2L
        override fun uptimeMs(): Long = 3L
        override fun bootCount(): Int = 4
    }

    /** Writes take 50 ms of virtual time, like a cancellable Room suspend call. */
    private class FakeStore : SnapshotStore {
        val misses = mutableListOf<CaptureTrigger>()
        val snapshots = mutableListOf<BatterySnapshot>()

        override suspend fun insert(snapshot: BatterySnapshot): Long {
            delay(50)
            snapshots += snapshot
            return snapshots.size.toLong()
        }

        override suspend fun recordMiss(wallMs: Long, trigger: CaptureTrigger, reason: String) {
            delay(50)
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
