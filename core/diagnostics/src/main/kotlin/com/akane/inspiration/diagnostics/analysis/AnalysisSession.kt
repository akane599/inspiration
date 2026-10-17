package com.akane.inspiration.diagnostics.analysis

import com.akane.inspiration.telemetry.diff.ReferenceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

sealed interface AnalysisState {
    data object Loading : AnalysisState

    /** The selected interval has no resolvable snapshot pair. */
    data object Empty : AnalysisState

    data class Ready(val analysis: IntervalAnalysis) : AnalysisState

    data class Failed(val message: String) : AnalysisState
}

/**
 * Keeps one [AnalysisState] for the shared [selection]. Recomputes when the selection or [refreshTicks]
 * changes, off the caller's thread on [dispatcher]. A refresh keeps the previous state until the new one
 * is ready; only a changed selection (or the first run) goes through [AnalysisState.Loading]. One instance
 * per process, so the resolver's history read runs once per change rather than once per screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisSession(
    private val analyzer: IntervalAnalyzer,
    selection: AnalysisSelection,
    refreshTicks: Flow<Long>,
    scope: CoroutineScope,
    private val nowWallMs: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow<AnalysisState>(AnalysisState.Loading)
    val state: StateFlow<AnalysisState> = _state.asStateFlow()

    init {
        var shown: ReferenceKind? = null
        // onStart lets the first computation run before any tick has been emitted.
        combine(selection.selected, refreshTicks.onStart { emit(INITIAL_TICK) }) { kind, _ -> kind }
            .onEach { kind ->
                if (kind != shown) {
                    shown = kind
                    _state.value = AnalysisState.Loading
                }
            }
            .mapLatest { kind -> compute(kind) }
            .onEach { _state.value = it }
            .launchIn(scope)
    }

    private suspend fun compute(kind: ReferenceKind): AnalysisState = try {
        val analysis = withContext(dispatcher) { analyzer.analyze(kind, nowWallMs()) }
        if (analysis == null) AnalysisState.Empty else AnalysisState.Ready(analysis)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        AnalysisState.Failed(error.message ?: error.javaClass.simpleName)
    }

    private companion object {
        const val INITIAL_TICK = -1L
    }
}
