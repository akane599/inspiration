package com.akane.inspiration.capture

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.telemetry.capture.CaptureResult
import com.akane.inspiration.telemetry.store.SnapshotStore
import java.util.concurrent.atomic.AtomicLong

/**
 * The store the capturer writes through, counting the history pages it stores. A FULL capture stores each history
 * page as it arrives, so one that then misses its deadline may already have stored some: [withMissTick] ticks for them.
 */
internal class HistoryPageCounter(
    private val store: SnapshotStore,
) : SnapshotStore by store {
    private val pages = AtomicLong()

    /** Counts a page that kept any event. Delegation would forward this past the counter, so it must stay overridden. */
    override suspend fun persistHistoryPage(
        statsStartMs: Long,
        events: List<HistoryEvent>,
        truncated: Boolean,
        cutoffWallMs: Long,
    ): Int =
        store.persistHistoryPage(statsStartMs, events, truncated, cutoffWallMs).also { kept ->
            if (kept > 0) pages.incrementAndGet()
        }

    /**
     * Runs [capture]. When it does not come back saved (a miss, or a throw) but history pages were stored meanwhile,
     * [onHistoryStored] fires once; a saved capture is ticked by its caller. A page another ingest stored while this
     * one waited for the capturer's ingest lock also counts, which costs no more than one extra re-read.
     */
    suspend fun withMissTick(
        onHistoryStored: () -> Unit,
        capture: suspend () -> CaptureResult,
    ): CaptureResult {
        val before = pages.get()
        var result: CaptureResult? = null
        try {
            return capture().also { result = it }
        } finally {
            if (result !is CaptureResult.Saved && pages.get() != before) onHistoryStored()
        }
    }
}
