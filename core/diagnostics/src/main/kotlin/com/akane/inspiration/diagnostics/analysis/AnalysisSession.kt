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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext

sealed interface AnalysisState {
    data object Loading : AnalysisState

    /** The selected interval has no resolvable snapshot pair. */
    data object Empty : AnalysisState

    /** [analysis] answers [kind], the selection it was computed for. */
    data class Ready(val analysis: IntervalAnalysis, val kind: ReferenceKind) : AnalysisState

    data class Failed(val message: String) : AnalysisState
}

/**
 * This state as a screen showing [selected] must treat it. A new selection is visible at once, but the session's
 * Loading for it arrives a dispatcher hop later; until then the state is still the previous selection's result,
 * which describes another interval. A [AnalysisState.Ready] for any other selection is therefore Loading.
 */
fun AnalysisState.forSelection(selected: ReferenceKind): AnalysisState =
    if (this is AnalysisState.Ready && kind != selected) AnalysisState.Loading else this

/**
 * Keeps one [AnalysisState] for the shared [selection]. Recomputes when the selection or [refreshTicks]
 * changes, off the caller's thread on [dispatcher]. A refresh keeps the previous state until the new one
 * is ready; only a changed selection (or the first run) goes through [AnalysisState.Loading]. One instance
 * per process, so the resolver's history read runs once per change rather than once per screen. Nothing is
 * computed while [state] has no collector (e.g. a background-only process start).
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
        // onStart lets the first computation run before any tick has been emitted. Loading and the result
        // go through one ordered stream, so a finished result for the old selection cannot land after the
        // new selection's Loading.
        val pipeline = combine(selection.selected, refreshTicks.onStart { emit(INITIAL_TICK) }) { kind, _ -> kind }
            .transformLatest { kind ->
                if (kind != shown) {
                    shown = kind
                    emit(AnalysisState.Loading)
                }
                emit(compute(kind))
            }
        // Visibility drives work (H13): compute only while someone observes the state. The last state is
        // kept in between, so a returning screen shows it at once and the restart recomputes it.
        _state.subscriptionCount
            .map { it > 0 }
            .distinctUntilChanged()
            .flatMapLatest { observed -> if (observed) pipeline else emptyFlow() }
            .onEach { _state.value = it }
            .launchIn(scope)
    }

    private suspend fun compute(kind: ReferenceKind): AnalysisState = try {
        val analysis = withContext(dispatcher) { analyzer.analyze(kind, nowWallMs()) }
        if (analysis == null) AnalysisState.Empty else AnalysisState.Ready(analysis, kind)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        AnalysisState.Failed(error.message ?: error.javaClass.simpleName)
    }

    private companion object {
        const val INITIAL_TICK = -1L
    }
}
