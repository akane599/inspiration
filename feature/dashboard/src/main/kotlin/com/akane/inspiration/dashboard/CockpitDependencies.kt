package com.akane.inspiration.dashboard

import com.akane.inspiration.diagnostics.analysis.AnalysisSelection
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.analysis.Timeline
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.store.Marker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the Cockpit screen needs from the app, pinned by docs/plans/phase-5.md ("Feature dependencies").
 * `:app` implements it in AppContainer; the screen never sees the container, the raw gateway or an Intent.
 * Changing a member needs a story-log DECISION.
 */
interface CockpitDependencies {
    val analysis: StateFlow<AnalysisState>
    val selection: AnalysisSelection
    val shizukuState: StateFlow<ShizukuState>
    val clocks: Clocks

    /**
     * Wall time of the last successful refresh in this process, whoever asked for it (this screen, the app-open
     * capture this screen's refresh joined, or the re-run when Shizuku became ready); null before the first. A new
     * value means newly stored history and a moved history horizon, which a timeline read earlier does not show.
     */
    val lastRefreshWallMs: StateFlow<Long?>

    /** FULL capture plus history ingest; debounced to 60 s by the container, so safe to call on start and on pull-to-refresh. */
    suspend fun refresh(): RefreshResult

    /** Re-runs the shared analysis from stored data without a capture; the shown state stays until it is replaced. */
    fun recomputeAnalysis()

    fun requestShizukuPermission()

    /** Cold flow: it reads only while collected. The caller starts and stops it with screen visibility (H13). */
    fun liveReadings(periodMs: Long): Flow<LiveReading>

    /** Keeps the Shizuku service bound; close it when the screen is hidden. */
    fun holdBinding(): AutoCloseable

    suspend fun timeline(
        from: Long,
        to: Long,
    ): Timeline

    suspend fun markers(): List<Marker>

    /** Does a FULL capture, then stores a marker on it. Null when the capture failed. */
    suspend fun createMarker(label: String): Long?
}
