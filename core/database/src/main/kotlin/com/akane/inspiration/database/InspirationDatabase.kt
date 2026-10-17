package com.akane.inspiration.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
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
                .build()

        const val DATABASE_NAME = "inspiration.db"
    }
}
