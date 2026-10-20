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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * The read holds its thread like a binder transaction, which neither the plan's deadline nor the guard can
     * interrupt, so the guard has cancelled the capture by the time the miss is recorded. Real threads and time;
     * the deadline is scaled down from [CapturePolicy.BROADCAST_FULL_DEADLINE_MS] to keep the test short.
     */
    @Test
    fun `a full read that blocks past the receiver guard still leaves a light row for the trigger`() {
        val quickPlan = CapturePlan.Full(CaptureTrigger.POWER_DISCONNECTED, deadlineMs = 300L)
        val guardMs = CapturePolicy.broadcastGuardMs(quickPlan)
        val store = FakeStore(insertMs = 50)
        val errors = Collections.synchronizedList(mutableListOf<Exception>())
        val finished = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            BroadcastCaptureRunner { errors += it }.start(
                scope = scope,
                deadlineMs = guardMs,
                capture = { SnapshotCapturer(FakeSource(blockMs = guardMs + 400L), store, FixedClocks).captureFor(quickPlan) },
                finish = { finished.countDown() },
            )
            assertTrue("the broadcast was never finished", finished.await(10, TimeUnit.SECONDS))

            assertEquals(emptyList<Exception>(), errors.toList())
            assertEquals(listOf(CaptureTrigger.POWER_DISCONNECTED), store.misses)
            assertEquals(listOf(SnapshotKind.LIGHT to CaptureTrigger.POWER_DISCONNECTED), store.saved())
        } finally {
            scope.cancel()
        }
    }

    /**
     * Audit R-03: a screen-off with the service cold binds it for a FULL read (bind plus read 2.5 s here, past the
     * warm deadline), and the screen-on 100 ms later finds it warm (read 1.5 s). Each stores a FULL row without paging
     * history (no cursor read, so no history ingest and no store tick), and the two captures do not cancel or miss
     * each other while both reads are in flight.
     */
    @Test
    fun `screen broadcasts in quick succession each store a full row without a history ingest`() = runTest {
        val store = FakeStore(insertMs = 50)
        val source = FakeSource(readMsByTrigger = mapOf(CaptureTrigger.SCREEN_OFF to 2_500L, CaptureTrigger.SCREEN_ON to 1_500L))
        val capturer = SnapshotCapturer(source, store, FixedClocks)
        for ((action, warm) in listOf(Intent.ACTION_SCREEN_OFF to false, Intent.ACTION_SCREEN_ON to true)) {
            val screenPlan = CapturePolicy.forBroadcast(action, serviceWarm = warm, shizukuReady = true, screenOffForMs = 100L)!!
            assertTrue(screenPlan is CapturePlan.Full)
            BroadcastCaptureRunner { throw it }.start(
                scope = this,
                deadlineMs = CapturePolicy.broadcastGuardMs(screenPlan),
                capture = { capturer.captureFor(screenPlan) },
                finish = {},
            )
            delay(100)
        }
        advanceUntilIdle()

        assertEquals(emptyList<CaptureTrigger>(), store.misses)
        // The warm screen-on read finishes first; each row keeps its own trigger.
        assertEquals(
            setOf(SnapshotKind.FULL to CaptureTrigger.SCREEN_OFF, SnapshotKind.FULL to CaptureTrigger.SCREEN_ON),
            store.saved().toSet(),
        )
        assertEquals(2, store.saved().size)
        assertEquals(listOf<Pair<Long, Long>?>(null, null), source.cursors)
        assertEquals(0, store.cursorReads)
    }

    @Test
    fun `a bind-on-demand screen-off capture past its deadline falls back to a light row inside the receiver guard`() = runTest {
        val bindPlan = CapturePolicy.forBroadcast(Intent.ACTION_SCREEN_OFF, serviceWarm = false, shizukuReady = true)!!
        val store = FakeStore(insertMs = 50)
        var finishedAtMs = -1L
        BroadcastCaptureRunner { throw it }.start(
            scope = this,
            deadlineMs = CapturePolicy.broadcastGuardMs(bindPlan),
            capture = { SnapshotCapturer(FakeSource(readMs = CapturePolicy.BROADCAST_BIND_FULL_DEADLINE_MS + 100), store, FixedClocks).captureFor(bindPlan) },
            finish = { finishedAtMs = testScheduler.currentTime },
        )
        advanceUntilIdle()

        assertEquals(listOf(CaptureTrigger.SCREEN_OFF), store.misses)
        assertEquals(listOf(SnapshotKind.LIGHT to CaptureTrigger.SCREEN_OFF), store.saved())
        assertTrue("finished at $finishedAtMs ms", finishedAtMs in 0..CapturePolicy.broadcastGuardMs(bindPlan))
    }

    @Test
    fun `light plans keep the plain broadcast guard`() {
        assertEquals(
            CapturePolicy.BROADCAST_FULL_DEADLINE_MS,
            CapturePolicy.broadcastGuardMs(CapturePlan.Light(CaptureTrigger.SCREEN_ON)),
        )
    }

    /** [readMs] is a cancellable wait; [blockMs] holds an IO thread, as an uninterruptible binder call does. */
    private class FakeSource(
        private val readMs: Long = 0L,
        private val blockMs: Long = 0L,
        private val readMsByTrigger: Map<CaptureTrigger, Long> = emptyMap(),
    ) : TelemetrySource {
        /** The history cursor of every full read; null is a read that pages no history. */
        val cursors = Collections.synchronizedList(mutableListOf<Pair<Long, Long>?>())

        override suspend fun full(trigger: CaptureTrigger, historyCursor: Pair<Long, Long>?): FullRead {
            cursors += historyCursor
            delay(readMsByTrigger[trigger] ?: readMs)
            if (blockMs > 0L) withContext(Dispatchers.IO) { Thread.sleep(blockMs) }
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
        var cursorReads = 0

        override suspend fun historyCursor(): Pair<Long, Long>? = null.also { cursorReads++ }
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
