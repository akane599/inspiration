package com.akane.inspiration.telemetry.source

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.akane.inspiration.ipc.ShizukuManager
import com.akane.inspiration.ipc.model.BatteryHistorySnapshot
import com.akane.inspiration.ipc.model.DumpTarget
import com.akane.inspiration.ipc.model.KernelBatterySnapshot
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.dump.DumpParse
import com.akane.inspiration.telemetry.dump.DumpParser
import com.akane.inspiration.telemetry.dump.DumpReader
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidTelemetrySource(
    private val context: Context,
    private val shizuku: ShizukuManager,
    private val clocks: Clocks = AndroidClocks(context),
    private val calibrationInputReader: CalibrationInputReader = AndroidCalibrationInputReader(context),
    private val serviceTimeoutMs: Long = DEFAULT_SERVICE_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TelemetrySource {
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    override suspend fun full(trigger: CaptureTrigger, historyCursor: Pair<Long, Long>?): FullRead? {
        val light = light()
        return shizuku.withTelemetryService(serviceTimeoutMs) { service ->
            val usage = runCatching { service.readBatteryUsage(0L) }.getOrNull()
            val batteryStats = readBatteryStats(service)
            val power = readPower(service)
            val alarm = readAlarm(service)
            val kernel = runCatching { service.readKernelBattery() }.getOrNull()
            val cpu = runCatching { service.readCpuTimeInState() }.getOrNull()
            val history = historyCursor?.let { cursor ->
                readHistory(service, cursor, usage?.statsStartMs ?: 0L)
            }.orEmpty()
            FullRead(
                light = light,
                usage = usage,
                batteryStats = batteryStats,
                power = power,
                alarm = alarm,
                kernel = kernel,
                cpuTimes = cpu?.entries.orEmpty(),
                history = history,
            )
        }
    }

    override fun light(): LightRead {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val clock = SnapshotClock(
            wallMs = clocks.wallMs(),
            elapsedRealtimeMs = clocks.elapsedRealtimeMs(),
            uptimeMs = clocks.uptimeMs(),
            bootCount = clocks.bootCount(),
            statsStartMs = null,
            statsDurationMs = null,
        )
        val currentUa = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeUnless { it == Int.MIN_VALUE }
        val charge = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeUnless { it == Long.MIN_VALUE }
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return LightRead(
            clock = clock,
            levelPct = intent?.levelPercent(),
            plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 },
            status = status.takeIf { it >= 0 }?.let(::statusName),
            temperatureDeciC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeUnless { it == Int.MIN_VALUE },
            voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
                ?.takeUnless { it == Int.MIN_VALUE },
            currentMa = currentUa?.div(1_000),
            chargeCounterUah = charge,
            thermalStatus = thermalStatus(),
        )
    }

    // Available at minSdk 31; a missing manager or service failure reads as unknown, never as "none".
    private fun thermalStatus(): Int? = runCatching { powerManager?.currentThermalStatus }.getOrNull()

    // The battery-manager reads and the sysfs reads (first call) block, so the whole reading runs on IO.
    override suspend fun kernelReading(): KernelRead? = withContext(ioDispatcher) {
        val light = light()
        val kernel = shizuku.withTelemetryService(serviceTimeoutMs) { service ->
            runCatching { service.readKernelBattery() }.getOrNull()
        } ?: return@withContext null
        val calibration = calibrationInputReader.read().copy(
            sysfsCurrentRaw = kernel?.currentNowRaw ?: calibrationInputReader.read().sysfsCurrentRaw,
            sysfsVoltageRaw = kernel?.voltageNowRaw ?: calibrationInputReader.read().sysfsVoltageRaw,
            bmCurrentNowUa = light.currentMa?.times(1_000),
            bmVoltageMv = light.voltageMv,
            isCharging = light.status == "charging" || light.status == "full",
            plugged = light.plugged == true,
        )
        KernelRead(kernel, calibration, light)
    }

    private suspend fun readBatteryStats(service: com.akane.inspiration.ipc.ITelemetryUserService): DumpParse<com.akane.inspiration.telemetry.dump.DumpSnapshot> =
        readDump(service, DumpTarget.BATTERYSTATS_PROTO, DumpParser::parseBatteryStats)

    private suspend fun readPower(service: com.akane.inspiration.ipc.ITelemetryUserService): DumpParse<com.akane.inspiration.telemetry.dump.PowerDump> =
        readDump(service, DumpTarget.POWER_PROTO, DumpParser::parsePower)

    private suspend fun readAlarm(service: com.akane.inspiration.ipc.ITelemetryUserService): DumpParse<com.akane.inspiration.telemetry.dump.AlarmDump> =
        readDump(service, DumpTarget.ALARM_PROTO, DumpParser::parseAlarm)

    private suspend fun <T> readDump(
        service: com.akane.inspiration.ipc.ITelemetryUserService,
        target: Int,
        parser: (ByteArray) -> DumpParse<T>,
    ): DumpParse<T> {
        val dump = runCatching { service.openDump(target) }.getOrElse { return DumpParse.Unavailable("remote") }
        if (!dump.available) return DumpParse.Unavailable(dump.reason ?: "unavailable")
        val bytes = dump.fd?.let { DumpReader.readAll(it, timeoutMs = serviceTimeoutMs) } ?: return DumpParse.Unavailable("io")
        return parser(bytes)
    }

    private fun readHistory(
        service: com.akane.inspiration.ipc.ITelemetryUserService,
        cursor: Pair<Long, Long>,
        usageStatsStartMs: Long,
    ): List<BatteryHistorySnapshot> {
        var statsStartMs = cursor.first.takeIf { it != 0L } ?: usageStatsStartMs
        var afterHistoryTimeMs = cursor.second
        val pages = mutableListOf<BatteryHistorySnapshot>()
        repeat(MAX_HISTORY_PAGES) {
            val page = runCatching {
                service.readBatteryHistory(statsStartMs, afterHistoryTimeMs, HISTORY_PAGE_SIZE)
            }.getOrElse {
                pages += BatteryHistorySnapshot.unavailable("remote")
                return pages
            }
            pages += page
            if (!page.available || !page.truncated || page.events.isEmpty()) return pages
            statsStartMs = page.statsStartMs
            afterHistoryTimeMs = page.events.last().historyTimeMs
        }
        return pages
    }

    private fun Intent.levelPercent(): Int? {
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }

    private fun statusName(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }

    private companion object {
        const val DEFAULT_SERVICE_TIMEOUT_MS = 2_000L
        const val MAX_HISTORY_PAGES = 8
        const val HISTORY_PAGE_SIZE = 4_096
    }
}
