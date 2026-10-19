package com.akane.inspiration.telemetry.source

import com.akane.inspiration.ipc.ITelemetryUserService
import com.akane.inspiration.ipc.ShizukuManager
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.model.BatteryHistorySnapshot
import com.akane.inspiration.ipc.model.BatteryUsageSnapshot
import com.akane.inspiration.ipc.model.DumpResult
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.calibration.CalibrationInput
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.capture.CaptureResult
import com.akane.inspiration.telemetry.capture.SnapshotCapturer
import com.akane.inspiration.telemetry.common.Clocks
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * The real AndroidTelemetrySource + SnapshotCapturer against a service whose calls block like Binder
 * transactions: the calling thread is held and an interrupt does not end the call. A capture deadline must still
 * release its caller on time, and history pages read before it must be kept so a large backlog makes progress.
 * Real threads and time; deadlines are scaled down from the 10 s production capture deadline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidTelemetrySourceDeadlineTest {
    @Test
    fun `a capture deadline releases the caller while a binder call still blocks`() =
        runBlocking {
            val store = MemoryStore()
            val service = BlockingService(usageMs = 1_500L)
            val capturer = SnapshotCapturer(source(service), store, FixedClocks)

            val startedNs = System.nanoTime()
            val result = capturer.captureFull(CaptureTrigger.MARKER, deadlineMs = DEADLINE_MS, ingestHistory = false)
            val tookMs = elapsedMs(startedNs)

            assertTrue(
                "captureFull(deadlineMs = $DEADLINE_MS) returned after $tookMs ms: it waited for the blocking usage read",
                tookMs < DEADLINE_MS + SLACK_MS,
            )
            assertTrue(result is CaptureResult.Missed)
            assertEquals(listOf(CaptureTrigger.MARKER), store.misses.toList())
        }

    @Test
    fun `the ingest deadline bounds the read even while history pages are in flight`() =
        runBlocking {
            val service = BlockingService(pageMs = PAGE_MS)
            val capturer = SnapshotCapturer(source(service), MemoryStore(), FixedClocks)

            val startedNs = System.nanoTime()
            capturer.ingestHistory(CaptureTrigger.CHARGE_JOB, deadlineMs = DEADLINE_MS)
            val tookMs = elapsedMs(startedNs)

            assertTrue(
                "ingestHistory(deadlineMs = $DEADLINE_MS) returned after $tookMs ms: it waited for " +
                    "${service.historyRequests.size} blocking history pages ($PAGE_MS ms each)",
                tookMs < DEADLINE_MS + PAGE_MS + SLACK_MS,
            )
        }

    @Test
    fun `history pages read before the deadline are persisted and the next ingest resumes from them`() =
        runBlocking {
            val service = BlockingService(pageMs = PAGE_MS)
            val store = MemoryStore()
            val capturer = SnapshotCapturer(source(service), store, FixedClocks)

            capturer.ingestHistory(CaptureTrigger.CHARGE_JOB, deadlineMs = DEADLINE_MS)

            assertNotNull(
                "${service.historyRequests.size} history pages were requested inside the $DEADLINE_MS ms deadline, " +
                    "but none was persisted and the cursor did not move",
                store.cursor,
            )
            val cursor = store.cursor!!
            assertEquals(STATS_START_MS, cursor.first)
            assertEquals("the cursor is the last persisted event", store.history.maxOf { it.historyTimeMs }, cursor.second)

            service.historyRequests.clear()
            capturer.ingestHistory(CaptureTrigger.CHARGE_JOB, deadlineMs = DEADLINE_MS)
            assertEquals("the next ingest pages on from the cursor", cursor.second, service.historyRequests.first())
        }

    private fun elapsedMs(startedNs: Long) = (System.nanoTime() - startedNs) / 1_000_000

    private fun source(service: ITelemetryUserService) =
        AndroidTelemetrySource(
            context = RuntimeEnvironment.getApplication(),
            shizuku = DirectShizuku(service),
            clocks = FixedClocks,
            calibrationInputReader =
                CalibrationInputReader {
                    CalibrationInput(
                        null,
                        null,
                        null,
                        null,
                        isCharging = false,
                        plugged = false,
                    )
                },
        )

    /**
     * Every history page is full (truncated), as on a large first-ingest backlog, so paging continues until its
     * page cap. Each blocking call ignores interrupts the way a Binder transaction does.
     */
    private class BlockingService(
        private val usageMs: Long = 0L,
        private val pageMs: Long = 0L,
    ) : ITelemetryUserService.Default() {
        val historyRequests: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        override fun readBatteryUsage(maxAgeMs: Long): BatteryUsageSnapshot {
            blockUninterruptibly(usageMs)
            return BatteryUsageSnapshot.unavailable("not_under_test")
        }

        override fun openDump(target: Int): DumpResult = DumpResult.unavailable("not_under_test")

        override fun readBatteryHistory(
            statsStartMs: Long,
            afterHistoryTimeMs: Long,
            maxEvents: Int,
        ): BatteryHistorySnapshot {
            historyRequests += afterHistoryTimeMs
            blockUninterruptibly(pageMs)
            val time = afterHistoryTimeMs + 10L
            return BatteryHistorySnapshot(
                available = true,
                reason = null,
                statsStartMs = STATS_START_MS,
                sessionChanged = false,
                events = listOf(HistoryEvent(time, 1_000_000L + time, HistoryKind.LEVEL, 50, -1L, 250, 4_000, 0)),
                truncated = true,
            )
        }

        private fun blockUninterruptibly(ms: Long) {
            val endNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
            var interrupted = false
            while (true) {
                val leftNs = endNs - System.nanoTime()
                if (leftNs <= 0L) break
                try {
                    TimeUnit.NANOSECONDS.sleep(leftNs)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** A ready, already-bound service: the block runs directly, as ShizukuManagerImpl does once connected. */
    private class DirectShizuku(
        private val service: ITelemetryUserService,
    ) : ShizukuManager {
        override val state: StateFlow<ShizukuState> = MutableStateFlow(ShizukuState.Ready(2000, 13))
        override val telemetryService: StateFlow<ITelemetryUserService?> = MutableStateFlow(service)

        override fun requestPermission() = Unit

        override fun start() = Unit

        override suspend fun <T> withTelemetryService(
            timeoutMs: Long,
            block: suspend (ITelemetryUserService) -> T,
        ): T? = block(service)

        override fun holdBinding(): AutoCloseable = AutoCloseable {}

        override fun stop() = Unit
    }

    private object FixedClocks : Clocks {
        override fun wallMs(): Long = 1_000L

        override fun elapsedRealtimeMs(): Long = 2_000L

        override fun uptimeMs(): Long = 3_000L

        override fun bootCount(): Int = 4
    }

    private class MemoryStore : SnapshotStore {
        @Volatile var cursor: Pair<Long, Long>? = null
        val history: MutableList<HistoryEvent> = Collections.synchronizedList(mutableListOf())
        val misses: MutableList<CaptureTrigger> = Collections.synchronizedList(mutableListOf())

        override suspend fun insertHistory(
            events: List<HistoryEvent>,
            statsStartMs: Long,
        ) {
            history += events
        }

        override suspend fun historyCursor(): Pair<Long, Long>? = cursor

        override suspend fun setHistoryCursor(
            statsStartMs: Long,
            lastHistoryTimeMs: Long,
        ) {
            cursor = statsStartMs to lastHistoryTimeMs
        }

        override suspend fun historyBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ): List<HistoryEvent> = history.toList().filter { it.wallMs in fromWallMs..toWallMs }

        override suspend fun recordMiss(
            wallMs: Long,
            trigger: CaptureTrigger,
            reason: String,
        ) {
            misses += trigger
        }

        override suspend fun insert(snapshot: BatterySnapshot): Long = 1L

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

        override suspend fun prune(nowWallMs: Long) = Unit
    }

    private companion object {
        const val PAGE_MS = 150L
        const val DEADLINE_MS = 400L
        const val SLACK_MS = 400L
        const val STATS_START_MS = 77L
    }
}
