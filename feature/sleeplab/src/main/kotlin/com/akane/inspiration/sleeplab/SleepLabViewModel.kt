package com.akane.inspiration.sleeplab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.analysis.IntervalAnalysis
import com.akane.inspiration.diagnostics.analysis.forSelection
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.app.attempt
import com.akane.inspiration.telemetry.app.packageForUid
import com.akane.inspiration.telemetry.diff.ResolvedReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sleep Lab state holder (H5). It is Activity-scoped, so it outlives the tab. It reads nothing from the device:
 * the only device-touching call is `refresh()`, which the container debounces, and it is sent when the screen
 * becomes visible or the user asks. The analysis arrives through the shared [SleepLabDependencies.analysis]; this
 * class only shapes it, and does that work only while the screen is visible, so a refresh made from another
 * tab costs nothing here until this tab is shown again.
 */
class SleepLabViewModel(private val deps: SleepLabDependencies) : ViewModel() {

    private val _state = MutableStateFlow(SleepLabState.Initial)
    val state: StateFlow<SleepLabState> = _state.asStateFlow()

    private val effectChannel = Channel<SleepLabEffect>(Channel.BUFFERED)
    val effects: Flow<SleepLabEffect> = effectChannel.receiveAsFlow()

    private val visible = MutableStateFlow(false)
    private var refreshJob: Job? = null

    /** The user asked for the running refresh (main thread only, like every intent). */
    private var userWaiting = false

    init {
        viewModelScope.launch {
            combine(deps.analysis, deps.selection.selected, visible) { analysis, kind, isVisible ->
                Triple(analysis, kind, isVisible)
            }.collectLatest { (analysis, kind, isVisible) ->
                // collectLatest cancels the previous shaping (label lookups, the timeline read) on a new value.
                if (isVisible) {
                    // Until the session has recomputed after a selection change, its result belongs to the old one.
                    val current = analysis.forSelection(kind)
                    val slice = sliceUi(kind, current)
                    when (current) {
                        AnalysisState.Loading -> showNotReady(AnalysisUi.Loading, slice)
                        AnalysisState.Empty -> showNotReady(AnalysisUi.Empty, slice)
                        is AnalysisState.Failed -> showNotReady(AnalysisUi.Error, slice)
                        is AnalysisState.Ready -> showReady(current.analysis, slice)
                    }
                }
            }
        }
    }

    fun onIntent(intent: SleepLabIntent) {
        when (intent) {
            SleepLabIntent.Visible -> {
                visible.value = true
                startRefresh(userInitiated = false)
            }
            SleepLabIntent.Hidden -> visible.value = false
            SleepLabIntent.Refresh -> startRefresh(userInitiated = true)
            is SleepLabIntent.SelectTab -> _state.update { it.copy(tab = intent.tab) }
            SleepLabIntent.ClearSlice -> deps.selection.clearSlice()
        }
    }

    /** A different interval is being (re)computed, or has none: the old hypnogram would describe another period. */
    private fun showNotReady(analysis: AnalysisUi, slice: SliceUi?) {
        _state.update { it.copy(analysis = analysis, hypnogram = HypnogramUi.Loading, slice = slice) }
    }

    private suspend fun showReady(analysis: IntervalAnalysis, slice: SliceUi?) {
        val named = withOwnerPackages(analysis)
        val labels = labelsFor(named)
        _state.update { it.copy(analysis = sleepLabAnalysis(named, labels), slice = slice) }
        loadHypnogram(analysis.resolved)
    }

    /**
     * batterystats names a UID by one of its process names ("com.app:push"), or not at all; only the UID's installed
     * packages say which app it is. A UID with no installed package (or whose lookup failed) is shown by its UID.
     * Nothing is cached here: [SleepLabDependencies.appLabels] keeps the bounded cache, and a later emission asks again.
     */
    private suspend fun withOwnerPackages(analysis: IntervalAnalysis): IntervalAnalysis {
        val uids = analysis.diff.uids.map { uid ->
            val pkg = attempt { deps.appLabels.packageForUid(uid.uid, reported = uid.packageName) }
            if (pkg == uid.packageName) uid else uid.copy(packageName = pkg)
        }
        return analysis.copy(diff = analysis.diff.copy(uids = uids))
    }

    /** Only the packages the tabs will show are looked up; a label that cannot be read falls back to the package. */
    private suspend fun labelsFor(analysis: IntervalAnalysis): Map<String, String> =
        visiblePackages(analysis).mapNotNull { pkg -> attempt { deps.appLabels.label(pkg) }?.let { pkg to it } }.toMap()

    private suspend fun loadHypnogram(resolved: ResolvedReference) {
        val timeline = attempt { deps.timeline(resolved.coveredFromWallMs, resolved.coveredToWallMs) }
        _state.update {
            when {
                timeline != null -> it.copy(hypnogram = HypnogramUi.Ready(hypnogramData(timeline)))
                // A refresh that could not re-read keeps what is already on screen.
                it.hypnogram is HypnogramUi.Ready -> it
                else -> it.copy(hypnogram = HypnogramUi.Failed)
            }
        }
    }

    private fun startRefresh(userInitiated: Boolean) {
        // A user refresh during a running one (e.g. the on-enter refresh) joins it: it shows progress and
        // reports that refresh's failure.
        if (userInitiated) {
            userWaiting = true
            _state.update { it.copy(refreshing = true) }
        }
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            try {
                when (attempt { deps.refresh() }) {
                    is RefreshResult.Refreshed, is RefreshResult.Debounced -> Unit
                    is RefreshResult.Failed, null -> if (userWaiting) effectChannel.trySend(SleepLabEffect.RefreshFailed)
                }
            } finally {
                userWaiting = false
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    companion object {
        fun factory(deps: SleepLabDependencies): ViewModelProvider.Factory = viewModelFactory {
            initializer { SleepLabViewModel(deps) }
        }
    }
}
