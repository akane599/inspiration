package com.akane.inspiration.sleeplab

import com.akane.inspiration.diagnostics.analysis.AnalysisSelection
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.analysis.Timeline
import com.akane.inspiration.diagnostics.dictionary.DiagnosticDictionary
import com.akane.inspiration.telemetry.app.AppLabels
import com.akane.inspiration.telemetry.app.RefreshResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the Sleep Lab screen needs from the app, pinned by docs/plans/phase-5.md ("Feature dependencies").
 * `:app` implements it in AppContainer; the screen never sees the container, the raw gateway or an Intent.
 * Changing a member needs a story-log DECISION.
 */
interface SleepLabDependencies {
    val analysis: StateFlow<AnalysisState>
    val selection: AnalysisSelection
    val appLabels: AppLabels

    suspend fun refresh(): RefreshResult

    suspend fun timeline(from: Long, to: Long): Timeline

    /** Already loaded when the app started; cheap to call. */
    fun dictionary(): DiagnosticDictionary
}
