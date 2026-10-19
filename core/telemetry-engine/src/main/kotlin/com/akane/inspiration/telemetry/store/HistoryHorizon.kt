package com.akane.inspiration.telemetry.store

import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotKind

/** The availability key a full capture records for its battery-history read. */
const val HISTORY_SOURCE = "history"

/**
 * The captures whose full read pages the battery history into the store (`ingestHistory = true`). Broadcast full
 * captures read no history and record it unavailable, so only these can mark the history horizon.
 */
private val historyIngestTriggers = listOf(CaptureTrigger.APP_OPEN, CaptureTrigger.MARKER, CaptureTrigger.CHARGE_JOB)

/**
 * True when this capture's read stored the battery history up to its own wall time: a FULL snapshot whose every
 * history page was available. The read takes the snapshot's clock before it pages the history, so the stored
 * history reaches at least `clock.wallMs`. A read that missed its deadline saves no snapshot at all.
 */
fun BatterySnapshot.ingestedHistory(): Boolean =
    kind == SnapshotKind.FULL &&
        availability[HISTORY_SOURCE]?.let { it.available && it.reason == null } == true

/**
 * The wall time up to which the stored battery history is known to be complete: the newest capture that
 * [ingestedHistory], null when none is stored. A conservative bound (the ingest itself ran just after it), derived
 * from the persisted availability rows, so no extra state is written. The newest FULL snapshot answers it in one
 * read when it ingested; otherwise the newest capture of each ingesting trigger is checked.
 */
suspend fun SnapshotStore.historyHorizonWallMs(): Long? {
    latest(SnapshotKind.FULL)?.takeIf { it.ingestedHistory() }?.let { return it.clock.wallMs }
    return historyIngestTriggers
        .mapNotNull { trigger -> latestByTrigger(trigger)?.takeIf { it.ingestedHistory() }?.clock?.wallMs }
        .maxOrNull()
}
