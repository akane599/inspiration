package com.akane.inspiration.telemetry.store

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.status.UnplugSummaryCalculator

interface SnapshotStore {
    suspend fun insert(snapshot: BatterySnapshot): Long

    suspend fun byId(id: Long): BatterySnapshot?

    suspend fun latest(kind: SnapshotKind? = null): BatterySnapshot?

    /**
     * The earliest snapshot with `wallMs >= [wallMs]` matching the non-null filters. [statsStartMs] selects the
     * battery-stats session it starts ([com.akane.inspiration.telemetry.diff.BatteryStatsSession.sameStart]).
     */
    suspend fun firstAtOrAfter(
        wallMs: Long,
        kind: SnapshotKind?,
        statsStartMs: Long?,
        bootCount: Int?,
    ): BatterySnapshot?

    suspend fun lastAtOrBefore(
        wallMs: Long,
        kind: SnapshotKind?,
    ): BatterySnapshot?

    suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot?

    /**
     * The clock of [latestByTrigger], for callers that need only its times. The default derives it from the full
     * read (for fakes); the Room store reads the snapshot row alone, never its per-UID or other child rows.
     */
    suspend fun latestClockByTrigger(trigger: CaptureTrigger): SnapshotClock? = latestByTrigger(trigger)?.clock

    /**
     * Snapshots of both kinds with `from <= wallMs <= to`, oldest first. Only the snapshot and battery columns
     * are filled: the system timers are null and every per-UID, wakelock and cpu list is empty.
     */
    suspend fun snapshotsBetween(
        fromWallMs: Long,
        toWallMs: Long,
    ): List<BatterySnapshot>

    /**
     * The newest part of [snapshotsBetween] that [UnplugSummaryCalculator] needs for a "now" at [toWallMs], so the
     * summary equals the one over the whole range. The newest plugged-in snapshot before [toWallMs] marks the
     * unplug (else the newest [CaptureTrigger.POWER_DISCONNECTED] capture before it). The part starts at the
     * newest screen or boot capture at or before that mark, which fixes the screen state the calculator carries
     * forward, else at the mark; with no mark it is the whole range. The default returns the whole range (for
     * fakes); the Room store bounds it in SQL.
     */
    suspend fun unplugWindowSnapshots(
        fromWallMs: Long,
        toWallMs: Long,
    ): List<BatterySnapshot> = snapshotsBetween(fromWallMs, toWallMs)

    suspend fun insertHistory(
        events: List<HistoryEvent>,
        statsStartMs: Long,
    )

    suspend fun historyBetween(
        fromWallMs: Long,
        toWallMs: Long,
    ): List<HistoryEvent>

    /** Retain epoch provenance for analysis whose clocks restart with battery statistics. */
    suspend fun historySessions(): Map<Long, List<HistoryEvent>> =
        mapOf((historyCursor()?.first ?: 0L) to historyBetween(0L, Long.MAX_VALUE))

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

    /**
     * The wall time up to which the stored battery history is known to be complete: the newest snapshot that
     * [ingestedHistory] (FULL, history availability available with no reason), null when none is stored. A
     * conservative bound, as the ingest itself ran just after that clock, derived from the persisted availability
     * rows. The default reads full snapshots (for fakes); the Room store answers it in one query.
     */
    suspend fun historyHorizonWallMs(): Long? = scanHistoryHorizonWallMs()

    suspend fun historyCursor(): Pair<Long, Long>?

    suspend fun setHistoryCursor(
        statsStartMs: Long,
        lastHistoryTimeMs: Long,
    )

    suspend fun addMarker(
        snapshotId: Long,
        label: String,
        wallMs: Long,
    ): Long

    suspend fun marker(id: Long): Pair<Long, BatterySnapshot>?

    /** All markers, newest first. */
    suspend fun markers(): List<Marker>

    suspend fun insertSamples(samples: List<LiveReading>)

    /** Samples with `from <= wallMs <= to`, oldest first. */
    suspend fun samplesBetween(
        from: Long,
        to: Long,
    ): List<TelemetrySample>

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

    /** Deletes the audit rows [ids] in one transaction; PENDING rows are kept. A store without retention keeps all. */
    suspend fun deleteToolkitActions(ids: List<Long>) {}

    suspend fun recordMiss(
        wallMs: Long,
        trigger: CaptureTrigger,
        reason: String,
    )

    /**
     * The smallest `wallMs - elapsedRealtimeMs` among the stored snapshots of boot [bootCount], null when that boot
     * has none: the boot's earliest wall-clock frame, in which [pruneRetention] ages rows. The default scans
     * [snapshotsBetween] (for fakes); the Room store answers it in one indexed query.
     */
    suspend fun minClockOffsetMs(bootCount: Int): Long? =
        snapshotsBetween(Long.MIN_VALUE, Long.MAX_VALUE)
            .filter { it.clock.bootCount == bootCount }
            .minOfOrNull { it.clock.wallMs - it.clock.elapsedRealtimeMs }

    /** Deletes what is older than the retention at [nowWallMs], trusting it as given; captures call [pruneRetention]. */
    suspend fun prune(nowWallMs: Long)
}
