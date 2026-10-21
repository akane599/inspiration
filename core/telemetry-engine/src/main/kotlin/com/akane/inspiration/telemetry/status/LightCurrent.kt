package com.akane.inspiration.telemetry.status

import com.akane.inspiration.telemetry.calibration.CalibrationStart
import com.akane.inspiration.telemetry.calibration.CalibrationStore
import com.akane.inspiration.telemetry.source.LightRead

/**
 * Calibrates a [LightRead]'s current with what the live gauge has settled and persisted ([CalibrationStore]), so the
 * status notification and stored light/full snapshots use a current with the same unit and sign as the gauge.
 *
 * A light read's current is BatteryManager's CURRENT_NOW cut from µA to whole mA, in that source's own sign. It is kept
 * only once the BatteryManager unit is settled as µA (an mA HAL's value did not survive the cut) and the sign
 * convention is settled, each locked on this build or carried from an earlier one. Otherwise it is no reading: like
 * the gauge's unsupported unit, never a number that may be 1000x off or point the wrong way.
 */
object LightCurrent {
    /** [light] with its current calibrated; unchanged without a [store], and with no current when the store fails. */
    fun calibrate(
        light: LightRead,
        store: CalibrationStore?,
    ): LightRead {
        if (store == null) return light
        val start = runCatching { store.load() }.getOrNull() ?: CalibrationStart()
        return light.copy(currentMa = calibratedMa(light.currentMa, start))
    }

    /** [currentMa] (discharge negative) under the calibration [start] settled, or null where it settled none. */
    fun calibratedMa(
        currentMa: Int?,
        start: CalibrationStart,
    ): Int? {
        val milliamps = start.locks.batteryManagerMilliamps ?: start.guesses.batteryManagerMilliamps
        val dischargePositive = start.locks.batteryManagerDischargePositive ?: start.guesses.batteryManagerDischargePositive
        if (currentMa == null || milliamps != false || dischargePositive == null) return null
        return if (dischargePositive) -currentMa else currentMa
    }
}
