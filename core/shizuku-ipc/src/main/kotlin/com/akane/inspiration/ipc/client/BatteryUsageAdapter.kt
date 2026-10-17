package com.akane.inspiration.ipc.client

import com.akane.inspiration.ipc.model.BatteryUsageSnapshot
import com.akane.inspiration.ipc.model.UidUsage

/** Seam over `BatteryUsageStatsQuery.Builder`, so query gating is testable on the JVM. */
interface BatteryUsageQueryBuilder {
    fun setMaxStatsAgeMs(maxStatsAgeMs: Long)

    fun includeProcessStateData()

    fun includeVirtualUids()

    fun includePowerModels()

    fun includeBatteryHistory()

    /** Only called on API 36+, where the framework builder has it. */
    fun setPreferredHistoryDurationMs(durationMs: Long)
}

/** Read-only view of a framework `BatteryUsageStats`; the caller closes it after mapping. */
interface BatteryUsageAdapter : AutoCloseable {
    override fun close() = Unit
    val statsStartMs: Long
    val statsEndMs: Long
    val consumedPowerMah: Double
    val uidConsumers: List<UidConsumerAdapter>

    /** Framework session duration, or null where the release does not expose it. */
    val statsDurationMs: Long? get() = null

    /** The device-scope aggregate consumer, or null where it is unavailable. */
    val deviceConsumer: DeviceConsumerAdapter? get() = null
}

/** Read-only view of the framework device-scope `AggregateBatteryConsumer`. */
fun interface DeviceConsumerAdapter {
    fun componentMah(componentId: Int): Double
}

/** Read-only view of a framework `UidBatteryConsumer`. */
interface UidConsumerAdapter {
    val uid: Int
    val packageName: String?
    val totalMah: Double

    fun componentMah(componentId: Int): Double

    /** Only called on API 33+, where the query included process-state data. */
    fun processStateMah(processState: Int): Double
}

internal const val API_PROCESS_STATE_DATA = 33
internal const val API_LAST_POWER_MODELS = 34
internal const val API_PREFERRED_HISTORY_DURATION = 36

/**
 * Preferred history span requested on API 36+ (E3 asks for the longest). The framework trims to
 * this only when extended history is on, and its store holds ~4 MiB anyway. One year is
 * effectively unbounded while avoiding overflow that [Long.MAX_VALUE] could cause in server-side
 * time arithmetic.
 */
internal const val PREFERRED_HISTORY_DURATION_MS = 365L * 24 * 60 * 60 * 1000

/** Applies D9 (docs/plans/phase-1.md) to the query builder. */
internal fun configureBatteryUsageQuery(builder: BatteryUsageQueryBuilder, sdkInt: Int, maxAgeMs: Long) {
    builder.setMaxStatsAgeMs(maxAgeMs)
    if (sdkInt >= API_PROCESS_STATE_DATA) {
        builder.includeProcessStateData()
        builder.includeVirtualUids()
    }
    if (sdkInt <= API_LAST_POWER_MODELS) {
        builder.includePowerModels()
    }
}

/** Applies E3 (docs/plans/phase-2.md) to the history query builder. */
internal fun configureBatteryHistoryQuery(builder: BatteryUsageQueryBuilder, sdkInt: Int) {
    builder.includeBatteryHistory()
    if (sdkInt >= API_PREFERRED_HISTORY_DURATION) {
        builder.setPreferredHistoryDurationMs(PREFERRED_HISTORY_DURATION_MS)
    }
}

internal object BatteryUsageMapper {
    /**
     * `BatteryConsumer.POWER_COMPONENT_*` ids 0..17, identical in android-12.0.0_r34 through
     * android-16.0.0_r4. POWER_COMPONENT_BASE (18) is newer and is not queried, because
     * `getConsumedPower(18)` throws on releases whose component count is 18.
     */
    val COMPONENTS: List<Pair<Int, String>> = listOf(
        0 to "SCREEN",
        1 to "CPU",
        2 to "BLUETOOTH",
        3 to "CAMERA",
        4 to "AUDIO",
        5 to "VIDEO",
        6 to "FLASHLIGHT",
        7 to "SYSTEM_SERVICES",
        8 to "MOBILE_RADIO",
        9 to "SENSORS",
        10 to "GNSS",
        11 to "WIFI",
        12 to "WAKELOCK",
        13 to "MEMORY",
        14 to "PHONE",
        15 to "AMBIENT_DISPLAY",
        16 to "IDLE",
        17 to "REATTRIBUTED_TO_OTHER_CONSUMERS",
    )

    /** `BatteryConsumer.PROCESS_STATE_*` (SQ-1 §3); stable from API 33. */
    val PROCESS_STATES: List<Pair<Int, String>> = listOf(
        1 to "FOREGROUND",
        2 to "BACKGROUND",
        3 to "FOREGROUND_SERVICE",
        4 to "CACHED",
    )

    fun map(
        stats: BatteryUsageAdapter,
        sdkInt: Int,
        batteryTimeRemainingMs: Long,
        chargeTimeRemainingMs: Long,
    ): BatteryUsageSnapshot = BatteryUsageSnapshot(
        available = true,
        reason = null,
        statsStartMs = stats.statsStartMs,
        statsEndMs = stats.statsEndMs,
        dischargedPowerMah = stats.consumedPowerMah,
        batteryTimeRemainingMs = batteryTimeRemainingMs,
        chargeTimeRemainingMs = chargeTimeRemainingMs,
        uids = stats.uidConsumers.map { mapUid(it, sdkInt) },
        statsDurationMs = stats.statsDurationMs ?: (stats.statsEndMs - stats.statsStartMs),
        deviceComponents = stats.deviceConsumer?.let { positiveComponents(it::componentMah) }.orEmpty(),
    )

    // Zero components are dropped to keep the parcel small; an absent key means 0 mAh.
    private fun positiveComponents(mah: (Int) -> Double): Map<String, Double> = COMPONENTS
        .map { (id, name) -> name to mah(id) }
        .filter { (_, value) -> value > 0.0 }
        .toMap()

    private fun mapUid(consumer: UidConsumerAdapter, sdkInt: Int): UidUsage = UidUsage(
        uid = consumer.uid,
        packageName = consumer.packageName,
        totalMah = consumer.totalMah,
        components = positiveComponents(consumer::componentMah),
        byProcessState = if (sdkInt >= API_PROCESS_STATE_DATA) {
            PROCESS_STATES.associate { (state, name) -> name to consumer.processStateMah(state) }
        } else {
            null
        },
    )
}
