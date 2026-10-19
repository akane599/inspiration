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
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * [bindTimeoutMs] bounds the wait for a Ready, bound service (a cold process waits out the Shizuku binder push
 * and the service start), [dumpReadTimeoutMs] each dump read; the caller's capture deadline bounds the whole read.
 */
class AndroidTelemetrySource(
    private val context: Context,
    private val shizuku: ShizukuManager,
    private val clocks: Clocks = AndroidClocks(context),
    private val calibrationInputReader: CalibrationInputReader = AndroidCalibrationInputReader(context),
    private val bindTimeoutMs: Long = DEFAULT_BIND_TIMEOUT_MS,
    private val dumpReadTimeoutMs: Long = DEFAULT_DUMP_READ_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TelemetrySource {
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    override suspend fun full(trigger: CaptureTrigger, historyCursor: Pair<Long, Long>?): FullRead? =
        full(trigger, historyCursor) {}

    // light() makes blocking binder calls and UI refreshes call this from Main, so the whole read runs on IO.
    // Every service call goes through binderCall, so a capture deadline ends the read even mid-call.
    override suspend fun full(
        trigger: CaptureTrigger,
        historyCursor: Pair<Long, Long>?,
        onHistoryPage: suspend (BatteryHistorySnapshot) -> Unit,
    ): FullRead? = withContext(ioDispatcher) {
        val light = light()
        shizuku.withTelemetryService(bindTimeoutMs) { service ->
            val usage = binderCall { service.readBatteryUsage(0L) }
            val batteryStats = readBatteryStats(service)
            val alarm = readAlarm(service)
            val kernel = binderCall { service.readKernelBattery() }
            val cpu = binderCall { service.readCpuTimeInState() }
            val history = historyCursor?.let { cursor ->
                readHistory(service, cursor, usage?.statsStartMs ?: 0L, onHistoryPage)
            }.orEmpty()
            FullRead(
                light = light,
                usage = usage,
                batteryStats = batteryStats,
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
        val kernel = shizuku.withTelemetryService(bindTimeoutMs) { service ->
            runCatching { service.readKernelBattery() }.getOrNull()
        } ?: return@withContext null
        // sysfs values come only from this kernel read, never the reader's process-start cache; a missing
        // one makes PowerCalibrator fall back to the fresh BatteryManager current/voltage below.
        val calibration = calibrationInputReader.read().copy(
            sysfsCurrentRaw = kernel?.currentNowRaw,
            sysfsVoltageRaw = kernel?.voltageNowRaw,
            bmCurrentNowUa = light.currentMa?.times(1_000),
            bmVoltageMv = light.voltageMv,
            isCharging = light.status == "charging" || light.status == "full",
            plugged = light.plugged == true,
        )
        KernelRead(kernel, calibration, light)
    }

    private suspend fun readBatteryStats(service: com.akane.inspiration.ipc.ITelemetryUserService): DumpParse<com.akane.inspiration.telemetry.dump.DumpSnapshot> =
        readDump(service, DumpTarget.BATTERYSTATS_PROTO, DumpParser::parseBatteryStats)

    private suspend fun readAlarm(service: com.akane.inspiration.ipc.ITelemetryUserService): DumpParse<com.akane.inspiration.telemetry.dump.AlarmDump> =
        readDump(service, DumpTarget.ALARM_PROTO, DumpParser::parseAlarm)

    private suspend fun <T> readDump(
        service: com.akane.inspiration.ipc.ITelemetryUserService,
        target: Int,
        parser: (ByteArray) -> DumpParse<T>,
    ): DumpParse<T> {
        // An abandoned openDump still returns its pipe; release closes it.
        val dump = binderCall(release = { it.fd?.closeQuietly() }) { service.openDump(target) }
            ?: return DumpParse.Unavailable("remote")
        if (!dump.available) return DumpParse.Unavailable(dump.reason ?: "unavailable")
        val fd = dump.fd ?: return DumpParse.Unavailable("io")
        return when (val bytes = DumpReader.read(fd, timeoutMs = dumpReadTimeoutMs)) {
            is DumpParse.Ok -> parser(bytes.value)
            is DumpParse.Unavailable -> bytes
        }
    }

    /** Hands each page to [onPage] as it arrives, so pages read before a deadline are kept by the caller. */
    private suspend fun readHistory(
        service: com.akane.inspiration.ipc.ITelemetryUserService,
        cursor: Pair<Long, Long>,
        usageStatsStartMs: Long,
        onPage: suspend (BatteryHistorySnapshot) -> Unit,
    ): List<BatteryHistorySnapshot> {
        var statsStartMs = cursor.first.takeIf { it != 0L } ?: usageStatsStartMs
        var afterHistoryTimeMs = cursor.second
        val pages = mutableListOf<BatteryHistorySnapshot>()
        repeat(MAX_HISTORY_PAGES) {
            val page = binderCall {
                service.readBatteryHistory(statsStartMs, afterHistoryTimeMs, HISTORY_PAGE_SIZE)
            } ?: BatteryHistorySnapshot.unavailable("remote")
            onPage(page)
            pages += page
            if (!page.available || !page.truncated || page.events.isEmpty()) return pages
            statsStartMs = page.statsStartMs
            afterHistoryTimeMs = page.events.last().historyTimeMs
        }
        return pages
    }

    /**
     * One blocking service call, run on [ioDispatcher] without tying the caller to it. A Binder transaction
     * ignores Thread.interrupt, so withContext or runInterruptible would hold a cancelled caller, and its capture
     * deadline, until the call returned. Here the caller resumes as soon as it is cancelled; the abandoned call
     * finishes on its IO thread and [release] frees its result. Null when the call throws.
     */
    private suspend fun <T : Any> binderCall(
        release: (T) -> Unit = {},
        call: () -> T?,
    ): T? {
        currentCoroutineContext().ensureActive() // no new call once the deadline has passed
        return suspendCancellableCoroutine { continuation ->
            ioDispatcher.asExecutor().execute {
                val value = runCatching(call).getOrNull()
                continuation.resume(value) { _, unused, _ -> unused?.let(release) }
            }
        }
    }

    private fun java.io.Closeable.closeQuietly() {
        runCatching { close() }
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
        /** Room for a cold process: the Shizuku binder push, then the service process start (~0.3 s warm device). */
        const val DEFAULT_BIND_TIMEOUT_MS = 5_000L
        const val DEFAULT_DUMP_READ_TIMEOUT_MS = 2_000L
        const val MAX_HISTORY_PAGES = 8
        const val HISTORY_PAGE_SIZE = 4_096
    }
}
