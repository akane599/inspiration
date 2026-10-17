package com.akane.inspiration.debug.gallery

import com.akane.inspiration.designsystem.chart.model.HypnogramData
import com.akane.inspiration.designsystem.chart.model.HypnogramStage
import com.akane.inspiration.designsystem.chart.model.Lane
import com.akane.inspiration.designsystem.chart.model.LaneKind
import com.akane.inspiration.designsystem.chart.model.LaneSegment
import com.akane.inspiration.designsystem.chart.model.LineSeries
import com.akane.inspiration.designsystem.chart.model.ScrubChartData
import com.akane.inspiration.designsystem.chart.model.SeriesKind
import com.akane.inspiration.designsystem.chart.model.StageSegment
import com.akane.inspiration.designsystem.chart.model.TimePoint
import com.akane.inspiration.designsystem.chart.model.TimeRange
import kotlinx.collections.immutable.persistentListOf
import kotlin.random.Random

/** Synthetic, deterministic chart inputs for the debug design gallery. */
internal data class GalleryData(
    val chart: ScrubChartData,
    val hypnogram: HypnogramData,
)

/**
 * Builds 24 h of dense telemetry: one battery and one temperature point every [STEP_MS] ([POINT_COUNT]
 * per series), four toggling lanes and a night hypnogram. The same seed always yields the same data.
 */
internal object GalleryDataGenerator {
    const val SEED = 4242
    const val STEP_MS = 10_000L
    const val POINT_COUNT = 8_640
    const val WAKE_PULSE_COUNT = 25

    /** 2026-01-01T00:00:00Z, fixed so screenshots and frame stats are comparable across runs. */
    const val WINDOW_START_MS = 1_767_225_600_000L

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val BATTERY_START_PERCENT = 85f

    val window: TimeRange = TimeRange.of(WINDOW_START_MS, WINDOW_START_MS + POINT_COUNT * STEP_MS)

    /** The window starts at midday, so hours 11 to 19 are 23:00 to 07:00. */
    val night: TimeRange = TimeRange.of(WINDOW_START_MS + 11 * HOUR_MS, WINDOW_START_MS + 19 * HOUR_MS)

    fun generate(seed: Int = SEED): GalleryData {
        val random = Random(seed)
        val stages = nightStages(random)
        val pulses = List(WAKE_PULSE_COUNT) { night.startMs + random.nextLong(night.durationMs) }
        val hypnogram = HypnogramData.of(night, stages, pulses)

        val screen = screenSegments(random, stages)
        val charging = listOf(
            LaneSegment(hoursIn(4.0), hoursIn(5.0)),
            LaneSegment(hoursIn(10.5), hoursIn(14.0)),
        )
        val deep = stages.filter { it.stage == HypnogramStage.DEEP_IDLE }.map { LaneSegment(it.startMs, it.endMs) }
        val awake = stages.filter { it.stage != HypnogramStage.DEEP_IDLE && it.stage != HypnogramStage.LIGHT_IDLE }
            .map { LaneSegment(it.startMs, it.endMs) } +
            toggles(random, window.startMs, night.startMs, offMin = 40L..120L, onMin = 1L..3L) +
            toggles(random, night.endMs, window.endMs, offMin = 40L..120L, onMin = 1L..3L)

        val screenMask = mask(screen)
        val chargingMask = mask(charging)
        val battery = ArrayList<TimePoint>(POINT_COUNT)
        val temperature = ArrayList<TimePoint>(POINT_COUNT)
        var percent = BATTERY_START_PERCENT
        var celsius = 29f
        for (i in 0 until POINT_COUNT) {
            val tMs = WINDOW_START_MS + i * STEP_MS
            val rate = when {
                chargingMask[i] -> 0.04f
                screenMask[i] -> -0.012f
                else -> -0.0015f
            }
            percent = (percent + rate + (random.nextFloat() - 0.5f) * 0.004f).coerceIn(1f, 100f)
            val target = 27f + (if (screenMask[i]) 7f else 0f) + (if (chargingMask[i]) 5f else 0f)
            celsius += (target - celsius) * 0.01f + (random.nextFloat() - 0.5f) * 0.08f
            battery += TimePoint(tMs, percent)
            temperature += TimePoint(tMs, celsius)
        }

        val chart = ScrubChartData(
            window = window,
            series = persistentListOf(
                LineSeries.of(SeriesKind.BATTERY_PERCENT, battery),
                LineSeries.of(SeriesKind.TEMPERATURE_C, temperature),
            ),
            lanes = persistentListOf(
                Lane.of(LaneKind.SCREEN_ON, screen),
                Lane.of(LaneKind.DEEP_SLEEP, deep),
                Lane.of(LaneKind.AWAKE, awake),
                Lane.of(LaneKind.CHARGING, charging),
            ),
        )
        return GalleryData(chart = chart, hypnogram = hypnogram)
    }

    private fun hoursIn(hours: Double): Long = WINDOW_START_MS + (hours * HOUR_MS).toLong()

    /** About 40 stage segments; deep idle dominates early in the night, awake and maintenance later. */
    private fun nightStages(random: Random): List<StageSegment> {
        val out = ArrayList<StageSegment>()
        var t = night.startMs
        var previous: HypnogramStage? = null
        while (t < night.endMs) {
            val progress = (t - night.startMs).toFloat() / night.durationMs
            var stage: HypnogramStage
            do {
                stage = pickStage(random, progress)
            } while (stage == previous)
            val end = minOf(night.endMs, t + random.nextLong(3L, 22L) * MINUTE_MS)
            out += StageSegment(t, end, stage)
            t = end
            previous = stage
        }
        return out
    }

    private fun pickStage(random: Random, progress: Float): HypnogramStage {
        val deep = 0.05f + 0.45f * (1f - progress)
        val awake = 0.06f + 0.10f * progress
        val maintenance = 0.12f
        val roll = random.nextFloat()
        return when {
            roll < deep -> HypnogramStage.DEEP_IDLE
            roll < deep + awake -> HypnogramStage.AWAKE
            roll < deep + awake + maintenance -> HypnogramStage.MAINTENANCE
            else -> HypnogramStage.LIGHT_IDLE
        }
    }

    /** Waking-hour sessions (10 to 50 min off, 1 to 9 min on) plus a short check during half of the night wakes. */
    private fun screenSegments(random: Random, stages: List<StageSegment>): List<LaneSegment> {
        val day = toggles(random, window.startMs, night.startMs, offMin = 10L..50L, onMin = 1L..9L) +
            toggles(random, night.endMs, window.endMs, offMin = 10L..50L, onMin = 1L..9L)
        val nightChecks = stages.filter { it.stage == HypnogramStage.AWAKE && random.nextBoolean() }
            .map { LaneSegment(it.startMs, minOf(it.endMs, it.startMs + 2 * MINUTE_MS)) }
        return day + nightChecks
    }

    /** Alternating off/on spans inside [fromMs, toMs); returns the on spans. */
    private fun toggles(
        random: Random,
        fromMs: Long,
        toMs: Long,
        offMin: LongRange,
        onMin: LongRange,
    ): List<LaneSegment> {
        val out = ArrayList<LaneSegment>()
        var t = fromMs
        while (true) {
            val start = t + random.nextLong(offMin.first, offMin.last + 1) * MINUTE_MS
            if (start >= toMs) break
            val end = minOf(toMs, start + random.nextLong(onMin.first, onMin.last + 1) * MINUTE_MS)
            out += LaneSegment(start, end)
            t = end
        }
        return out
    }

    /** One flag per sample step: true where a segment covers it. */
    private fun mask(segments: List<LaneSegment>): BooleanArray {
        val flags = BooleanArray(POINT_COUNT)
        for (segment in segments) {
            val from = stepCeil(segment.startMs)
            val to = stepCeil(segment.endMs)
            for (i in from until to) flags[i] = true
        }
        return flags
    }

    private fun stepCeil(tMs: Long): Int {
        val steps = (tMs - WINDOW_START_MS + STEP_MS - 1) / STEP_MS
        return steps.coerceIn(0L, POINT_COUNT.toLong()).toInt()
    }
}
