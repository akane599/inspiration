package com.akane.inspiration.ipc.client

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.IPowerStatsService
import android.os.PowerMonitor
import android.os.ResultReceiver
import androidx.annotation.RequiresApi
import com.akane.inspiration.ipc.model.PowerMonitorReading
import com.akane.inspiration.ipc.model.PowerMonitorSnapshot
import com.akane.inspiration.ipc.model.Reasons
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class PowerMonitorInfo(
    val name: String,
    val type: Int,
)

internal class PowerMonitorValues(
    val energyUws: LongArray,
    val timestampsMs: LongArray,
)

/**
 * The oneway `IPowerStatsService` calls; faked in JVM tests. Each delivers exactly one callback,
 * possibly on a binder thread, with null when the service reported a failure.
 */
internal interface PowerStatsCalls {
    fun getSupportedPowerMonitors(onResult: (List<PowerMonitorInfo>?) -> Unit)

    fun getPowerMonitorReadings(
        indices: IntArray,
        onResult: (PowerMonitorValues?) -> Unit,
    )
}

internal const val API_POWER_MONITORS = 35
private const val POWER_STATS_TIMEOUT_MS = 2_000L

/**
 * Reads framework power monitors (binder "powerstats"), gated to API 35+ per D3. Never throws. Only the debug
 * probe calls it, in-process through Shizuku (the user-service txn 4 was removed), so it lives in the debug source
 * set.
 */
class PowerStatsClient internal constructor(
    private val binderSource: () -> IBinder?,
    private val sdkInt: Int,
    private val timeoutMs: Long,
    private val callsFactory: (IBinder) -> PowerStatsCalls,
) {
    // NewApi: read() returns unavailable below API_POWER_MONITORS before callsFactory ever runs.
    @SuppressLint("NewApi")
    constructor(
        binderSource: () -> IBinder?,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ) : this(binderSource, sdkInt, POWER_STATS_TIMEOUT_MS, { binder -> FrameworkPowerStatsCalls(binder) })

    fun read(): PowerMonitorSnapshot {
        if (sdkInt < API_POWER_MONITORS) return PowerMonitorSnapshot.unavailable(Reasons.API)
        return withBinder(binderSource, onFailure = { PowerMonitorSnapshot.unavailable(it) }) { binder ->
            readFrom(callsFactory(binder))
        }
    }

    private fun readFrom(calls: PowerStatsCalls): PowerMonitorSnapshot {
        val monitors =
            when (val result = await<List<PowerMonitorInfo>> { calls.getSupportedPowerMonitors(it) }) {
                is BinderResult.Failed -> return PowerMonitorSnapshot.unavailable(result.reason)
                is BinderResult.Ok -> result.value
            }
        if (monitors.isEmpty()) return PowerMonitorSnapshot(available = true, reason = null, monitors = emptyList())

        // PowerMonitor indices are positional: PowerStatsService.ensurePowerMonitors assigns
        // index++ in array order (android-16.0.0_r4 PowerStatsService.java:619,629).
        val indices = IntArray(monitors.size) { it }
        val values =
            when (val result = await<PowerMonitorValues> { calls.getPowerMonitorReadings(indices, it) }) {
                is BinderResult.Failed -> return PowerMonitorSnapshot.unavailable(result.reason)
                is BinderResult.Ok -> result.value
            }
        if (values.energyUws.size != monitors.size || values.timestampsMs.size != monitors.size) {
            return PowerMonitorSnapshot.unavailable(Reasons.REMOTE)
        }
        return PowerMonitorSnapshot(
            available = true,
            reason = null,
            monitors =
                monitors.mapIndexed { i, monitor ->
                    PowerMonitorReading(
                        name = monitor.name,
                        type = monitor.type,
                        energyUws = values.energyUws[i],
                        timestampMs = values.timestampsMs[i],
                    )
                },
        )
    }

    /** Starts one oneway call and waits at most [timeoutMs] for its single callback. */
    private fun <T : Any> await(start: ((T?) -> Unit) -> Unit): BinderResult<T> {
        val latch = CountDownLatch(1)
        val delivered = AtomicReference<Delivery<T>?>(null)
        start { value ->
            if (delivered.compareAndSet(null, Delivery(value))) latch.countDown()
        }
        val completed =
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        if (!completed) return BinderResult.Failed(Reasons.TIMEOUT)
        val value = delivered.get()?.value ?: return BinderResult.Failed(Reasons.REMOTE)
        return BinderResult.Ok(value)
    }

    private class Delivery<T>(
        val value: T?,
    )
}

/**
 * Bundle keys and result codes from `android.os.IPowerStatsService`
 * (core/java/android/os/IPowerStatsService.aidl:23-34, android-16.0.0_r4), filled by
 * PowerStatsService.getSupportedPowerMonitorsImpl / getPowerMonitorReadingsImpl
 * (services/core/java/com/android/server/powerstats/PowerStatsService.java:709-710, 788-791).
 */
private const val KEY_MONITORS = "monitors"
private const val KEY_ENERGY = "energy"
private const val KEY_TIMESTAMPS = "timestamps"
private const val RESULT_SUCCESS = 0

@RequiresApi(API_POWER_MONITORS)
private class FrameworkPowerStatsCalls(
    binder: IBinder,
) : PowerStatsCalls {
    private val service: IPowerStatsService = IPowerStatsService.Stub.asInterface(binder)

    override fun getSupportedPowerMonitors(onResult: (List<PowerMonitorInfo>?) -> Unit) {
        service.getSupportedPowerMonitors(
            receiver(onResult) { data ->
                data
                    .getParcelableArray(KEY_MONITORS, PowerMonitor::class.java)
                    ?.map { PowerMonitorInfo(name = it.name, type = it.type) }
            },
        )
    }

    override fun getPowerMonitorReadings(
        indices: IntArray,
        onResult: (PowerMonitorValues?) -> Unit,
    ) {
        service.getPowerMonitorReadings(
            indices,
            receiver(onResult) { data ->
                val energy = data.getLongArray(KEY_ENERGY)
                val timestamps = data.getLongArray(KEY_TIMESTAMPS)
                if (energy == null || timestamps == null) null else PowerMonitorValues(energy, timestamps)
            },
        )
    }

    /**
     * A [ResultReceiver] with no handler, so the result is parsed on the binder thread that
     * delivers it. A malformed or failed result reports null instead of throwing there.
     */
    private fun <T> receiver(
        onResult: (T?) -> Unit,
        parse: (Bundle) -> T?,
    ): ResultReceiver =
        object : ResultReceiver(null) {
            override fun onReceiveResult(
                resultCode: Int,
                resultData: Bundle?,
            ) {
                val parsed =
                    if (resultCode == RESULT_SUCCESS && resultData != null) {
                        try {
                            parse(resultData)
                        } catch (e: RuntimeException) {
                            null
                        }
                    } else {
                        null
                    }
                onResult(parsed)
            }
        }
}
