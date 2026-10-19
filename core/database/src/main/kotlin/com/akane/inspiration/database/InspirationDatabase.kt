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
        TelemetrySampleEntity::class,
        TelemetryHourlyEntity::class,
        ChargeSessionEntity::class,
        CaptureMissEntity::class,
        HealthSampleEntity::class,
        ToolkitActionEntity::class,
    ],
    version = 4,
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
        fun create(context: Context, name: String = DATABASE_NAME): InspirationDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                InspirationDatabase::class.java,
                name,
            ).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE battery_health_sample_new (wallMs INTEGER NOT NULL PRIMARY KEY, fccMah REAL NOT NULL, designMah REAL NOT NULL, cycleCount INTEGER, measuredMah REAL)")
                db.execSQL("INSERT INTO battery_health_sample_new SELECT wallMs, fccMah, designMah, cycleCount, measuredMah FROM battery_health_sample")
                db.execSQL("DROP TABLE battery_health_sample")
                db.execSQL("ALTER TABLE battery_health_sample_new RENAME TO battery_health_sample")
            }
        }

        /** Derived estimates from older versions cannot be certified across history discontinuities. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM charge_session")
                db.execSQL("UPDATE battery_health_sample SET measuredMah = NULL")
            }
        }

        /** `latestByTrigger` (newest row of one trigger) seeks this index instead of scanning `snapshot`. */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_snapshot_trigger_wallMs` ON `snapshot` (`trigger`, `wallMs`)")
            }
        }

        const val DATABASE_NAME = "inspiration.db"
    }
}
