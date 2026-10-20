package com.akane.inspiration.ipc.client

import android.os.IBinder
import android.os.IDeviceIdleController
import android.os.IPowerManager
import com.akane.inspiration.ipc.model.DeviceIdleSnapshot

/** `IDeviceIdleController` read; faked in JVM tests. */
internal fun interface DeviceIdleCalls {
    fun getFullPowerWhitelist(): Array<String>?
}

/** `IPowerManager` reads; faked in JVM tests. */
internal interface PowerManagerCalls {
    fun isDeviceIdleMode(): Boolean

    fun isLightDeviceIdleMode(): Boolean
}

data class IdleFlags(
    val deepIdle: Boolean,
    val lightIdle: Boolean,
)

/** Reads the power-save whitelist from binder "deviceidle". Never throws. */
class DeviceIdleClient internal constructor(
    private val binderSource: () -> IBinder?,
    private val callsFactory: (IBinder) -> DeviceIdleCalls,
) {
    constructor(binderSource: () -> IBinder?) : this(
        binderSource,
        { binder ->
            val service = IDeviceIdleController.Stub.asInterface(binder)
            DeviceIdleCalls { service.fullPowerWhitelist }
        },
    )

    fun readWhitelist(): BinderResult<List<String>> =
        withBinder(binderSource, { BinderResult.Failed(it) }) { binder ->
            BinderResult.Ok(callsFactory(binder).getFullPowerWhitelist()?.toList().orEmpty())
        }
}

/** Reads deep/light idle flags from binder "power". Never throws. */
class PowerManagerClient internal constructor(
    private val binderSource: () -> IBinder?,
    private val callsFactory: (IBinder) -> PowerManagerCalls,
) {
    constructor(binderSource: () -> IBinder?) : this(
        binderSource,
        { binder ->
            val service = IPowerManager.Stub.asInterface(binder)
            object : PowerManagerCalls {
                override fun isDeviceIdleMode(): Boolean = service.isDeviceIdleMode

                override fun isLightDeviceIdleMode(): Boolean = service.isLightDeviceIdleMode
            }
        },
    )

    fun readIdleFlags(): BinderResult<IdleFlags> =
        withBinder(binderSource, { BinderResult.Failed(it) }) { binder ->
            val calls = callsFactory(binder)
            BinderResult.Ok(IdleFlags(deepIdle = calls.isDeviceIdleMode(), lightIdle = calls.isLightDeviceIdleMode()))
        }
}

/**
 * Combines both reads into one [DeviceIdleSnapshot]; the idle-flag failure reason wins. Only the debug probe
 * calls it, in-process through Shizuku (the user-service txn 5 was removed), so it lives in the debug source set.
 */
class DeviceIdleReader(
    private val deviceIdle: DeviceIdleClient,
    private val powerManager: PowerManagerClient,
) {
    fun read(): DeviceIdleSnapshot {
        val flags =
            when (val result = powerManager.readIdleFlags()) {
                is BinderResult.Failed -> return DeviceIdleSnapshot.unavailable(result.reason)
                is BinderResult.Ok -> result.value
            }
        val whitelist =
            when (val result = deviceIdle.readWhitelist()) {
                is BinderResult.Failed -> return DeviceIdleSnapshot.unavailable(result.reason)
                is BinderResult.Ok -> result.value
            }
        return DeviceIdleSnapshot(
            available = true,
            reason = null,
            deepIdle = flags.deepIdle,
            lightIdle = flags.lightIdle,
            powerSaveWhitelist = whitelist,
        )
    }
}
