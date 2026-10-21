package com.akane.inspiration.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SnapshotEntity::class,
        SnapshotSystemEntity::class,
        SnapshotAvailabilityEntity::class,
        SnapshotUidEntity::class,
        SnapshotComponentEntity::class,
        SnapshotWakelockEntity::class,
        SnapshotAlarmEntity::class,
        SnapshotUidSensorEntity::class,
        SnapshotCpuFreqEntity::class,
        MarkerEntity::class,
        HistoryEventEntity::class,
        HistoryCursorEntity::class,
        HistoryWatermarkEntity::class,
        TelemetrySampleEntity::class,
        TelemetryHourlyEntity::class,
        ChargeSessionEntity::class,
        CaptureMissEntity::class,
        HealthSampleEntity::class,
        ToolkitActionEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class InspirationDatabase : RoomDatabase() {
    abstract fun snapshotDao(): SnapshotDao

    abstract fun markerDao(): MarkerDao

    abstract fun historyDao(): HistoryDao

    abstract fun telemetryDao(): TelemetryDao

    abstract fun chargeSessionDao(): ChargeSessionDao

    abstract fun captureMissDao(): CaptureMissDao

    abstract fun healthDao(): HealthDao

    abstract fun toolkitActionDao(): ToolkitActionDao

    abstract fun maintenanceDao(): MaintenanceDao

    companion object {
        fun create(
            context: Context,
            name: String = DATABASE_NAME,
        ): InspirationDatabase =
            Room
                .databaseBuilder(
                    context.applicationContext,
                    InspirationDatabase::class.java,
                    name,
                ).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                )
                .build()

        val MIGRATION_1_2: Migration =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE battery_health_sample_new (wallMs INTEGER NOT NULL PRIMARY KEY, fccMah REAL NOT NULL, designMah REAL NOT NULL, cycleCount INTEGER, measuredMah REAL)",
                    )
                    db.execSQL(
                        "INSERT INTO battery_health_sample_new SELECT wallMs, fccMah, designMah, cycleCount, measuredMah FROM battery_health_sample",
                    )
                    db.execSQL("DROP TABLE battery_health_sample")
                    db.execSQL("ALTER TABLE battery_health_sample_new RENAME TO battery_health_sample")
                }
            }

        /** Derived estimates from older versions cannot be certified across history discontinuities. */
        val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DELETE FROM charge_session")
                    db.execSQL("UPDATE battery_health_sample SET measuredMah = NULL")
                }
            }

        /** `latestByTrigger` (newest row of one trigger) seeks this index instead of scanning `snapshot`. */
        val MIGRATION_3_4: Migration =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_snapshot_trigger_wallMs` ON `snapshot` (`trigger`, `wallMs`)")
                }
            }

        /** No historical identity is inferred: all legacy proof remains SQL NULL. */
        val MIGRATION_4_5: Migration =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `snapshot` ADD COLUMN `uidIdentityUserId` INTEGER")
                    db.execSQL("ALTER TABLE `snapshot` ADD COLUMN `uidIdentityUserSerial` INTEGER")
                    db.execSQL("ALTER TABLE `snapshot` ADD COLUMN `uidIdentityReaderEpoch` TEXT")
                    db.execSQL("ALTER TABLE `snapshot` ADD COLUMN `uidIdentityPackageChangeSequence` INTEGER")
                    db.execSQL("ALTER TABLE `snapshot_uid` ADD COLUMN `ownerInstallFingerprint` TEXT")
                }
            }

        /** `HistoryDao.newestOfEachKindBefore` seeks this index once per kind instead of scanning `history_event`. */
        val MIGRATION_5_6: Migration =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_event_kind_wallMs` ON `history_event` (`kind`, `wallMs`)")
                }
            }

        /**
         * Adds the history prune watermark. A database with history state is seeded so that H never underestimates
         * what was processed (the cursor or the newest stored event) and W starts at the oldest known stored wall time;
         * an empty one gets no row, like a fresh install (no row reads as `Long.MIN_VALUE` for both).
         */
        val MIGRATION_6_7: Migration =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `history_watermark` (`id` INTEGER NOT NULL, " +
                            "`seenThroughHistoryTimeMs` INTEGER NOT NULL, `prunedBeforeWallMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    )
                    db.execSQL(
                        "INSERT INTO history_watermark (id, seenThroughHistoryTimeMs, prunedBeforeWallMs) SELECT " +
                            "${HistoryWatermarkEntity.SINGLETON_ID}, " +
                            "MAX(COALESCE((SELECT lastHistoryTimeMs FROM history_cursor WHERE id = ${HistoryCursorEntity.SINGLETON_ID}), 0), " +
                            "COALESCE((SELECT MAX(historyTimeMs) FROM history_event), 0)), " +
                            "COALESCE((SELECT MIN(wallMs) FROM history_event WHERE wallMs >= 0), ${Long.MIN_VALUE}) " +
                            "WHERE EXISTS(SELECT 1 FROM history_cursor WHERE id = ${HistoryCursorEntity.SINGLETON_ID}) " +
                            "OR EXISTS(SELECT 1 FROM history_event)",
                    )
                }
            }

        /**
         * Earlier builds dated a reboot START from the previous boot's wall anchor, so it carried the moment the
         * device went down instead of the moment it came back (audit A03). A START whose same-session, same-history-
         * time group has a row with a known wall time takes that wall time: those rows were dated by the CURRENT_TIME
         * or RESET written with the START, so the value is exact and the row keeps its id and place. Any other START
         * is deleted: its wall time is wrong by the powered-off gap and nothing stored can date it. Dropping the
         * rewind marker makes the next ingest page the stats session again once, so the extractor, which now dates
         * START itself, restores every deleted START the framework still holds. STARTs the framework or retention
         * already dropped stay lost: this is a repair of what is provable, not a reconstruction of history.
         */
        val MIGRATION_7_8: Migration =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // A known-wall companion row of the START: same stats session, same history time, any other kind.
                    val companionWallMs =
                        "SELECT MIN(c.wallMs) FROM history_event c WHERE c.statsStartMs = history_event.statsStartMs " +
                            "AND c.historyTimeMs = history_event.historyTimeMs AND c.kind != '$START_KIND' AND c.wallMs >= 0"
                    // OR IGNORE: a duplicate START at that wall time (unique index) stays and this one is deleted below.
                    db.execSQL(
                        "UPDATE OR IGNORE history_event SET wallMs = ($companionWallMs) " +
                            "WHERE kind = '$START_KIND' AND ($companionWallMs) IS NOT NULL",
                    )
                    db.execSQL(
                        "DELETE FROM history_event WHERE kind = '$START_KIND' AND NOT EXISTS (" +
                            "SELECT 1 FROM history_event c WHERE c.statsStartMs = history_event.statsStartMs " +
                            "AND c.historyTimeMs = history_event.historyTimeMs AND c.kind != '$START_KIND' " +
                            "AND c.wallMs >= 0 AND c.wallMs = history_event.wallMs)",
                    )
                    db.execSQL("DELETE FROM history_cursor WHERE id = ${HistoryDao.REWOUND_MARKER.id}")
                }
            }

        /** `HistoryKind.START` as `history_event.kind` stores it (`RoomSnapshotStore` writes the code's decimal string). */
        private const val START_KIND = 7

        const val DATABASE_NAME = "inspiration.db"
    }
}
