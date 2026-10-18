package com.akane.inspiration.telemetry.store

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SnapshotKind

interface SnapshotStore {
    suspend fun insert(snapshot: BatterySnapshot): Long

    suspend fun byId(id: Long): BatterySnapshot?

    suspend fun latest(kind: SnapshotKind? = null): BatterySnapshot?

    suspend fun firstAtOrAfter(
        wallMs: Long,
        kind: SnapshotKind?,
        statsStartMs: Long?,
        bootCount: Int?,
    ): BatterySnapshot?

    suspend fun lastAtOrBefore(wallMs: Long, kind: SnapshotKind?): BatterySnapshot?

    suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot?

    /**
     * Snapshots of both kinds with `from <= wallMs <= to`, oldest first. Only the snapshot and battery columns
     * are filled: the system timers are null and every per-UID, wakelock and cpu list is empty.
     */
    suspend fun snapshotsBetween(fromWallMs: Long, toWallMs: Long): List<BatterySnapshot>

    suspend fun insertHistory(events: List<HistoryEvent>, statsStartMs: Long)

    suspend fun historyBetween(fromWallMs: Long, toWallMs: Long): List<HistoryEvent>

    /**
     * The newest event of each kind strictly before [wallMs], oldest first: at most one per kind, so the cost
     * does not grow with the history before the bound. The default scans everything before it (for fakes);
     * the Room store answers it in one query.
     */
    suspend fun lastHistoryOfEachKindBefore(wallMs: Long): List<HistoryEvent> =
        historyBetween(0L, wallMs - 1)
            .groupBy { it.kind }
            .values
            .map { events -> events.maxBy { it.wallMs } }
            .sortedBy { it.wallMs }

    suspend fun historyCursor(): Pair<Long, Long>?

    suspend fun setHistoryCursor(statsStartMs: Long, lastHistoryTimeMs: Long)

    suspend fun addMarker(snapshotId: Long, label: String, wallMs: Long): Long

    suspend fun marker(id: Long): Pair<Long, BatterySnapshot>?

    /** All markers, newest first. */
    suspend fun markers(): List<Marker>

    suspend fun insertSamples(samples: List<LiveReading>)

    /** Samples with `from <= wallMs <= to`, oldest first. */
    suspend fun samplesBetween(from: Long, to: Long): List<TelemetrySample>

    suspend fun upsertChargeSession(
        statsStartMs: Long,
        segment: ChargeSegment,
        verdict: SegmentVerdict,
    )

    /** All charge sessions, oldest first by end time. */
    suspend fun chargeSessions(): List<ChargeSessionRecord>

    suspend fun acceptedEstimatesChronological(limit: Int): List<Double>

    /** Health samples, oldest first. Inserting an existing `wallMs` replaces that sample. */
    suspend fun healthSamples(): List<HealthSample>

    suspend fun insertHealthSample(sample: HealthSample)

    /** Inserts [record] (its id is ignored), prunes old display-only rows while preserving pending operations and successful override chains and returns the new id. */
    suspend fun insertToolkitAction(record: ToolkitActionRecord): Long

    /** Overwrites the row with `record.id`; a missing row is left missing. */
    suspend fun updateToolkitAction(record: ToolkitActionRecord)

    /** The newest [limit] audit rows, newest first. */
    suspend fun toolkitActions(limit: Int): List<ToolkitActionRecord>

    /** Rows still in [ToolkitActionStatus.PENDING], oldest first. */
    suspend fun pendingToolkitActions(): List<ToolkitActionRecord>

    suspend fun recordMiss(wallMs: Long, trigger: CaptureTrigger, reason: String)

    suspend fun prune(nowWallMs: Long)
}
