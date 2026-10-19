package com.akane.inspiration.telemetry.calibration

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/**
 * [CalibrationStore] over [SharedPreferences], tied to [buildFingerprint]. An OS update can bring a new fuel-gauge
 * driver or health HAL, so a new build re-learns the locks. A new battery does not need to: the units, sign and
 * per-cell reporting belong to the driver and HAL, not to the cell, so the battery's identity is not part of the key.
 */
class SharedPreferencesCalibrationStore(
    private val prefs: SharedPreferences,
    private val buildFingerprint: String = Build.FINGERPRINT,
) : CalibrationStore {
    override fun load(): CalibrationLocks? {
        if (prefs.getString(KEY_FINGERPRINT, null) != buildFingerprint) return null
        return CalibrationLocks(
            sysfsMilliamps = prefs.lock(KEY_SYSFS_MILLIAMPS),
            batteryManagerMilliamps = prefs.lock(KEY_BATTERY_MANAGER_MILLIAMPS),
            dischargePositive = prefs.lock(KEY_DISCHARGE_POSITIVE),
            perCellCurrent = prefs.lock(KEY_PER_CELL_CURRENT),
            sysfsMicrovolts = prefs.lock(KEY_SYSFS_MICROVOLTS),
        )
    }

    override fun save(locks: CalibrationLocks) {
        prefs
            .edit()
            .clear()
            .putString(KEY_FINGERPRINT, buildFingerprint)
            .putLock(KEY_SYSFS_MILLIAMPS, locks.sysfsMilliamps)
            .putLock(KEY_BATTERY_MANAGER_MILLIAMPS, locks.batteryManagerMilliamps)
            .putLock(KEY_DISCHARGE_POSITIVE, locks.dischargePositive)
            .putLock(KEY_PER_CELL_CURRENT, locks.perCellCurrent)
            .putLock(KEY_SYSFS_MICROVOLTS, locks.sysfsMicrovolts)
            .apply()
    }

    private fun SharedPreferences.lock(key: String): Boolean? = if (contains(key)) getBoolean(key, false) else null

    private fun SharedPreferences.Editor.putLock(
        key: String,
        value: Boolean?,
    ): SharedPreferences.Editor = if (value == null) this else putBoolean(key, value)

    companion object {
        private const val PREFS_NAME = "power_calibration"
        private const val KEY_FINGERPRINT = "buildFingerprint"
        private const val KEY_SYSFS_MILLIAMPS = "sysfsMilliamps"
        private const val KEY_BATTERY_MANAGER_MILLIAMPS = "batteryManagerMilliamps"
        private const val KEY_DISCHARGE_POSITIVE = "dischargePositive"
        private const val KEY_PER_CELL_CURRENT = "perCellCurrent"
        private const val KEY_SYSFS_MICROVOLTS = "sysfsMicrovolts"

        fun from(context: Context): SharedPreferencesCalibrationStore =
            SharedPreferencesCalibrationStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
