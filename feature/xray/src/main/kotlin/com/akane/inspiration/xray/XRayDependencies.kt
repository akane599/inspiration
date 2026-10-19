package com.akane.inspiration.xray

import com.akane.inspiration.diagnostics.analysis.AnalysisSelection
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.dictionary.DiagnosticDictionary
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.telemetry.app.AppLabels
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.toolkit.ToolkitActions
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the X-Ray screen needs from the app, pinned by docs/plans/phase-5.md ("Feature dependencies").
 * `:app` implements it in AppContainer; the screen never sees the container, the raw gateway or an Intent.
 * Changing a member needs a story-log DECISION.
 */
interface XRayDependencies {
    val analysis: StateFlow<AnalysisState>
    val selection: AnalysisSelection
    val appLabels: AppLabels
    val toolkit: ToolkitActions
    val shizukuState: StateFlow<ShizukuState>

    suspend fun refresh(): RefreshResult

    /** Re-runs the shared analysis from stored data without a capture; the shown state stays until it is replaced. */
    fun recomputeAnalysis()

    /** Already loaded when the app started; cheap to call. */
    fun dictionary(): DiagnosticDictionary
}
