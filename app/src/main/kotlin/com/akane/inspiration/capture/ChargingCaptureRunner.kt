package com.akane.inspiration.capture

import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.capacity.CapacityEstimator
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.ChargeSegmentExtractor
import com.akane.inspiration.telemetry.capacity.Integrator
import com.akane.inspiration.telemetry.capacity.KernelCapacity
import com.akane.inspiration.telemetry.capture.SnapshotCapturer
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.SnapshotStore
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
 */
internal suspend fun runChargeJobCapture(
    capturer: SnapshotCapturer,
    store: SnapshotStore,
    clocks: Clocks,
    deadlineMs: Long,
    analyze: suspend () -> Unit,
    runner: ChargingCaptureRunner = ChargingCaptureRunner(),
) {
    val ingest = capturer.ingestHistory(CaptureTrigger.CHARGE_JOB, deadlineMs)
    val lastPluggedHistoryWallMs =
        if (ingest.historyAvailable) {
            store.lastHistoryOfEachKindBefore(Long.MAX_VALUE).lastOrNull { it.kind == HistoryKind.PLUGGED }?.wallMs
        } else {
            null
        }
    val lastPowerConnectedWallMs = store.latestClockByTrigger(CaptureTrigger.POWER_CONNECTED)?.wallMs
    val lastChargeJobClock = store.latestClockByTrigger(CaptureTrigger.CHARGE_JOB)
    runner.run(
        shouldCapture = CapturePolicy.chargeJobShouldRun(
            lastPluggedHistoryWallMs = lastPluggedHistoryWallMs,
            lastPowerConnectedWallMs = lastPowerConnectedWallMs,
            lastChargeJobWallMs = lastChargeJobClock?.wallMs,
            lastChargeJobBootCount = lastChargeJobClock?.bootCount,
            currentBootCount = clocks.bootCount(),
            historyAvailable = ingest.historyAvailable,
        ),
        capture = { capturer.captureFull(ingest) },
        analyze = analyze,
        prune = { store.prune(clocks.wallMs()) },
    )
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
        val known = store.chargeSessions()
        val knownKeys = known.mapTo(HashSet()) { it.startWallMs to it.endWallMs }
        val accepted = store.acceptedEstimatesChronological(ACCEPTED_LIMIT).toMutableList()
        var consecutiveRejections = known.takeLastWhile { !it.accepted }.size

        // One stream across stats sessions: a charge that ends in a reset spans two of them.
        val segments = ChargeSegmentExtractor.fromSessions(store.historySessions())
            // Wall times, not (statsStart, historyTime): a battery-stats reset changes the epoch key but not these.
            .filter { (_, segment) -> (segment.startWallMs to segment.endWallMs) !in knownKeys }
            .sortedBy { it.second.endWallMs }
        for ((statsStartMs, segment) in segments) {
            val verdict = guarded("charge segment ${segment.startHistoryTimeMs} failed") {
                val enriched = withRecordedCurrent(segment)
                CapacityEstimator.evaluate(enriched, kernel, accepted.takeLast(MEDIAN_WINDOW), consecutiveRejections)
                    .also { store.upsertChargeSession(statsStartMs, enriched, it) }
            } ?: continue
            if (verdict.accepted) {
                verdict.estimateMah?.let(accepted::add)
                consecutiveRejections = 0
            } else {
                consecutiveRejections++
            }
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
            samples.any { it.bootCount != first.bootCount || it.currentMa == null ||
                (it.plugged != true && it.wallMs != segment.endWallMs) } ||
            samples.zipWithNext().any { (a, b) ->
                b.wallMs - a.wallMs !in 1L..MAX_SAMPLE_GAP_MS ||
                    b.elapsedRealtimeMs - a.elapsedRealtimeMs !in 1L..MAX_SAMPLE_GAP_MS
            }
        ) return segment

        // Use monotonic times and extend only the short gaps at the session boundaries.
        val points = buildList {
            if (startGap > 0) add(first.elapsedRealtimeMs - startGap to first.currentMa!!.toDouble())
            samples.forEach { add(it.elapsedRealtimeMs to it.currentMa!!.toDouble()) }
            if (endGap > 0) add(last.elapsedRealtimeMs + endGap to last.currentMa!!.toDouble())
        }
        return segment.copy(
            integratedMah = Integrator.trapezoidMah(points),
            maxTemperatureDeciC = (samples.mapNotNull { it.temperatureDeciC } +
                listOfNotNull(segment.maxTemperatureDeciC)).maxOrNull(),
        )
    }

    private suspend fun recordHealth(kernel: KernelCapacity, nowWallMs: Long) {
        val fcc = kernel.chargeFullMah ?: return
        val design = kernel.designMah ?: return
        val cycles = kernel.cycleCount
        val estimate = CapacityEstimator.combine(store.acceptedEstimatesChronological(ACCEPTED_LIMIT), kernel)
        val sample = HealthSample(nowWallMs, fcc, design, cycles, estimate.measuredMah)
        val latest = store.healthSamples().lastOrNull()
        if (latest == null || latest.copy(wallMs = sample.wallMs) != sample) store.insertHealthSample(sample)
    }

    private suspend fun <T> guarded(what: String, block: suspend () -> T): T? = try {
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
