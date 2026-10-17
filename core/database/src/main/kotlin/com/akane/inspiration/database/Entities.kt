package com.akane.inspiration.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "snapshot",
    indices = [
        Index(value = ["bootCount", "elapsedRealtimeMs"]),
        Index(value = ["wallMs"]),
        Index(value = ["statsStartMs"]),
    ],
)
data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val trigger: String,
    val wallMs: Long,
    val elapsedRealtimeMs: Long,
    val uptimeMs: Long,
    val bootCount: Int,
    val statsStartMs: Long? = null,
    val statsDurationMs: Long? = null,
    val levelPct: Int? = null,
    val plugged: Boolean? = null,
    val statusText: String? = null,
    val temperatureDeciC: Int? = null,
    val voltageMv: Int? = null,
    val currentMa: Int? = null,
    val chargeCounterUah: Long? = null,
    val dischargedMah: Double? = null,
    @ColumnInfo(defaultValue = "0") val truncatedUids: Int = 0,
)

@Entity(
    tableName = "snapshot_system",
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SnapshotSystemEntity(
    @PrimaryKey val snapshotId: Long,
    val startClockTimeMs: Long,
    val totalRealtimeMs: Long,
    val totalUptimeMs: Long,
    val batteryRealtimeMs: Long,
    val batteryUptimeMs: Long,
    val screenOffRealtimeMs: Long,
    val screenOffUptimeMs: Long,
    val screenDozeMs: Long,
    val screenOnMs: Long,
    val interactiveMs: Long,
    val deepDozeMs: Long,
    val deepDozeCount: Long,
    val lightDozeMs: Long,
    val dischargeTotalMah: Double,
    val dischargeScreenOffMah: Double,
    val chargeTimeRemainingMs: Long? = null,
    val dischargeTimeRemainingMs: Long? = null,
    // Signal-strength bins 0..4 (index i -> signalBin{i}Ms). Only the first 5 bins reported by the
    // dump are persisted; all null means the dump carried no bins.
    val signalBin0Ms: Long? = null,
    val signalBin1Ms: Long? = null,
    val signalBin2Ms: Long? = null,
    val signalBin3Ms: Long? = null,
    val signalBin4Ms: Long? = null,
    val signalScanningMs: Long? = null,
    val mobileRadioActiveMs: Long? = null,
)

@Entity(
    tableName = "snapshot_availability",
    primaryKeys = ["snapshotId", "source"],
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SnapshotAvailabilityEntity(
    val snapshotId: Long,
    val source: String,
    val available: Boolean,
    val reason: String? = null,
)

@Entity(
    tableName = "snapshot_uid",
    primaryKeys = ["snapshotId", "uid"],
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SnapshotUidEntity(
    val snapshotId: Long,
    val uid: Int,
    val packageName: String? = null,
    val totalMah: Double,
    val fgMah: Double? = null,
    val bgMah: Double? = null,
    val fgsMah: Double? = null,
    val cachedMah: Double? = null,
    val cpuUserMs: Long,
    val cpuSystemMs: Long,
    val fgActivityMs: Long,
    val fgServiceMs: Long,
)

@Entity(
    tableName = "snapshot_component",
    primaryKeys = ["snapshotId", "uid", "component"],
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SnapshotComponentEntity(
    val snapshotId: Long,
    val uid: Int,
    val component: String,
    val mah: Double,
)

@Entity(
    tableName = "snapshot_wakelock",
    primaryKeys = ["snapshotId", "kind", "uid", "name"],
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SnapshotWakelockEntity(
    val snapshotId: Long,
    val kind: String,
    val uid: Int,
    val name: String,
    val durationMs: Long,
    val bgDurationMs: Long,
    val count: Long,
)

@Entity(
    tableName = "snapshot_alarm",
    primaryKeys = ["snapshotId", "uid", "tag"],
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SnapshotAlarmEntity(
    val snapshotId: Long,
    val uid: Int,
    val tag: String,
    // Batterystats count; 0 on a row that only the alarm dump reported.
    val count: Long,
    // The alarm dump's own wakeupCount for the same (uid, tag); never part of [count].
    @ColumnInfo(defaultValue = "0") val alarmDumpWakeups: Long = 0,
)

@Entity(
    tableName = "snapshot_uid_sensor",
    primaryKeys = ["snapshotId", "uid", "handle"],
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SnapshotUidSensorEntity(
    val snapshotId: Long,
    val uid: Int,
    val handle: Int,
    val totalMs: Long,
    val bgMs: Long,
    val count: Long,
)

@Entity(
    tableName = "snapshot_cpu_freq",
    primaryKeys = ["snapshotId", "policy", "freqKhz"],
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SnapshotCpuFreqEntity(
    val snapshotId: Long,
    val policy: Int,
    val freqKhz: Long,
    val timeMs: Long,
)

@Entity(
    tableName = "marker",
    indices = [Index(value = ["snapshotId"])],
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
data class MarkerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val snapshotId: Long,
    val label: String,
    val wallMs: Long,
)

@Entity(
    tableName = "history_event",
    indices = [Index(value = ["wallMs"]), Index(value = ["historyTimeMs", "kind", "wallMs"], unique = true)],
)
data class HistoryEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val statsStartMs: Long,
    val historyTimeMs: Long,
    val wallMs: Long,
    val kind: String,
    val levelPct: Int? = null,
    val chargeUah: Long? = null,
    val temperatureDeciC: Int? = null,
    val voltageMv: Int? = null,
    val plugType: String? = null,
)

@Entity(tableName = "history_cursor")
data class HistoryCursorEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val statsStartMs: Long,
    val lastHistoryTimeMs: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(tableName = "telemetry_sample", indices = [Index(value = ["wallMs"])])
data class TelemetrySampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wallMs: Long,
    val elapsedRealtimeMs: Long,
    val bootCount: Int,
    val currentMa: Int? = null,
    val voltageMv: Int? = null,
    val watts: Double? = null,
    val temperatureDeciC: Int? = null,
    val levelPct: Int? = null,
    val plugged: Boolean? = null,
)

@Entity(tableName = "telemetry_hourly")
data class TelemetryHourlyEntity(
    @PrimaryKey val hourStartWallMs: Long,
    val sampleCount: Long,
    val avgCurrentMa: Double? = null,
    val minCurrentMa: Int? = null,
    val maxCurrentMa: Int? = null,
    val avgVoltageMv: Double? = null,
    val avgWatts: Double? = null,
    val maxTemperatureDeciC: Int? = null,
    val minLevelPct: Int? = null,
    val maxLevelPct: Int? = null,
)

@Entity(
    tableName = "charge_session",
    indices = [Index(value = ["statsStartMs", "startHistoryTimeMs"], unique = true)],
)
data class ChargeSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val statsStartMs: Long,
    val startHistoryTimeMs: Long,
    val startWallMs: Long,
    val endWallMs: Long,
    val startPct: Int,
    val endPct: Int,
    val deltaChargeUah: Long? = null,
    val integratedMah: Double? = null,
    val maxTemperatureDeciC: Int? = null,
    val estimateMah: Double? = null,
    val independent: Boolean,
    val accepted: Boolean,
    val rejectReason: String? = null,
)

@Entity(tableName = "battery_health_sample")
data class HealthSampleEntity(
    @PrimaryKey val wallMs: Long,
    val fccMah: Double,
    val designMah: Double,
    val cycleCount: Int,
    val measuredMah: Double? = null,
)

/** Audit row for a privileged toolkit write; [status] holds a stable explicit code, never an ordinal. */
@Entity(tableName = "toolkit_action", indices = [Index(value = ["status"])])
data class ToolkitActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val kind: String,
    val pkg: String? = null,
    val op: String,
    val param: String? = null,
    val status: String,
    val code: Int? = null,
    val beforeRaw: String? = null,
    val afterRaw: String? = null,
    val restoresId: Long? = null,
)

@Entity(tableName = "capture_miss", indices = [Index(value = ["wallMs"])])
data class CaptureMissEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wallMs: Long,
    val trigger: String,
    val reason: String,
)
