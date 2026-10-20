package com.akane.inspiration.telemetry.store

import com.akane.inspiration.ipc.model.BatteryHistorySnapshot
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.SourceAvailability

/** The availability key a full capture records for its battery-history read. */
const val HISTORY_SOURCE = "history"

/**
 * How far past the history horizon the last known state is still trusted. Screen broadcasts store LIGHT snapshots
 * (no history ingest) and the app-open refresh is debounced for 60 s, so an interval often ends seconds after the
 * last ingest; that is not stale extrapolation. Two minutes is the debounce plus slack. A longer gap counts as
 * unknown from the horizon itself, not from the end of this grace.
 */
const val HISTORY_HORIZON_GRACE_MS = 2 * 60_000L

/**
 * Where the last known state ends for a window ending at [toWallMs] whose history is known up to [knownToWallMs]:
 * [toWallMs] when the gap is within [HISTORY_HORIZON_GRACE_MS], else [knownToWallMs].
 */
fun knownStateEndWallMs(
    knownToWallMs: Long,
    toWallMs: Long,
): Long = if (toWallMs - knownToWallMs <= HISTORY_HORIZON_GRACE_MS) toWallMs else knownToWallMs

/**
 * The captures whose full read pages the battery history into the store (`ingestHistory = true`). Broadcast full
 * captures read no history and record it unavailable, so only these can mark the history horizon.
 */
private val historyIngestTriggers = listOf(CaptureTrigger.APP_OPEN, CaptureTrigger.MARKER, CaptureTrigger.CHARGE_JOB)

/**
 * The [HISTORY_SOURCE] reason of a read whose every page was available but whose last page was still truncated: it
 * stopped at its page cap, so newer events are unread. The pages it read are stored and moved the cursor, so the
 * next ingest continues from them; until one reaches the newest event, the horizon stays at the last complete read.
 */
const val HISTORY_PAGE_CAP_REASON = "page_cap"

/**
 * The [HISTORY_SOURCE] availability of one read's history [pages], in read order. Available when any page was: those
 * pages are stored. No reason, the mark of a complete read ([ingestedHistory]), only when every page was available
 * and the last one reached the newest event. Otherwise the first failed page's reason, or [HISTORY_PAGE_CAP_REASON]
 * when the last page is still truncated. No pages (a read that paged no history) is unavailable with no reason.
 */
internal fun historyReadAvailability(pages: List<BatteryHistorySnapshot>): SourceAvailability {
    val failed = pages.firstOrNull { !it.available }
    val reason =
        when {
            failed != null -> failed.reason ?: HISTORY_UNAVAILABLE_REASON
            pages.lastOrNull()?.truncated == true -> HISTORY_PAGE_CAP_REASON
            else -> null
        }
    return SourceAvailability(pages.any { it.available }, reason)
}

/** True when one read's history [pages] stored the history complete up to the newest event ([historyReadAvailability]). */
internal fun historyReadComplete(pages: List<BatteryHistorySnapshot>): Boolean = historyReadAvailability(pages).isComplete()

/**
 * True when this capture's read stored the battery history up to its own wall time: a FULL snapshot whose every
 * history page was available and whose last page reached the newest event ([historyReadAvailability]). The read
 * takes the snapshot's clock before it pages the history, so the stored history reaches at least `clock.wallMs`. A
 * read that missed its deadline saves no snapshot at all.
 */
fun BatterySnapshot.ingestedHistory(): Boolean =
    kind == SnapshotKind.FULL && availability[HISTORY_SOURCE]?.isComplete() == true

private fun SourceAvailability.isComplete(): Boolean = available && reason == null

/** A failed page that carried no reason of its own. */
private const val HISTORY_UNAVAILABLE_REASON = "unavailable"

/** The default of [SnapshotStore.historyHorizonWallMs], from full snapshot reads (for fakes). */
internal suspend fun SnapshotStore.scanHistoryHorizonWallMs(): Long? {
    latest(SnapshotKind.FULL)?.takeIf { it.ingestedHistory() }?.let { return it.clock.wallMs }
    return historyIngestTriggers
        .mapNotNull { trigger -> latestByTrigger(trigger)?.takeIf { it.ingestedHistory() }?.clock?.wallMs }
        .maxOrNull()
}
