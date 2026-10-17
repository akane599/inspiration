package com.akane.inspiration.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.akane.inspiration.InspirationApplication
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.client.BatteryStatsClient
import com.akane.inspiration.ipc.model.DumpTarget
import com.akane.inspiration.telemetry.calibration.CalibrationInput
import com.akane.inspiration.telemetry.calibration.PowerCalibrator
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.diff.ReferenceResolver
import com.akane.inspiration.telemetry.diff.SnapshotDiffer
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.InputStream

class ProbeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        if (action !in setOf(ACTION_PROBE, ACTION_PROBE_CAPTURE, ACTION_PROBE_DIFF, ACTION_REQUEST_PERMISSION)) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(PROBE_TIMEOUT_MS) {
                    when (action) {
                        ACTION_PROBE -> runProbe(context.applicationContext)
                        ACTION_PROBE_CAPTURE -> probeCapture(context.applicationContext)
                        ACTION_PROBE_DIFF -> probeDiff(context.applicationContext, intent)
                        ACTION_REQUEST_PERMISSION -> requestPermission(context.applicationContext)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                log(JSONObject().put("probe", "timeout"))
            } catch (error: Exception) {
                log(
                    JSONObject()
                        .put("probe", "error")
                        .put("error", error.javaClass.simpleName),
                )
            } finally {
                log(JSONObject().put("probe", "done"))
                pendingResult.finish()
            }
        }
    }

    private suspend fun runProbe(context: Context) {
        val deadlineElapsedMs = SystemClock.elapsedRealtime() + PROBE_TIMEOUT_MS
        val manager = (context as InspirationApplication).appContainer.shizukuManager
        val bindStartedAt = SystemClock.elapsedRealtime()
        manager.start()
        log(JSONObject().put("state", manager.state.value.label()))
        if (manager.state.value !is ShizukuState.Ready) return

        val holder = manager.holdBinding()
        try {
            val service =
                manager.withTelemetryService(
                    (deadlineElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(1L),
                ) { it }
            val bindElapsedMs = SystemClock.elapsedRealtime() - bindStartedAt
            if (service == null) {
                log(JSONObject().put("bind", JSONObject().put("ms", bindElapsedMs).put("bound", false)))
                return
            }
            log(JSONObject().put("bind", JSONObject().put("ms", bindElapsedMs).put("bound", true)))

            val batteryManager = context.getSystemService(BatteryManager::class.java)
            val batteryManagerReading = batteryManagerReading(context, batteryManager)
            val kernel = service.readKernelBattery()
            val calibrationInput =
                CalibrationInput(
                    sysfsCurrentRaw = kernel.currentNowRaw,
                    sysfsVoltageRaw = kernel.voltageNowRaw,
                    bmCurrentNowUa = batteryManagerReading.currentNowUa,
                    bmVoltageMv = batteryManagerReading.voltageMv,
                    isCharging = batteryManagerReading.isCharging,
                    plugged = batteryManagerReading.plugged,
                )
            val calibrator = PowerCalibrator()
            val calibration = calibrator.calibrate(listOf(calibrationInput))
            val reading = calibrator.toReading(calibrationInput, calibration)
            log(
                JSONObject().put(
                    "kernel",
                    JSONObject()
                        .put("available", kernel.available)
                        .putNullable("reason", kernel.reason)
                        .put(
                            "snapshot",
                            JSONObject()
                                .putNullable("currentNowRaw", kernel.currentNowRaw)
                                .putNullable("voltageNowRaw", kernel.voltageNowRaw)
                                .putNullable("chargeCounterRaw", kernel.chargeCounterRaw)
                                .putNullable("chargeFullRaw", kernel.chargeFullRaw)
                                .putNullable("chargeFullDesignRaw", kernel.chargeFullDesignRaw)
                                .putNullable("cycleCount", kernel.cycleCount)
                                .putNullable("temperatureDeciC", kernel.temperatureDeciC)
                                .putNullable("status", kernel.status)
                                .putNullable("capacityPercent", kernel.capacityPercent)
                                .putNullable("sourcePath", kernel.sourcePath)
                                .put("readAtElapsedMs", kernel.readAtElapsedMs),
                        ).put("batteryManager", batteryManagerReading.toJson())
                        .put("calibration", calibration.toJson())
                        .putNullable("reading", reading?.toJson()),
                ),
            )

            val usageStartedAt = SystemClock.elapsedRealtime()
            val usage = service.readBatteryUsage(0L)
            val usageElapsedMs = SystemClock.elapsedRealtime() - usageStartedAt
            log(
                JSONObject().put(
                    "usage",
                    JSONObject()
                        .put("available", usage.available)
                        .put("userServiceMs", usageElapsedMs)
                        .putNullable("reason", usage.reason)
                        .put("dischargedMah", usage.dischargedPowerMah)
                        .put(
                            "topUids",
                            JSONArray().apply {
                                usage.uids.sortedByDescending { it.totalMah }.take(3).forEach { uid ->
                                    put(
                                        JSONObject()
                                            .put("uid", uid.uid)
                                            .putNullable("packageName", uid.packageName)
                                            .put("totalMah", uid.totalMah),
                                    )
                                }
                            },
                        ).put("byProcessStatePresent", usage.uids.any { it.byProcessState != null })
                        .put("statsDurationMs", usage.statsDurationMs)
                        .put("deviceComponentCount", usage.deviceComponents.size)
                        .put("truncatedUids", usage.truncatedUids),
                ),
            )

            val history = service.readBatteryHistory(usage.statsStartMs, 0L, MAX_HISTORY_EVENTS)
            log(
                JSONObject().put(
                    "history",
                    JSONObject()
                        .put("available", history.available)
                        .putNullable("reason", history.reason)
                        .put("count", history.events.size)
                        .put("truncated", history.truncated)
                        .put("sessionChanged", history.sessionChanged)
                        .putNullable("firstKind", history.events.firstOrNull()?.kind)
                        .putNullable("lastKind", history.events.lastOrNull()?.kind),
                ),
            )

            val power = service.readPowerMonitors()
            log(
                JSONObject().put(
                    "power",
                    JSONObject()
                        .put("available", power.available)
                        .putNullable("reason", power.reason)
                        .put("count", power.monitors.size)
                        .put(
                            "first",
                            JSONArray().apply {
                                power.monitors.take(3).forEach { monitor ->
                                    put(
                                        JSONObject()
                                            .put("name", monitor.name)
                                            .put("type", monitor.type)
                                            .put("energyUws", monitor.energyUws)
                                            .put("timestampMs", monitor.timestampMs),
                                    )
                                }
                            },
                        ),
                ),
            )

            val idle = service.readDeviceIdle()
            log(
                JSONObject().put(
                    "idle",
                    JSONObject()
                        .put("available", idle.available)
                        .putNullable("reason", idle.reason)
                        .put("deepIdle", idle.deepIdle)
                        .put("lightIdle", idle.lightIdle)
                        .put("whitelistCount", idle.powerSaveWhitelist.size),
                ),
            )

            val cpu = service.readCpuTimeInState()
            log(
                JSONObject().put(
                    "cpu",
                    JSONObject()
                        .put("available", cpu.available)
                        .putNullable("reason", cpu.reason)
                        .put("entryCount", cpu.entries.size),
                ),
            )

            log(JSONObject().put("dump", readDump(service.openDump(DumpTarget.BATTERYSTATS_PROTO))))

            val warmStartedAt = SystemClock.elapsedRealtime()
            val warm = service.readKernelBattery()
            log(
                JSONObject().put(
                    "warm",
                    JSONObject()
                        .put("ms", SystemClock.elapsedRealtime() - warmStartedAt)
                        .put("available", warm.available)
                        .putNullable("reason", warm.reason),
                ),
            )

            log(JSONObject().put("inproc", readInProcess()))
        } finally {
            holder.close()
        }
    }

    private suspend fun probeCapture(context: Context) {
        val app = context as InspirationApplication
        val result = app.appContainer.capturer.captureFull(
            trigger = CaptureTrigger.MARKER,
            deadlineMs = PROBE_TIMEOUT_MS,
            ingestHistory = true,
        )
        val latest = app.appContainer.store.latest()
        log(
            JSONObject().put(
                "capture",
                JSONObject()
                    .put("result", result.javaClass.simpleName)
                    .putNullable("snapshotId", latest?.id)
                    .putNullable("kind", latest?.kind?.name)
                    .putNullable("trigger", latest?.trigger?.name)
                    .putNullable("wallMs", latest?.clock?.wallMs),
            ),
        )
        logJournalMode(app)
    }

    private suspend fun probeDiff(context: Context, intent: Intent) {
        val app = context as InspirationApplication
        val store = app.appContainer.store
        val latest = store.latest()
        if (latest == null) {
            log(JSONObject().put("diff", JSONObject().put("available", false).put("reason", "no_snapshots")))
            return
        }
        val reference = intent.getStringExtra(EXTRA_REFERENCE_KIND)
            ?.let(::referenceKind)
            ?.let { ReferenceResolver(store).resolve(it, latest.clock.wallMs)?.let { resolved -> resolved.ref to resolved.now } }
            ?: store.lastAtOrBefore(latest.clock.wallMs - 1, null)?.let { it to latest }
        if (reference == null) {
            log(JSONObject().put("diff", JSONObject().put("available", false).put("reason", "no_reference")))
            return
        }
        val diff = SnapshotDiffer.diff(reference.first, reference.second)
        log(
            JSONObject().put(
                "diff",
                JSONObject()
                    .put("available", true)
                    .put("reference", intent.getStringExtra(EXTRA_REFERENCE_KIND) ?: "latest_two")
                    .put("durationMs", diff.durationMs)
                    .put("batteryStatsValid", diff.validity.batteryStats)
                    .put("kernelCountersValid", diff.validity.kernelCounters)
                    .put("problems", JSONArray(diff.validity.problems)),
            ),
        )
        logJournalMode(app)
    }

    private fun referenceKind(value: String): ReferenceKind? = when (value) {
        "since_unplugged" -> ReferenceKind.SinceUnplugged
        "since_screen_off" -> ReferenceKind.SinceScreenOff
        "screen_on_session" -> ReferenceKind.ScreenOnSession
        "last_24h" -> ReferenceKind.Last24h
        else -> null
    }

    private fun logJournalMode(app: InspirationApplication) {
        SQLiteDatabase.openDatabase(
            app.getDatabasePath(DATABASE_NAME).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            database.rawQuery("PRAGMA journal_mode", null).use { cursor ->
                val mode = if (cursor.moveToFirst()) cursor.getString(0) else null
                log(JSONObject().put("database", JSONObject().putNullable("journalMode", mode)))
            }
        }
    }

    private fun requestPermission(context: Context) {
        val manager = (context as InspirationApplication).appContainer.shizukuManager
        manager.start()
        if (manager.state.value is ShizukuState.PermissionRequired) {
            manager.requestPermission()
        }
        log(JSONObject().put("permission", manager.state.value.label()))
    }

    private fun readInProcess(): JSONObject {
        val startedAt = SystemClock.elapsedRealtime()
        val usage = BatteryStatsClient(binderSource = { shizukuSystemService(BATTERY_STATS_SERVICE) }).readUsage(0L)
        return JSONObject()
            .put("available", usage.available)
            .putNullable("reason", usage.reason)
            .put("inProcessMs", SystemClock.elapsedRealtime() - startedAt)
    }

    private fun shizukuSystemService(name: String) = SystemServiceHelper.getSystemService(name)?.let(::ShizukuBinderWrapper)

    private fun readDump(dump: com.akane.inspiration.ipc.model.DumpResult): JSONObject {
        val bytesRead =
            dump.fd?.let { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    input.readAtMost(MAX_DUMP_BYTES)
                }
            }
        return JSONObject()
            .put("available", dump.available)
            .putNullable("reason", dump.reason)
            .putNullable("bytesRead", bytesRead)
    }

    private fun batteryManagerReading(
        context: Context,
        manager: BatteryManager?,
    ): BatteryManagerReading {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, UNKNOWN_STATUS) ?: UNKNOWN_STATUS
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: false
        return BatteryManagerReading(
            currentNowUa = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).validBatteryValue(),
            voltageMv = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE).validBatteryValue(),
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING,
            plugged = plugged,
        )
    }

    private fun Int?.validBatteryValue(): Int? = this?.takeUnless { it == Int.MIN_VALUE || it == 0 }

    private fun JSONObject.putNullable(
        name: String,
        value: Any?,
    ): JSONObject = put(name, value ?: JSONObject.NULL)

    private fun log(value: JSONObject) {
        Log.i(TAG, value.toString())
    }

    private fun ShizukuState.label(): String =
        when (this) {
            ShizukuState.NotInstalled -> "NotInstalled"
            ShizukuState.NotRunning -> "NotRunning"
            ShizukuState.PermissionRequired -> "PermissionRequired"
            ShizukuState.PermissionDenied -> "PermissionDenied"
            is ShizukuState.Ready -> "Ready"
        }

    private data class BatteryManagerReading(
        val currentNowUa: Int?,
        val voltageMv: Int?,
        val isCharging: Boolean,
        val plugged: Boolean,
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("currentNowUa", currentNowUa ?: JSONObject.NULL)
                .put("voltageMv", voltageMv ?: JSONObject.NULL)
                .put("isCharging", isCharging)
                .put("plugged", plugged)
    }

    private fun com.akane.inspiration.telemetry.calibration.PowerCalibration.toJson(): JSONObject =
        JSONObject()
            .put("currentScaleToMa", currentScaleToMa)
            .put("dischargeIsNegative", dischargeIsNegative)
            .put("voltageScaleToMv", voltageScaleToMv)
            .put("cellCount", cellCount)
            .put("confidence", confidence.name)

    private fun com.akane.inspiration.telemetry.calibration.PowerReading.toJson(): JSONObject =
        JSONObject()
            .put("currentMa", currentMa)
            .put("voltageV", voltageV)
            .put("watts", watts)

    private companion object {
        const val ACTION_PROBE = "com.akane.inspiration.debug.PROBE"
        const val ACTION_PROBE_CAPTURE = "com.akane.inspiration.debug.PROBE_CAPTURE"
        const val ACTION_PROBE_DIFF = "com.akane.inspiration.debug.PROBE_DIFF"
        const val ACTION_REQUEST_PERMISSION = "com.akane.inspiration.debug.PROBE_REQUEST_PERMISSION"
        const val EXTRA_REFERENCE_KIND = "reference_kind"
        const val BATTERY_STATS_SERVICE = "batterystats"
        const val DATABASE_NAME = "inspiration.db"
        const val MAX_DUMP_BYTES = 4 * 1024 * 1024
        const val MAX_HISTORY_EVENTS = 4096
        const val PROBE_TIMEOUT_MS = 10_000L
        const val TAG = "InspirationProbe"
        const val UNKNOWN_STATUS = -1
    }
}

private fun InputStream.readAtMost(maxBytes: Int): Int {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (total < maxBytes) {
        val count = read(buffer, 0, minOf(buffer.size, maxBytes - total))
        if (count < 0) break
        total += count
    }
    return total
}
