package com.akane.inspiration.telemetry.interval

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.telemetry.store.SnapshotStore

/**
 * The stored history [IntervalMetricsCalculator.compute] needs for an interval that starts at [fromWallMs] and is cut
 * at [toWallMs]: it computes the same metrics as every stored event with `0 <= wallMs <= toWallMs` (audit R-15). The
 * events from [fromWallMs] on are read as they are; before it only the state the interval opens in, from every event at
 * each kind's newest wall time ([SnapshotStore.newestHistoryOfEachKindBefore], index seeks in the Room store). Only
 * when those cannot fix the level baseline (a SHUTDOWN after the newest LEVEL/RESET/START, see
 * [IntervalMetricsCalculator.seedReplayFromWallMs]) is the history before the interval read, from that SHUTDOWN, or
 * from the start when no event of those four kinds precedes the interval.
 */
suspend fun SnapshotStore.intervalHistory(
    fromWallMs: Long,
    toWallMs: Long,
): List<HistoryEvent> {
    if (toWallMs < 0) return emptyList()
    val bound = fromWallMs.coerceIn(0L, if (toWallMs == Long.MAX_VALUE) toWallMs else toWallMs + 1)
    val seeds = newestHistoryOfEachKindBefore(bound)
    if (seeds.isNotEmpty()) {
        IntervalMetricsCalculator.seedReplayFromWallMs(seeds)?.let { return historyBetween(it, toWallMs) }
    }
    return seeds + if (bound <= toWallMs) historyBetween(bound, toWallMs) else emptyList()
}
