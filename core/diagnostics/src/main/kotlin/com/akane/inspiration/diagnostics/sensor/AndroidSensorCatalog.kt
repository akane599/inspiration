package com.akane.inspiration.diagnostics.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.akane.inspiration.diagnostics.model.SensorKind

/**
 * Resolves handles from `SensorManager.getSensorList(TYPE_ALL)`. `Sensor.getHandle()` is hidden,
 * so it is read by plain reflection (process-wide hidden-API exemptions are installed by the app).
 * The map is built on first use; any failure leaves it empty, so every unknown handle is OTHER.
 */
class AndroidSensorCatalog(private val context: Context) : SensorCatalog {
    private val kindsByHandle: Map<Int, SensorKind> by lazy { buildKinds() }

    override fun kindOf(handle: Int): SensorKind =
        if (handle == GPS_SENSOR_HANDLE) SensorKind.GPS else kindsByHandle[handle] ?: SensorKind.OTHER

    private fun buildKinds(): Map<Int, SensorKind> = try {
        val getHandle = Sensor::class.java.getMethod("getHandle")
        val manager = context.getSystemService(SensorManager::class.java)
        val kinds = HashMap<Int, SensorKind>()
        manager.getSensorList(Sensor.TYPE_ALL).forEach { sensor ->
            val kind = kindOfType(sensor.type)
            if (kind != SensorKind.OTHER) kinds[getHandle.invoke(sensor) as Int] = kind
        }
        kinds
    } catch (_: Throwable) {
        emptyMap()
    }

    private fun kindOfType(type: Int): SensorKind = when (type) {
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> SensorKind.ACCELEROMETER
        Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> SensorKind.GYROSCOPE
        else -> SensorKind.OTHER
    }
}
