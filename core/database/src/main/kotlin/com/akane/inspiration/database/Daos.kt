package com.akane.inspiration.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

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
}

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(events: List<HistoryEventEntity>): List<Long>

    @Query("SELECT * FROM history_event WHERE wallMs BETWEEN :fromWallMs AND :toWallMs ORDER BY wallMs, id")
    suspend fun between(fromWallMs: Long, toWallMs: Long): List<HistoryEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(cursor: HistoryCursorEntity)

    @Query("SELECT * FROM history_cursor WHERE id = :id")
    suspend fun cursor(id: Int = HistoryCursorEntity.SINGLETON_ID): HistoryCursorEntity?
}

@Dao
interface TelemetryDao {
    @Insert
    suspend fun insertSamples(samples: List<TelemetrySampleEntity>): List<Long>

    @Query("SELECT * FROM telemetry_hourly WHERE hourStartWallMs = :hourStartWallMs")
    suspend fun hourly(hourStartWallMs: Long): TelemetryHourlyEntity?
}

@Dao
interface ChargeSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ChargeSessionEntity): Long

    @Query("SELECT estimateMah FROM charge_session WHERE accepted = 1 AND estimateMah IS NOT NULL ORDER BY endWallMs ASC LIMIT :limit")
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

    @Transaction
    suspend fun prune(nowWallMs: Long) {
        val cutoffWallMs = nowWallMs - RETENTION_MS
        downsampleSamples(cutoffWallMs)
        deleteSamplesBefore(cutoffWallMs)
        deleteUnmarkedSnapshotsBefore(cutoffWallMs)
        deleteHistoryBefore(cutoffWallMs)
        deleteMissesBefore(cutoffWallMs)
    }

    companion object {
        const val HOUR_MS = 60L * 60L * 1_000L
        const val RETENTION_MS = 30L * 24L * HOUR_MS
    }
}
