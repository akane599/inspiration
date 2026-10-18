package com.akane.inspiration.xray

import android.graphics.Bitmap
import com.akane.inspiration.diagnostics.analysis.AnalysisSelection
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.analysis.IntervalAnalysis
import com.akane.inspiration.diagnostics.dictionary.DiagnosticDictionary
import com.akane.inspiration.diagnostics.model.DiagnosticReport
import com.akane.inspiration.diagnostics.story.BatteryStory
import com.akane.inspiration.ipc.ITelemetryUserService
import com.akane.inspiration.ipc.ShizukuManager
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.ToolkitApi
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.app.AppLabels
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.diff.ResolvedReference
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.dump.UidWakelock
import com.akane.inspiration.telemetry.interval.IntervalMetrics
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.DiffValidity
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotDiff
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.UidDelta
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal const val CHATTY = "com.example.chatty"
internal const val MAPS = "com.example.maps"
internal const val HOUR_MS = 3_600_000L

internal fun clockAt(wallMs: Long) = SnapshotClock(wallMs, wallMs, wallMs / 2, 1, null, null)

internal fun snapshotAt(wallMs: Long) = BatterySnapshot(
    id = wallMs,
    kind = SnapshotKind.LIGHT,
    trigger = CaptureTrigger.APP_OPEN,
    clock = clockAt(wallMs),
    battery = BatteryState(null, null, null, null, null, null, null, null),
    system = null,
    uids = emptyList(),
    deviceComponentsMah = emptyMap(),
    kernelWakelocks = emptyList(),
    wakeupReasons = emptyList(),
    cpuFreq = emptyList(),
    availability = emptyMap(),
    truncatedUids = 0,
)

internal fun uidDelta(
    uid: Int,
    packageName: String?,
    totalMah: Double,
    components: Map<String, Double> = emptyMap(),
    valid: Boolean = true,
    packageChanged: Boolean = false,
    wakelocks: List<UidWakelock> = emptyList(),
    alarms: List<UidAlarm> = emptyList(),
) = UidDelta(
    uid = uid,
    packageName = packageName,
    packageChanged = packageChanged,
    valid = valid,
    totalMah = totalMah,
    fgMah = null,
    bgMah = null,
    fgsMah = null,
    cachedMah = null,
    componentsMah = components,
    cpuUserMs = 0,
    cpuSystemMs = 0,
    fgActivityMs = 0,
    fgServiceMs = 0,
    partialWakelocks = wakelocks,
    wakeupAlarms = alarms,
    sensors = emptyList(),
)

internal fun diffOf(
    uids: List<UidDelta> = emptyList(),
    components: Map<String, Double> = emptyMap(),
    batteryStats: Boolean = true,
    invalidComponents: Set<String> = emptySet(),
) = SnapshotDiff(
    ref = clockAt(0),
    now = clockAt(HOUR_MS),
    validity = DiffValidity(batteryStats, true, emptyList(), invalidComponents),
    durationMs = HOUR_MS,
    uids = uids,
    deviceComponentsMah = components,
    kernelWakelocks = emptyList(),
    sleep = null,
    levelDropPct = null,
    dischargedMah = null,
    signalStrengthMs = emptyList(),
    signalScanningMs = null,
    mobileRadioActiveMs = null,
)

internal fun resolvedOf(
    fromMs: Long = 0,
    toMs: Long = HOUR_MS,
    perUidAvailable: Boolean = true,
    gapMs: Long = 0,
) = ResolvedReference(
    ref = snapshotAt(fromMs),
    now = snapshotAt(toMs),
    boundaryFromWallMs = fromMs,
    boundaryToWallMs = toMs,
    coveredFromWallMs = fromMs,
    coveredToWallMs = toMs,
    gapMs = gapMs,
    perUidAvailable = perUidAvailable,
)

internal fun readyOf(diff: SnapshotDiff, resolved: ResolvedReference = resolvedOf()): AnalysisState.Ready =
    AnalysisState.Ready(
        IntervalAnalysis(
            resolved = resolved,
            diff = diff,
            metrics = IntervalMetrics(resolved.boundaryFromWallMs, resolved.boundaryToWallMs, 0, 0, 0, 0, null, null, 0, 0, 0, null, null, emptyList()),
            report = DiagnosticReport(emptyList(), emptyMap(), 0),
            story = BatteryStory(emptyList()),
        ),
    )

/** Two apps and a handful of components: enough for ranking, shares and the detail sheet. */
internal fun sampleDiff() = diffOf(
    uids = listOf(
        uidDelta(10_001, MAPS, 20.0, mapOf("SCREEN" to 12.0, "CPU" to 8.0)),
        uidDelta(10_002, CHATTY, 60.0, mapOf("CPU" to 40.0, "MOBILE_RADIO" to 15.0, "WIFI" to 5.0)),
    ),
    components = mapOf("SCREEN" to 50.0, "CPU" to 30.0, "MOBILE_RADIO" to 15.0, "WIFI" to 5.0),
)

// ---- the device behind the toolkit ----------------------------------------------------------

/**
 * The privileged service as the toolkit sees it, built with a dynamic proxy (the Binder stub cannot load in
 * plain JVM tests). It holds the "device" state: reads answer from it and writes change it, so a test can
 * tell what the screen read back from what was merely asked for.
 */
internal class FakeDevice {
    var runAnyInBackground = "allow"
    var bucket = StandbyBucket.ACTIVE.rawValue

    /** When set, every call about a package answers this refusal code. */
    var refuseWith: Int? = null

    /** When set, reads still answer but every write answers this refusal code. */
    var refuseWritesWith: Int? = null
    val calls = mutableListOf<String>()
    val writes = mutableListOf<String>()
    val store = MemoryStore()

    /** When set, every service call suspends until it completes: a slow Binder round trip a test can release. */
    var gate: CompletableDeferred<Unit>? = null

    private fun refusal() = refuseWith?.let { ToolkitResult.refused(it) }

    private fun writeRefusal() = refuseWritesWith?.let { ToolkitResult.refused(it) } ?: refusal()

    private fun appOps() = AppOp.entries.joinToString(",") { op ->
        "${op.argv}=${if (op == AppOp.RUN_ANY_IN_BACKGROUND) runAnyInBackground else "allow"}"
    }

    val service: ITelemetryUserService = Proxy.newProxyInstance(
        ITelemetryUserService::class.java.classLoader,
        arrayOf(ITelemetryUserService::class.java),
    ) { _, method, args ->
        if (method.name == "getApiVersion") return@newProxyInstance ToolkitApi.VERSION
        calls += method.name
        when (method.name) {
            "getAppOps" -> refusal() ?: ToolkitResult.done(after = appOps())
            "getStandbyBucket" -> refusal() ?: ToolkitResult.done(after = bucket.toString())
            "setAppOp" -> {
                writes += "setAppOp"
                writeRefusal() ?: run {
                    val before = runAnyInBackground
                    val mode = AppOpMode.fromCode(args[2] as Int)!!
                    check(AppOp.fromCode(args[1] as Int) == AppOp.RUN_ANY_IN_BACKGROUND)
                    runAnyInBackground = if (mode == AppOpMode.IGNORE) "ignore" else "allow"
                    ToolkitResult.done(before = before, after = runAnyInBackground)
                }
            }
            "setStandbyBucket" -> {
                writes += "setStandbyBucket"
                writeRefusal() ?: run {
                    val before = bucket
                    bucket = StandbyBucket.fromCode(args[1] as Int)!!.rawValue
                    ToolkitResult.done(before = before.toString(), after = bucket.toString())
                }
            }
            "forceStop" -> {
                writes += "forceStop"
                writeRefusal() ?: ToolkitResult.done(before = "stopped=false", after = "stopped=true")
            }
            else -> null
        }
    } as ITelemetryUserService
}

private class FakeShizuku(private val device: FakeDevice) : ShizukuManager {
    private val service = device.service
    override val state: StateFlow<ShizukuState> = MutableStateFlow(ShizukuState.Ready(2000, 13))
    override val telemetryService: StateFlow<ITelemetryUserService?> = MutableStateFlow(service)

    override fun requestPermission() = Unit

    override fun start() = Unit

    override suspend fun <T> withTelemetryService(timeoutMs: Long, block: suspend (ITelemetryUserService) -> T): T? {
        device.gate?.await()
        return block(service)
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
    override suspend fun snapshotsBetween(fromWallMs: Long, toWallMs: Long): List<BatterySnapshot> = emptyList()
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

/** X-Ray's dependencies over a real [ToolkitActions] that talks to a [FakeDevice]; nothing else is real. */
internal class FakeXRayDeps(
    shizuku: ShizukuState = ShizukuState.Ready(2000, 13),
    val device: FakeDevice = FakeDevice(),
) : XRayDependencies {
    override val analysis = MutableStateFlow<AnalysisState>(AnalysisState.Loading)
    override val selection = AnalysisSelection()
    override val shizukuState = MutableStateFlow(shizuku)
    override val toolkit = ToolkitActions(ToolkitGateway(FakeShizuku(device)), device.store, holdBinding = { AutoCloseable {} })
    override val appLabels: AppLabels = object : AppLabels {
        override suspend fun label(packageName: String) = "Label of $packageName"
        override suspend fun icon(packageName: String): Bitmap? {
            iconLoads++
            return null
        }

        override suspend fun packageForUid(uid: Int): String? = packagesByUid[uid]
    }

    /** What `PackageManager` would answer for a UID batterystats reported without a package. */
    val packagesByUid = mutableMapOf<Int, String>()

    /** Icons the ViewModel asked for; rows load their own, so it should ask for none. */
    var iconLoads = 0

    var refreshResult: RefreshResult = RefreshResult.Refreshed(0L)
    var refreshCalls = 0

    /** When set, a refresh suspends until it completes: a capture still running when the screen is left. */
    var refreshGate: CompletableDeferred<Unit>? = null

    override suspend fun refresh(): RefreshResult {
        refreshCalls += 1
        refreshGate?.await()
        return refreshResult
    }

    override fun dictionary() = DiagnosticDictionary(emptyList())
}
