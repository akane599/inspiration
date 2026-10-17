package com.akane.inspiration.ipc.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ServiceManager
import android.os.SystemClock
import androidx.annotation.Keep
import com.akane.inspiration.ipc.ITelemetryUserService
import com.akane.inspiration.ipc.client.BatteryStatsClient
import com.akane.inspiration.ipc.client.DeviceIdleClient
import com.akane.inspiration.ipc.client.DeviceIdleReader
import com.akane.inspiration.ipc.client.PowerManagerClient
import com.akane.inspiration.ipc.client.PowerStatsClient
import com.akane.inspiration.ipc.model.BatteryHistorySnapshot
import com.akane.inspiration.ipc.model.BatteryUsageSnapshot
import com.akane.inspiration.ipc.model.BgDexoptReason
import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.model.BgDexoptStatus
import com.akane.inspiration.ipc.model.ClusterFreqTime
import com.akane.inspiration.ipc.model.CpuTimeSnapshot
import com.akane.inspiration.ipc.model.DeviceIdleSnapshot
import com.akane.inspiration.ipc.model.DumpResult
import com.akane.inspiration.ipc.model.DumpTarget
import com.akane.inspiration.ipc.model.KernelBatterySnapshot
import com.akane.inspiration.ipc.model.PowerMonitorSnapshot
import com.akane.inspiration.ipc.model.Reasons
import com.akane.inspiration.ipc.model.ToolkitApi
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.toolkit.AndroidPackageInspector
import com.akane.inspiration.ipc.toolkit.AndroidPluggedProbe
import com.akane.inspiration.ipc.toolkit.BgDexoptController
import com.akane.inspiration.ipc.toolkit.CommandRunner
import com.akane.inspiration.ipc.toolkit.NoContextInspector
import com.akane.inspiration.ipc.toolkit.NoContextPluggedProbe
import com.akane.inspiration.ipc.toolkit.ProcessCommandRunner
import com.akane.inspiration.ipc.toolkit.ToolkitExecutor
import java.io.File
import java.io.FileDescriptor
import java.io.IOException

internal interface DumpPipe {
    val readEnd: ParcelFileDescriptor?
    val writeDescriptor: FileDescriptor

    fun closeRead()
    fun closeWrite()
}

private class ParcelFileDescriptorDumpPipe private constructor(
    private val read: ParcelFileDescriptor,
    private val write: ParcelFileDescriptor,
) : DumpPipe {
    override val readEnd: ParcelFileDescriptor = read
    override val writeDescriptor: FileDescriptor = write.fileDescriptor

    override fun closeRead() {
        read.closeQuietly()
    }

    override fun closeWrite() {
        write.closeQuietly()
    }

    private fun ParcelFileDescriptor.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
            // The peer may have closed the pipe after receiving a failed dump.
        }
    }

    companion object {
        fun create(): DumpPipe {
            val pipe = ParcelFileDescriptor.createPipe()
            return ParcelFileDescriptorDumpPipe(pipe[0], pipe[1])
        }
    }
}

/**
 * Shell-UID Shizuku service. All binder entry points reject callers other than this app before
 * accessing framework services or sysfs.
 */
class TelemetryUserService private constructor(
    private val appUid: Int?,
    private val fileRoot: File,
    private val callingUid: () -> Int,
    private val elapsedRealtimeMs: () -> Long,
    private val serviceBinder: (String) -> IBinder?,
    private val pipeFactory: () -> DumpPipe,
    private val startThread: (Runnable) -> Unit,
    private val toolkit: ToolkitExecutor,
    private val exitProcess: () -> Unit,
    @Suppress("unused") constructionMarker: Unit,
) : ITelemetryUserService.Stub() {
    /** Shizuku's fallback construction path has no Context, so it deliberately fails closed. */
    constructor() : this(
        appUid = null,
        fileRoot = File("/"),
        callingUid = Binder::getCallingUid,
        elapsedRealtimeMs = SystemClock::elapsedRealtime,
        serviceBinder = ServiceManager::getService,
        pipeFactory = ParcelFileDescriptorDumpPipe::create,
        startThread = { runnable -> Thread(runnable).start() },
        toolkit = failClosedToolkit(),
        exitProcess = { System.exit(0) },
        constructionMarker = Unit,
    )

    /** Shizuku API v13+ construction path; this is the only path that can authorize callers. */
    @Keep
    constructor(context: Context) : this(
        appUid = resolveApplicationUid(context),
        fileRoot = File("/"),
        callingUid = Binder::getCallingUid,
        elapsedRealtimeMs = SystemClock::elapsedRealtime,
        serviceBinder = ServiceManager::getService,
        pipeFactory = ParcelFileDescriptorDumpPipe::create,
        startThread = { runnable -> Thread(runnable).start() },
        toolkit = createToolkit(context),
        exitProcess = { System.exit(0) },
        constructionMarker = Unit,
    )

    internal constructor(
        appUid: Int?,
        fileRoot: File,
        callingUid: () -> Int,
        elapsedRealtimeMs: () -> Long = { 0L },
        serviceBinder: (String) -> IBinder? = { null },
        pipeFactory: () -> DumpPipe = ParcelFileDescriptorDumpPipe::create,
        startThread: (Runnable) -> Unit = { runnable -> Thread(runnable).start() },
        toolkit: ToolkitExecutor = failClosedToolkit(),
        exitProcess: () -> Unit = { System.exit(0) },
    ) : this(
        appUid,
        fileRoot,
        callingUid,
        elapsedRealtimeMs,
        serviceBinder,
        pipeFactory,
        startThread,
        toolkit,
        exitProcess,
        Unit,
    )

    override fun destroy() {
        // Shizuku's server (same shell UID as this process) calls destroy() when the app unbinds with
        // remove=true; without accepting it the process leaks and answers later calls with stale state.
        if (!isCallerAllowed() && !isCalledByOwnUid()) return
        // The bg-dexopt unplug/cap guard dies with this process; cancel a running job (bounded) first.
        try {
            toolkit.cancelOnShutdown()
        } finally {
            exitProcess()
        }
    }

    private fun isCalledByOwnUid(): Boolean = try {
        callingUid() == Process.myUid()
    } catch (_: RuntimeException) {
        false
    }

    override fun readKernelBattery(): KernelBatterySnapshot {
        if (!isCallerAllowed()) return KernelBatterySnapshot.unavailable(Reasons.CALLER)

        val batteryDirectory = batterySupplyDirectory() ?: return KernelBatterySnapshot.unavailable(Reasons.IO)
        val snapshot = KernelBatterySnapshot(
            available = true,
            reason = null,
            currentNowRaw = batteryDirectory.longNode("current_now"),
            voltageNowRaw = batteryDirectory.longNode("voltage_now"),
            chargeCounterRaw = batteryDirectory.longNode("charge_counter"),
            chargeFullRaw = batteryDirectory.longNode("charge_full"),
            chargeFullDesignRaw = batteryDirectory.longNode("charge_full_design"),
            cycleCount = batteryDirectory.intNode("cycle_count"),
            temperatureDeciC = batteryDirectory.intNode("temp"),
            status = batteryDirectory.textNode("status"),
            capacityPercent = batteryDirectory.intNode("capacity"),
            sourcePath = batteryDirectory.path,
            readAtElapsedMs = elapsedRealtimeMs(),
        )
        return if (snapshot.hasReadableValue()) snapshot else KernelBatterySnapshot.unavailable(Reasons.IO)
    }

    override fun readCpuTimeInState(): CpuTimeSnapshot {
        if (!isCallerAllowed()) return CpuTimeSnapshot.unavailable(Reasons.CALLER)

        val policyDirectory = File(fileRoot, CPU_FREQ_POLICY_PATH)
        val entries = policyDirectory.listFiles()
            ?.filter { it.isDirectory && POLICY_NAME.matches(it.name) }
            ?.sortedBy { it.name }
            ?.flatMap { directory -> parseTimeInState(directory, policyNumber(directory.name)) }
            .orEmpty()
        return if (entries.isEmpty()) CpuTimeSnapshot.unavailable(Reasons.IO) else CpuTimeSnapshot(
            available = true,
            reason = null,
            entries = entries,
        )
    }

    override fun readBatteryUsage(maxAgeMs: Long): BatteryUsageSnapshot {
        if (!isCallerAllowed()) return BatteryUsageSnapshot.unavailable(Reasons.CALLER)
        return BatteryStatsClient(binderSource = { serviceBinder(BATTERY_STATS_SERVICE) }).readUsage(maxAgeMs)
    }

    override fun readBatteryHistory(
        statsStartMs: Long,
        afterHistoryTimeMs: Long,
        maxEvents: Int,
    ): BatteryHistorySnapshot {
        if (!isCallerAllowed()) return BatteryHistorySnapshot.unavailable(Reasons.CALLER)
        return BatteryStatsClient(binderSource = { serviceBinder(BATTERY_STATS_SERVICE) })
            .readHistory(statsStartMs, afterHistoryTimeMs, maxEvents)
    }

    override fun readPowerMonitors(): PowerMonitorSnapshot {
        if (!isCallerAllowed()) return PowerMonitorSnapshot.unavailable(Reasons.CALLER)
        return PowerStatsClient(binderSource = { serviceBinder(POWER_STATS_SERVICE) }).read()
    }

    override fun readDeviceIdle(): DeviceIdleSnapshot {
        if (!isCallerAllowed()) return DeviceIdleSnapshot.unavailable(Reasons.CALLER)
        return DeviceIdleReader(
            deviceIdle = DeviceIdleClient { serviceBinder(DEVICE_IDLE_SERVICE) },
            powerManager = PowerManagerClient { serviceBinder(POWER_SERVICE) },
        ).read()
    }

    override fun openDump(target: Int): DumpResult {
        if (!isCallerAllowed()) return DumpResult.unavailable(Reasons.CALLER)
        if (!DumpTarget.isValid(target)) return DumpResult.unavailable(Reasons.TARGET)
        val dumpRequest = dumpRequest(target) ?: return DumpResult.unavailable(Reasons.TARGET)
        val binder = serviceBinder(dumpRequest.serviceName) ?: return DumpResult.unavailable(Reasons.REMOTE)
        val pipe = try {
            pipeFactory()
        } catch (_: IOException) {
            return DumpResult.unavailable(Reasons.IO)
        }
        try {
            startThread(
                Runnable {
                    try {
                        binder.dump(pipe.writeDescriptor, dumpRequest.args)
                    } catch (_: Exception) {
                        // The caller observes an early EOF when the framework dump fails.
                    } finally {
                        pipe.closeWrite()
                    }
                },
            )
        } catch (_: Throwable) {
            pipe.closeRead()
            pipe.closeWrite()
            return DumpResult.unavailable(Reasons.IO)
        }
        return DumpResult(available = true, reason = null, fd = pipe.readEnd)
    }

    // Typed privileged actions (txns 20-31). The caller-UID check runs first, on the binder thread;
    // a denied caller gets a CALLER refusal with no parsing and no process spawned.
    override fun setAppOp(packageName: String?, op: Int, mode: Int): ToolkitResult =
        guarded { toolkit.setAppOp(packageName, op, mode) }

    override fun getAppOps(packageName: String?): ToolkitResult = guarded { toolkit.getAppOps(packageName) }

    override fun setStandbyBucket(packageName: String?, bucket: Int): ToolkitResult =
        guarded { toolkit.setStandbyBucket(packageName, bucket) }

    override fun getStandbyBucket(packageName: String?): ToolkitResult =
        guarded { toolkit.getStandbyBucket(packageName) }

    override fun applyDozeProfile(profile: Int, expectedBeforeRaw: String?): ToolkitResult =
        guarded { toolkit.applyDozeProfile(profile, expectedBeforeRaw) }

    override fun getDozeState(): ToolkitResult = guarded { toolkit.getDozeState() }

    override fun forceStop(packageName: String?): ToolkitResult = guarded { toolkit.forceStop(packageName) }

    override fun startBgDexopt(): ToolkitResult = guarded { toolkit.startBgDexopt() }

    override fun getBgDexoptStatus(): BgDexoptStatus =
        if (isCallerAllowed()) {
            toolkit.bgDexoptStatus()
        } else {
            BgDexoptStatus(BgDexoptState.UNKNOWN, 0L, 0L, 0, BgDexoptReason.CALLER)
        }

    override fun cancelBgDexopt(): ToolkitResult = guarded { toolkit.cancelBgDexopt() }

    override fun restoreDozeRaw(rawOrNull: String?, expectedCurrentRaw: String?): ToolkitResult =
        guarded { toolkit.restoreDozeRaw(rawOrNull, expectedCurrentRaw) }

    override fun getApiVersion(): Int = if (isCallerAllowed()) ToolkitApi.VERSION else -1

    private inline fun guarded(block: () -> ToolkitResult): ToolkitResult =
        if (isCallerAllowed()) block() else ToolkitResult.refused(ToolkitCode.CALLER)

    private fun isCallerAllowed(): Boolean = try {
        appUid != null && callingUid() == appUid
    } catch (_: RuntimeException) {
        false
    }

    private fun batterySupplyDirectory(): File? {
        val supplies = File(fileRoot, POWER_SUPPLY_PATH).listFiles()
            ?.filter { it.isDirectory && it.textNode("type") == BATTERY_TYPE }
            .orEmpty()
        return supplies.firstOrNull { it.name == PREFERRED_BATTERY_NAME } ?: supplies.firstOrNull()
    }

    private fun parseTimeInState(directory: File, policy: Int): List<ClusterFreqTime> =
        directory.textNode("stats/time_in_state")
            ?.lineSequence()
            ?.mapNotNull { line ->
                val fields = line.trim().split(WHITESPACE, limit = 3)
                val frequencyKhz = fields.getOrNull(0)?.toLongOrNull()
                val ticks = fields.getOrNull(1)?.toLongOrNull()
                if (frequencyKhz == null || ticks == null) null else ClusterFreqTime(
                    policy = policy,
                    freqKhz = frequencyKhz,
                    timeMs = ticks * TICK_DURATION_MS,
                )
            }
            ?.toList()
            .orEmpty()

    private fun policyNumber(name: String): Int = name.removePrefix(POLICY_PREFIX).toInt()

    private fun File.textNode(name: String): String? = try {
        resolve(name).takeIf(File::isFile)?.readText()?.trim()?.takeIf(String::isNotEmpty)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun File.longNode(name: String): Long? = textNode(name)?.toLongOrNull()

    private fun File.intNode(name: String): Int? = textNode(name)?.toIntOrNull()

    private fun KernelBatterySnapshot.hasReadableValue(): Boolean =
        currentNowRaw != null ||
            voltageNowRaw != null ||
            chargeCounterRaw != null ||
            chargeFullRaw != null ||
            chargeFullDesignRaw != null ||
            cycleCount != null ||
            temperatureDeciC != null ||
            status != null ||
            capacityPercent != null

    private data class DumpRequest(val serviceName: String, val args: Array<String>)

    private companion object {
        const val POWER_SUPPLY_PATH = "sys/class/power_supply"
        const val CPU_FREQ_POLICY_PATH = "sys/devices/system/cpu/cpufreq"
        const val BATTERY_TYPE = "Battery"
        const val PREFERRED_BATTERY_NAME = "battery"
        const val POLICY_PREFIX = "policy"
        const val TICK_DURATION_MS = 10L
        val POLICY_NAME = Regex("policy\\d+")
        val WHITESPACE = Regex("\\s+")

        const val BATTERY_STATS_SERVICE = "batterystats"
        const val POWER_STATS_SERVICE = "powerstats"
        const val DEVICE_IDLE_SERVICE = "deviceidle"
        const val POWER_SERVICE = "power"

        fun failClosedToolkit(): ToolkitExecutor {
            val runner = CommandRunner { throw IOException("toolkit unavailable without a Context") }
            return ToolkitExecutor(
                runner = runner,
                inspector = NoContextInspector,
                dexopt = BgDexoptController(
                    runner = runner,
                    plugged = NoContextPluggedProbe,
                    clockMs = System::currentTimeMillis,
                    startThread = { },
                ),
                ownPackage = "",
            )
        }

        fun createToolkit(context: Context): ToolkitExecutor {
            val runner = ProcessCommandRunner()
            val dexopt = BgDexoptController(
                runner = runner,
                plugged = AndroidPluggedProbe(context, runner),
                clockMs = System::currentTimeMillis,
                startThread = { job -> Thread(job, "bg-dexopt").apply { isDaemon = true }.start() },
            )
            return ToolkitExecutor(
                runner = runner,
                inspector = AndroidPackageInspector(context, runner),
                dexopt = dexopt,
                ownPackage = context.packageName,
            )
        }

        fun resolveApplicationUid(context: Context): Int? = try {
            context.packageManager.getPackageUid(context.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: RuntimeException) {
            null
        }

        fun dumpRequest(target: Int): DumpRequest? = when (target) {
            DumpTarget.BATTERYSTATS_PROTO -> DumpRequest(BATTERY_STATS_SERVICE, arrayOf("--proto"))
            DumpTarget.POWER_PROTO -> DumpRequest(POWER_SERVICE, arrayOf("--proto"))
            DumpTarget.ALARM_PROTO -> DumpRequest("alarm", arrayOf("--proto"))
            DumpTarget.DEVICEIDLE_TEXT -> DumpRequest(DEVICE_IDLE_SERVICE, emptyArray())
            else -> null
        }
    }
}
