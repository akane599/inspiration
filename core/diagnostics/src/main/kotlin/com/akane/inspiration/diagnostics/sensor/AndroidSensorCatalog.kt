package com.akane.inspiration.diagnostics.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.util.Log
import com.akane.inspiration.diagnostics.model.SensorKind

/**
 * Resolves handles from `SensorManager.getSensorList(TYPE_ALL)`. `Sensor.getHandle()` is hidden,
 * so it is read by plain reflection; the app exempts exactly [GET_HANDLE_SIGNATURE] from the
 * hidden-API policy, and nothing else in its process.
 * The map is built once, on first use; any failure leaves it absent, so every non-GPS handle is OTHER and
 * [available] is false, and the failure is logged once. Only the failures that can occur are caught (a missing or
 * failing sensor service, a hidden or inaccessible `getHandle()`, a non-Int handle); errors propagate.
 */
class AndroidSensorCatalog(
    private val context: Context,
) : SensorCatalog {
    private val kindsByHandle: Map<Int, SensorKind>? by lazy { buildKinds() }

    override val available: Boolean get() = kindsByHandle != null

    override fun kindOf(handle: Int): SensorKind =
        if (handle == GPS_SENSOR_HANDLE) SensorKind.GPS else kindsByHandle?.get(handle) ?: SensorKind.OTHER

    private fun buildKinds(): Map<Int, SensorKind>? {
        val sensors =
            try {
                context.getSystemService(SensorManager::class.java)?.getSensorList(Sensor.TYPE_ALL).orEmpty()
            } catch (e: RuntimeException) {
                // A missing or failing sensor service: there is nothing to map.
                return unavailable(e)
            }
        return try {
            val getHandle = Sensor::class.java.getMethod(GET_HANDLE)
            val kinds = HashMap<Int, SensorKind>()
            sensors.forEach { sensor ->
                val kind = kindOfType(sensor.type)
                if (kind != SensorKind.OTHER) kinds[getHandle.invoke(sensor) as Int] = kind
            }
            kinds
        } catch (e: ReflectiveOperationException) {
            // NoSuchMethodException when the hidden-API policy hides getHandle(); IllegalAccess or
            // InvocationTarget from invoke().
            unavailable(e)
        } catch (e: SecurityException) {
            unavailable(e)
        } catch (e: ClassCastException) {
            unavailable(e)
        }
    }

    private fun unavailable(cause: Exception): Map<Int, SensorKind>? {
        Log.w(TAG, "Sensor handles unavailable; accelerometer and gyroscope report as OTHER", cause)
        return null
    }

    private fun kindOfType(type: Int): SensorKind =
        when (type) {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> SensorKind.ACCELEROMETER
            Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> SensorKind.GYROSCOPE
            else -> SensorKind.OTHER
        }

    companion object {
        private const val TAG = "AndroidSensorCatalog"
        private const val GET_HANDLE = "getHandle"

        /**
         * The hidden-API signature of the one hidden member this catalog reads, for
         * `HiddenApiBypass.addHiddenApiExemptions`. Without the exemption, a platform that hides
         * `getHandle()` from apps leaves accelerometer and gyroscope handles as OTHER.
         */
        const val GET_HANDLE_SIGNATURE = "Landroid/hardware/Sensor;->$GET_HANDLE()I"
    }
}
