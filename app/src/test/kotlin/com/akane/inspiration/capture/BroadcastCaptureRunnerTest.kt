package com.akane.inspiration.capture

import android.content.Intent
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.capture.SnapshotCapturer
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastCaptureRunnerTest {
    @Test
    fun `starting capture returns before persistence and finishes exactly once`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var finished = 0
        val job = BroadcastCaptureRunner { throw it }.start(this, 2_000, { gate.await() }, { finished++ })
        assertEquals(0, finished)
        runCurrent()
        assertEquals(0, finished)
        gate.complete(Unit)
        job.join()
        assertEquals(1, finished)
    }

    @Test
    fun `deadline finishes stalled persistence without blocking the caller`() = runTest {
        var finished = 0
        BroadcastCaptureRunner { throw it }.start(this, 2_000, { CompletableDeferred<Unit>().await() }, { finished++ })
        advanceUntilIdle()
        assertEquals(1, finished)
    }

    @Test
    fun `already cancelled scope still releases pending broadcast`() = runTest {
        val parent = Job().apply { cancel() }
        var finished = 0
        val job = BroadcastCaptureRunner { throw it }.start(
            CoroutineScope(coroutineContext + parent), 2_000, { error("must not capture") }, { finished++ },
        )
        job.join()
        assertEquals(1, finished)
    }

    @Test
    fun `failed capture still schedules boot work and finishes broadcast`() = runTest {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("disk full")
        BroadcastCaptureRunner {
            assertSame(failure, it)
            events += "logged"
        }.run(
            capture = { throw failure },
            afterCapture = { events += "scheduled" },
            finish = { events += "finished" },
        )
        assertEquals(listOf("logged", "scheduled", "finished"), events)
    }

    @Test
    fun `scheduling failure is contained and broadcast finishes`() = runTest {
        val events = mutableListOf<String>()
        BroadcastCaptureRunner { events += "logged" }.run(
            capture = { events += "captured" },
            afterCapture = { error("scheduler unavailable") },
            finish = { events += "finished" },
        )
        assertEquals(listOf("captured", "logged", "finished"), events)
    }

    @Test
    fun `light capture past guard still stores its row`() = runTest {
        // Shizuku is not ready, so the unplug gets a LIGHT plan; a concurrent Room write holds the lock for 3 s.
        val plan = CapturePolicy.forBroadcast(Intent.ACTION_POWER_DISCONNECTED, serviceWarm = false, shizukuReady = false)!!
        val store = SlowInsertStore(insertMs = 3_000L)
        val capturer = SnapshotCapturer(LightOnlySource, store, FixedClocks)
        var finished = 0

        BroadcastCaptureRunner { throw it }.start(
            this, CapturePolicy.broadcastGuardMs(plan), { capturer.captureFor(plan) }, { finished++ },
        )
        advanceUntilIdle()

        val row = store.snapshots.single()
        assertEquals(SnapshotKind.LIGHT, row.kind)
        assertEquals(CaptureTrigger.POWER_DISCONNECTED, row.trigger)
        assertEquals(1, finished)
    }

    @Test
    fun `cancellation propagates and finishes without follow-up work`() = runTest {
        val events = mutableListOf<String>()
        val cancellation = CancellationException("stopped")
        try {
            BroadcastCaptureRunner { events += "logged" }.run(
                capture = { throw cancellation },
                afterCapture = { events += "scheduled" },
                finish = { events += "finished" },
            )
            error("expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertEquals(listOf("finished"), events)
    }

    private object LightOnlySource : TelemetrySource {
        override suspend fun full(trigger: CaptureTrigger, historyCursor: Pair<Long, Long>?): FullRead? = error("LIGHT plan must not read full")

        override fun light(): LightRead =
            LightRead(
                clock = SnapshotClock(1_000_000L, 0L, 0L, 4, null, null),
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

    /** Each insert waits [insertMs] of virtual time for the write lock, like a cancellable Room suspend call. */
    private class SlowInsertStore(private val insertMs: Long) : SnapshotStore {
        val snapshots = mutableListOf<BatterySnapshot>()

        override suspend fun insert(snapshot: BatterySnapshot): Long {
            delay(insertMs)
            snapshots += snapshot
            return snapshots.size.toLong()
        }

        override suspend fun recordMiss(wallMs: Long, trigger: CaptureTrigger, reason: String) = Unit
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
