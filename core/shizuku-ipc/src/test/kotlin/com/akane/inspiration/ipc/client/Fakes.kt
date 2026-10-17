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
) : BatteryUsageAdapter

internal class FakeBatteryStatsCalls(
    private val usage: BatteryUsageAdapter? = FakeBatteryUsage(),
    private val failure: Throwable? = null,
) : BatteryStatsCalls {
    val builder = RecordingQueryBuilder()

    override fun getBatteryUsageStats(configure: (BatteryUsageQueryBuilder) -> Unit): BatteryUsageAdapter? {
        configure(builder)
        failure?.let { throw it }
        return usage
    }

    override fun computeBatteryTimeRemaining(): Long = 3_600_000L

    override fun computeChargeTimeRemaining(): Long = -1L
}
