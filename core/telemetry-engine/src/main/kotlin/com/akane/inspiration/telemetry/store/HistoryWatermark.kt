package com.akane.inspiration.telemetry.store

import com.akane.inspiration.ipc.model.HistoryEvent

/**
 * What the history ingest has already seen and pruned, so a re-page (after a relabel, a stats reset or the one-time
 * rewind) cannot bring back a row a cutoff already removed, whatever the wall clock says now
 * (docs/plans/prune-watermark.md).
 *
 * [seenThroughHistoryTimeMs] (H) is the highest history time of any page the ingest processed. [prunedBeforeWallMs]
 * (W) is the highest retention cutoff ever applied, by the prune or the ingest. Both only rise, except that
 * [judgePage]'s restart guard lowers H. [NEUTRAL] (no stored row) keeps exactly what the cutoff alone keeps.
 */
data class HistoryWatermark(
    val seenThroughHistoryTimeMs: Long,
    val prunedBeforeWallMs: Long,
) {
    /**
     * Keeps [event] iff it clears [cutoffWallMs] and, when its position was already seen (`historyTimeMs <= H`), also
     * W. A new position is judged by the cutoff alone, so a corrected forward jump cannot stop the ingest.
     */
    fun keeps(
        event: HistoryEvent,
        cutoffWallMs: Long,
    ): Boolean =
        event.wallMs >= cutoffWallMs &&
            (event.historyTimeMs > seenThroughHistoryTimeMs || event.wallMs >= prunedBeforeWallMs)

    /**
     * Judges one page (ascending history time) against this watermark, stored before it, and returns the events to
     * store and the watermark after the page: W rises to [cutoffWallMs] and H to the page's last history time.
     *
     * Restart guard: a page that is not [truncated] reached the end of the history, so when its last history time is
     * below H the history clock restarted. The whole page is then judged by the cutoff alone and H becomes that last
     * time; this is the only way H falls. W still only rises.
     */
    fun judgePage(
        events: List<HistoryEvent>,
        truncated: Boolean,
        cutoffWallMs: Long,
    ): HistoryPage {
        val lastHistoryTimeMs = events.lastOrNull()?.historyTimeMs
        val restarted = !truncated && lastHistoryTimeMs != null && lastHistoryTimeMs < seenThroughHistoryTimeMs
        val judge = if (restarted) copy(seenThroughHistoryTimeMs = Long.MIN_VALUE) else this
        val seenThrough =
            when {
                lastHistoryTimeMs == null -> seenThroughHistoryTimeMs
                restarted -> lastHistoryTimeMs
                else -> maxOf(seenThroughHistoryTimeMs, lastHistoryTimeMs)
            }
        return HistoryPage(
            kept = events.filter { judge.keeps(it, cutoffWallMs) },
            watermark = HistoryWatermark(seenThrough, maxOf(prunedBeforeWallMs, cutoffWallMs)),
        )
    }

    companion object {
        /** No stored row: nothing seen, nothing pruned. */
        val NEUTRAL = HistoryWatermark(Long.MIN_VALUE, Long.MIN_VALUE)
    }
}

/** [HistoryWatermark.judgePage]'s result: the events to store and the watermark to store with them. */
data class HistoryPage(
    val kept: List<HistoryEvent>,
    val watermark: HistoryWatermark,
)
