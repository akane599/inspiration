package com.akane.inspiration.capture

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.KernelCapacity
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.Marker
import com.akane.inspiration.telemetry.store.SnapshotStore
import com.akane.inspiration.telemetry.store.TelemetrySample
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeHealthPipelineTest {
    private val store = FakeStore()
    private val logged = mutableListOf<String>()
    private var kernel: KernelCapacity? = KernelCapacity(chargeFullMah = 2_400.0, designMah = 2_500.0, cycleCount = 100)

    private fun pipeline() = ChargeHealthPipeline(store, { kernel }, { message, _ -> logged += message })

    @Test
    fun `a completed charge segment is evaluated and upserted with the current stats epoch`() = runTest {
        store.cursor = 77L to 12_000L
        store.history += charge(startMs = 0L)

        pipeline().run(nowWallMs = 100_000L)

        val session = store.sessions.single()
        assertEquals(77L, session.statsStartMs)
        assertTrue(session.accepted)
        assertEquals(2_500.0, session.estimateMah!!, 1e-6)
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `a health sample is written once and an unchanged one is not rewritten`() = runTest {
        store.history += charge(startMs = 0L)

        pipeline().run(nowWallMs = 100_000L)
        pipeline().run(nowWallMs = 200_000L)

        assertEquals(listOf(HealthSample(100_000L, 2_400.0, 2_500.0, 100, measuredMah = 2_500.0)), store.health)
        assertEquals("known session is not re-evaluated or re-upserted", 1, store.upsertCalls)
    }

    @Test
    fun `a changed cycle count writes a new health sample`() = runTest {
        pipeline().run(nowWallMs = 100_000L)
        kernel = kernel!!.copy(cycleCount = 101)

        pipeline().run(nowWallMs = 200_000L)

        assertEquals(listOf(100, 101), store.health.map { it.cycleCount })
        assertNull("no accepted session means no measured capacity", store.health.last().measuredMah)
    }

    @Test
    fun `a failing segment is logged and swallowed while later segments and health still run`() = runTest {
        store.history += charge(startMs = 0L)
        store.history += charge(startMs = 10_000L)
        store.failUpsertForStartWallMs = 0L

        pipeline().run(nowWallMs = 100_000L)

        assertEquals(listOf(10_000L), store.sessions.map { it.startWallMs })
        assertEquals(1, logged.size)
        assertEquals(1, store.health.size)
    }

    @Test
    fun `a failing health write is logged and does not throw`() = runTest {
        store.failHealthWrite = true

        pipeline().run(nowWallMs = 100_000L)

        assertEquals(listOf("health sample write failed"), logged)
    }

    @Test
    fun `without kernel capacity nothing is evaluated or written`() = runTest {
        kernel = null
        store.history += charge(startMs = 0L)

        pipeline().run(nowWallMs = 100_000L)

        assertTrue(store.sessions.isEmpty())
        assertTrue(store.health.isEmpty())
    }

    @Test
    fun `runner analyzes after capture and before prune`() = runTest {
        val order = mutableListOf<String>()

        ChargingCaptureRunner().run(
            shouldCapture = true,
            capture = { order += "capture" },
            prune = { order += "prune" },
            analyze = { order += "analyze" },
        )

        assertEquals(listOf("capture", "analyze", "prune"), order)
    }

    // PLUGGED 40% -> UNPLUGGED 80% with 1_000_000 uAh of charge counter delta: a 2_500 mAh pack.
    private fun charge(startMs: Long) = listOf(
        event(startMs, HistoryKind.PLUGGED, 40, 1_000_000L),
        event(startMs + 1_000L, HistoryKind.LEVEL, 60, 1_500_000L),
        event(startMs + 2_000L, HistoryKind.UNPLUGGED, 80, 2_000_000L),
    )

    private fun event(timeMs: Long, kind: Int, levelPct: Int, chargeUah: Long) =
        HistoryEvent(timeMs, timeMs, kind, levelPct, chargeUah, 300, 4_000, 0)

    private class FakeStore : SnapshotStore {
        var cursor: Pair<Long, Long>? = null
        val history = mutableListOf<HistoryEvent>()
        val sessions = mutableListOf<ChargeSessionRecord>()
        val health = mutableListOf<HealthSample>()
        var upsertCalls = 0
        var failUpsertForStartWallMs: Long? = null
        var failHealthWrite = false

        override suspend fun historyBetween(fromWallMs: Long, toWallMs: Long): List<HistoryEvent> = history
        override suspend fun historyCursor(): Pair<Long, Long>? = cursor

        override suspend fun upsertChargeSession(statsStartMs: Long, segment: ChargeSegment, verdict: SegmentVerdict) {
            upsertCalls++
            if (segment.startWallMs == failUpsertForStartWallMs) error("disk full")
            sessions += ChargeSessionRecord(
                statsStartMs = statsStartMs,
                startHistoryTimeMs = segment.startHistoryTimeMs,
                startWallMs = segment.startWallMs,
                endWallMs = segment.endWallMs,
                startPct = segment.startPct,
                endPct = segment.endPct,
                deltaChargeUah = segment.deltaChargeUah,
                integratedMah = segment.integratedMah,
                maxTemperatureDeciC = segment.maxTemperatureDeciC,
                estimateMah = verdict.estimateMah,
                independent = verdict.independent,
                accepted = verdict.accepted,
                rejectReason = verdict.rejectReason,
            )
        }

        override suspend fun chargeSessions(): List<ChargeSessionRecord> = sessions.sortedBy { it.endWallMs }

        override suspend fun acceptedEstimatesChronological(limit: Int): List<Double> =
            chargeSessions().filter { it.accepted }.mapNotNull { it.estimateMah }.take(limit)

        override suspend fun healthSamples(): List<HealthSample> = health.sortedBy { it.wallMs }

        override suspend fun insertHealthSample(sample: HealthSample) {
            if (failHealthWrite) error("disk full")
            health += sample
        }

        override suspend fun insert(snapshot: BatterySnapshot): Long = 0
        override suspend fun byId(id: Long): BatterySnapshot? = null
        override suspend fun latest(kind: SnapshotKind?): BatterySnapshot? = null
        override suspend fun firstAtOrAfter(wallMs: Long, kind: SnapshotKind?, statsStartMs: Long?, bootCount: Int?): BatterySnapshot? = null
        override suspend fun lastAtOrBefore(wallMs: Long, kind: SnapshotKind?): BatterySnapshot? = null
        override suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot? = null
        override suspend fun insertHistory(events: List<HistoryEvent>, statsStartMs: Long) = Unit
        override suspend fun setHistoryCursor(statsStartMs: Long, lastHistoryTimeMs: Long) = Unit
        override suspend fun addMarker(snapshotId: Long, label: String, wallMs: Long): Long = 0
        override suspend fun marker(id: Long): Pair<Long, BatterySnapshot>? = null
        override suspend fun markers(): List<Marker> = emptyList()
        override suspend fun insertSamples(samples: List<LiveReading>) = Unit
        override suspend fun samplesBetween(from: Long, to: Long): List<TelemetrySample> = emptyList()
        override suspend fun insertToolkitAction(record: ToolkitActionRecord): Long = 0
        override suspend fun updateToolkitAction(record: ToolkitActionRecord) = Unit
        override suspend fun toolkitActions(limit: Int): List<ToolkitActionRecord> = emptyList()
        override suspend fun pendingToolkitActions(): List<ToolkitActionRecord> = emptyList()
        override suspend fun recordMiss(wallMs: Long, trigger: CaptureTrigger, reason: String) = Unit
        override suspend fun prune(nowWallMs: Long) = Unit
    }
}
