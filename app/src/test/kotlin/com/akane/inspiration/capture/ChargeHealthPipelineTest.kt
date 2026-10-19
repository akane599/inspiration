package com.akane.inspiration.capture

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.capacity.CapacityEstimator
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
    fun `overlapping history clocks are extracted and stored under their own epochs`() =
        runTest {
            store.cursor = 2L to 100L
            store.epochs =
                listOf(
                    1L to charge(0L),
                    2L to charge(10_000L).map { it.copy(historyTimeMs = it.historyTimeMs - 10_000L) },
                )
            pipeline().run(100_000L)
            assertEquals(listOf(1L, 2L), store.sessions.map { it.statsStartMs })
            assertEquals(listOf(0L, 10_000L), store.sessions.map { it.startWallMs })
        }

    @Test
    fun `a charge relabelled by a backward clock step is stored under the label it started in`() =
        runTest {
            // The step shifts the reported stats start back; the run after it is newer but has the smaller label.
            val (beforeStep, afterStep) = charge(0L).partition { it.kind != HistoryKind.UNPLUGGED }
            store.cursor = 940L to 2_000L
            store.epochs = listOf(1_000L to beforeStep, 940L to afterStep)

            pipeline().run(100_000L)

            val session = store.sessions.single()
            assertEquals(1_000L to 0L, session.statsStartMs to session.startHistoryTimeMs)
            assertEquals(0L to 2_000L, session.startWallMs to session.endWallMs)
            assertEquals(2_500.0, session.estimateMah!!, 1e-6)
        }

    @Test
    fun `a charge ending in a stats reset is stored once under the epoch it started in`() =
        runTest {
            // Battery stats reset at unplug: PLUGGED/LEVEL stay under epoch 1, the RESET (already unplugged) opens 2.
            store.cursor = 2L to 50L
            store.epochs =
                listOf(
                    1L to charge(0L).dropLast(1),
                    2L to listOf(event(2_000L, HistoryKind.RESET, 80, 2_000_000L).copy(historyTimeMs = 0L)),
                )

            pipeline().run(100_000L)
            pipeline().run(200_000L)

            val session = store.sessions.single()
            assertEquals(1L, session.statsStartMs)
            assertEquals(0L to 2_000L, session.startWallMs to session.endWallMs)
            assertEquals(2_500.0, session.estimateMah!!, 1e-6)
        }

    @Test
    fun `a completed charge segment is evaluated and upserted with the current stats epoch`() =
        runTest {
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
    fun `missing or nonpositive design defers new verdicts until a positive design returns`() =
        runTest {
            store.history += charge(0L)
            for (designMah in listOf(null, 0.0, -1.0)) {
                kernel = kernel!!.copy(designMah = designMah)

                pipeline().run(100_000L)

                assertTrue("design $designMah must not make a terminal rejection", store.sessions.isEmpty())
                assertEquals(0, store.upsertCalls)
            }
            kernel = kernel!!.copy(designMah = 2_500.0)

            pipeline().run(200_000L)

            assertTrue(store.sessions.single().accepted)
            assertEquals(1, store.upsertCalls)
        }

    @Test
    fun `persisted missing design rejection recovers after history and current samples expire`() =
        runTest {
            val segment =
                segment(0L).copy(
                    startHistoryTimeMs = 123L,
                    deltaChargeUah = null,
                    integratedMah = 1_000.0,
                    maxTemperatureDeciC = 315,
                )
            kernel = kernel!!.copy(designMah = null)
            store.upsertChargeSession(77L, segment, CapacityEstimator.evaluate(segment, kernel!!, emptyList(), 0))
            val rejected = store.sessions.single()
            assertEquals("missing_design_capacity", rejected.rejectReason)
            assertTrue(store.history.isEmpty())
            assertTrue(store.samples.isEmpty())

            pipeline().run(100_000L)
            assertEquals(rejected, store.sessions.single())
            assertEquals(1, store.upsertCalls)
            kernel = kernel!!.copy(designMah = 2_500.0)

            pipeline().run(200_000L)
            pipeline().run(300_000L)

            assertEquals(
                rejected.copy(estimateMah = 2_500.0, independent = true, accepted = true, rejectReason = null),
                store.sessions.single(),
            )
            assertEquals("one replacement, then no more writes", 2, store.upsertCalls)
            assertEquals(2_500.0, store.health.single().measuredMah!!, 1e-6)
            assertTrue(logged.isEmpty())
        }

    @Test
    fun `stored retry identity and evidence take precedence over re-extracted wall range`() =
        runTest {
            val segment = segment(0L).copy(startHistoryTimeMs = 123L)
            store.upsertChargeSession(77L, segment, rejected("missing_design_capacity"))
            val persisted = store.sessions.single()
            // The same wall range was re-ingested under another stats/history epoch with different counters.
            store.epochs = listOf(88L to charge(0L).map { it.copy(chargeUah = it.chargeUah * 2) })

            pipeline().run(100_000L)
            pipeline().run(200_000L)

            assertEquals(
                persisted.copy(estimateMah = 2_500.0, independent = true, accepted = true, rejectReason = null),
                store.sessions.single(),
            )
            assertEquals(2, store.upsertCalls)
        }

    @Test
    fun `stored retry evidence wins when re-extraction shifts walls under the same storage key`() =
        runTest {
            val segment = segment(0L).copy(startHistoryTimeMs = 123L)
            store.upsertChargeSession(77L, segment, rejected("missing_design_capacity"))
            val persisted = store.sessions.single()
            store.epochs =
                listOf(
                    77L to
                        charge(0L).map {
                            it.copy(historyTimeMs = it.historyTimeMs + 123L, wallMs = it.wallMs + 10_000L)
                        },
                )

            pipeline().run(100_000L)
            pipeline().run(200_000L)

            assertEquals(
                persisted.copy(estimateMah = 2_500.0, independent = true, accepted = true, rejectReason = null),
                store.sessions.single(),
            )
            assertEquals(2, store.upsertCalls)
        }

    @Test
    fun `accepted sessions and genuine rejection reasons remain terminal`() =
        runTest {
            val verdicts =
                listOf(
                    accepted(1_500.0),
                    rejected("missing_capacity"),
                    rejected("temperature_too_high"),
                    rejected("outside_design_bounds"),
                    rejected("median_deviation"),
                )
            verdicts.forEachIndexed { index, verdict ->
                val startMs = index * 10_000L
                store.upsertChargeSession(77L, segment(startMs), verdict)
                store.history += charge(startMs)
            }
            val persisted = store.chargeSessions()

            pipeline().run(100_000L)
            store.epochs = listOf(77L to store.history.map { it.copy(wallMs = it.wallMs + 1_000L) })
            pipeline().run(200_000L)

            assertEquals(persisted, store.chargeSessions())
            assertEquals(verdicts.size, store.upsertCalls)
        }

    @Test
    fun `future accepted sessions do not affect median for recovered or new segments`() =
        runTest {
            for (startMs in listOf(0L, 10_000L, 20_000L)) {
                store.upsertChargeSession(77L, segment(startMs), accepted(2_500.0))
            }
            store.upsertChargeSession(77L, segment(30_000L), rejected("missing_design_capacity"))
            store.history += charge(40_000L)
            for (startMs in listOf(50_000L, 60_000L, 70_000L)) {
                store.upsertChargeSession(77L, segment(startMs, 1_500.0), accepted(1_500.0))
            }

            pipeline().run(100_000L)

            val evaluated = store.chargeSessions().filter { it.startWallMs in listOf(30_000L, 40_000L) }
            assertEquals(2, evaluated.size)
            assertTrue(evaluated.all { it.accepted && it.estimateMah == 2_500.0 })
        }

    @Test
    fun `future genuine rejections do not reset median for recovered or new segments`() =
        runTest {
            for (startMs in listOf(0L, 10_000L, 20_000L)) {
                store.upsertChargeSession(77L, segment(startMs), accepted(2_500.0))
            }
            store.upsertChargeSession(77L, segment(30_000L, 1_500.0), rejected("missing_design_capacity"))
            store.history += charge(40_000L).map { it.copy(chargeUah = it.chargeUah * 3 / 5) }
            for (startMs in listOf(50_000L, 60_000L, 70_000L)) {
                store.upsertChargeSession(77L, segment(startMs), rejected("temperature_too_high"))
            }

            pipeline().run(100_000L)

            val evaluated = store.chargeSessions().filter { it.startWallMs in listOf(30_000L, 40_000L) }
            assertEquals(listOf("median_deviation", "median_deviation"), evaluated.map { it.rejectReason })
        }

    @Test
    fun `earlier genuine rejection streak resets median and recovered acceptance resets the streak`() =
        runTest {
            for (startMs in listOf(0L, 10_000L, 20_000L)) {
                store.upsertChargeSession(77L, segment(startMs), accepted(2_500.0))
            }
            for (startMs in listOf(30_000L, 40_000L, 50_000L)) {
                store.upsertChargeSession(77L, segment(startMs), rejected("temperature_too_high"))
            }
            store.upsertChargeSession(77L, segment(60_000L, 1_500.0), rejected("missing_design_capacity"))
            store.history += charge(70_000L).map { it.copy(chargeUah = it.chargeUah * 3 / 5) }

            pipeline().run(100_000L)

            val evaluated = store.chargeSessions().filter { it.startWallMs in listOf(60_000L, 70_000L) }
            assertTrue(evaluated.first().accepted)
            assertEquals(1_500.0, evaluated.first().estimateMah!!, 1e-6)
            assertEquals("median_deviation", evaluated.last().rejectReason)
        }

    @Test
    fun `sessions ending at the same time are not earlier median inputs`() =
        runTest {
            for (startMs in listOf(0L, 10_000L, 20_000L)) {
                store.upsertChargeSession(77L, segment(startMs).copy(endWallMs = 100_000L), accepted(2_500.0))
            }
            store.upsertChargeSession(
                77L,
                segment(90_000L, 1_500.0).copy(endWallMs = 100_000L),
                rejected("missing_design_capacity"),
            )

            pipeline().run(200_000L)

            assertTrue(store.chargeSessions().last().accepted)
            assertEquals(1_500.0, store.chargeSessions().last().estimateMah!!, 1e-6)
        }

    @Test
    fun `a health sample is written once and an unchanged one is not rewritten`() =
        runTest {
            store.history += charge(startMs = 0L)

            pipeline().run(nowWallMs = 100_000L)
            pipeline().run(nowWallMs = 200_000L)

            assertEquals(listOf(HealthSample(100_000L, 2_400.0, 2_500.0, 100, measuredMah = 2_500.0)), store.health)
            assertEquals("known session is not re-evaluated or re-upserted", 1, store.upsertCalls)
        }

    @Test
    fun `capacity history is retained when cycle count is unavailable`() =
        runTest {
            kernel = kernel!!.copy(cycleCount = null)
            pipeline().run(100_000L)
            kernel = kernel!!.copy(chargeFullMah = 2_350.0)
            pipeline().run(200_000L)
            assertEquals(listOf(2_400.0, 2_350.0), store.health.map { it.fccMah })
            assertTrue(store.health.all { it.cycleCount == null })
        }

    @Test
    fun `a changed cycle count writes a new health sample`() =
        runTest {
            pipeline().run(nowWallMs = 100_000L)
            kernel = kernel!!.copy(cycleCount = 101)

            pipeline().run(nowWallMs = 200_000L)

            assertEquals(listOf(100, 101), store.health.map { it.cycleCount })
            assertNull("no accepted session means no measured capacity", store.health.last().measuredMah)
        }

    @Test
    fun `a failing segment is logged and swallowed while later segments and health still run`() =
        runTest {
            store.history += charge(startMs = 0L)
            store.history += charge(startMs = 10_000L)
            store.failUpsertForStartWallMs = 0L

            pipeline().run(nowWallMs = 100_000L)

            assertEquals(listOf(10_000L), store.sessions.map { it.startWallMs })
            assertEquals(1, logged.size)
            assertEquals(1, store.health.size)
        }

    @Test
    fun `a failing health write is logged and does not throw`() =
        runTest {
            store.failHealthWrite = true

            pipeline().run(nowWallMs = 100_000L)

            assertEquals(listOf("health sample write failed"), logged)
        }

    @Test
    fun `without kernel capacity nothing is evaluated or written`() =
        runTest {
            kernel = null
            store.history += charge(startMs = 0L)

            pipeline().run(nowWallMs = 100_000L)

            assertTrue(store.sessions.isEmpty())
            assertTrue(store.health.isEmpty())
        }

    @Test
    fun `runner analyzes after capture and before prune`() =
        runTest {
            val order = mutableListOf<String>()

            ChargingCaptureRunner().run(
                shouldCapture = true,
                capture = { order += "capture" },
                prune = { order += "prune" },
                analyze = { order += "analyze" },
            )

            assertEquals(listOf("capture", "analyze", "prune"), order)
        }

    @Test
    fun `complete recorded current supplies capacity when charge counters are absent`() =
        runTest {
            store.history += charge(0).map { it.copy(chargeUah = -1) }
            store.samples += listOf(sample(0), sample(1_000), sample(2_000).copy(plugged = false))

            pipeline().run(100_000)

            val session = store.sessions.single()
            assertNull(session.deltaChargeUah)
            assertEquals(1_000.0, session.integratedMah!!, 1e-6)
            assertEquals(2_500.0, session.estimateMah!!, 1e-6)
            assertTrue(session.accepted)
        }

    @Test
    fun `partial recording missing current and boot gaps do not supply capacity`() =
        runTest {
            val cases =
                listOf(
                    listOf(sample(1_000)),
                    listOf(sample(0), sample(1_000).copy(currentMa = null), sample(2_000)),
                    listOf(sample(0), sample(1_000).copy(bootCount = 2), sample(2_000)),
                    listOf(sample(0), sample(1_000).copy(elapsedRealtimeMs = 0), sample(2_000)),
                    listOf(sample(0), sample(1_000).copy(plugged = false), sample(2_000)),
                )
            for (samples in cases) {
                store.sessions.clear()
                store.history.clear()
                store.samples.clear()
                store.history += charge(0).map { it.copy(chargeUah = -1) }
                store.samples += samples
                pipeline().run(100_000)
                assertEquals("missing_capacity", store.sessions.single().rejectReason)
                assertNull(store.sessions.single().integratedMah)
            }
        }

    @Test
    fun `recordings that miss a session boundary or contain long gaps are rejected`() =
        runTest {
            val cases =
                listOf(
                    listOf(sample(6_000), sample(10_000)),
                    listOf(sample(0), sample(4_000)),
                    listOf(sample(0), sample(10_000)),
                )
            for (samples in cases) {
                store.sessions.clear()
                store.history.clear()
                store.samples.clear()
                store.history += listOf(event(0, HistoryKind.PLUGGED, 40, -1), event(10_000, HistoryKind.UNPLUGGED, 80, -1))
                store.samples += samples
                pipeline().run(100_000)
                assertEquals("missing_capacity", store.sessions.single().rejectReason)
            }
        }

    @Test
    fun `recorded temperature participates in current based capacity evaluation`() =
        runTest {
            store.history += charge(0).map { it.copy(chargeUah = -1) }
            store.samples += listOf(sample(0), sample(1_000).copy(temperatureDeciC = 460), sample(2_000))
            pipeline().run(100_000)
            assertEquals("temperature_too_high", store.sessions.single().rejectReason)
        }

    private fun segment(
        startMs: Long,
        estimateMah: Double = 2_500.0,
    ) = ChargeSegment(
        startHistoryTimeMs = startMs,
        startWallMs = startMs,
        endWallMs = startMs + 2_000L,
        startPct = 40,
        endPct = 80,
        deltaChargeUah = (estimateMah * 400).toLong(),
        integratedMah = null,
        maxTemperatureDeciC = 300,
    )

    private fun accepted(estimateMah: Double) = SegmentVerdict(estimateMah, true, true, null)

    private fun rejected(reason: String) = SegmentVerdict(null, false, false, reason)

    private fun sample(wallMs: Long) =
        TelemetrySample(
            wallMs,
            wallMs,
            1,
            1_800_000,
            4_000,
            7_200.0,
            300,
            60,
            true,
        )

    // PLUGGED 40% -> UNPLUGGED 80% with 1_000_000 uAh of charge counter delta: a 2_500 mAh pack.
    private fun charge(startMs: Long) =
        listOf(
            event(startMs, HistoryKind.PLUGGED, 40, 1_000_000L),
            event(startMs + 1_000L, HistoryKind.LEVEL, 60, 1_500_000L),
            event(startMs + 2_000L, HistoryKind.UNPLUGGED, 80, 2_000_000L),
        )

    private fun event(
        timeMs: Long,
        kind: Int,
        levelPct: Int,
        chargeUah: Long,
    ) = HistoryEvent(timeMs, timeMs, kind, levelPct, chargeUah, 300, 4_000, 0)

    private class FakeStore : SnapshotStore {
        var cursor: Pair<Long, Long>? = null
        var epochs: List<Pair<Long, List<HistoryEvent>>>? = null
        val history = mutableListOf<HistoryEvent>()
        val samples = mutableListOf<TelemetrySample>()
        val sessions = mutableListOf<ChargeSessionRecord>()
        val health = mutableListOf<HealthSample>()
        var upsertCalls = 0
        var failUpsertForStartWallMs: Long? = null
        var failHealthWrite = false

        override suspend fun historyBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ): List<HistoryEvent> = history

        override suspend fun historyCursor(): Pair<Long, Long>? = cursor

        override suspend fun historySessions(): List<Pair<Long, List<HistoryEvent>>> = epochs ?: listOf((cursor?.first ?: 0L) to history)

        override suspend fun upsertChargeSession(
            statsStartMs: Long,
            segment: ChargeSegment,
            verdict: SegmentVerdict,
        ) {
            upsertCalls++
            if (segment.startWallMs == failUpsertForStartWallMs) error("disk full")
            // Match Room's unique (statsStartMs, startHistoryTimeMs) replacement, not append-only storage.
            sessions.removeAll { it.statsStartMs == statsStartMs && it.startHistoryTimeMs == segment.startHistoryTimeMs }
            sessions +=
                ChargeSessionRecord(
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
            chargeSessions().filter { it.accepted }.mapNotNull { it.estimateMah }.takeLast(limit)

        override suspend fun healthSamples(): List<HealthSample> = health.sortedBy { it.wallMs }

        override suspend fun insertHealthSample(sample: HealthSample) {
            if (failHealthWrite) error("disk full")
            health += sample
        }

        override suspend fun insert(snapshot: BatterySnapshot): Long = 0

        override suspend fun byId(id: Long): BatterySnapshot? = null

        override suspend fun latest(kind: SnapshotKind?): BatterySnapshot? = null

        override suspend fun firstAtOrAfter(
            wallMs: Long,
            kind: SnapshotKind?,
            statsStartMs: Long?,
            bootCount: Int?,
        ): BatterySnapshot? = null

        override suspend fun lastAtOrBefore(
            wallMs: Long,
            kind: SnapshotKind?,
        ): BatterySnapshot? = null

        override suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot? = null

        override suspend fun snapshotsBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ): List<BatterySnapshot> = emptyList()

        override suspend fun insertHistory(
            events: List<HistoryEvent>,
            statsStartMs: Long,
        ) = Unit

        override suspend fun setHistoryCursor(
            statsStartMs: Long,
            lastHistoryTimeMs: Long,
        ) = Unit

        override suspend fun addMarker(
            snapshotId: Long,
            label: String,
            wallMs: Long,
        ): Long = 0

        override suspend fun marker(id: Long): Pair<Long, BatterySnapshot>? = null

        override suspend fun markers(): List<Marker> = emptyList()

        override suspend fun insertSamples(samples: List<LiveReading>) = Unit

        override suspend fun samplesBetween(
            from: Long,
            to: Long,
        ): List<TelemetrySample> = samples.filter { it.wallMs in from..to }

        override suspend fun insertToolkitAction(record: ToolkitActionRecord): Long = 0

        override suspend fun updateToolkitAction(record: ToolkitActionRecord) = Unit

        override suspend fun toolkitActions(limit: Int): List<ToolkitActionRecord> = emptyList()

        override suspend fun pendingToolkitActions(): List<ToolkitActionRecord> = emptyList()

        override suspend fun recordMiss(
            wallMs: Long,
            trigger: CaptureTrigger,
            reason: String,
        ) = Unit

        override suspend fun prune(nowWallMs: Long) = Unit
    }
}
