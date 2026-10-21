package com.akane.inspiration.designsystem.chart.math

import com.akane.inspiration.designsystem.chart.model.TimeRange
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private const val MINUTE_MS = 60_000L
private const val MINUTES_PER_DAY = 1_440

/** Sub-day steps in minutes: 1/5/10/15/30 min, 1/2/3/6/12 h. */
private val MINUTE_STEPS = intArrayOf(1, 5, 10, 15, 30, 60, 120, 180, 360, 720)

/** Day steps; 2 and 7 days exist so a week-long window still fits a small tick budget. */
private val DAY_STEPS = intArrayOf(1, 2, 7)

/**
 * Ticks chosen by [niceTimeTickSet].
 *
 * @property dayAligned the chosen step is whole days, so every tick is a local midnight and is labelled with
 *   its date; otherwise the step is sub-day and ticks need the time of day. Label formatters decide from this,
 *   not from the window length, because a multi-day window can still fit a sub-day step.
 */
data class NiceTimeTicks(
    val ticksMs: List<Long>,
    val dayAligned: Boolean,
)

/**
 * Tick instants inside [window] (inclusive), at most [maxTicks], aligned to wall-clock boundaries of
 * [zone] (so 3-hour ticks land on 00:00, 03:00, ...), using the finest step that fits the budget.
 * Sub-day slots skipped by a DST gap are dropped; overlaps include both distinct instants in time order.
 */
fun niceTimeTickSet(
    window: TimeRange,
    maxTicks: Int,
    zone: ZoneId,
): NiceTimeTicks {
    if (maxTicks < 1 || window.durationMs <= 0L) return NiceTimeTicks(emptyList(), dayAligned = false)
    for (minutes in MINUTE_STEPS) {
        // Cheap prune: a step this fine cannot fit and would only generate throw-away candidates.
        if (window.durationMs / (minutes * MINUTE_MS) >= maxTicks * 2L) continue
        val ticks = wallClockTicks(window, zone, minutes)
        if (ticks.size <= maxTicks) return NiceTimeTicks(ticks, dayAligned = false)
    }
    var last = emptyList<Long>()
    for (days in DAY_STEPS) {
        last = dayTicks(window, zone, days)
        if (last.size <= maxTicks) return NiceTimeTicks(last, dayAligned = true)
    }
    val stride = (last.size + maxTicks - 1) / maxTicks
    return NiceTimeTicks(last.filterIndexed { index, _ -> index % stride == 0 }, dayAligned = true)
}

/** True when [epochMs] is the start of a local day in [zone] (midnight, or the first instant after a DST gap). */
fun isStartOfDay(
    epochMs: Long,
    zone: ZoneId,
): Boolean = localDate(epochMs, zone).atStartOfDay(zone).toInstant().toEpochMilli() == epochMs

private fun localDate(
    epochMs: Long,
    zone: ZoneId,
): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

private fun wallClockTicks(
    window: TimeRange,
    zone: ZoneId,
    stepMinutes: Int,
): List<Long> {
    val out = ArrayList<Long>()
    var date = localDate(window.startMs, zone).minusDays(1)
    val lastDate = localDate(window.endMs, zone).plusDays(1)
    while (!date.isAfter(lastDate)) {
        var minute = 0
        while (minute < MINUTES_PER_DAY) {
            val local = date.atTime(LocalTime.ofSecondOfDay(minute * 60L))
            for (offset in zone.rules.getValidOffsets(local)) {
                val t = local.toInstant(offset).toEpochMilli()
                if (t in window.startMs..window.endMs) out += t
            }
            minute += stepMinutes
        }
        date = date.plusDays(1)
    }
    // Local slots interleave the two overlap offsets, so order only after enumerating all instants.
    return out.distinct().sorted()
}

private fun dayTicks(
    window: TimeRange,
    zone: ZoneId,
    stepDays: Int,
): List<Long> {
    val out = ArrayList<Long>()
    var date = localDate(window.startMs, zone).minusDays(1)
    val lastDate = localDate(window.endMs, zone).plusDays(1)
    while (!date.isAfter(lastDate)) {
        // Offset by 3 so 7-day ticks fall on Mondays (epoch day 0 is a Thursday).
        val aligned = if (stepDays == 7) (date.toEpochDay() + 3) % 7 == 0L else date.toEpochDay() % stepDays == 0L
        if (aligned) {
            val t = date.atStartOfDay(zone).toInstant().toEpochMilli()
            if (t in window.startMs..window.endMs && (out.isEmpty() || t > out.last())) out += t
        }
        date = date.plusDays(1)
    }
    return out
}
