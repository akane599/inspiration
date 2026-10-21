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

/**
 * The most samples one [SnapshotStore.samplesBetween] read returns: a day of one live collector at its fastest 1.5 s
 * period. Samples are stored only while plugged in and a screen collects, so a real window stays under it; two
 * collectors over a long charge could pass it, and then the read keeps the newest.
 */
const val SAMPLES_BETWEEN_LIMIT = 24 * 60 * 60 * 1_000 / 1_500

/** The rows [SnapshotStore.unplugWindow] reads for [UnplugSummaryCalculator.compute], each list oldest first. */
data class UnplugWindow(
    val snapshots: List<BatterySnapshot>,
    val history: List<HistoryEvent>,
)

interface SnapshotStore {
    /**
     * Runs [block] so that every read it makes through this store sees one state: a write or prune from elsewhere lands
     * before or after all of them, never between two. The default runs it as is (for fakes); the Room store runs it in
     * one transaction.
     */
    suspend fun <R> readConsistently(block: suspend () -> R): R = block()

    suspend fun insert(snapshot: BatterySnapshot): Long

    /**
     * The whole snapshot with this id. No production path reads by id; the store tests read every [insert] back through
     * it, so it stays as their round-trip check (kept, audit §3).
     */
    suspend fun byId(id: Long): BatterySnapshot?

    /** The newest snapshot by wall time, of one [kind] or of either. Read by the debug probe and the store tests. */
    suspend fun latest(kind: SnapshotKind? = null): BatterySnapshot?

    /**
     * The clock of the last stored snapshot in insertion order, not wall time: a previous boot whose clock ran ahead
     * does not hide the current boot's captures. A BOOT capture reads it to tell a reboot from a stopped-state exit.
     * The default derives it from [latest] (for fakes); the Room store reads the newest row by id alone, never its
     * child rows.
     */
    suspend fun latestClock(): SnapshotClock? = latest()?.clock

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

    /**
     * The newest snapshot taken for [trigger], the same row [latestClockByTrigger] reads. Production needs only that
     * clock; this full read is the clock's default for fakes, and the store tests check the trigger ordering through it
     * (kept, audit §3).
     */
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
     * What [UnplugSummaryCalculator] reads for a "now" at [toWallMs] from the range, read consistently: the newest
     * part of the bare [snapshotsBetween] it needs and the history events of [UnplugSummaryCalculator.HISTORY_KINDS],
     * oldest first, so the summary equals the one over the whole range. The newest plugged-in snapshot before
     * [toWallMs] marks the unplug (else the newest [CaptureTrigger.POWER_DISCONNECTED] capture before it). The
     * snapshots start at the newest screen capture at or before that mark, which fixes the screen state the
     * calculator carries forward, else at the mark; with no mark they are the whole range. When the newest history
     * plug or unplug before [toWallMs] is older than those snapshots, they start at the newest screen capture at or
     * before it instead (else the newest snapshot at or before it). The events start at the newest history screen
     * event at or before the first snapshot (the screen state the calculator carries forward), else at that snapshot.
     * The default returns the whole range (for fakes); the Room store bounds it with index seeks.
     */
    suspend fun unplugWindow(
        fromWallMs: Long,
        toWallMs: Long,
    ): UnplugWindow =
        readConsistently {
            UnplugWindow(
                snapshots = snapshotsBetween(fromWallMs, toWallMs),
                history = historyBetween(fromWallMs, toWallMs).filter { it.kind in UnplugSummaryCalculator.HISTORY_KINDS },
            )
        }

    suspend fun insertHistory(
        events: List<HistoryEvent>,
        statsStartMs: Long,
    )

    suspend fun historyBetween(
        fromWallMs: Long,
        toWallMs: Long,
    ): List<HistoryEvent>

    /**
     * The stored history (known wall times only) with its stats-session provenance, for analysis whose clocks
     * restart with battery statistics: runs of consecutive events stored under one `statsStartMs` label, oldest
     * run first in ingestion order. The label orders nothing: `BatteryStatsImpl` shifts the reported start on
     * every wall-clock step, so a backward step gives the continuing session a smaller label, and a label may
     * recur. Consumers order events within a run by history time.
     */
    suspend fun historySessions(): List<Pair<Long, List<HistoryEvent>>> =
        listOf((historyCursor()?.first ?: 0L) to historyBetween(0L, Long.MAX_VALUE))

    /**
     * The newest event of each kind with `0 <= wallMs <` [wallMs], oldest first: at most one per kind (of events
     * of one kind at one wall time, the first stored). Events at one wall time keep their stored order, so a RESET
     * stays ahead of the screen and doze state it restates. The default scans everything before the bound (for
     * fakes); the Room store answers it with index seeks per kind, so its cost does not grow with that history.
     */
    suspend fun lastHistoryOfEachKindBefore(wallMs: Long): List<HistoryEvent> =
        historyBetween(0L, wallMs - 1)
            .withIndex()
            .groupBy { it.value.kind }
            .values
            .map { events -> events.maxBy { it.value.wallMs } }
            // Room's `ORDER BY wallMs, id`: the position in `historyBetween` stands in for the row id.
            .sortedWith(compareBy({ it.value.wallMs }, { it.index }))
            .map { it.value }

    /**
     * Like [lastHistoryOfEachKindBefore], but every event at each kind's newest wall time (all of a tie), ordered by
     * wall time, then stored order. The default reads each of those wall times on top of
     * [lastHistoryOfEachKindBefore]; the Room store answers it in one query of index seeks.
     */
    suspend fun newestHistoryOfEachKindBefore(wallMs: Long): List<HistoryEvent> {
        val newest = lastHistoryOfEachKindBefore(wallMs)
        return newest.map { it.wallMs }.distinct().sorted().flatMap { wall ->
            val kinds = newest.filter { it.wallMs == wall }.mapTo(HashSet()) { it.kind }
            historyBetween(wall, wall).filter { it.kind in kinds }
        }
    }

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

    /**
     * Stores one available history page of the session [statsStartMs] ([events] unfiltered, ascending) and moves the
     * cursor to its last event. Keeps what [HistoryWatermark.keeps] keeps at [cutoffWallMs] and returns how many
     * events that is (before the insert's dedupe). The default judges by the cutoff alone ([HistoryWatermark.NEUTRAL])
     * in separate [insertHistory] and [setHistoryCursor] calls (for fakes); the Room store applies its stored
     * watermark ([HistoryWatermark.judgePage]) and advances it with the page in one transaction.
     */
    suspend fun persistHistoryPage(
        statsStartMs: Long,
        events: List<HistoryEvent>,
        truncated: Boolean,
        cutoffWallMs: Long,
    ): Int {
        val kept = events.filter { HistoryWatermark.NEUTRAL.keeps(it, cutoffWallMs) }
        insertHistory(kept, statsStartMs)
        events.lastOrNull()?.let { setHistoryCursor(statsStartMs, it.historyTimeMs) }
        return kept.size
    }

    suspend fun addMarker(
        snapshotId: Long,
        label: String,
        wallMs: Long,
    ): Long

    suspend fun marker(id: Long): Pair<Long, BatterySnapshot>?

    /** All markers, newest first. */
    suspend fun markers(): List<Marker>

    suspend fun insertSamples(samples: List<LiveReading>)

    /** Samples with `from <= wallMs <= to`, oldest first: at most [SAMPLES_BETWEEN_LIMIT], the newest of them. */
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

    /**
     * Deletes what is older than the retention at [nowWallMs], trusting it as given; captures call [pruneRetention].
     * The Room store also raises its history watermark's W to the history cutoff, so a re-page cannot bring back what
     * this deleted ([persistHistoryPage]).
     */
    suspend fun prune(nowWallMs: Long)
}
