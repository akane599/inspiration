package com.akane.inspiration.ipc.client

import android.os.BatteryConsumer
import android.os.BatteryStats
import android.os.BatteryUsageStats
import android.os.BatteryUsageStatsQuery
import android.os.Build
import android.os.IBinder
import android.os.UidBatteryConsumer
import com.akane.inspiration.ipc.model.BatteryHistorySnapshot
import com.akane.inspiration.ipc.model.BatteryUsageSnapshot
import com.akane.inspiration.ipc.model.Reasons
import com.android.internal.app.IBatteryStats
import com.android.internal.os.BatteryStatsHistoryIterator
import java.lang.reflect.Method

/** The `IBatteryStats` calls this client makes; faked in JVM tests. */
internal interface BatteryStatsCalls {
    /** Builds one query via [configure] and returns the first result, or null if none. */
    fun getBatteryUsageStats(configure: (BatteryUsageQueryBuilder) -> Unit): BatteryUsageAdapter?

    /** Like [getBatteryUsageStats], for a history query; the caller closes the result. */
    fun getBatteryHistory(configure: (BatteryUsageQueryBuilder) -> Unit): BatteryHistoryAdapter?

    fun computeBatteryTimeRemaining(): Long

    fun computeChargeTimeRemaining(): Long
}

/** Reads `IBatteryStats` (binder "batterystats"). Never throws. */
class BatteryStatsClient internal constructor(
    private val binderSource: () -> IBinder?,
    private val sdkInt: Int,
    private val parcelBytes: (BatteryUsageSnapshot) -> Int,
    private val callsFactory: (IBinder) -> BatteryStatsCalls,
) {
    constructor(
        binderSource: () -> IBinder?,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ) : this(
        binderSource,
        sdkInt,
        UsageParcelBudget::measureParcelBytes,
        { binder -> FrameworkBatteryStatsCalls(binder, sdkInt) },
    )

    /** The result fits the E3b parcel budget ([UsageParcelBudget]). */
    fun readUsage(maxAgeMs: Long): BatteryUsageSnapshot = withBinder(
        binderSource,
        onFailure = { BatteryUsageSnapshot.unavailable(it) },
    ) { binder ->
        val calls = callsFactory(binder)
        val stats = calls.getBatteryUsageStats { configureBatteryUsageQuery(it, sdkInt, maxAgeMs) }
        if (stats == null) {
            BatteryUsageSnapshot.unavailable(Reasons.REMOTE)
        } else {
            try {
                val snapshot = BatteryUsageMapper.map(
                    stats = stats,
                    sdkInt = sdkInt,
                    batteryTimeRemainingMs = calls.computeBatteryTimeRemaining(),
                    chargeTimeRemainingMs = calls.computeChargeTimeRemaining(),
                )
                UsageParcelBudget.fit(snapshot, parcelBytes)
            } finally {
                try {
                    stats.close()
                } catch (_: Exception) {
                    // Releasing framework buffers must not mask the read result or failure.
                } catch (_: LinkageError) {
                    // Same for an unexpected framework shape.
                }
            }
        }
    }

    /** One keep-oldest history page (E3); see [BatteryHistoryExtractor]. */
    fun readHistory(statsStartMs: Long, afterHistoryTimeMs: Long, maxEvents: Int): BatteryHistorySnapshot =
        withBinder(binderSource, onFailure = { BatteryHistorySnapshot.unavailable(it) }) { binder ->
            val history = callsFactory(binder).getBatteryHistory { configureBatteryHistoryQuery(it, sdkInt) }
            if (history == null) {
                BatteryHistorySnapshot.unavailable(Reasons.REMOTE)
            } else {
                try {
                    BatteryHistoryExtractor.extract(
                        sessionStatsStartMs = history.statsStartMs,
                        records = history.records(),
                        requestedStatsStartMs = statsStartMs,
                        afterHistoryTimeMs = afterHistoryTimeMs,
                        maxEvents = maxEvents,
                    )
                } finally {
                    history.close()
                }
            }
        }
}

internal class FrameworkBatteryStatsCalls(binder: IBinder, private val sdkInt: Int) : BatteryStatsCalls {
    private val service: IBatteryStats = IBatteryStats.Stub.asInterface(binder)

    override fun getBatteryUsageStats(configure: (BatteryUsageQueryBuilder) -> Unit): BatteryUsageAdapter? {
        val builder = FrameworkQueryBuilder()
        configure(builder)
        val stats = service.getBatteryUsageStats(listOf(builder.delegate.build()))?.firstOrNull()
        return stats?.let { FrameworkBatteryUsageAdapter(it, sdkInt) }
    }

    override fun getBatteryHistory(configure: (BatteryUsageQueryBuilder) -> Unit): BatteryHistoryAdapter? {
        val builder = FrameworkQueryBuilder()
        configure(builder)
        val stats = service.getBatteryUsageStats(listOf(builder.delegate.build()))?.firstOrNull()
        return stats?.let(::FrameworkBatteryHistory)
    }

    override fun computeBatteryTimeRemaining(): Long = service.computeBatteryTimeRemaining()

    override fun computeChargeTimeRemaining(): Long = service.computeChargeTimeRemaining()
}

private class FrameworkQueryBuilder : BatteryUsageQueryBuilder {
    val delegate = BatteryUsageStatsQuery.Builder()

    override fun setMaxStatsAgeMs(maxStatsAgeMs: Long) {
        delegate.setMaxStatsAgeMs(maxStatsAgeMs)
    }

    override fun includeProcessStateData() {
        delegate.includeProcessStateData()
    }

    override fun includeVirtualUids() {
        delegate.includeVirtualUids()
    }

    @Suppress("DEPRECATION")
    override fun includePowerModels() {
        delegate.includePowerModels()
    }

    override fun includeBatteryHistory() {
        delegate.includeBatteryHistory()
    }

    override fun setPreferredHistoryDurationMs(durationMs: Long) {
        delegate.setPreferredHistoryDurationMs(durationMs)
    }
}

/**
 * History view over a `BatteryUsageStats` queried with `includeBatteryHistory()`. The iterator is
 * `Iterator<HistoryItem>` on API 34+ (android-35/36/37.0 sources); an older shape surfaces as a
 * [LinkageError], which the client maps to no_such_method.
 */
private class FrameworkBatteryHistory(private val stats: BatteryUsageStats) : BatteryHistoryAdapter {
    private var iterator: BatteryStatsHistoryIterator? = null

    override val statsStartMs: Long get() = stats.statsStartTimestamp

    override fun records(): Iterator<HistoryRecord> {
        val items = stats.iterateBatteryStatsHistory().also { iterator = it }
        return object : Iterator<HistoryRecord> {
            private var next: HistoryRecord? = null

            override fun hasNext(): Boolean {
                if (next == null && items.hasNext()) next = items.next()?.toRecord()
                return next != null
            }

            override fun next(): HistoryRecord {
                if (!hasNext()) throw NoSuchElementException()
                return checkNotNull(next).also { next = null }
            }
        }
    }

    override fun close() {
        closeQuietly(iterator)
        closeQuietly(stats)
    }

    private fun BatteryStats.HistoryItem.toRecord() = HistoryRecord(
        time = time,
        currentTime = currentTime,
        cmd = cmd.toInt(),
        states = states,
        states2 = states2,
        batteryLevel = batteryLevel.toInt(),
        plugType = batteryPlugType.toInt(),
        chargeUah = batteryChargeUah,
        temperatureDeciC = batteryTemperature.toInt(),
        voltageMv = batteryVoltage.toInt(),
    )

    /** Both are `AutoCloseable` on API 34+ only, so the check is at runtime. */
    private fun closeQuietly(resource: Any?) {
        try {
            (resource as? AutoCloseable)?.close()
        } catch (_: Exception) {
            // Releasing history buffers is best effort; the read already completed or failed.
        } catch (_: LinkageError) {
            // Same: an unexpected framework shape must not mask the read result.
        }
    }
}

private class FrameworkBatteryUsageAdapter(
    private val stats: BatteryUsageStats,
    private val sdkInt: Int,
) : BatteryUsageAdapter {
    override fun close() {
        stats.close()
    }

    override val statsStartMs: Long get() = stats.statsStartTimestamp
    override val statsEndMs: Long get() = stats.statsEndTimestamp
    override val consumedPowerMah: Double get() = stats.consumedPower

    override val statsDurationMs: Long?
        get() = try {
            stats.statsDuration
        } catch (_: NoSuchMethodError) {
            null
        }

    override val deviceConsumer: DeviceConsumerAdapter?
        get() = try {
            stats.getAggregateBatteryConsumer(BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
        } catch (_: NoSuchMethodError) {
            null
        }?.let { device -> DeviceConsumerAdapter { componentId -> device.getConsumedPower(componentId) } }

    override val uidConsumers: List<UidConsumerAdapter>
        get() {
            val processStates = if (sdkInt >= API_PROCESS_STATE_DATA) ProcessStatePower() else null
            return stats.uidBatteryConsumers.orEmpty().map { FrameworkUidConsumer(it, processStates) }
        }
}

private class FrameworkUidConsumer(
    private val consumer: UidBatteryConsumer,
    private val processStates: ProcessStatePower?,
) : UidConsumerAdapter {
    override val uid: Int get() = consumer.uid
    override val packageName: String? get() = consumer.packageWithHighestDrain
    override val totalMah: Double get() = consumer.consumedPower

    override fun componentMah(componentId: Int): Double = consumer.getConsumedPower(componentId)

    override fun processStateMah(processState: Int): Double =
        processStates?.mah(consumer, processState) ?: 0.0
}

/**
 * Per-process-state mAh via `BatteryConsumer.getKey(int, int)` + `getConsumedPower(Key)` (API 33+;
 * android-14.0.0_r75 BatteryConsumer.java:351,374; android-16.0.0_r4 :554,578). Reached by
 * reflection because `BatteryConsumer.Key` is not in :core:hidden-api-stub. A missing key (the
 * component has no entry for that state) contributes 0.
 */
private class ProcessStatePower {
    private val getKey: Method = BatteryConsumer::class.java.getMethod(
        "getKey",
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
    )
    private val getConsumedPowerByKey: Method = BatteryConsumer::class.java.getMethod(
        "getConsumedPower",
        Class.forName("android.os.BatteryConsumer\$Key"),
    )

    fun mah(consumer: BatteryConsumer, processState: Int): Double =
        BatteryUsageMapper.COMPONENTS.sumOf { (componentId, _) ->
            val key = getKey.invoke(consumer, componentId, processState)
            if (key == null) 0.0 else getConsumedPowerByKey.invoke(consumer, key) as Double
        }
}
