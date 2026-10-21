package com.akane.inspiration.telemetry.capacity

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.calibration.Confidence
import com.akane.inspiration.telemetry.calibration.microampHourCounter
import kotlin.math.abs

data class ChargeSegment(
    val startHistoryTimeMs: Long,
    val startWallMs: Long,
    val endWallMs: Long,
    val startPct: Int,
    val endPct: Int,
    val deltaChargeUah: Long?,
    val integratedMah: Double?,
    val maxTemperatureDeciC: Int?,
)

data class KernelCapacity(
    val chargeFullMah: Double?,
    val designMah: Double?,
    val cycleCount: Int?,
)

data class SegmentVerdict(
    val estimateMah: Double?,
    val independent: Boolean,
    val accepted: Boolean,
    val rejectReason: String?,
)

data class CapacityEstimate(
    val kernelChargeFullMah: Double?,
    val kernelHealthPct: Double?,
    val designMah: Double?,
    val cycleCount: Int?,
    val measuredMah: Double?,
    val measuredHealthPct: Double?,
    val independent: Boolean,
    val sampleCount: Int,
    val confidence: Confidence,
)

object ChargeSegmentExtractor {
    /**
     * Segments over every stats session as one stream, each tagged with the `statsStartMs` its PLUGGED
     * event was stored under. [sessions] are runs of one label, oldest first in ingestion order (see
     * `SnapshotStore.historySessions`). Android resets battery stats on unplug after a deep charge, so the
     * charge's start lands in the old session and its end (the RESET, then maybe UNPLUGGED) in the new one;
     * a wall-clock step relabels the continuing session, possibly with a smaller start. Walking the runs
     * separately, or by label value, would lose such charges. History clocks may restart per session, so the
     * runs keep their given order and each is ordered by history time; START/SHUTDOWN still split boots.
     */
    fun fromSessions(sessions: List<Pair<Long, List<HistoryEvent>>>): List<Pair<Long, ChargeSegment>> =
        walk(
            sessions.flatMap { (statsStartMs, events) ->
                events.sortedBy(HistoryEvent::historyTimeMs).map { statsStartMs to it }
            },
        )

    private fun walk(stream: List<Pair<Long, HistoryEvent>>): List<Pair<Long, ChargeSegment>> {
        val segments = ArrayList<Pair<Long, ChargeSegment>>()
        var active = mutableListOf<HistoryEvent>()
        var activeEpoch = NO_EPOCH
        // A reset recorded while still plugged: only an unplug at this same instant may still close the charge.
        var resetWhilePluggedAtWallMs: Long? = null

        fun discard() {
            active = mutableListOf()
            resetWhilePluggedAtWallMs = null
        }

        fun close(end: HistoryEvent) {
            active += end
            active.toChargeSegment()?.let { segments += activeEpoch to it }
            discard()
        }

        for ((epoch, event) in stream) {
            when (event.kind) {
                HistoryKind.START, HistoryKind.SHUTDOWN -> {
                    discard()
                }

                HistoryKind.RESET -> {
                    when {
                        active.isEmpty() -> Unit

                        // The reset record already carries the unplug: the charge ended here.
                        event.plugType == UNPLUGGED_PLUG_TYPE -> close(event)

                        else -> resetWhilePluggedAtWallMs = event.wallMs
                    }
                }

                HistoryKind.PLUGGED -> {
                    discard()
                    active += event
                    activeEpoch = epoch
                }

                HistoryKind.UNPLUGGED -> {
                    val resetAt = resetWhilePluggedAtWallMs
                    if (active.isNotEmpty() && (resetAt == null || resetAt == event.wallMs)) close(event) else discard()
                }

                else -> {
                    when {
                        active.isEmpty() -> Unit

                        resetWhilePluggedAtWallMs == null -> active += event

                        // Other transitions of the reset instant are not evidence that charging went on.
                        event.wallMs == resetWhilePluggedAtWallMs -> Unit

                        // Charging went on after a mid-charge reset, which still discards the segment.
                        else -> discard()
                    }
                }
            }
        }
        return segments
    }

    private fun List<HistoryEvent>.toChargeSegment(): ChargeSegment? {
        val start = first()
        val end = last()
        if (start.wallMs < 0L || end.wallMs <= start.wallMs) return null
        if (end.levelPct - start.levelPct < MINIMUM_SOC_RISE_PCT) return null

        return ChargeSegment(
            startHistoryTimeMs = start.historyTimeMs,
            startWallMs = start.wallMs,
            endWallMs = end.wallMs,
            startPct = start.levelPct,
            endPct = end.levelPct,
            // A charge under the µAh floor (a mAh kernel's counter) is unknown, so the delta is too.
            deltaChargeUah =
                microampHourCounter(start.chargeUah)
                    ?.let { startCharge -> microampHourCounter(end.chargeUah)?.minus(startCharge) },
            integratedMah = null,
            maxTemperatureDeciC = maxOfOrNull(HistoryEvent::temperatureDeciC),
        )
    }

    private const val MINIMUM_SOC_RISE_PCT = 40
    private const val UNPLUGGED_PLUG_TYPE = 0
    private const val NO_EPOCH = 0L
}

object Integrator {
    fun trapezoidMah(samples: List<Pair<Long, Double>>): Double? {
        if (samples.size < 2) return null

        var totalMah = 0.0
        for ((previous, current) in samples.zipWithNext()) {
            val elapsedMs = current.first - previous.first
            if (elapsedMs !in 0L..MAX_SAMPLE_GAP_MS || !previous.second.isFinite() || !current.second.isFinite()) {
                return null
            }
            totalMah += (previous.second + current.second) / 2.0 * elapsedMs / MILLIS_PER_HOUR
        }
        return totalMah
    }

    private const val MAX_SAMPLE_GAP_MS = 5_000L
    private const val MILLIS_PER_HOUR = 3_600_000.0
}

object CapacityEstimator {
    fun evaluate(
        segment: ChargeSegment,
        kernel: KernelCapacity,
        recentAcceptedChronological: List<Double>,
        consecutiveRejections: Int,
    ): SegmentVerdict {
        if (segment.maxTemperatureDeciC != null && segment.maxTemperatureDeciC > MAX_TEMPERATURE_DECI_C) {
            return rejected("temperature_too_high")
        }

        val estimateMah = segment.capacityMah() ?: return rejected("missing_capacity")
        val designMah = kernel.designMah?.takeIf { it > 0.0 } ?: return rejected("missing_design_capacity")
        if (estimateMah !in designMah * MINIMUM_DESIGN_RATIO..designMah * MAXIMUM_DESIGN_RATIO) {
            return rejected("outside_design_bounds")
        }

        if (recentAcceptedChronological.size >= MINIMUM_MEDIAN_SAMPLES &&
            consecutiveRejections < MEDIAN_RESET_REJECTIONS
        ) {
            val median = recentAcceptedChronological.median()
            if (median > 0.0 && abs(estimateMah - median) / median > MAX_MEDIAN_DEVIATION) {
                return rejected("median_deviation")
            }
        }

        return SegmentVerdict(
            estimateMah = estimateMah,
            independent = estimateMah.isIndependentOf(kernel.chargeFullMah),
            accepted = true,
            rejectReason = null,
        )
    }

    fun combine(
        acceptedChronological: List<Double>,
        kernel: KernelCapacity,
    ): CapacityEstimate {
        val emaMah = acceptedChronological.ema()
        val independent = emaMah?.isIndependentOf(kernel.chargeFullMah) ?: false
        val measuredMah = emaMah?.takeIf { independent }
        val designMah = kernel.designMah

        return CapacityEstimate(
            kernelChargeFullMah = kernel.chargeFullMah,
            kernelHealthPct = kernel.chargeFullMah.healthOf(designMah),
            designMah = designMah,
            cycleCount = kernel.cycleCount,
            measuredMah = measuredMah,
            measuredHealthPct = measuredMah.healthOf(designMah),
            independent = independent,
            sampleCount = acceptedChronological.size,
            confidence = acceptedChronological.confidence(),
        )
    }

    private fun ChargeSegment.capacityMah(): Double? {
        val deltaPct = endPct - startPct
        if (deltaPct <= 0) return null
        val chargeMah =
            deltaChargeUah?.let { it / MICROAMP_HOURS_PER_MILLIAMP_HOUR }
                ?: integratedMah?.takeIf(Double::isFinite)
                ?: return null
        return chargeMah / (deltaPct / PERCENT)
    }

    private fun Double?.isIndependentOf(kernelChargeFullMah: Double?): Boolean {
        val estimateMah = this ?: return false
        val kernelMah = kernelChargeFullMah?.takeIf { it > 0.0 } ?: return true
        return abs(estimateMah - kernelMah) / kernelMah > INDEPENDENCE_THRESHOLD
    }

    private fun Double?.healthOf(designMah: Double?): Double? =
        if (this != null && designMah != null && designMah > 0.0) this / designMah * PERCENT else null

    private fun List<Double>.median(): Double {
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun List<Double>.ema(): Double? =
        firstOrNull()?.let { first -> drop(1).fold(first) { ema, value -> EMA_ALPHA * value + (1 - EMA_ALPHA) * ema } }

    private fun List<Double>.confidence(): Confidence =
        when (size) {
            0 -> Confidence.UNCALIBRATED
            in 1 until MINIMUM_MEDIAN_SAMPLES -> Confidence.LOW
            else -> Confidence.HIGH
        }

    private fun rejected(reason: String) =
        SegmentVerdict(
            estimateMah = null,
            independent = false,
            accepted = false,
            rejectReason = reason,
        )

    private const val MAX_TEMPERATURE_DECI_C = 450
    private const val MINIMUM_DESIGN_RATIO = 0.5
    private const val MAXIMUM_DESIGN_RATIO = 1.2
    private const val MINIMUM_MEDIAN_SAMPLES = 3
    private const val MEDIAN_RESET_REJECTIONS = 3
    private const val MAX_MEDIAN_DEVIATION = 0.15
    private const val INDEPENDENCE_THRESHOLD = 0.01
    private const val EMA_ALPHA = 0.3
    private const val MICROAMP_HOURS_PER_MILLIAMP_HOUR = 1_000.0
    private const val PERCENT = 100.0
}
