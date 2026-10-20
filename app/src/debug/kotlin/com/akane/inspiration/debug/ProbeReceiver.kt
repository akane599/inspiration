package com.akane.inspiration.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.os.BatteryManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.akane.inspiration.InspirationApplication
import com.akane.inspiration.diagnostics.dictionary.DiagnosticDictionary
import com.akane.inspiration.diagnostics.dictionary.EntryKind
import com.akane.inspiration.diagnostics.model.AwakeViolation
import com.akane.inspiration.diagnostics.model.Insight
import com.akane.inspiration.diagnostics.model.ModemDrain
import com.akane.inspiration.diagnostics.model.NightVampire
import com.akane.inspiration.diagnostics.model.SensorLeak
import com.akane.inspiration.diagnostics.rules.DiagnosticInput
import com.akane.inspiration.diagnostics.story.AndroidStoryWriter
import com.akane.inspiration.diagnostics.story.BatteryStoryBuilder
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.client.BatteryStatsClient
import com.akane.inspiration.ipc.client.DeviceIdleClient
import com.akane.inspiration.ipc.client.DeviceIdleReader
import com.akane.inspiration.ipc.client.PowerManagerClient
import com.akane.inspiration.ipc.client.PowerStatsClient
import com.akane.inspiration.ipc.model.DeviceIdleSnapshot
import com.akane.inspiration.ipc.model.DumpTarget
import com.akane.inspiration.ipc.model.PowerMonitorSnapshot
import com.akane.inspiration.telemetry.calibration.CalibrationEvidence
import com.akane.inspiration.telemetry.calibration.CalibrationInput
import com.akane.inspiration.telemetry.calibration.CalibrationStart
import com.akane.inspiration.telemetry.calibration.PowerCalibration
import com.akane.inspiration.telemetry.calibration.PowerCalibrator
import com.akane.inspiration.telemetry.calibration.SharedPreferencesCalibrationStore
import com.akane.inspiration.telemetry.capture.CaptureResult
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.diff.ReferenceResolver
import com.akane.inspiration.telemetry.diff.SnapshotDiffer
import com.akane.inspiration.telemetry.interval.IntervalMetrics
import com.akane.inspiration.telemetry.interval.IntervalMetricsCalculator
import com.akane.inspiration.telemetry.interval.intervalHistory
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.store.SnapshotStore
import com.akane.inspiration.telemetry.store.ingestedHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.InputStream
import java.util.Locale

class ProbeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(PROBE_TIMEOUT_MS) {
                    when (action) {
                        ACTION_PROBE -> runProbe(context.applicationContext)
                        ACTION_PROBE_CAPTURE -> probeCapture(context.applicationContext)
                        ACTION_PROBE_DIFF -> probeDiff(context.applicationContext, intent)
                        ACTION_PROBE_INSIGHTS -> probeInsights(context.applicationContext, intent)
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
            val calibrationStart = runCatching { SharedPreferencesCalibrationStore.from(context).load() }.getOrNull()
            val calibration = probeCalibration(calibrationStart, calibrationInput)
            val reading = PowerCalibrator().toReading(calibrationInput, calibration)
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
                        .put("calibrationRestored", calibrationStart != null)
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

            val power = readPowerMonitorsInProcess()
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

            val idle = readDeviceIdleInProcess()
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
        val result =
            app.appContainer.capturer.captureFull(
                trigger = CaptureTrigger.MARKER,
                deadlineMs = PROBE_TIMEOUT_MS,
                ingestHistory = true,
            )
        // Like every history-ingesting capture: a screen open meanwhile re-reads what it stored.
        if (result is CaptureResult.Saved) app.appContainer.storeTicks.tick()
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

    /** The snapshot pair a diff probe works on, with the covered bounds and per-UID availability. */
    private class ProbeDiffInput(
        val ref: BatterySnapshot,
        val now: BatterySnapshot,
        val coveredFromWallMs: Long,
        val coveredToWallMs: Long,
        val perUidAvailable: Boolean,
    )

    /** Resolves the PROBE_DIFF / PROBE_INSIGHTS snapshot pair; logs the reason under [section] when none exists. */
    private suspend fun resolveProbeDiffInput(
        store: SnapshotStore,
        intent: Intent,
        section: String,
    ): ProbeDiffInput? {
        val latest = store.latest()
        if (latest == null) {
            log(JSONObject().put(section, JSONObject().put("available", false).put("reason", "no_snapshots")))
            return null
        }
        val resolved =
            intent
                .getStringExtra(EXTRA_REFERENCE_KIND)
                ?.let(::referenceKind)
                ?.let { ReferenceResolver(store).resolve(it, latest.clock.wallMs) }
        val input =
            if (resolved != null) {
                ProbeDiffInput(
                    resolved.ref,
                    resolved.now,
                    resolved.coveredFromWallMs,
                    resolved.coveredToWallMs,
                    resolved.perUidAvailable,
                )
            } else {
                store.lastAtOrBefore(latest.clock.wallMs - 1, null)?.let { ref ->
                    ProbeDiffInput(
                        ref,
                        latest,
                        ref.clock.wallMs,
                        latest.clock.wallMs,
                        ref.kind == SnapshotKind.FULL && latest.kind == SnapshotKind.FULL,
                    )
                }
            }
        if (input == null) {
            log(JSONObject().put(section, JSONObject().put("available", false).put("reason", "no_reference")))
        }
        return input
    }

    // Usage: adb shell am broadcast -a com.akane.inspiration.debug.PROBE_INSIGHTS [--es reference_kind since_unplugged] -n com.akane.inspiration/.debug.ProbeReceiver
    private suspend fun probeInsights(
        context: Context,
        intent: Intent,
    ) {
        val app = context as InspirationApplication
        val container = app.appContainer
        val input = resolveProbeDiffInput(container.store, intent, "insights") ?: return
        val metrics = container.store.probeIntervalMetrics(input.now, input.coveredFromWallMs, input.coveredToWallMs)
        if (metrics == null) {
            log(JSONObject().put("insights", JSONObject().put("available", false).put("reason", "zero_length")))
            return
        }
        val diff = SnapshotDiffer.diff(input.ref, input.now)
        val report = container.ruleEngine.evaluate(DiagnosticInput(diff, metrics, input.perUidAvailable))
        val story = BatteryStoryBuilder().build(metrics, diff.sleep, report, endsNow = true)

        log(JSONObject().put("insightsMetrics", metrics.toJson().put("perUidAvailable", input.perUidAvailable)))
        report.insights.forEach { log(JSONObject().put("insight", it.toJson())) }
        log(
            JSONObject().put(
                "insightsReport",
                JSONObject()
                    .put("count", report.insights.size)
                    .put("notEvaluable", JSONObject().apply { report.notEvaluable.forEach { (rule, why) -> put(rule.name, why) } })
                    .put("skippedInvalidUids", report.skippedInvalidUids),
            ),
        )
        log(
            JSONObject().put(
                "story",
                JSONObject()
                    .put("sentences", JSONArray(story.sentences.map { it.javaClass.simpleName }))
                    .put("en", AndroidStoryWriter(app).render(story, Locale.ENGLISH)),
            ),
        )

        val dictionary = container.dictionary
        val partials =
            diff.uids
                .filter { it.valid }
                .flatMap { it.partialWakelocks }
                .groupBy { it.name }
                .mapValues { (_, locks) -> locks.sumOf { it.durationMs } }
                .entries
                .sortedByDescending { it.value }
                .take(TOP_LOOKUPS)
        val kernels = diff.kernelWakelocks.sortedByDescending { it.durationMs }.take(TOP_LOOKUPS)
        log(
            JSONObject().put(
                "dictionary",
                JSONObject()
                    .put("partial", dictionaryLookups(dictionary, EntryKind.PARTIAL, partials.map { it.key to it.value }))
                    .put("kernel", dictionaryLookups(dictionary, EntryKind.KERNEL, kernels.map { it.name to it.durationMs })),
            ),
        )
    }

    private fun dictionaryLookups(
        dictionary: DiagnosticDictionary,
        kind: EntryKind,
        names: List<Pair<String, Long>>,
    ): JSONArray =
        JSONArray().apply {
            names.forEach { (name, durationMs) ->
                val explanation = dictionary.explain(kind, name, Locale.ENGLISH)
                put(
                    JSONObject()
                        .put("name", name)
                        .put("durationMs", durationMs)
                        .putNullable("id", explanation?.id)
                        .putNullable("title", explanation?.title),
                )
            }
        }

    private fun IntervalMetrics.toJson(): JSONObject =
        JSONObject()
            .put("fromWallMs", fromWallMs)
            .put("toWallMs", toWallMs)
            .put("screenOnMs", screenOnMs)
            .put("screenOffMs", screenOffMs)
            .put("unknownMs", unknownMs)
            .put("chargingMs", chargingMs)
            .putNullable("startLevel", startLevel)
            .putNullable("endLevel", endLevel)
            .put("levelUsedPct", levelUsedPct)
            .put("levelDropScreenOn", levelDropScreenOn)
            .put("levelDropScreenOff", levelDropScreenOff)
            .putNullable("screenOnPctPerHour", screenOnPctPerHour)
            .putNullable("screenOffPctPerHour", screenOffPctPerHour)
            .put("discontinuities", JSONArray(discontinuities))

    private fun Insight.toJson(): JSONObject {
        val json =
            JSONObject()
                .put("rule", rule.name)
                .put("severity", severity.name)
                .putNullable("uid", subjectUid)
        when (this) {
            is NightVampire -> {
                json
                    .putNullable("packageName", packageName)
                    .put("totalMah", totalMah)
                    .put("bgShare", bgShare)
                    .put("fgActivityMs", fgActivityMs)
            }

            is AwakeViolation -> {
                json
                    .put("awakeRatio", awakeRatio)
                    .put("screenOffMs", screenOffMs)
                    .put("culpritScope", culpritScope.name)
                    .put("culpritCount", culprits.size)
                    .put(
                        "culprits",
                        JSONArray().apply {
                            culprits.take(MAX_LOGGED_CULPRITS).forEach { culprit ->
                                put(
                                    JSONObject()
                                        .put("kind", culprit.kind.name)
                                        .put("name", culprit.name)
                                        .putNullable("uid", culprit.uid)
                                        .putNullable("durationMs", culprit.durationMs)
                                        .putNullable("count", culprit.count),
                                )
                            }
                        },
                    )
            }

            is ModemDrain -> {
                json
                    .put("mobileRadioMah", mobileRadioMah)
                    .put("screenMah", screenMah)
                    .put("signalAvailable", signalAvailable)
                    .put("signalBinPct", JSONArray(signalBinPct))
                    .putNullable("scanningMs", scanningMs)
                    .putNullable("radioActiveMs", radioActiveMs)
            }

            is SensorLeak -> {
                json
                    .putNullable("packageName", packageName)
                    .put("sensor", sensor.name)
                    .put("durationMs", durationMs)
            }
        }
        return json
    }

    private suspend fun probeDiff(
        context: Context,
        intent: Intent,
    ) {
        val app = context as InspirationApplication
        val input = resolveProbeDiffInput(app.appContainer.store, intent, "diff") ?: return
        val diff = SnapshotDiffer.diff(input.ref, input.now)
        log(
            JSONObject().put(
                "diff",
                JSONObject()
                    .put("available", true)
                    .put("reference", intent.getStringExtra(EXTRA_REFERENCE_KIND) ?: "latest_two")
                    .put("durationMs", diff.durationMs)
                    .put("batteryStatsValid", diff.validity.batteryStats)
                    .put("kernelCountersValid", diff.validity.kernelCounters)
                    .put("invalidUidCount", diff.uids.count { !it.valid })
                    .put("problems", JSONArray(diff.validity.problems)),
            ),
        )
        logJournalMode(app)
    }

    private fun referenceKind(value: String): ReferenceKind? =
        when (value) {
            "since_unplugged" -> ReferenceKind.SinceUnplugged
            "since_screen_off" -> ReferenceKind.SinceScreenOff
            "screen_on_session" -> ReferenceKind.ScreenOnSession
            "last_24h" -> ReferenceKind.Last24h
            else -> null
        }

    private fun logJournalMode(app: InspirationApplication) {
        SQLiteDatabase
            .openDatabase(
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

    /**
     * The app process exempts only Sensor.getHandle() (N-26). The probe's in-process reads link hidden framework
     * interfaces directly, so it exempts exactly those, and only when a probe runs. Exemptions accumulate, so
     * calling this before each read is harmless.
     */
    private fun exemptInProcessHiddenApis() {
        HiddenApiBypass.addHiddenApiExemptions(*IN_PROCESS_HIDDEN_API_PREFIXES)
    }

    /**
     * Power monitors through Shizuku's binder wrapper. The user service no longer has a transaction for this
     * (txn 4 is reserved): only this probe reads them.
     */
    private fun readPowerMonitorsInProcess(): PowerMonitorSnapshot {
        exemptInProcessHiddenApis()
        return PowerStatsClient(binderSource = { shizukuSystemService(POWER_STATS_SERVICE) }).read()
    }

    /** Idle flags and the power-save whitelist through Shizuku's binder wrapper (txn 5 is reserved). */
    private fun readDeviceIdleInProcess(): DeviceIdleSnapshot {
        exemptInProcessHiddenApis()
        return DeviceIdleReader(
            deviceIdle = DeviceIdleClient { shizukuSystemService(DEVICE_IDLE_SERVICE) },
            powerManager = PowerManagerClient { shizukuSystemService(POWER_SERVICE) },
        ).read()
    }

    private fun readInProcess(): JSONObject {
        // D1 comparison: the BatteryStatsClient read in-process instead of through the user service.
        exemptInProcessHiddenApis()
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
            is ShizukuState.UnsupportedServer -> "UnsupportedServer"
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
        const val ACTION_PROBE_INSIGHTS = "com.akane.inspiration.debug.PROBE_INSIGHTS"
        const val ACTION_REQUEST_PERMISSION = "com.akane.inspiration.debug.PROBE_REQUEST_PERMISSION"
        val ACTIONS =
            setOf(
                ACTION_PROBE,
                ACTION_PROBE_CAPTURE,
                ACTION_PROBE_DIFF,
                ACTION_PROBE_INSIGHTS,
                ACTION_REQUEST_PERMISSION,
            )
        const val MAX_LOGGED_CULPRITS = 5
        const val TOP_LOOKUPS = 3
        const val EXTRA_REFERENCE_KIND = "reference_kind"
        const val BATTERY_STATS_SERVICE = "batterystats"
        const val POWER_STATS_SERVICE = "powerstats"
        const val DEVICE_IDLE_SERVICE = "deviceidle"
        const val POWER_SERVICE = "power"
        const val DATABASE_NAME = "inspiration.db"
        const val MAX_DUMP_BYTES = 4 * 1024 * 1024
        const val MAX_HISTORY_EVENTS = 4096
        const val PROBE_TIMEOUT_MS = 10_000L
        const val TAG = "InspirationProbe"
        const val UNKNOWN_STATUS = -1

        /**
         * The hidden classes the in-process reads link: BatteryStatsClient's `:core:hidden-api-stub` battery
         * surface, then the power-stats, device-idle and power-manager binder interfaces (each prefix also covers
         * the interface's `$Stub`). `android.os.PowerMonitor` is public API 35 and needs no exemption.
         */
        val IN_PROCESS_HIDDEN_API_PREFIXES =
            arrayOf(
                "Lcom/android/internal/app/IBatteryStats",
                "Lcom/android/internal/os/BatteryStatsHistoryIterator",
                "Landroid/os/BatteryStats",
                "Landroid/os/BatteryUsageStats",
                "Landroid/os/BatteryConsumer",
                "Landroid/os/UidBatteryConsumer",
                "Landroid/os/AggregateBatteryConsumer",
                "Landroid/os/IPowerStatsService",
                "Landroid/os/IDeviceIdleController",
                "Landroid/os/IPowerManager",
            )
    }
}

/**
 * The live-current calibration rules (LiveTelemetry) for one probe reading: the units, sign and cell layout come from
 * every reading the app has settled ([start], the persisted [SharedPreferencesCalibrationStore]) plus [input], never
 * from [input] alone. The probe only reads the store: a debug probe must not move the app's calibration.
 */
internal fun probeCalibration(
    start: CalibrationStart?,
    input: CalibrationInput,
): PowerCalibration = CalibrationEvidence(start ?: CalibrationStart()).apply { add(input) }.calibration()

/**
 * Metrics for a probe pair under IntervalAnalyzer's rules, which only take a reference kind, not the probe's
 * latest-two pair: null when no wall time passes between the snapshots (compute needs `to >= from`, and a zero-length
 * pair measures nothing), state past the history horizon unknown, and only the interval's own history read (R-15).
 */
internal suspend fun SnapshotStore.probeIntervalMetrics(
    now: BatterySnapshot,
    fromWallMs: Long,
    toWallMs: Long,
): IntervalMetrics? {
    if (toWallMs <= fromWallMs) return null
    val horizon = if (now.ingestedHistory()) now.clock.wallMs else historyHorizonWallMs() ?: Long.MIN_VALUE
    return IntervalMetricsCalculator.compute(intervalHistory(fromWallMs, toWallMs), fromWallMs, toWallMs, horizon)
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
