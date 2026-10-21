package com.akane.inspiration.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.akane.inspiration.telemetry.store.RETENTION_WINDOW_MS
import com.akane.inspiration.telemetry.store.ToolkitActionStatusCodes
import com.akane.inspiration.telemetry.toolkit.ToolkitAuditLog

@Dao
interface SnapshotDao {
    @Insert
    suspend fun insert(snapshot: SnapshotEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSystem(system: SnapshotSystemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAvailability(availability: List<SnapshotAvailabilityEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUids(uids: List<SnapshotUidEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComponents(components: List<SnapshotComponentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlarms(alarms: List<SnapshotAlarmEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUidSensors(sensors: List<SnapshotUidSensorEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCpuFrequencies(frequencies: List<SnapshotCpuFreqEntity>)

    /**
     * Merges duplicate parsed wakelocks at insert time by adding their durations and counts.
     */
    @Query(
        """
        INSERT INTO snapshot_wakelock (snapshotId, kind, uid, name, durationMs, bgDurationMs, count)
        VALUES (:snapshotId, :kind, :uid, :name, :durationMs, :bgDurationMs, :count)
        ON CONFLICT(snapshotId, kind, uid, name) DO UPDATE SET
            durationMs = durationMs + excluded.durationMs,
            bgDurationMs = bgDurationMs + excluded.bgDurationMs,
            count = count + excluded.count
        """,
    )
    suspend fun mergeWakelock(
        snapshotId: Long,
        kind: String,
        uid: Int,
        name: String,
        durationMs: Long,
        bgDurationMs: Long,
        count: Long,
    )

    /** Returns -1 for each row whose key is already stored; [mergeWakelocks] adds those through [mergeWakelock]. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWakelocksIgnoringConflicts(wakelocks: List<SnapshotWakelockEntity>): List<Long>

    /**
     * [mergeWakelock] for a batch: new keys go in as one list insert, and a key that is already stored (or repeated
     * in [wakelocks]) gets its durations and counts added. Merge repeated keys first so the batch stays one insert.
     */
    @Transaction
    suspend fun mergeWakelocks(wakelocks: List<SnapshotWakelockEntity>) {
        val ids = insertWakelocksIgnoringConflicts(wakelocks)
        wakelocks.forEachIndexed { index, wakelock ->
            if (ids[index] == -1L) {
                mergeWakelock(
                    snapshotId = wakelock.snapshotId,
                    kind = wakelock.kind,
                    uid = wakelock.uid,
                    name = wakelock.name,
                    durationMs = wakelock.durationMs,
                    bgDurationMs = wakelock.bgDurationMs,
                    count = wakelock.count,
                )
            }
        }
    }

    @Query("SELECT * FROM snapshot WHERE id = :id")
    suspend fun byId(id: Long): SnapshotEntity?

    @Query("SELECT * FROM snapshot WHERE (:kind IS NULL OR kind = :kind) ORDER BY wallMs DESC, id DESC LIMIT 1")
    suspend fun latest(kind: String? = null): SnapshotEntity?

    /** The last inserted row: ids grow with each insert, whatever the wall clock said. */
    @Query("SELECT * FROM snapshot ORDER BY id DESC LIMIT 1")
    suspend fun lastInserted(): SnapshotEntity?

    /**
     * [statsStartMs] matches starts within [statsStartToleranceMs]. The `session*` parameters, the session clock's
     * boot, elapsed time and boot-relative start (`statsStart - wall + elapsed`), also match an earlier row of that
     * boot whose start moved with a wall-clock step: the SQL form of `BatteryStatsSession.sameStart` on two clocks.
     */
    @Query(
        """
        SELECT * FROM snapshot
        WHERE wallMs >= :wallMs
          AND (:kind IS NULL OR kind = :kind)
          AND (:statsStartMs IS NULL
            OR statsStartMs BETWEEN :statsStartMs - :statsStartToleranceMs AND :statsStartMs + :statsStartToleranceMs
            OR (bootCount = :sessionBootCount AND elapsedRealtimeMs < :sessionElapsedMs
              AND statsStartMs - wallMs + elapsedRealtimeMs BETWEEN :sessionStartElapsedMs - :statsStartToleranceMs
                AND :sessionStartElapsedMs + :statsStartToleranceMs))
          AND (:bootCount IS NULL OR bootCount = :bootCount)
        ORDER BY wallMs ASC, id ASC
        LIMIT 1
        """,
    )
    suspend fun firstAtOrAfter(
        wallMs: Long,
        kind: String? = null,
        statsStartMs: Long? = null,
        bootCount: Int? = null,
        statsStartToleranceMs: Long = 0L,
        sessionBootCount: Int? = null,
        sessionElapsedMs: Long? = null,
        sessionStartElapsedMs: Long? = null,
    ): SnapshotEntity?

    @Query("SELECT * FROM snapshot WHERE wallMs <= :wallMs AND (:kind IS NULL OR kind = :kind) ORDER BY wallMs DESC, id DESC LIMIT 1")
    suspend fun lastAtOrBefore(
        wallMs: Long,
        kind: String? = null,
    ): SnapshotEntity?

    /** Current-boot elapsed chronology first; saved order is the best effort when that boot has no row. */
    @Query(
        """
        SELECT * FROM snapshot WHERE trigger = :trigger
        ORDER BY CASE WHEN :currentBootCount IS NOT NULL AND bootCount = :currentBootCount THEN 0 ELSE 1 END,
            CASE WHEN :currentBootCount IS NOT NULL AND bootCount = :currentBootCount THEN elapsedRealtimeMs END DESC,
            id DESC
        LIMIT 1
        """,
    )
    suspend fun latestByTrigger(
        trigger: String,
        currentBootCount: Int? = null,
    ): SnapshotEntity?

    @Query("SELECT * FROM snapshot WHERE wallMs BETWEEN :fromWallMs AND :toWallMs ORDER BY wallMs ASC, id ASC")
    suspend fun between(
        fromWallMs: Long,
        toWallMs: Long,
    ): List<SnapshotEntity>

    /**
     * The newest part of [between] that the status summary needs for a "now" at [toWallMs] (see
     * `SnapshotStore.unplugWindow`). `mark` is the newest plugged-in row before now, else the newest
     * POWER_DISCONNECTED capture before now, else [fromWallMs]; the rows start at the newest screen capture at
     * or before `mark` (whose trigger fixes the screen state), else at `mark`. A BOOT row is no start: the
     * calculator resets the state where a new boot starts (the boot count changes or elapsedRealtime runs
     * backwards), which it reads from each row and the one before it, so every pair after the start is the
     * same as in [between]. Every bound stays inside the range.
     */
    @Query(
        """
        WITH mark(wallMs) AS (
            SELECT COALESCE(
                (SELECT wallMs FROM snapshot WHERE plugged = 1 AND wallMs >= :fromWallMs AND wallMs < :toWallMs
                    ORDER BY wallMs DESC LIMIT 1),
                (SELECT wallMs FROM snapshot WHERE trigger = 'POWER_DISCONNECTED'
                    AND wallMs >= :fromWallMs AND wallMs < :toWallMs ORDER BY wallMs DESC LIMIT 1),
                :fromWallMs
            )
        )
        SELECT * FROM snapshot
        WHERE wallMs BETWEEN :fromWallMs AND :toWallMs
          AND wallMs >= COALESCE(
              (SELECT s.wallMs FROM snapshot s, mark WHERE s.trigger IN ('SCREEN_ON', 'SCREEN_OFF')
                  AND s.wallMs >= :fromWallMs AND s.wallMs <= mark.wallMs ORDER BY s.wallMs DESC LIMIT 1),
              (SELECT wallMs FROM mark)
          )
        ORDER BY wallMs ASC, id ASC
        """,
    )
    suspend fun unplugWindow(
        fromWallMs: Long,
        toWallMs: Long,
    ): List<SnapshotEntity>

    /**
     * Where [unplugWindow] starts instead when a history plug or unplug at [atWallMs] is older than its rows: the newest
     * screen capture with `fromWallMs <= wallMs <= atWallMs`, else the newest snapshot in that range, else [fromWallMs].
     */
    @Query(
        """
        SELECT COALESCE(
            (SELECT wallMs FROM snapshot WHERE trigger IN ('SCREEN_ON', 'SCREEN_OFF')
                AND wallMs >= :fromWallMs AND wallMs <= :atWallMs ORDER BY wallMs DESC LIMIT 1),
            (SELECT wallMs FROM snapshot WHERE wallMs >= :fromWallMs AND wallMs <= :atWallMs
                ORDER BY wallMs DESC LIMIT 1),
            :fromWallMs
        )
        """,
    )
    suspend fun unplugWindowStartAtOrBefore(
        fromWallMs: Long,
        atWallMs: Long,
    ): Long

    @Query("SELECT * FROM snapshot_system WHERE snapshotId = :snapshotId")
    suspend fun systemForSnapshot(snapshotId: Long): SnapshotSystemEntity?

    @Query("SELECT * FROM snapshot_availability WHERE snapshotId = :snapshotId ORDER BY source")
    suspend fun availabilityForSnapshot(snapshotId: Long): List<SnapshotAvailabilityEntity>

    /** The newest wall time of a [kind] snapshot whose [source] read was available with no reason; null if none. */
    @Query(
        """
        SELECT MAX(s.wallMs) FROM snapshot AS s
        JOIN snapshot_availability AS a ON a.snapshotId = s.id
        WHERE s.kind = :kind AND a.source = :source AND a.available = 1 AND a.reason IS NULL
        """,
    )
    suspend fun newestWallMsWithCompleteSource(
        kind: String,
        source: String,
    ): Long?

    @Query("SELECT * FROM snapshot_uid WHERE snapshotId = :snapshotId ORDER BY uid")
    suspend fun uidsForSnapshot(snapshotId: Long): List<SnapshotUidEntity>

    @Query("SELECT * FROM snapshot_component WHERE snapshotId = :snapshotId ORDER BY uid, component")
    suspend fun componentsForSnapshot(snapshotId: Long): List<SnapshotComponentEntity>

    @Query("SELECT * FROM snapshot_wakelock WHERE snapshotId = :snapshotId ORDER BY kind, uid, name")
    suspend fun wakelocksForSnapshot(snapshotId: Long): List<SnapshotWakelockEntity>

    @Query("SELECT * FROM snapshot_alarm WHERE snapshotId = :snapshotId ORDER BY uid, tag")
    suspend fun alarmsForSnapshot(snapshotId: Long): List<SnapshotAlarmEntity>

    @Query("SELECT * FROM snapshot_uid_sensor WHERE snapshotId = :snapshotId ORDER BY uid, handle")
    suspend fun uidSensorsForSnapshot(snapshotId: Long): List<SnapshotUidSensorEntity>

    @Query("SELECT * FROM snapshot_cpu_freq WHERE snapshotId = :snapshotId ORDER BY policy, freqKhz")
    suspend fun cpuFrequenciesForSnapshot(snapshotId: Long): List<SnapshotCpuFreqEntity>
}

@Dao
interface MarkerDao {
    @Insert
    suspend fun insert(marker: MarkerEntity): Long

    @Query("SELECT * FROM marker WHERE id = :id")
    suspend fun byId(id: Long): MarkerEntity?

    @Query("SELECT * FROM marker ORDER BY wallMs DESC, id DESC")
    suspend fun all(): List<MarkerEntity>
}

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(events: List<HistoryEventEntity>): List<Long>

    @Query("SELECT * FROM history_event WHERE wallMs BETWEEN :fromWallMs AND :toWallMs ORDER BY wallMs, id")
    suspend fun between(
        fromWallMs: Long,
        toWallMs: Long,
    ): List<HistoryEventEntity>

    /**
     * Every event at each `kind`'s newest wall time with `0 <= wallMs < beforeWallMs` (all of a tie), ordered by
     * `wallMs, id`. Rows with an unknown wall time (`wallMs = -1`) are never included. Each step seeks the
     * `(kind, wallMs)` index: the recursive CTE walks the distinct kinds (the next larger kind each time), a
     * `MAX(wallMs)` per kind reads one index entry, and the join fetches that wall time's rows; nothing scans the
     * history before the bound.
     */
    @Query(NEWEST_OF_EACH_KIND_BEFORE_SQL)
    suspend fun newestOfEachKindBefore(beforeWallMs: Long): List<HistoryEventEntity>

    /** The newest wall time of a [kind] event with `fromWallMs <= wallMs <= atWallMs`: one `(kind, wallMs)` index seek. */
    @Query("SELECT MAX(wallMs) FROM history_event WHERE kind = :kind AND wallMs BETWEEN :fromWallMs AND :atWallMs")
    suspend fun newestWallMs(
        kind: String,
        fromWallMs: Long,
        atWallMs: Long,
    ): Long?

    /** The events of [kinds] with `fromWallMs <= wallMs <= toWallMs`, ordered like [between]. */
    @Query("SELECT * FROM history_event WHERE kind IN (:kinds) AND wallMs BETWEEN :fromWallMs AND :toWallMs ORDER BY wallMs, id")
    suspend fun ofKindsBetween(
        kinds: List<String>,
        fromWallMs: Long,
        toWallMs: Long,
    ): List<HistoryEventEntity>

    /** Whether a row of a kind other than [kind] is stored at exactly this history time and wall time (one index seek). */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM history_event WHERE historyTimeMs = :historyTimeMs AND wallMs = :wallMs AND kind != :kind)",
    )
    suspend fun hasOtherKindAt(
        historyTimeMs: Long,
        wallMs: Long,
        kind: String,
    ): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(cursor: HistoryCursorEntity)

    @Query("SELECT * FROM history_cursor WHERE id = :id")
    suspend fun cursor(id: Int = HistoryCursorEntity.SINGLETON_ID): HistoryCursorEntity?

    @Query("SELECT * FROM history_watermark WHERE id = ${HistoryWatermarkEntity.SINGLETON_ID}")
    suspend fun watermark(): HistoryWatermarkEntity?

    /** Writes the whole row: only for a read-modify-write inside the page's transaction (the prune raises W alone). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeWatermark(watermark: HistoryWatermarkEntity)

    /**
     * The cursor to resume the history ingest from. Once per database it is first rewound to the start of its stats
     * session, so an install that ingested history with an earlier build pages it again: those builds skipped a
     * RESET's restated screen and doze state (audit N-01). The unique index keeps the rows already stored, and the
     * ingest still skips what the retention prune would delete. A marker row records that the rewind ran; a database
     * whose first cursor is written by this build gets it with that cursor (see [advanceCursor]).
     */
    @Transaction
    suspend fun resumeCursor(): HistoryCursorEntity? {
        val current = cursor()
        if (current == null || cursor(REWOUND_MARKER.id) != null) return current
        upsertCursor(REWOUND_MARKER)
        return current.copy(lastHistoryTimeMs = 0L).also { upsertCursor(it) }
    }

    /**
     * Moves the cursor forward within a stats session (a late, older write must not undo a newer one); a new session
     * starts its own cursor. The first cursor ever written also marks the rewind of [resumeCursor] as done: nothing
     * before it was ingested by an earlier build.
     */
    @Transaction
    suspend fun advanceCursor(cursor: HistoryCursorEntity) {
        val current = cursor()
        if (current == null) upsertCursor(REWOUND_MARKER)
        if (current != null && current.statsStartMs == cursor.statsStartMs && current.lastHistoryTimeMs >= cursor.lastHistoryTimeMs) {
            return
        }
        upsertCursor(cursor)
    }

    companion object {
        /** A second `history_cursor` row whose presence says the one-time rewind ran; its values mean nothing. */
        internal val REWOUND_MARKER = HistoryCursorEntity(id = 2, statsStartMs = 0L, lastHistoryTimeMs = 0L)
    }
}

/** [HistoryDao.newestOfEachKindBefore]'s query; the database tests check its plan. */
internal const val NEWEST_OF_EACH_KIND_BEFORE_SQL =
    "WITH RECURSIVE kinds(kind) AS (" +
        "SELECT MIN(kind) FROM history_event " +
        "UNION ALL SELECT (SELECT MIN(h.kind) FROM history_event h WHERE h.kind > kinds.kind) " +
        "FROM kinds WHERE kinds.kind IS NOT NULL), " +
        "newest(kind, wallMs) AS (SELECT kinds.kind, (SELECT MAX(h.wallMs) FROM history_event h " +
        "WHERE h.kind = kinds.kind AND h.wallMs >= 0 AND h.wallMs < :beforeWallMs) " +
        "FROM kinds WHERE kinds.kind IS NOT NULL) " +
        "SELECT history_event.* FROM newest JOIN history_event " +
        "ON history_event.kind = newest.kind AND history_event.wallMs = newest.wallMs " +
        "ORDER BY history_event.wallMs, history_event.id"

@Dao
interface TelemetryDao {
    @Insert
    suspend fun insertSamples(samples: List<TelemetrySampleEntity>): List<Long>

    /** The newest [limit] samples with `fromWallMs <= wallMs <= toWallMs`, ordered by `wallMs, id`. */
    @Query(
        "SELECT * FROM (SELECT * FROM telemetry_sample WHERE wallMs BETWEEN :fromWallMs AND :toWallMs " +
            "ORDER BY wallMs DESC, id DESC LIMIT :limit) ORDER BY wallMs, id",
    )
    suspend fun between(
        fromWallMs: Long,
        toWallMs: Long,
        limit: Int,
    ): List<TelemetrySampleEntity>

    /**
     * No production screen reads the hourly table yet: it is the long-horizon store that TEMPLATE.md §6.3 and
     * phase-2 decision E9 specify for samples past the 30-day retention. Tests use this read as the oracle for
     * [MaintenanceDao.prune]'s downsample.
     */
    @Query("SELECT * FROM telemetry_hourly WHERE hourStartWallMs = :hourStartWallMs")
    suspend fun hourly(hourStartWallMs: Long): TelemetryHourlyEntity?
}

@Dao
interface HealthDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sample: HealthSampleEntity)

    @Query("SELECT * FROM battery_health_sample ORDER BY wallMs")
    suspend fun all(): List<HealthSampleEntity>
}

@Dao
interface ToolkitActionDao {
    @Insert
    suspend fun insert(action: ToolkitActionEntity): Long

    @Update
    suspend fun update(action: ToolkitActionEntity)

    @Query("SELECT * FROM toolkit_action ORDER BY id DESC LIMIT :limit")
    suspend fun newest(limit: Int): List<ToolkitActionEntity>

    @Query("SELECT * FROM toolkit_action WHERE status = :status ORDER BY id")
    suspend fun withStatus(status: String): List<ToolkitActionEntity>

    /**
     * Display history may expire; pending writes and override chains must remain recoverable. A chain also
     * holds failed writes that moved their target to a third value (a known `afterRaw` other than `beforeRaw`);
     * this keeps a superset of those, the toolkit decides exactly.
     */
    @Query(
        "DELETE FROM toolkit_action WHERE status != $PENDING_SQL " +
            "AND NOT (status IN ($DONE_SQL, $RESTORED_SQL) AND kind IN $OVERRIDE_KINDS_SQL) " +
            "AND NOT (status = $FAILED_SQL AND kind IN $OVERRIDE_KINDS_SQL AND afterRaw IS NOT NULL " +
            "AND afterRaw != '' AND (beforeRaw IS NULL OR afterRaw != beforeRaw)) " +
            "AND id NOT IN (SELECT id FROM toolkit_action ORDER BY id DESC LIMIT :keep)",
    )
    suspend fun pruneTo(keep: Int): Int

    @Transaction
    suspend fun insertAndPrune(action: ToolkitActionEntity): Long {
        val id = insert(action)
        pruneTo(MAX_ROWS)
        return id
    }

    /** Settled rows only: a PENDING row is never deleted, whatever the caller passes. */
    @Query("DELETE FROM toolkit_action WHERE status != $PENDING_SQL AND id IN (:ids)")
    suspend fun deleteSettledChunk(ids: List<Long>): Int

    /** Deletes the settled rows [ids] in one transaction, in chunks under SQLite's bound-argument limit. */
    @Transaction
    suspend fun deleteSettled(ids: List<Long>) {
        for (chunk in ids.chunked(DELETE_CHUNK)) deleteSettledChunk(chunk)
    }

    companion object {
        const val MAX_ROWS = ToolkitAuditLog.RETAINED_ROWS
        const val DELETE_CHUNK = 500
    }
}

/** The override kinds as an SQL list, from the values the toolkit writes. */
private const val OVERRIDE_KINDS_SQL =
    "('" + ToolkitAuditLog.KIND_APP_OP + "', '" + ToolkitAuditLog.KIND_BUCKET + "', '" + ToolkitAuditLog.KIND_DOZE + "')"

/** Status literals for the retention SQL, from the persisted [ToolkitActionStatusCodes]. */
private const val PENDING_SQL = "'" + ToolkitActionStatusCodes.PENDING + "'"
private const val DONE_SQL = "'" + ToolkitActionStatusCodes.DONE + "'"
private const val RESTORED_SQL = "'" + ToolkitActionStatusCodes.RESTORED + "'"
private const val FAILED_SQL = "'" + ToolkitActionStatusCodes.FAILED + "'"

@Dao
interface ChargeSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ChargeSessionEntity): Long

    @Query("SELECT * FROM charge_session ORDER BY endWallMs, id")
    suspend fun all(): List<ChargeSessionEntity>

    // Newest `limit` accepted estimates, returned oldest first.
    @Query(
        "SELECT estimateMah FROM (SELECT estimateMah, endWallMs FROM charge_session " +
            "WHERE accepted = 1 AND estimateMah IS NOT NULL ORDER BY endWallMs DESC LIMIT :limit) " +
            "ORDER BY endWallMs ASC",
    )
    suspend fun acceptedEstimatesChronological(limit: Int): List<Double>
}

@Dao
interface CaptureMissDao {
    @Insert
    suspend fun insert(miss: CaptureMissEntity): Long
}

@Dao
interface MaintenanceDao {
    /**
     * Aggregates the samples before [cutoffWallMs] into `telemetry_hourly`, merging into an hour that already has a
     * row (late samples after a wall-clock correction): counts add, MIN/MAX combine and a side that is null keeps the
     * other. The table holds no per-column non-null counts, so averages are weighted by `sampleCount`; this is exact
     * when every sample in the hour carries the column and approximate when only some do.
     */
    @Query(
        """
        INSERT INTO telemetry_hourly (
            hourStartWallMs, sampleCount, avgCurrentMa, minCurrentMa, maxCurrentMa, avgVoltageMv,
            avgWatts, maxTemperatureDeciC, minLevelPct, maxLevelPct
        )
        SELECT
            (wallMs / :hourMs) * :hourMs,
            COUNT(*),
            AVG(currentMa),
            MIN(currentMa),
            MAX(currentMa),
            AVG(voltageMv),
            AVG(watts),
            MAX(temperatureDeciC),
            MIN(levelPct),
            MAX(levelPct)
        FROM telemetry_sample
        WHERE wallMs < :cutoffWallMs
        GROUP BY (wallMs / :hourMs) * :hourMs
        ON CONFLICT(hourStartWallMs) DO UPDATE SET
            sampleCount = sampleCount + excluded.sampleCount,
            avgCurrentMa = CASE
                WHEN avgCurrentMa IS NULL THEN excluded.avgCurrentMa
                WHEN excluded.avgCurrentMa IS NULL THEN avgCurrentMa
                ELSE (avgCurrentMa * sampleCount + excluded.avgCurrentMa * excluded.sampleCount)
                    / (sampleCount + excluded.sampleCount)
            END,
            minCurrentMa = MIN(COALESCE(minCurrentMa, excluded.minCurrentMa), COALESCE(excluded.minCurrentMa, minCurrentMa)),
            maxCurrentMa = MAX(COALESCE(maxCurrentMa, excluded.maxCurrentMa), COALESCE(excluded.maxCurrentMa, maxCurrentMa)),
            avgVoltageMv = CASE
                WHEN avgVoltageMv IS NULL THEN excluded.avgVoltageMv
                WHEN excluded.avgVoltageMv IS NULL THEN avgVoltageMv
                ELSE (avgVoltageMv * sampleCount + excluded.avgVoltageMv * excluded.sampleCount)
                    / (sampleCount + excluded.sampleCount)
            END,
            avgWatts = CASE
                WHEN avgWatts IS NULL THEN excluded.avgWatts
                WHEN excluded.avgWatts IS NULL THEN avgWatts
                ELSE (avgWatts * sampleCount + excluded.avgWatts * excluded.sampleCount)
                    / (sampleCount + excluded.sampleCount)
            END,
            maxTemperatureDeciC = MAX(
                COALESCE(maxTemperatureDeciC, excluded.maxTemperatureDeciC),
                COALESCE(excluded.maxTemperatureDeciC, maxTemperatureDeciC)
            ),
            minLevelPct = MIN(COALESCE(minLevelPct, excluded.minLevelPct), COALESCE(excluded.minLevelPct, minLevelPct)),
            maxLevelPct = MAX(COALESCE(maxLevelPct, excluded.maxLevelPct), COALESCE(excluded.maxLevelPct, maxLevelPct))
        """,
    )
    suspend fun downsampleSamples(
        cutoffWallMs: Long,
        hourMs: Long = HOUR_MS,
    )

    @Query("DELETE FROM telemetry_sample WHERE wallMs < :cutoffWallMs")
    suspend fun deleteSamplesBefore(cutoffWallMs: Long): Int

    @Query("DELETE FROM telemetry_hourly WHERE hourStartWallMs < :cutoffWallMs")
    suspend fun deleteHourlyBefore(cutoffWallMs: Long): Int

    @Query("DELETE FROM snapshot WHERE wallMs < :cutoffWallMs AND id NOT IN (SELECT snapshotId FROM marker)")
    suspend fun deleteUnmarkedSnapshotsBefore(cutoffWallMs: Long): Int

    @Query("DELETE FROM history_event WHERE wallMs < :cutoffWallMs")
    suspend fun deleteHistoryBefore(cutoffWallMs: Long): Int

    @Query("DELETE FROM capture_miss WHERE wallMs < :cutoffWallMs")
    suspend fun deleteMissesBefore(cutoffWallMs: Long): Int

    /** The boot's earliest wall-clock frame: the smallest `wallMs - elapsedRealtimeMs` of its snapshots, null if none. */
    @Query("SELECT MIN(wallMs - elapsedRealtimeMs) FROM snapshot WHERE bootCount = :bootCount")
    suspend fun minClockOffsetMs(bootCount: Int): Long?

    /**
     * Raises the history watermark's W to [cutoffWallMs], creating the row (H unknown) when there is none. Never
     * touches H, which only the ingest moves; a whole-row REPLACE would reset it.
     */
    @Query(
        """
        INSERT INTO history_watermark (id, seenThroughHistoryTimeMs, prunedBeforeWallMs)
        VALUES (${HistoryWatermarkEntity.SINGLETON_ID}, :unseenHistoryTimeMs, :cutoffWallMs)
        ON CONFLICT(id) DO UPDATE SET prunedBeforeWallMs = MAX(prunedBeforeWallMs, excluded.prunedBeforeWallMs)
        """,
    )
    suspend fun raiseHistoryPrunedBefore(
        cutoffWallMs: Long,
        unseenHistoryTimeMs: Long = Long.MIN_VALUE,
    )

    @Transaction
    suspend fun prune(nowWallMs: Long) {
        val cutoffWallMs = nowWallMs - RETENTION_MS
        // Keep the partial cutoff hour intact so later prunes never replace an aggregate
        // with only the samples that expired since the previous prune.
        val sampleCutoffWallMs = Math.floorDiv(cutoffWallMs, HOUR_MS) * HOUR_MS
        downsampleSamples(sampleCutoffWallMs)
        deleteSamplesBefore(sampleCutoffWallMs)
        // The hourly aggregates outlive the samples (they are the long-horizon store), so they get their own
        // much longer cutoff; the 30-day one would delete each row in the transaction that wrote it.
        deleteHourlyBefore(nowWallMs - HOURLY_RETENTION_MS)
        deleteUnmarkedSnapshotsBefore(cutoffWallMs)
        deleteHistoryBefore(cutoffWallMs)
        raiseHistoryPrunedBefore(cutoffWallMs)
        deleteMissesBefore(cutoffWallMs)
    }

    companion object {
        const val HOUR_MS = 60L * 60L * 1_000L
        const val RETENTION_MS = RETENTION_WINDOW_MS

        /** How long `telemetry_hourly` keeps an aggregate, counted from the start of its hour. */
        const val HOURLY_RETENTION_MS = 365L * 24L * HOUR_MS
    }
}
