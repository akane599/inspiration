package com.akane.inspiration.toolkit

import android.graphics.Bitmap
import com.akane.inspiration.ipc.ITelemetryUserService
import com.akane.inspiration.ipc.ShizukuManager
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.model.BgDexoptReason
import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.ToolkitApi
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.app.AppEntry
import com.akane.inspiration.telemetry.app.AppLabels
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.Marker
import com.akane.inspiration.telemetry.store.SnapshotStore
import com.akane.inspiration.telemetry.store.TelemetrySample
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import com.akane.inspiration.telemetry.toolkit.ToolkitActions
import com.akane.inspiration.telemetry.toolkit.ToolkitGateway
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.akane.inspiration.ipc.model.BgDexoptStatus as ServiceDexopt

internal const val CHATTY = "com.example.chatty"
internal const val MAPS = "com.example.maps"
internal const val MAIL = "com.example.mail"
internal const val CALC = "com.example.calc"

internal val DEFAULT_APPS = listOf(
    AppEntry(CHATTY, "Chatty", launchable = true, restricted = false),
    AppEntry(MAPS, "Maps", launchable = true, restricted = false),
    AppEntry(CALC, "Calculator", launchable = true, restricted = false),
    AppEntry(MAIL, "Mail", launchable = false, restricted = true),
)

private const val DEFAULT_EFFECTIVE = "inactive_to=1800000,light_after_inactive_to=60000"

// ---- the device behind the toolkit ----------------------------------------------------------

/** One app as the device holds it. App ops read as `allow` until set, buckets as ACTIVE. */
internal class FakeApp {
    val ops = AppOp.entries.associateWith { "allow" }.toMutableMap()
    var bucket = StandbyBucket.ACTIVE.rawValue
}

/**
 * The privileged service as the toolkit sees it, built with a dynamic proxy (the Binder stub cannot load in
 * plain JVM tests). It holds the device state: reads answer from it and writes change it, so a test can tell
 * what the screen read back from what was merely asked for.
 */
internal class FakeDevice {
    private val apps = mutableMapOf<String, FakeApp>()
    var clock = 1_000L

    fun app(pkg: String): FakeApp = apps.getOrPut(pkg) { FakeApp() }

    var serviceUp = true

    /** While set and not completed, every service call suspends: a test completes it to let an in-flight call finish. */
    var gate: CompletableDeferred<Unit>? = null

    /** `device_idle_constants`; null is unset. */
    var dozeRaw: String? = null

    /** The next doze apply is refused as STALE because somebody else changed the setting meanwhile. */
    var dozeStaleOnce = false

    /** A doze apply that the "system" does not take, so the service rolls it back. */
    var dozeNotEffective = false

    var plugged = true
    var dexopt = ServiceDexopt(BgDexoptState.IDLE, 0, 0, 0, BgDexoptReason.NONE)

    /** Every call about these packages answers a hard-deny refusal. */
    val protectedPackages = mutableSetOf<String>()

    /** Reads still answer for these packages, but every write is refused. */
    val refuseWritesFor = mutableSetOf<String>()

    val calls = mutableListOf<String>()
    val writes = mutableListOf<String>()
    var holds = 0
    var releases = 0
    val store = MemoryStore()

    fun now(): Long {
        clock += 10
        return clock
    }

    private fun denied(pkg: String) = if (pkg in protectedPackages) ToolkitResult.refused(ToolkitCode.DENY_PROTECTED) else null

    private fun writeDenied(pkg: String) = denied(pkg) ?: if (pkg in refuseWritesFor) ToolkitResult.refused(ToolkitCode.DENY_PROTECTED) else null

    private fun dozeCode(): Int = when (dozeRaw) {
        null -> DozeProfile.STANDARD.code
        else -> DozeProfile.entries.firstOrNull { it.constants == dozeRaw }?.code ?: ToolkitCode.DOZE_CUSTOM
    }

    private fun dozeEffective(): String = dozeRaw ?: DEFAULT_EFFECTIVE

    val service: ITelemetryUserService = Proxy.newProxyInstance(
        ITelemetryUserService::class.java.classLoader,
        arrayOf(ITelemetryUserService::class.java),
    ) { _, method, args ->
        if (method.name == "getApiVersion") return@newProxyInstance ToolkitApi.VERSION
        calls += method.name
        when (method.name) {
            "getAppOps" -> {
                val pkg = args[0] as String
                denied(pkg) ?: ToolkitResult.done(after = app(pkg).ops.entries.joinToString(",") { (op, mode) -> "${op.argv}=$mode" })
            }
            "getStandbyBucket" -> {
                val pkg = args[0] as String
                denied(pkg) ?: ToolkitResult.done(after = app(pkg).bucket.toString())
            }
            "setAppOp" -> {
                writes += "setAppOp"
                val pkg = args[0] as String
                writeDenied(pkg) ?: run {
                    val op = AppOp.fromCode(args[1] as Int)!!
                    val mode = AppOpMode.fromCode(args[2] as Int)!!
                    val before = app(pkg).ops.getValue(op)
                    app(pkg).ops[op] = mode.argv
                    ToolkitResult.done(before = before, after = mode.argv)
                }
            }
            "setStandbyBucket" -> {
                writes += "setStandbyBucket"
                val pkg = args[0] as String
                writeDenied(pkg) ?: run {
                    val before = app(pkg).bucket
                    app(pkg).bucket = StandbyBucket.fromCode(args[1] as Int)!!.rawValue
                    ToolkitResult.done(before = before.toString(), after = app(pkg).bucket.toString())
                }
            }
            "forceStop" -> {
                writes += "forceStop"
                writeDenied(args[0] as String) ?: ToolkitResult.done(before = "stopped=false", after = "stopped=true")
            }
            "getDozeState" -> ToolkitResult.done(code = dozeCode(), after = dozeRaw, message = dozeEffective())
            "applyDozeProfile" -> {
                writes += "applyDozeProfile"
                val expected = args[1] as String?
                when {
                    dozeStaleOnce -> {
                        dozeStaleOnce = false
                        dozeRaw = "idle_to=1"
                        ToolkitResult.refused(ToolkitCode.STALE)
                    }
                    expected != dozeRaw -> ToolkitResult.refused(ToolkitCode.STALE)
                    dozeNotEffective -> ToolkitResult.unavailable(ToolkitCode.NOT_EFFECTIVE, before = dozeRaw, after = dozeRaw)
                    else -> {
                        val before = dozeRaw
                        dozeRaw = DozeProfile.fromCode(args[0] as Int)!!.constants
                        ToolkitResult.done(before = before, after = dozeRaw)
                    }
                }
            }
            "restoreDozeRaw" -> {
                writes += "restoreDozeRaw"
                if (args[1] as String? != dozeRaw) {
                    ToolkitResult.refused(ToolkitCode.STALE)
                } else {
                    val before = dozeRaw
                    dozeRaw = args[0] as String?
                    ToolkitResult.done(before = before, after = dozeRaw)
                }
            }
            "startBgDexopt" -> {
                writes += "startBgDexopt"
                when {
                    !plugged -> ToolkitResult.refused(ToolkitCode.NOT_PLUGGED)
                    dexopt.state == BgDexoptState.RUNNING -> ToolkitResult.refused(ToolkitCode.BUSY)
                    else -> {
                        dexopt = ServiceDexopt(BgDexoptState.RUNNING, now(), 0, 0, BgDexoptReason.NONE)
                        ToolkitResult.done()
                    }
                }
            }
            "getBgDexoptStatus" -> dexopt
            "cancelBgDexopt" -> {
                writes += "cancelBgDexopt"
                dexopt = ServiceDexopt(BgDexoptState.CANCELLED, dexopt.startedAtMs, now(), 0, BgDexoptReason.USER)
                ToolkitResult.done()
            }
            else -> null
        }
    } as ITelemetryUserService

    fun count(call: String): Int = calls.count { it == call }
}

private class FakeShizuku(private val device: FakeDevice) : ShizukuManager {
    override val state: StateFlow<ShizukuState> = MutableStateFlow(ShizukuState.Ready(2000, 13))
    override val telemetryService: StateFlow<ITelemetryUserService?> = MutableStateFlow(device.service)

    override fun requestPermission() = Unit

    override fun start() = Unit

    override suspend fun <T> withTelemetryService(timeoutMs: Long, block: suspend (ITelemetryUserService) -> T): T? {
        device.gate?.await()
        return if (device.serviceUp) block(device.service) else null
    }

    override fun holdBinding(): AutoCloseable = AutoCloseable {}

    override fun stop() = Unit
}

/** Keeps the toolkit audit rows in memory; everything else reads as empty. */
internal class MemoryStore : SnapshotStore {
    val rows = mutableListOf<ToolkitActionRecord>()

    override suspend fun insertToolkitAction(record: ToolkitActionRecord): Long {
        val id = rows.size + 1L
        rows += record.copy(id = id)
        return id
    }

    override suspend fun updateToolkitAction(record: ToolkitActionRecord) {
        val index = rows.indexOfFirst { it.id == record.id }
        if (index >= 0) rows[index] = record
    }

    override suspend fun toolkitActions(limit: Int): List<ToolkitActionRecord> = rows.sortedByDescending { it.id }.take(limit)

    override suspend fun pendingToolkitActions(): List<ToolkitActionRecord> =
        rows.filter { it.status == ToolkitActionStatus.PENDING }.sortedBy { it.id }

    override suspend fun insert(snapshot: BatterySnapshot): Long = 0L
    override suspend fun byId(id: Long): BatterySnapshot? = null
    override suspend fun latest(kind: SnapshotKind?): BatterySnapshot? = null
    override suspend fun firstAtOrAfter(wallMs: Long, kind: SnapshotKind?, statsStartMs: Long?, bootCount: Int?): BatterySnapshot? = null
    override suspend fun lastAtOrBefore(wallMs: Long, kind: SnapshotKind?): BatterySnapshot? = null
    override suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot? = null
    override suspend fun insertHistory(events: List<HistoryEvent>, statsStartMs: Long) = Unit
    override suspend fun historyBetween(fromWallMs: Long, toWallMs: Long): List<HistoryEvent> = emptyList()
    override suspend fun historyCursor(): Pair<Long, Long>? = null
    override suspend fun setHistoryCursor(statsStartMs: Long, lastHistoryTimeMs: Long) = Unit
    override suspend fun addMarker(snapshotId: Long, label: String, wallMs: Long): Long = 0L
    override suspend fun marker(id: Long): Pair<Long, BatterySnapshot>? = null
    override suspend fun markers(): List<Marker> = emptyList()
    override suspend fun insertSamples(samples: List<LiveReading>) = Unit
    override suspend fun samplesBetween(from: Long, to: Long): List<TelemetrySample> = emptyList()
    override suspend fun upsertChargeSession(statsStartMs: Long, segment: ChargeSegment, verdict: SegmentVerdict) = Unit
    override suspend fun chargeSessions(): List<ChargeSessionRecord> = emptyList()
    override suspend fun acceptedEstimatesChronological(limit: Int): List<Double> = emptyList()
    override suspend fun healthSamples(): List<HealthSample> = emptyList()
    override suspend fun insertHealthSample(sample: HealthSample) = Unit
    override suspend fun recordMiss(wallMs: Long, trigger: CaptureTrigger, reason: String) = Unit
    override suspend fun prune(nowWallMs: Long) = Unit
}

/**
 * Toolkit's dependencies over a real [ToolkitActions] that talks to a [FakeDevice]; nothing else is real.
 * [scope] runs the toolkit's own hold supervisor and bridge, so a test controls their time.
 */
internal class FakeToolkitDeps(
    shizuku: ShizukuState = ShizukuState.Ready(2000, 13),
    val device: FakeDevice = FakeDevice(),
    scope: CoroutineScope,
) : ToolkitDependencies {
    override val toolkit = ToolkitActions(
        gateway = ToolkitGateway(FakeShizuku(device), holdBridgeScope = scope),
        store = device.store,
        holdBinding = {
            device.holds += 1
            AutoCloseable { device.releases += 1 }
        },
        nowMs = device::now,
        supervisorScope = scope,
    )
    override val appLabels: AppLabels = object : AppLabels {
        override suspend fun label(packageName: String) = DEFAULT_APPS.firstOrNull { it.packageName == packageName }?.label ?: "Label of $packageName"

        override suspend fun icon(packageName: String): Bitmap? = null
    }
    override val shizukuState = MutableStateFlow(shizuku)

    var apps: List<AppEntry> = DEFAULT_APPS
    var appsFailure = false
    var grantRequests = 0

    /** Holds the screen took itself. It must stay 0: `ToolkitActions` holds the binding of a running job. */
    var screenHolds = 0

    override fun requestShizukuPermission() {
        grantRequests += 1
    }

    override suspend fun installedApps(): List<AppEntry> {
        check(!appsFailure) { "package manager unavailable" }
        return apps
    }

    override fun holdBinding(): AutoCloseable {
        screenHolds += 1
        return AutoCloseable {}
    }
}
