package com.akane.inspiration.shell

import android.graphics.Bitmap
import com.akane.inspiration.dashboard.CockpitDependencies
import com.akane.inspiration.diagnostics.analysis.AnalysisSelection
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.analysis.Timeline
import com.akane.inspiration.diagnostics.dictionary.DiagnosticDictionary
import com.akane.inspiration.health.HealthDependencies
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.model.BgDexoptStatus
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.sleeplab.SleepLabDependencies
import com.akane.inspiration.telemetry.app.AppEntry
import com.akane.inspiration.telemetry.app.AppLabels
import com.akane.inspiration.telemetry.app.KernelCapacitySnapshot
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.Marker
import com.akane.inspiration.telemetry.store.TelemetrySample
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.toolkit.GatewayReply
import com.akane.inspiration.telemetry.toolkit.GatewayUnavailable
import com.akane.inspiration.telemetry.toolkit.ToolkitActions
import com.akane.inspiration.telemetry.toolkit.ToolkitAuditStore
import com.akane.inspiration.telemetry.toolkit.ToolkitBackend
import com.akane.inspiration.toolkit.ToolkitDependencies
import com.akane.inspiration.xray.XRayDependencies
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Fakes for previews and screenshots: no Shizuku, no database, no clock. Every read is empty and every
 * toolkit write is unavailable, so a preview can never reach a privileged call.
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
    override val clocks: Clocks = object : Clocks {
        override fun wallMs() = 0L
        override fun elapsedRealtimeMs() = 0L
        override fun uptimeMs() = 0L
        override fun bootCount() = 0
    }
    override val appLabels: AppLabels = object : AppLabels {
        override suspend fun label(packageName: String) = packageName
        override suspend fun icon(packageName: String): Bitmap? = null
    }
    override val toolkit = ToolkitActions(UnavailableBackend, EmptyAudit, holdBinding = { AutoCloseable {} })

    override suspend fun refresh(): RefreshResult = RefreshResult.Debounced(0L)
    override fun requestShizukuPermission() = Unit
    override fun liveReadings(periodMs: Long): Flow<LiveReading> = emptyFlow()
    override fun holdBinding(): AutoCloseable = AutoCloseable {}
    override suspend fun timeline(from: Long, to: Long) = Timeline(emptyList(), emptyList(), emptyList(), emptyList(), from, to)
    override suspend fun markers(): List<Marker> = emptyList()
    override suspend fun createMarker(label: String): Long? = null
    override fun dictionary() = DiagnosticDictionary(emptyList())
    override suspend fun kernelBattery(): KernelCapacitySnapshot? = null
    override suspend fun healthSamples(): List<HealthSample> = emptyList()
    override suspend fun chargeSessions(): List<ChargeSessionRecord> = emptyList()
    override suspend fun samplesBetween(from: Long, to: Long): List<TelemetrySample> = emptyList()
    override suspend fun historyBetween(from: Long, to: Long): List<HistoryEvent> = emptyList()
    override suspend fun installedApps(): List<AppEntry> = emptyList()
}

private object UnavailableBackend : ToolkitBackend {
    private val unavailable = GatewayReply.Unavailable(GatewayUnavailable.NO_SERVICE)

    override suspend fun setAppOp(pkg: String, op: AppOp, mode: AppOpMode): GatewayReply<ToolkitResult> = unavailable
    override suspend fun getAppOps(pkg: String): GatewayReply<ToolkitResult> = unavailable
    override suspend fun setStandbyBucket(pkg: String, bucket: StandbyBucket): GatewayReply<ToolkitResult> = unavailable
    override suspend fun getStandbyBucket(pkg: String): GatewayReply<ToolkitResult> = unavailable
    override suspend fun applyDozeProfile(profile: DozeProfile, expectedBeforeRaw: String?): GatewayReply<ToolkitResult> = unavailable
    override suspend fun getDozeState(): GatewayReply<ToolkitResult> = unavailable
    override suspend fun restoreDozeRaw(rawOrNull: String?, expectedCurrentRaw: String?): GatewayReply<ToolkitResult> = unavailable
    override suspend fun forceStop(pkg: String): GatewayReply<ToolkitResult> = unavailable
    override suspend fun startBgDexopt(): GatewayReply<ToolkitResult> = unavailable
    override suspend fun bgDexoptStatus(): GatewayReply<BgDexoptStatus> = unavailable
    override suspend fun cancelBgDexopt(): GatewayReply<ToolkitResult> = unavailable
}

private object EmptyAudit : ToolkitAuditStore {
    override suspend fun insert(record: ToolkitActionRecord): Long = 0L
    override suspend fun update(record: ToolkitActionRecord) = Unit
    override suspend fun recent(limit: Int): List<ToolkitActionRecord> = emptyList()
    override suspend fun pending(): List<ToolkitActionRecord> = emptyList()
}
