package com.akane.inspiration.ipc.client

import android.os.BatteryConsumer
import android.os.BatteryUsageStats
import android.os.BatteryUsageStatsQuery
import android.os.Build
import android.os.IBinder
import android.os.UidBatteryConsumer
import com.akane.inspiration.ipc.model.BatteryUsageSnapshot
import com.akane.inspiration.ipc.model.Reasons
import com.android.internal.app.IBatteryStats
import java.lang.reflect.Method

/** The `IBatteryStats` calls this client makes; faked in JVM tests. */
internal interface BatteryStatsCalls {
    /** Builds one query via [configure] and returns the first result, or null if none. */
    fun getBatteryUsageStats(configure: (BatteryUsageQueryBuilder) -> Unit): BatteryUsageAdapter?

    fun computeBatteryTimeRemaining(): Long

    fun computeChargeTimeRemaining(): Long
}

/** Reads `IBatteryStats` (binder "batterystats"). Never throws. */
class BatteryStatsClient internal constructor(
    private val binderSource: () -> IBinder?,
    private val sdkInt: Int,
    private val callsFactory: (IBinder) -> BatteryStatsCalls,
) {
    constructor(
        binderSource: () -> IBinder?,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ) : this(binderSource, sdkInt, { binder -> FrameworkBatteryStatsCalls(binder, sdkInt) })

    fun readUsage(maxAgeMs: Long): BatteryUsageSnapshot = withBinder(
        binderSource,
        onFailure = { BatteryUsageSnapshot.unavailable(it) },
    ) { binder ->
        val calls = callsFactory(binder)
        val stats = calls.getBatteryUsageStats { configureBatteryUsageQuery(it, sdkInt, maxAgeMs) }
        if (stats == null) {
            BatteryUsageSnapshot.unavailable(Reasons.REMOTE)
        } else {
            BatteryUsageMapper.map(
                stats = stats,
                sdkInt = sdkInt,
                batteryTimeRemainingMs = calls.computeBatteryTimeRemaining(),
                chargeTimeRemainingMs = calls.computeChargeTimeRemaining(),
            )
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
}

private class FrameworkBatteryUsageAdapter(
    private val stats: BatteryUsageStats,
    private val sdkInt: Int,
) : BatteryUsageAdapter {
    override val statsStartMs: Long get() = stats.statsStartTimestamp
    override val statsEndMs: Long get() = stats.statsEndTimestamp
    override val consumedPowerMah: Double get() = stats.consumedPower

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
