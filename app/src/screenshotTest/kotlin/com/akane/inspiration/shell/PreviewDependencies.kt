package com.akane.inspiration.shell

import android.graphics.Bitmap
import com.akane.inspiration.dashboard.CockpitDependencies
import com.akane.inspiration.diagnostics.analysis.AnalysisSelection
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.analysis.Timeline
import com.akane.inspiration.diagnostics.dictionary.DiagnosticDictionary
import com.akane.inspiration.health.HealthDependencies
import com.akane.inspiration.ipc.ITelemetryUserService
import com.akane.inspiration.ipc.ShizukuManager
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.sleeplab.SleepLabDependencies
import com.akane.inspiration.telemetry.app.AppEntry
import com.akane.inspiration.telemetry.app.AppLabels
import com.akane.inspiration.telemetry.app.KernelCapacitySnapshot
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.common.Clocks
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
import com.akane.inspiration.telemetry.toolkit.ToolkitActions
import com.akane.inspiration.telemetry.toolkit.ToolkitGateway
import com.akane.inspiration.toolkit.ToolkitDependencies
import com.akane.inspiration.xray.XRayDependencies
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Fakes for previews and screenshots: no Shizuku, no database, no clock. Every read is empty and the
 * toolkit sits on a Shizuku manager that never binds, so a preview can never reach a privileged call.
 */
internal object PreviewDependencies :
    CockpitDependencies,
    XRayDependencies,
    SleepLabDependencies,
    HealthDependencies,
    ToolkitDependencies {
    override val analysis: StateFlow<AnalysisState> = MutableStateFlow(AnalysisState.Empty)
    override val selection = AnalysisSelection()
    override val shizukuState: StateFlow<ShizukuState> = MutableStateFlow(ShizukuState.NotRunning)
    override val clocks: Clocks =
        object : Clocks {
            override fun wallMs() = 0L

            override fun elapsedRealtimeMs() = 0L

            override fun uptimeMs() = 0L

            override fun bootCount() = 0
        }
    override val appLabels: AppLabels =
        object : AppLabels {
            override suspend fun label(packageName: String) = packageName

            override suspend fun icon(packageName: String): Bitmap? = null
        }
    override val toolkit = ToolkitActions(ToolkitGateway(NeverBoundShizuku), EmptyStore, holdBinding = { AutoCloseable {} })

    override val lastRefreshWallMs: StateFlow<Long?> = MutableStateFlow(null)

    override suspend fun refresh(): RefreshResult = RefreshResult.Debounced(0L)

    override fun recomputeAnalysis() = Unit

    override fun requestShizukuPermission() = Unit

    override fun liveReadings(periodMs: Long): Flow<LiveReading> = emptyFlow()

    override fun holdBinding(): AutoCloseable = AutoCloseable {}

    override suspend fun timeline(
        from: Long,
        to: Long,
    ) = Timeline(emptyList(), emptyList(), emptyList(), emptyList(), from, to)

    override suspend fun markers(): List<Marker> = emptyList()

    override suspend fun createMarker(label: String): Long? = null

    override fun dictionary() = DiagnosticDictionary(emptyList())

    override suspend fun kernelBattery(): KernelCapacitySnapshot? = null

    override suspend fun healthSamples(): List<HealthSample> = emptyList()

    override suspend fun chargeSessions(): List<ChargeSessionRecord> = emptyList()

    override suspend fun samplesBetween(
        from: Long,
        to: Long,
    ): List<TelemetrySample> = emptyList()

    override suspend fun historyBetween(
        from: Long,
        to: Long,
    ): List<HistoryEvent> = emptyList()

    override suspend fun installedApps(): List<AppEntry> = emptyList()
}

/** A Shizuku manager that is not running: no service ever binds, so every gateway call is unavailable. */
private object NeverBoundShizuku : ShizukuManager {
    override val state: StateFlow<ShizukuState> = MutableStateFlow(ShizukuState.NotRunning)
    override val telemetryService: StateFlow<ITelemetryUserService?> = MutableStateFlow(null)

    override fun requestPermission() = Unit

    override fun start() = Unit

    override suspend fun <T> withTelemetryService(
        timeoutMs: Long,
        block: suspend (ITelemetryUserService) -> T,
    ): T? = null

    override fun holdBinding(): AutoCloseable = AutoCloseable {}

    override fun stop() = Unit
}

/** An empty in-memory store; the toolkit audit log and everything else read as empty and writes are dropped. */
private object EmptyStore : SnapshotStore {
    override suspend fun insert(snapshot: BatterySnapshot): Long = 0L

    override suspend fun byId(id: Long): BatterySnapshot? = null

    override suspend fun latest(kind: SnapshotKind?): BatterySnapshot? = null

    override suspend fun firstAtOrAfter(
        wallMs: Long,
        kind: SnapshotKind?,
        statsStartMs: Long?,
        bootCount: Int?,
    ): BatterySnapshot? = null

    override suspend fun lastAtOrBefore(
        wallMs: Long,
        kind: SnapshotKind?,
    ): BatterySnapshot? = null

    override suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot? = null

    override suspend fun snapshotsBetween(
        fromWallMs: Long,
        toWallMs: Long,
    ): List<BatterySnapshot> = emptyList()

    override suspend fun insertHistory(
        events: List<HistoryEvent>,
        statsStartMs: Long,
    ) = Unit

    override suspend fun historyBetween(
        fromWallMs: Long,
        toWallMs: Long,
    ): List<HistoryEvent> = emptyList()

    override suspend fun historyCursor(): Pair<Long, Long>? = null

    override suspend fun setHistoryCursor(
        statsStartMs: Long,
        lastHistoryTimeMs: Long,
    ) = Unit

    override suspend fun addMarker(
        snapshotId: Long,
        label: String,
        wallMs: Long,
    ): Long = 0L

    override suspend fun marker(id: Long): Pair<Long, BatterySnapshot>? = null

    override suspend fun markers(): List<Marker> = emptyList()

    override suspend fun insertSamples(samples: List<LiveReading>) = Unit

    override suspend fun samplesBetween(
        from: Long,
        to: Long,
    ): List<TelemetrySample> = emptyList()

    override suspend fun upsertChargeSession(
        statsStartMs: Long,
        segment: ChargeSegment,
        verdict: SegmentVerdict,
    ) = Unit

    override suspend fun chargeSessions(): List<ChargeSessionRecord> = emptyList()

    override suspend fun acceptedEstimatesChronological(limit: Int): List<Double> = emptyList()

    override suspend fun healthSamples(): List<HealthSample> = emptyList()

    override suspend fun insertHealthSample(sample: HealthSample) = Unit

    override suspend fun insertToolkitAction(record: ToolkitActionRecord): Long = 0L

    override suspend fun updateToolkitAction(record: ToolkitActionRecord) = Unit

    override suspend fun toolkitActions(limit: Int): List<ToolkitActionRecord> = emptyList()

    override suspend fun pendingToolkitActions(): List<ToolkitActionRecord> = emptyList()

    override suspend fun recordMiss(
        wallMs: Long,
        trigger: CaptureTrigger,
        reason: String,
    ) = Unit

    override suspend fun prune(nowWallMs: Long) = Unit
}
