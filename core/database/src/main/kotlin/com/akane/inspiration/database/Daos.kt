package com.akane.inspiration.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface SnapshotDao {
    @Insert
    suspend fun insert(snapshot: SnapshotEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSystem(system: SnapshotSystemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAvailability(availability: SnapshotAvailabilityEntity)

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

    @Query(
        """
        SELECT * FROM snapshot
        WHERE wallMs >= :wallMs
          AND (:kind IS NULL OR kind = :kind)
          AND (:statsStartMs IS NULL OR statsStartMs = :statsStartMs)
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
    ): SnapshotEntity?

    @Query("SELECT * FROM snapshot WHERE wallMs <= :wallMs AND (:kind IS NULL OR kind = :kind) ORDER BY wallMs DESC, id DESC LIMIT 1")
    suspend fun lastAtOrBefore(wallMs: Long, kind: String? = null): SnapshotEntity?

    @Query("SELECT * FROM snapshot WHERE trigger = :trigger ORDER BY wallMs DESC, id DESC LIMIT 1")
    suspend fun latestByTrigger(trigger: String): SnapshotEntity?

    @Query("SELECT * FROM snapshot WHERE wallMs BETWEEN :fromWallMs AND :toWallMs ORDER BY wallMs ASC, id ASC")
    suspend fun between(fromWallMs: Long, toWallMs: Long): List<SnapshotEntity>

    /**
     * The newest part of [between] that the status summary needs for a "now" at [toWallMs] (see
     * `SnapshotStore.unplugWindowSnapshots`). `mark` is the newest plugged-in row before now, else the newest
     * POWER_DISCONNECTED capture before now, else [fromWallMs]; the rows start at the newest screen or boot
     * capture at or before `mark`, else at `mark`. Every bound stays inside the range.
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
              (SELECT s.wallMs FROM snapshot s, mark WHERE s.trigger IN ('SCREEN_ON', 'SCREEN_OFF', 'BOOT')
                  AND s.wallMs >= :fromWallMs AND s.wallMs <= mark.wallMs ORDER BY s.wallMs DESC LIMIT 1),
              (SELECT wallMs FROM mark)
          )
        ORDER BY wallMs ASC, id ASC
        """,
    )
    suspend fun unplugWindow(fromWallMs: Long, toWallMs: Long): List<SnapshotEntity>

    @Query("SELECT * FROM snapshot_system WHERE snapshotId = :snapshotId")
    suspend fun systemForSnapshot(snapshotId: Long): SnapshotSystemEntity?

    @Query("SELECT * FROM snapshot_availability WHERE snapshotId = :snapshotId ORDER BY source")
    suspend fun availabilityForSnapshot(snapshotId: Long): List<SnapshotAvailabilityEntity>

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

    @Query("DELETE FROM snapshot WHERE id = :id")
    suspend fun deleteById(id: Long): Int
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
    suspend fun between(fromWallMs: Long, toWallMs: Long): List<HistoryEventEntity>

    /**
     * The newest event of each `kind` with `wallMs < beforeWallMs`, oldest first: at most one row per kind,
     * however much history precedes the bound. (SQLite returns the bare `id` of the row that holds each
     * group's `MAX(wallMs)`.)
     */
    @Query(
        "SELECT * FROM history_event WHERE id IN " +
            "(SELECT id FROM (SELECT id, MAX(wallMs) FROM history_event WHERE wallMs < :beforeWallMs GROUP BY kind)) " +
            "ORDER BY wallMs, id",
    )
    suspend fun lastOfEachKindBefore(beforeWallMs: Long): List<HistoryEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(cursor: HistoryCursorEntity)

    @Query("SELECT * FROM history_cursor WHERE id = :id")
    suspend fun cursor(id: Int = HistoryCursorEntity.SINGLETON_ID): HistoryCursorEntity?
}

@Dao
interface TelemetryDao {
    @Insert
    suspend fun insertSamples(samples: List<TelemetrySampleEntity>): List<Long>

    @Query("SELECT * FROM telemetry_sample WHERE wallMs BETWEEN :fromWallMs AND :toWallMs ORDER BY wallMs, id")
    suspend fun between(fromWallMs: Long, toWallMs: Long): List<TelemetrySampleEntity>

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

    /** Display history may expire; pending writes and successful restore chains must remain recoverable. */
    @Query(
        "DELETE FROM toolkit_action WHERE status != 'pending' " +
            "AND NOT (status IN ('done', 'restored') AND kind IN ('appop', 'bucket', 'doze')) " +
            "AND id NOT IN (SELECT id FROM toolkit_action ORDER BY id DESC LIMIT :keep)",
    )
    suspend fun pruneTo(keep: Int): Int

    @Transaction
    suspend fun insertAndPrune(action: ToolkitActionEntity): Long {
        val id = insert(action)
        pruneTo(MAX_ROWS)
        return id
    }

    companion object {
        const val MAX_ROWS = 500
    }
}

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
    @Query(
        """
        INSERT OR REPLACE INTO telemetry_hourly (
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
        """,
    )
    suspend fun downsampleSamples(cutoffWallMs: Long, hourMs: Long = HOUR_MS)

    @Query("DELETE FROM telemetry_sample WHERE wallMs < :cutoffWallMs")
    suspend fun deleteSamplesBefore(cutoffWallMs: Long): Int

    @Query("DELETE FROM snapshot WHERE wallMs < :cutoffWallMs AND id NOT IN (SELECT snapshotId FROM marker)")
    suspend fun deleteUnmarkedSnapshotsBefore(cutoffWallMs: Long): Int

    @Query("DELETE FROM history_event WHERE wallMs < :cutoffWallMs")
    suspend fun deleteHistoryBefore(cutoffWallMs: Long): Int

    @Query("DELETE FROM capture_miss WHERE wallMs < :cutoffWallMs")
    suspend fun deleteMissesBefore(cutoffWallMs: Long): Int

    /** The boot's earliest wall-clock frame: the smallest `wallMs - elapsedRealtimeMs` of its snapshots, null if none. */
    @Query("SELECT MIN(wallMs - elapsedRealtimeMs) FROM snapshot WHERE bootCount = :bootCount")
    suspend fun minClockOffsetMs(bootCount: Int): Long?

    @Transaction
    suspend fun prune(nowWallMs: Long) {
        val cutoffWallMs = nowWallMs - RETENTION_MS
        // Keep the partial cutoff hour intact so later prunes never replace an aggregate
        // with only the samples that expired since the previous prune.
        val sampleCutoffWallMs = Math.floorDiv(cutoffWallMs, HOUR_MS) * HOUR_MS
        downsampleSamples(sampleCutoffWallMs)
        deleteSamplesBefore(sampleCutoffWallMs)
        deleteUnmarkedSnapshotsBefore(cutoffWallMs)
        deleteHistoryBefore(cutoffWallMs)
        deleteMissesBefore(cutoffWallMs)
    }

    companion object {
        const val HOUR_MS = 60L * 60L * 1_000L
        const val RETENTION_MS = 30L * 24L * HOUR_MS
    }
}
