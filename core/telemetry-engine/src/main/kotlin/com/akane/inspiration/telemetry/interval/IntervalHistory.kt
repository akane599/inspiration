package com.akane.inspiration.telemetry.interval

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.telemetry.store.SnapshotStore

/**
 * The stored history [IntervalMetricsCalculator.compute] needs for an interval that starts at [fromWallMs] and is cut
 * at [toWallMs]: it computes the same metrics as every stored event with `0 <= wallMs <= toWallMs`, without loading
 * the history before the interval (audit R-15). The events from [fromWallMs] on are read as they are; before it only
 * the state the interval opens in, from the newest event of each kind (with every event of that kind at that wall
 * time, so ties keep their stored order). When those cannot fix the level baseline, see
 * [IntervalMetricsCalculator.seedReplayFromWallMs], the history is replayed from where it can.
 */
suspend fun SnapshotStore.intervalHistory(
    fromWallMs: Long,
    toWallMs: Long,
): List<HistoryEvent> {
    if (toWallMs < 0) return emptyList()
    val bound = fromWallMs.coerceIn(0L, if (toWallMs == Long.MAX_VALUE) toWallMs else toWallMs + 1)
    val newest = lastHistoryOfEachKindBefore(bound)
    val seeds =
        newest.map { it.wallMs }.distinct().sorted().flatMap { wallMs ->
            val kinds = newest.filter { it.wallMs == wallMs }.mapTo(HashSet()) { it.kind }
            historyBetween(wallMs, wallMs).filter { it.kind in kinds }
        }
    if (seeds.isNotEmpty()) {
        IntervalMetricsCalculator.seedReplayFromWallMs(seeds)?.let { return historyBetween(it, toWallMs) }
    }
    return seeds + if (bound <= toWallMs) historyBetween(bound, toWallMs) else emptyList()
}
