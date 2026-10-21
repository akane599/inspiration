package com.akane.inspiration.telemetry.calibration

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/**
 * [CalibrationStore] over [SharedPreferences], tied to [buildFingerprint]. An OS update can bring a new fuel-gauge
 * driver or health HAL, so on a new build the saved locks come back as guesses only, which lean each choice until the
 * new build's evidence decides it. A new battery needs no such care: the units, sign and per-cell reporting belong to
 * the driver and HAL, not to the cell, so the battery's identity is not part of the key.
 */
class SharedPreferencesCalibrationStore(
    private val prefs: SharedPreferences,
    private val buildFingerprint: String = Build.FINGERPRINT,
) : CalibrationStore {
    override fun load(): CalibrationStart? {
        val savedOn = prefs.getString(KEY_FINGERPRINT, null) ?: return null
        val locks = prefs.values(LOCK_PREFIX)
        val guesses = prefs.values(GUESS_PREFIX)
        return if (savedOn == buildFingerprint) {
            CalibrationStart(locks, guesses)
        } else {
            CalibrationStart(guesses = locks.orElse(guesses))
        }
    }

    override fun save(start: CalibrationStart) {
        prefs
            .edit()
            .clear()
            .putString(KEY_FINGERPRINT, buildFingerprint)
            .putValues(LOCK_PREFIX, start.locks)
            .putValues(GUESS_PREFIX, start.guesses)
            .apply()
    }

    private fun SharedPreferences.values(prefix: String): CalibrationLocks =
        CalibrationLocks(
            sysfsMilliamps = flag(prefix + SYSFS_MILLIAMPS),
            batteryManagerMilliamps = flag(prefix + BATTERY_MANAGER_MILLIAMPS),
            dischargePositive = flag(prefix + SYSFS_DISCHARGE_POSITIVE),
            batteryManagerDischargePositive = flag(prefix + BATTERY_MANAGER_DISCHARGE_POSITIVE),
            perCellCurrent = flag(prefix + PER_CELL_CURRENT),
            sysfsMicrovolts = flag(prefix + SYSFS_MICROVOLTS),
        )

    private fun SharedPreferences.flag(key: String): Boolean? = if (contains(key)) getBoolean(key, false) else null

    private fun SharedPreferences.Editor.putValues(
        prefix: String,
        values: CalibrationLocks,
    ): SharedPreferences.Editor =
        putFlag(prefix + SYSFS_MILLIAMPS, values.sysfsMilliamps)
            .putFlag(prefix + BATTERY_MANAGER_MILLIAMPS, values.batteryManagerMilliamps)
            .putFlag(prefix + SYSFS_DISCHARGE_POSITIVE, values.dischargePositive)
            .putFlag(prefix + BATTERY_MANAGER_DISCHARGE_POSITIVE, values.batteryManagerDischargePositive)
            .putFlag(prefix + PER_CELL_CURRENT, values.perCellCurrent)
            .putFlag(prefix + SYSFS_MICROVOLTS, values.sysfsMicrovolts)

    private fun SharedPreferences.Editor.putFlag(
        key: String,
        value: Boolean?,
    ): SharedPreferences.Editor = if (value == null) this else putBoolean(key, value)

    companion object {
        private const val PREFS_NAME = "power_calibration"
        private const val KEY_FINGERPRINT = "buildFingerprint"
        private const val LOCK_PREFIX = "lock."
        private const val GUESS_PREFIX = "guess."
        private const val SYSFS_MILLIAMPS = "sysfsMilliamps"
        private const val BATTERY_MANAGER_MILLIAMPS = "batteryManagerMilliamps"
        // The legacy shared dischargePositive has unknown source provenance. Relearn BOTH signs; keep unit/cell evidence.
        private const val SYSFS_DISCHARGE_POSITIVE = "sysfsDischargePositive.v2"
        private const val BATTERY_MANAGER_DISCHARGE_POSITIVE = "batteryManagerDischargePositive.v2"
        private const val PER_CELL_CURRENT = "perCellCurrent"
        private const val SYSFS_MICROVOLTS = "sysfsMicrovolts"

        fun from(context: Context): SharedPreferencesCalibrationStore =
            SharedPreferencesCalibrationStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
