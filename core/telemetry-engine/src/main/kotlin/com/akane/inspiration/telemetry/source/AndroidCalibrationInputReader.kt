package com.akane.inspiration.telemetry.source

import android.content.Context
import android.os.BatteryManager
import com.akane.inspiration.telemetry.calibration.CalibrationInput
import java.io.File

fun interface CalibrationInputReader {
    fun read(): CalibrationInput
}

/** Reads calibration-only inputs once; live readings always use the fresh kernel/BatteryManager values. */
class AndroidCalibrationInputReader(
    private val context: Context,
    private val sysfsRoot: File = File("/sys/class/power_supply"),
) : CalibrationInputReader {
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val cached by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { readUncached() }

    override fun read(): CalibrationInput = cached

    private fun readUncached(): CalibrationInput {
        val batteryDirectory = sysfsRoot.listFiles()
            ?.firstOrNull { it.isDirectory && File(it, "type").readTextOrNull()?.trim() == "Battery" }
            ?: File(sysfsRoot, "battery")
        val sticky = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        return CalibrationInput(
            sysfsCurrentRaw = File(batteryDirectory, "current_now").readLongOrNull(),
            sysfsVoltageRaw = File(batteryDirectory, "voltage_now").readLongOrNull(),
            bmCurrentNowUa = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                ?.takeUnless { it == Int.MIN_VALUE },
            bmVoltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
                ?.takeUnless { it == Int.MIN_VALUE },
            isCharging = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in setOf(
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_STATUS_FULL,
            ),
            plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } == true,
        )
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    private fun File.readLongOrNull(): Long? = readTextOrNull()?.trim()?.toLongOrNull()
}
