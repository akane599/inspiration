package com.akane.inspiration.diagnostics.sensor

import com.akane.inspiration.diagnostics.model.SensorKind

/** `BatteryStats.GPS_SENSOR`: the pseudo sensor handle batterystats uses for GPS time. */
const val GPS_SENSOR_HANDLE = -10000

/** Maps a batterystats sensor handle to the kind the rules care about. */
fun interface SensorCatalog {
    fun kindOf(handle: Int): SensorKind
}
