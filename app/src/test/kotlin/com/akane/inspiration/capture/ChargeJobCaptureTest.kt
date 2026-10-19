package com.akane.inspiration.capture

import com.akane.inspiration.di.StoreTicks
import com.akane.inspiration.health.HealthDependencies
import com.akane.inspiration.health.HealthIntent
import com.akane.inspiration.health.HealthViewModel
import com.akane.inspiration.ipc.model.BatteryHistorySnapshot
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.app.KernelCapacitySnapshot
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.capture.SnapshotCapturer
import com.akane.inspiration.telemetry.common.Clocks
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

/** runChargeJobCapture: one full read per run, and the PLUGGED lookup never loads the whole history. */
class ChargeJobCaptureTest {
    @Test
    fun `a due capture saves the ingest read instead of reading batterystats again`() =
        runTest {
            val source = CountingSource(history = listOf(event(5_000, HistoryKind.PLUGGED)))
            val store = FakeStore()

            runChargeJobCapture(
                SnapshotCapturer(source, store, FixedClocks),
                store,
                FixedClocks,
                DEADLINE_MS,
                analyze = {},
                onHistoryStored = {},
            )

            assertEquals(1, source.fullReads)
            assertEquals(listOf(SnapshotKind.FULL to CaptureTrigger.CHARGE_JOB), store.saved())
            assertEquals(listOf(event(5_000, HistoryKind.PLUGGED)), store.history)
            assertEquals(0, store.wholeHistoryLoads)
        }

    @Test
    fun `a skipped run still reads once and saves nothing`() =
        runTest {
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
                onHistoryStored = {},
            )

            assertEquals(1, source.fullReads)
            assertEquals(listOf(SnapshotKind.FULL to CaptureTrigger.CHARGE_JOB), store.saved())
            assertEquals(false, analyzed)
            assertEquals(0, store.wholeHistoryLoads)
        }

    @Test
    fun `a newer PLUGGED event from the per-kind lookup makes the run capture`() =
        runTest {
            val source = CountingSource(history = listOf(event(5_000, HistoryKind.PLUGGED), event(5_500, HistoryKind.LEVEL)))
            val store = FakeStore(snapshots = mutableListOf(snapshot(CaptureTrigger.CHARGE_JOB, wallMs = 4_000)))

            runChargeJobCapture(
                SnapshotCapturer(source, store, FixedClocks),
                store,
                FixedClocks,
                DEADLINE_MS,
                analyze = {},
                onHistoryStored = {},
            )

            assertEquals(1, source.fullReads)
            assertEquals(2, store.saved().size)
            assertEquals(1, store.perKindLookups)
            assertEquals(0, store.wholeHistoryLoads)
        }

    @Test
    fun `after a forward wall clock jump the charge job prunes on this boot's elapsed time`() =
        runTest {
            // The earlier charge job read wall 4_000 at elapsed 2; one minute later the wall clock reads a year ahead.
            val jumped = ClocksAt(wallMs = 4_000L + 60_000L + YEAR_MS, elapsedMs = 2L + 60_000L)
            val source =
                CountingSource(
                    history = listOf(event(5_000, HistoryKind.PLUGGED)),
                    clock = SnapshotClock(jumped.wallMs(), jumped.elapsedRealtimeMs(), 3, BOOT, null, null),
                )
            val store = FakeStore(snapshots = mutableListOf(snapshot(CaptureTrigger.CHARGE_JOB, wallMs = 4_000)))

            runChargeJobCapture(SnapshotCapturer(source, store, jumped), store, jumped, DEADLINE_MS, analyze = {}, onHistoryStored = {})

            assertEquals(2, store.saved().size)
            assertEquals("retention 'now' is one minute after the earlier job, not a year", listOf(64_000L), store.prunedAt)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a charge job's pass reaches a visible Health screen once its analysis is stored`() {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        try {
            runTest(main) {
                val source = CountingSource(history = listOf(event(5_000, HistoryKind.PLUGGED)))
                val store = FakeStore()
                val ticks = StoreTicks(FixedClocks::wallMs)
                val screen = StoreHealth(store, ticks.lastWallMs)
                val vm = HealthViewModel(screen, shapingDispatcher = main)
                vm.onIntent(HealthIntent.Visible)
                runCurrent()
                val readsOnEnter = screen.historyReads
                val ticksSeenByAnalysis = mutableListOf<Long?>()

                runChargeJobCapture(
                    SnapshotCapturer(source, store, FixedClocks),
                    store,
                    FixedClocks,
                    DEADLINE_MS,
                    analyze = { ticksSeenByAnalysis += ticks.lastWallMs.value },
                    onHistoryStored = ticks::tick,
                )
                runCurrent()

                assertEquals("the tick waits for the sessions and health sample the analysis stores", listOf(null), ticksSeenByAnalysis)
                assertEquals("the visible screen re-read the store once", readsOnEnter + 1, screen.historyReads)
                vm.onIntent(HealthIntent.Hidden)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    /** Health's reads served from the store the charge job writes, its tick wired as the container wires it. */
    private class StoreHealth(
        private val store: SnapshotStore,
        override val lastRefreshWallMs: StateFlow<Long?>,
    ) : HealthDependencies {
        var historyReads = 0

        override val clocks: Clocks = FixedClocks

        override suspend fun refresh(): RefreshResult = RefreshResult.Debounced(0L)

        override suspend fun kernelBattery(): KernelCapacitySnapshot? = null

        override suspend fun healthSamples(): List<HealthSample> = store.healthSamples()

        override suspend fun chargeSessions(): List<ChargeSessionRecord> = store.chargeSessions()

        override suspend fun samplesBetween(
            from: Long,
            to: Long,
        ): List<TelemetrySample> = store.samplesBetween(from, to)

        override fun liveReadings(periodMs: Long): Flow<LiveReading> = emptyFlow()

        override fun holdBinding(): AutoCloseable = AutoCloseable {}

        override suspend fun historyBetween(
            from: Long,
            to: Long,
        ): List<HistoryEvent> {
            historyReads++
            return store.historyBetween(from, to)
        }
    }

    private class CountingSource(
        private val history: List<HistoryEvent>,
        private val clock: SnapshotClock = SnapshotClock(7_000, 2, 3, BOOT, null, null),
    ) : TelemetrySource {
        var fullReads = 0

        override suspend fun full(
            trigger: CaptureTrigger,
            historyCursor: Pair<Long, Long>?,
        ): FullRead {
            fullReads++
            return FullRead(
                light = light(),
                usage = null,
                batteryStats = DumpParse.Unavailable("not_needed"),
                alarm = DumpParse.Unavailable("not_needed"),
                kernel = null,
                cpuTimes = emptyList(),
                history = listOf(BatteryHistorySnapshot(true, null, 10L, false, history, false)),
            )
        }

        override fun light(): LightRead =
            LightRead(
                clock = clock,
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

    private class ClocksAt(
        private val wallMs: Long,
        private val elapsedMs: Long,
    ) : Clocks {
        override fun wallMs(): Long = wallMs

        override fun elapsedRealtimeMs(): Long = elapsedMs

        override fun uptimeMs(): Long = elapsedMs

        override fun bootCount(): Int = BOOT
    }

    private class FakeStore(
        private val snapshots: MutableList<BatterySnapshot> = mutableListOf(),
    ) : SnapshotStore {
        val history = mutableListOf<HistoryEvent>()
        var wholeHistoryLoads = 0
        var perKindLookups = 0
        val prunedAt = mutableListOf<Long>()
        private var cursor: Pair<Long, Long>? = null

        fun saved() = snapshots.map { it.kind to it.trigger }

        override suspend fun insert(snapshot: BatterySnapshot): Long {
            snapshots += snapshot
            return snapshots.size.toLong()
        }

        override suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot? =
            snapshots.filter { it.trigger == trigger }.maxByOrNull { it.clock.wallMs }

        override suspend fun insertHistory(
            events: List<HistoryEvent>,
            statsStartMs: Long,
        ) {
            history += events
        }

        override suspend fun historyBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ): List<HistoryEvent> {
            wholeHistoryLoads++
            return history.filter { it.wallMs in fromWallMs..toWallMs }
        }

        override suspend fun lastHistoryOfEachKindBefore(wallMs: Long): List<HistoryEvent> {
            perKindLookups++
            return history
                .filter { it.wallMs < wallMs }
                .groupBy { it.kind }
                .values
                .map { events -> events.maxBy { it.wallMs } }
                .sortedBy { it.wallMs }
        }

        override suspend fun historyCursor(): Pair<Long, Long>? = cursor

        override suspend fun setHistoryCursor(
            statsStartMs: Long,
            lastHistoryTimeMs: Long,
        ) {
            cursor = statsStartMs to lastHistoryTimeMs
        }

        override suspend fun recordMiss(
            wallMs: Long,
            trigger: CaptureTrigger,
            reason: String,
        ) = Unit

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

        override suspend fun snapshotsBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ): List<BatterySnapshot> = snapshots.filter { it.clock.wallMs in fromWallMs..toWallMs }

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
        ): List<TelemetrySample> = emptyList()

        override suspend fun upsertChargeSession(
            statsStartMs: Long,
            segment: ChargeSegment,
            verdict: SegmentVerdict,
        ) = Unit

        override suspend fun chargeSessions(): List<ChargeSessionRecord> = emptyList()

        override suspend fun acceptedEstimatesChronological(limit: Int): List<Double> = emptyList()

        override suspend fun healthSamples(): List<HealthSample> = emptyList()

        override suspend fun insertHealthSample(sample: HealthSample) = Unit

        override suspend fun insertToolkitAction(record: ToolkitActionRecord): Long = 0

        override suspend fun updateToolkitAction(record: ToolkitActionRecord) = Unit

        override suspend fun toolkitActions(limit: Int): List<ToolkitActionRecord> = emptyList()

        override suspend fun pendingToolkitActions(): List<ToolkitActionRecord> = emptyList()

        override suspend fun prune(nowWallMs: Long) {
            prunedAt += nowWallMs
        }
    }

    private companion object {
        const val BOOT = 4
        const val DEADLINE_MS = 10_000L
        const val YEAR_MS = 365L * 24L * 60L * 60_000L

        fun event(
            wallMs: Long,
            kind: Int,
        ) = HistoryEvent(wallMs, wallMs, kind, 70, 1_000_000, 250, 4_000, 0)

        fun snapshot(
            trigger: CaptureTrigger,
            wallMs: Long,
        ) = BatterySnapshot(
            id = 0L,
            kind = SnapshotKind.FULL,
            trigger = trigger,
            clock = SnapshotClock(wallMs, 2, 3, BOOT, null, null),
            battery =
                com.akane.inspiration.telemetry.snapshot
                    .BatteryState(70, true, "charging", 250, 4_000, 1_000, 1_000_000, null),
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
