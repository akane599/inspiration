package com.akane.inspiration.capture

import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.capacity.CapacityEstimator
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.ChargeSegmentExtractor
import com.akane.inspiration.telemetry.capacity.Integrator
import com.akane.inspiration.telemetry.capacity.KernelCapacity
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.capture.SnapshotCapturer
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.SnapshotStore
import com.akane.inspiration.telemetry.store.pruneRetention
import kotlinx.coroutines.CancellationException

class ChargingCaptureRunner {
    suspend fun run(
        shouldCapture: Boolean,
        capture: suspend () -> Unit,
        prune: suspend () -> Unit,
        analyze: suspend () -> Unit = {},
    ) {
        if (!shouldCapture) return
        capture()
        analyze()
        prune()
    }
}

/**
 * One charge-job pass. A single full read ingests the history, the policy decides from it, and a due capture
 * saves that same read: the job never dumps batterystats twice. The PLUGGED lookup reads one row per history
 * kind, not the whole history table.
 *
 * The pass ends with [onHistoryStored], however it ends: by then the store holds the ingested history and what
 * [analyze] derived from it (charge sessions, a health sample), which a screen open meanwhile must re-read.
 */
internal suspend fun runChargeJobCapture(
    capturer: SnapshotCapturer,
    store: SnapshotStore,
    clocks: Clocks,
    deadlineMs: Long,
    analyze: suspend () -> Unit,
    onHistoryStored: () -> Unit,
    runner: ChargingCaptureRunner = ChargingCaptureRunner(),
) {
    try {
        val ingest = capturer.ingestHistory(CaptureTrigger.CHARGE_JOB, deadlineMs)
        // Only a complete ingest makes the newest stored PLUGGED event this charge's: a capped or partly failed one
        // may have left it unread, and the policy then decides from the POWER_CONNECTED capture (audit B-02).
        val lastPluggedHistoryWallMs =
            if (ingest.historyComplete) {
                store.lastHistoryOfEachKindBefore(Long.MAX_VALUE).lastOrNull { it.kind == HistoryKind.PLUGGED }?.wallMs
            } else {
                null
            }
        val lastPowerConnectedWallMs = store.latestClockByTrigger(CaptureTrigger.POWER_CONNECTED)?.wallMs
        val lastChargeJobClock = store.latestClockByTrigger(CaptureTrigger.CHARGE_JOB)
        runner.run(
            shouldCapture =
                CapturePolicy.chargeJobShouldRun(
                    lastPluggedHistoryWallMs = lastPluggedHistoryWallMs,
                    lastPowerConnectedWallMs = lastPowerConnectedWallMs,
                    lastChargeJobWallMs = lastChargeJobClock?.wallMs,
                    lastChargeJobBootCount = lastChargeJobClock?.bootCount,
                    currentBootCount = clocks.bootCount(),
                    historyComplete = ingest.historyComplete,
                ),
            capture = { capturer.captureFull(ingest) },
            analyze = analyze,
            prune = { store.pruneRetention(clocks) },
        )
    } finally {
        onHistoryStored()
    }
}

/**
 * Turns the ingested battery history into charge sessions and a health sample. One-shot and idempotent:
 * it reads only what the charge job already stored, so it never polls or schedules anything.
 *
 * Failures are reported through [log] and swallowed per segment (and for the health sample) so analysis
 * can never fail the capture job; only cancellation propagates.
 */
class ChargeHealthPipeline(
    private val store: SnapshotStore,
    private val kernelCapacity: suspend () -> KernelCapacity?,
    private val log: (message: String, error: Throwable) -> Unit,
) {
    suspend fun run(nowWallMs: Long) {
        val kernel = guarded("kernel capacity read failed") { kernelCapacity() } ?: return
        guarded("charge session analysis failed") { evaluateSegments(kernel) }
        guarded("health sample write failed") { recordHealth(kernel, nowWallMs) }
    }

    private suspend fun evaluateSegments(kernel: KernelCapacity) {
        // Missing design is a transient prerequisite, not a verdict about a charge segment.
        if (kernel.designMah?.takeIf { it > 0.0 } == null) return
        val (retry, terminal) =
            store.chargeSessions().partition {
                !it.accepted && it.rejectReason == "missing_design_capacity"
            }
        val knownKeys = terminal.mapTo(HashSet()) { it.startWallMs to it.endWallMs }
        val terminalIdentities = terminal.mapTo(HashSet()) { it.statsStartMs to it.startHistoryTimeMs }
        // Charge sessions outlive raw history and current samples. Reuse their evidence and storage identity.
        val recovered =
            retry.map { row ->
                row.statsStartMs to
                    ChargeSegment(
                        startHistoryTimeMs = row.startHistoryTimeMs,
                        startWallMs = row.startWallMs,
                        endWallMs = row.endWallMs,
                        startPct = row.startPct,
                        endPct = row.endPct,
                        deltaChargeUah = row.deltaChargeUah,
                        integratedMah = row.integratedMah,
                        maxTemperatureDeciC = row.maxTemperatureDeciC,
                    )
            }

        // One stream across stats sessions: a charge that ends in a reset spans two of them.
        val segments =
            (recovered + ChargeSegmentExtractor.fromSessions(store.historySessions()))
                // Prefer persisted evidence even if re-extraction changes its wall-clock frame or stats epoch.
                .distinctBy { (statsStartMs, segment) -> statsStartMs to segment.startHistoryTimeMs }
                .distinctBy { (_, segment) -> segment.startWallMs to segment.endWallMs }
                .filter { (statsStartMs, segment) ->
                    (statsStartMs to segment.startHistoryTimeMs) !in terminalIdentities &&
                        (segment.startWallMs to segment.endWallMs) !in knownKeys
                }.sortedBy { it.second.endWallMs }
        val evaluated =
            terminal
                .map { row ->
                    row.endWallMs to SegmentVerdict(row.estimateMah, row.independent, row.accepted, row.rejectReason)
                }.toMutableList()
        for ((statsStartMs, segment) in segments) {
            // A recovered segment can precede terminal rows: future (or tied) verdicts are not prior evidence.
            val previous = evaluated.filter { it.first < segment.endWallMs }.sortedBy { it.first }.map { it.second }
            val accepted = previous.filter { it.accepted }.mapNotNull { it.estimateMah }
            val consecutiveRejections = previous.takeLastWhile { !it.accepted }.size
            val verdict =
                guarded("charge segment ${segment.startHistoryTimeMs} failed") {
                    val enriched = withRecordedCurrent(segment)
                    CapacityEstimator
                        .evaluate(enriched, kernel, accepted.takeLast(MEDIAN_WINDOW), consecutiveRejections)
                        .also { store.upsertChargeSession(statsStartMs, enriched, it) }
                } ?: continue
            evaluated += segment.endWallMs to verdict
        }
    }

    private suspend fun withRecordedCurrent(segment: ChargeSegment): ChargeSegment {
        if (segment.deltaChargeUah != null || segment.integratedMah != null) return segment
        val samples = store.samplesBetween(segment.startWallMs, segment.endWallMs).sortedBy { it.wallMs }
        if (samples.size < 2) return segment
        val first = samples.first()
        val last = samples.last()
        val startGap = first.wallMs - segment.startWallMs
        val endGap = segment.endWallMs - last.wallMs
        if (startGap !in 0L..MAX_SAMPLE_GAP_MS || endGap !in 0L..MAX_SAMPLE_GAP_MS ||
            samples.any {
                it.bootCount != first.bootCount || it.currentMa == null ||
                    (it.plugged != true && it.wallMs != segment.endWallMs)
            } ||
            samples.zipWithNext().any { (a, b) ->
                b.wallMs - a.wallMs !in 1L..MAX_SAMPLE_GAP_MS ||
                    b.elapsedRealtimeMs - a.elapsedRealtimeMs !in 1L..MAX_SAMPLE_GAP_MS
            }
        ) {
            return segment
        }

        // Use monotonic times and extend only the short gaps at the session boundaries.
        val points =
            buildList {
                if (startGap > 0) add(first.elapsedRealtimeMs - startGap to first.currentMa!!.toDouble())
                samples.forEach { add(it.elapsedRealtimeMs to it.currentMa!!.toDouble()) }
                if (endGap > 0) add(last.elapsedRealtimeMs + endGap to last.currentMa!!.toDouble())
            }
        return segment.copy(
            integratedMah = Integrator.trapezoidMah(points),
            maxTemperatureDeciC =
                (
                    samples.mapNotNull { it.temperatureDeciC } +
                        listOfNotNull(segment.maxTemperatureDeciC)
                ).maxOrNull(),
        )
    }

    private suspend fun recordHealth(
        kernel: KernelCapacity,
        nowWallMs: Long,
    ) {
        val fcc = kernel.chargeFullMah ?: return
        val design = kernel.designMah ?: return
        val cycles = kernel.cycleCount
        val estimate = CapacityEstimator.combine(store.acceptedEstimatesChronological(ACCEPTED_LIMIT), kernel)
        val sample = HealthSample(nowWallMs, fcc, design, cycles, estimate.measuredMah)
        val latest = store.healthSamples().lastOrNull()
        if (latest == null || latest.copy(wallMs = sample.wallMs) != sample) store.insertHealthSample(sample)
    }

    private suspend fun <T> guarded(
        what: String,
        block: suspend () -> T,
    ): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log(what, error)
            null
        }

    private companion object {
        const val ACCEPTED_LIMIT = 1_000
        const val MEDIAN_WINDOW = 10
        const val MAX_SAMPLE_GAP_MS = 5_000L
    }
}
