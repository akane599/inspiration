package com.akane.inspiration.diagnostics.analysis

/**
 * What a [TimelineSegment] shows. SCREEN_ON and CHARGING are independent lanes, so they may overlap
 * every other kind; DEEP_IDLE, LIGHT_IDLE, MAINTENANCE and AWAKE (the device-idle track) never
 * overlap each other. "Deep idle" is Doze, not the CPU-suspend "deep sleep" metric.
 */
enum class SegmentKind { SCREEN_ON, CHARGING, DEEP_IDLE, LIGHT_IDLE, MAINTENANCE, AWAKE }

/** A span of [kind] in epoch milliseconds, `startMs < endMs`, clamped to its [Timeline] window. */
data class TimelineSegment(val kind: SegmentKind, val startMs: Long, val endMs: Long)

/** One reading at [tMs]: battery percent for level points, degrees Celsius for temperature points. */
data class TimelinePoint(val tMs: Long, val value: Float)

/**
 * Battery-history timeline for the wall-clock window [from, to] (epoch ms). [segments] are sorted by
 * start, then by [SegmentKind] order; [wakePulsesMs] are sorted and distinct. Built by [TimelineBuilder].
 */
data class Timeline(
    val segments: List<TimelineSegment>,
    val levelPoints: List<TimelinePoint>,
    val temperaturePoints: List<TimelinePoint>,
    val wakePulsesMs: List<Long>,
    val from: Long,
    val to: Long,
)
