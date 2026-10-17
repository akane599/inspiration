package com.akane.inspiration.designsystem.chart.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * A closed time interval in epoch milliseconds. Build with [TimeRange.of], which orders the ends,
 * so `startMs <= endMs` always holds for factory-built values.
 */
@Immutable
data class TimeRange internal constructor(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs

    companion object {
        fun of(aMs: Long, bMs: Long): TimeRange =
            if (aMs <= bMs) TimeRange(aMs, bMs) else TimeRange(bMs, aMs)
    }
}

@Immutable
data class TimePoint(val tMs: Long, val value: Float)

enum class SeriesKind { BATTERY_PERCENT, TEMPERATURE_C }

/** Points sorted by time with non-finite values dropped. Build with [LineSeries.of]. */
@Immutable
data class LineSeries internal constructor(val kind: SeriesKind, val points: ImmutableList<TimePoint>) {
    companion object {
        fun of(kind: SeriesKind, points: List<TimePoint>): LineSeries =
            LineSeries(kind, points.filter { it.value.isFinite() }.sortedBy { it.tMs }.toImmutableList())
    }
}

enum class LaneKind { SCREEN_ON, DEEP_SLEEP, AWAKE, CHARGING }

@Immutable
data class LaneSegment(val startMs: Long, val endMs: Long)

/** Segments sorted by start with backwards (`endMs < startMs`) segments dropped. Build with [Lane.of]. */
@Immutable
data class Lane internal constructor(val kind: LaneKind, val segments: ImmutableList<LaneSegment>) {
    companion object {
        fun of(kind: LaneKind, segments: List<LaneSegment>): Lane =
            Lane(kind, segments.filter { it.endMs >= it.startMs }.sortedBy { it.startMs }.toImmutableList())
    }
}

@Immutable
data class ScrubChartData(
    val window: TimeRange,
    val series: ImmutableList<LineSeries>,
    val lanes: ImmutableList<Lane>,
)

enum class HypnogramStage { AWAKE, MAINTENANCE, LIGHT_IDLE, DEEP_IDLE }

@Immutable
data class StageSegment(val startMs: Long, val endMs: Long, val stage: HypnogramStage)

/** Stage segments and wake pulses sorted by time, backwards segments dropped. Build with [HypnogramData.of]. */
@Immutable
data class HypnogramData internal constructor(
    val window: TimeRange,
    val segments: ImmutableList<StageSegment>,
    val wakePulsesMs: ImmutableList<Long>,
) {
    companion object {
        fun of(window: TimeRange, segments: List<StageSegment>, wakePulsesMs: List<Long>): HypnogramData =
            HypnogramData(
                window,
                segments.filter { it.endMs >= it.startMs }.sortedBy { it.startMs }.toImmutableList(),
                wakePulsesMs.sorted().toImmutableList(),
            )
    }
}
