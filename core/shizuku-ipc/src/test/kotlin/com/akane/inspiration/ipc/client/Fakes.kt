package com.akane.inspiration.ipc.client

import android.os.IBinder
import java.lang.reflect.Proxy

/** An [IBinder] whose methods are never called by the clients under test. */
internal val fakeBinder: IBinder = Proxy.newProxyInstance(
    IBinder::class.java.classLoader,
    arrayOf(IBinder::class.java),
) { _, method, _ -> error("unexpected IBinder.${method.name}") } as IBinder

internal class RecordingQueryBuilder : BatteryUsageQueryBuilder {
    val calls = mutableListOf<String>()

    override fun setMaxStatsAgeMs(maxStatsAgeMs: Long) {
        calls += "setMaxStatsAgeMs($maxStatsAgeMs)"
    }

    override fun includeProcessStateData() {
        calls += "includeProcessStateData"
    }

    override fun includeVirtualUids() {
        calls += "includeVirtualUids"
    }

    override fun includePowerModels() {
        calls += "includePowerModels"
    }

    override fun includeBatteryHistory() {
        calls += "includeBatteryHistory"
    }

    override fun setPreferredHistoryDurationMs(durationMs: Long) {
        calls += "setPreferredHistoryDurationMs($durationMs)"
    }
}

internal class FakeUidConsumer(
    override val uid: Int,
    override val packageName: String?,
    override val totalMah: Double,
    private val components: Map<Int, Double> = emptyMap(),
    private val processStates: Map<Int, Double> = emptyMap(),
) : UidConsumerAdapter {
    var processStateCalls = 0

    override fun componentMah(componentId: Int): Double = components[componentId] ?: 0.0

    override fun processStateMah(processState: Int): Double {
        processStateCalls++
        return processStates[processState] ?: 0.0
    }
}

internal class FakeBatteryUsage(
    override val statsStartMs: Long = 1_000L,
    override val statsEndMs: Long = 2_000L,
    override val consumedPowerMah: Double = 42.5,
    override val uidConsumers: List<UidConsumerAdapter> = emptyList(),
    override val statsDurationMs: Long? = null,
    override val deviceConsumer: DeviceConsumerAdapter? = null,
    private val closeFailure: Throwable? = null,
) : BatteryUsageAdapter {
    var closed = 0

    override fun close() {
        closed++
        closeFailure?.let { throw it }
    }
}

internal class MapperFailingBatteryUsage : BatteryUsageAdapter {
    var closed = 0

    override val statsStartMs: Long get() = throw IllegalStateException("mapper failed")
    override val statsEndMs: Long = 2_000L
    override val consumedPowerMah: Double = 42.5
    override val uidConsumers: List<UidConsumerAdapter> = emptyList()

    override fun close() {
        closed++
    }
}

/** History over [records]; [failure] is thrown from the walk after [failAfter] records. */
internal class FakeBatteryHistory(
    override val statsStartMs: Long = 1_000L,
    private val records: List<HistoryRecord> = emptyList(),
    private val failure: Throwable? = null,
    private val failAfter: Int = 0,
) : BatteryHistoryAdapter {
    var closed = 0
    var consumed = 0

    override fun records(): Iterator<HistoryRecord> = iterator {
        records.forEachIndexed { index, record ->
            if (failure != null && index == failAfter) throw failure
            consumed++
            yield(record)
        }
        if (failure != null && failAfter >= records.size) throw failure
    }

    override fun close() {
        closed++
    }
}

internal class FakeBatteryStatsCalls(
    private val usage: BatteryUsageAdapter? = FakeBatteryUsage(),
    private val failure: Throwable? = null,
    private val history: BatteryHistoryAdapter? = FakeBatteryHistory(),
    private val batteryTimeRemaining: () -> Long = { 3_600_000L },
) : BatteryStatsCalls {
    val builder = RecordingQueryBuilder()

    override fun getBatteryUsageStats(configure: (BatteryUsageQueryBuilder) -> Unit): BatteryUsageAdapter? {
        configure(builder)
        failure?.let { throw it }
        return usage
    }

    override fun getBatteryHistory(configure: (BatteryUsageQueryBuilder) -> Unit): BatteryHistoryAdapter? {
        configure(builder)
        failure?.let { throw it }
        return history
    }

    override fun computeBatteryTimeRemaining(): Long = batteryTimeRemaining()

    override fun computeChargeTimeRemaining(): Long = -1L
}
