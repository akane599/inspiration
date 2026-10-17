package com.akane.inspiration.capture

import com.akane.inspiration.telemetry.capacity.CapacityEstimator
import com.akane.inspiration.telemetry.capacity.ChargeSegmentExtractor
import com.akane.inspiration.telemetry.capacity.KernelCapacity
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
        val statsStartMs = store.historyCursor()?.first ?: 0L
        val accepted = store.acceptedEstimatesChronological(ACCEPTED_LIMIT).toMutableList()
        var consecutiveRejections = known.takeLastWhile { !it.accepted }.size

        val segments = ChargeSegmentExtractor.fromHistory(store.historyBetween(0L, Long.MAX_VALUE))
            // Wall times, not (statsStart, historyTime): a battery-stats reset changes the epoch key but not these.
            .filter { (it.startWallMs to it.endWallMs) !in knownKeys }
            .sortedBy { it.endWallMs }
        for (segment in segments) {
            val verdict = guarded("charge segment ${segment.startHistoryTimeMs} failed") {
                CapacityEstimator.evaluate(segment, kernel, accepted.takeLast(MEDIAN_WINDOW), consecutiveRejections)
                    .also { store.upsertChargeSession(statsStartMs, segment, it) }
            } ?: continue
            if (verdict.accepted) {
                verdict.estimateMah?.let(accepted::add)
                consecutiveRejections = 0
            } else {
                consecutiveRejections++
            }
        }
    }

    private suspend fun recordHealth(kernel: KernelCapacity, nowWallMs: Long) {
        val fcc = kernel.chargeFullMah ?: return
        val design = kernel.designMah ?: return
        val cycles = kernel.cycleCount ?: return
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
    }
}
