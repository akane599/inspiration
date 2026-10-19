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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sleep Lab state holder (H5). It is Activity-scoped, so it outlives the tab. It reads nothing from the device:
 * the only device-touching call is `refresh()`, which the container debounces, and it is sent when the screen
 * becomes visible or the user asks. The analysis arrives through the shared [SleepLabDependencies.analysis]; this
 * class only shapes it, and does that work only while the screen is visible, so a refresh made from another
 * tab costs nothing here until this tab is shown again. The hypnogram's timeline, which spans the whole interval,
 * is read and shaped on [shapingDispatcher], never on the main thread.
 */
class SleepLabViewModel(
    private val deps: SleepLabDependencies,
    private val shapingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(SleepLabState.Initial)
    val state: StateFlow<SleepLabState> = _state.asStateFlow()

    private val effectChannel = Channel<SleepLabEffect>(Channel.BUFFERED)
    val effects: Flow<SleepLabEffect> = effectChannel.receiveAsFlow()

    private val visible = MutableStateFlow(false)
    private var refreshJob: Job? = null

    /** Covered measurement endpoints of the retained chart, independent of the selected reference kind. */
    private var hypnogramWindow: Pair<Long, Long>? = null

    /** The interval whose timeline the hypnogram shows (or failed to read), so its Retry can read it again. */
    private var hypnogramSource: ResolvedReference? = null
    private var hypnogramRetry: Job? = null

    /** This visible visit explicitly joined the running refresh (main thread only, like every intent). */
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

            SleepLabIntent.Hidden -> {
                visible.value = false
                // End this visit's UI ownership without cancelling the shared capture.
                userWaiting = false
                while (effectChannel.tryReceive().isSuccess) Unit
                _state.update { it.copy(refreshing = false) }
            }

            SleepLabIntent.Refresh -> {
                startRefresh(userInitiated = true)
            }

            is SleepLabIntent.SelectTab -> {
                _state.update { it.copy(tab = intent.tab) }
            }

            SleepLabIntent.ClearSlice -> {
                deps.selection.clearSlice()
            }
        }
    }

    /** A different interval is being (re)computed, or has none: the old hypnogram would describe another period. */
    private fun showNotReady(
        analysis: AnalysisUi,
        slice: SliceUi?,
    ) {
        hypnogramWindow = null
        hypnogramSource = null
        _state.update { it.copy(analysis = analysis, hypnogram = HypnogramUi.Loading, slice = slice) }
    }

    private suspend fun showReady(
        analysis: IntervalAnalysis,
        slice: SliceUi?,
    ) {
        val named = withOwnerPackages(analysis)
        val labels = labelsFor(named)
        val window = analysis.resolved.coveredFromWallMs to analysis.resolved.coveredToWallMs
        val sameWindow = hypnogramWindow == window
        hypnogramWindow = window
        hypnogramSource = analysis.resolved
        _state.update {
            it.copy(
                analysis = sleepLabAnalysis(named, labels),
                slice = slice,
                hypnogram = if (sameWindow) it.hypnogram else HypnogramUi.Loading,
            )
        }
        loadHypnogram(analysis.resolved)
    }

    /**
     * batterystats names a UID by one of its process names ("com.app:push"), or not at all; only the UID's installed
     * packages say which app it is. A UID with no installed package (or whose lookup failed) is shown by its UID.
     * Nothing is cached here: [SleepLabDependencies.appLabels] keeps the bounded cache, and a later emission asks again.
     */
    private suspend fun withOwnerPackages(analysis: IntervalAnalysis): IntervalAnalysis {
        val uids =
            analysis.diff.uids.map { uid ->
                val pkg = attempt { deps.appLabels.packageForUid(uid.uid, reported = uid.packageName) }
                if (pkg == uid.packageName) uid else uid.copy(packageName = pkg)
            }
        return analysis.copy(diff = analysis.diff.copy(uids = uids))
    }

    /** Only the packages the tabs will show are looked up; a label that cannot be read falls back to the package. */
    private suspend fun labelsFor(analysis: IntervalAnalysis): Map<String, String> =
        visiblePackages(analysis).mapNotNull { pkg -> attempt { deps.appLabels.label(pkg) }?.let { pkg to it } }.toMap()

    /** The hypnogram's own Retry: re-reads the timeline of the shown interval, which needs no capture. */
    private fun retryHypnogram() {
        val resolved = hypnogramSource ?: return
        if (hypnogramRetry?.isActive == true) return
        _state.update { it.copy(hypnogram = HypnogramUi.Loading) }
        hypnogramRetry = viewModelScope.launch { loadHypnogram(resolved) }
    }

    private suspend fun loadHypnogram(resolved: ResolvedReference) {
        val window = resolved.coveredFromWallMs to resolved.coveredToWallMs
        val hypnogram =
            withContext(shapingDispatcher) {
                attempt { deps.timeline(resolved.coveredFromWallMs, resolved.coveredToWallMs) }?.let(::hypnogramData)
            }
        // A retry that finishes after another interval took over must not show (or fail) that interval's chart.
        if (hypnogramWindow != window) return
        _state.update {
            when {
                hypnogram != null -> it.copy(hypnogram = HypnogramUi.Ready(hypnogram))

                // Only a failed refresh of these exact covered endpoints may retain its ready chart.
                it.hypnogram is HypnogramUi.Ready -> it

                else -> it.copy(hypnogram = HypnogramUi.Failed)
            }
        }
    }

    private fun startRefresh(userInitiated: Boolean) {
        // A user refresh during a running one (e.g. the on-enter refresh) joins it: it shows progress and
        // reports that refresh's failure.
        if (userInitiated && visible.value) {
            userWaiting = true
            _state.update { it.copy(refreshing = true) }
            // Both read only stored data, so retrying a failed one need not wait for (or get) a capture.
            if (deps.analysis.value is AnalysisState.Failed) deps.recomputeAnalysis()
            if (_state.value.hypnogram is HypnogramUi.Failed) retryHypnogram()
        }
        if (refreshJob?.isActive == true) return
        refreshJob =
            viewModelScope.launch {
                try {
                    when (attempt { deps.refresh() }) {
                        is RefreshResult.Refreshed -> {
                            Unit
                        }

                        // No new capture means no refresh tick: what the user asked for is recomputed from what is stored.
                        is RefreshResult.Debounced -> {
                            if (userWaiting && visible.value) deps.recomputeAnalysis()
                        }

                        is RefreshResult.Failed, null -> {
                            if (userWaiting && visible.value) {
                                deps.recomputeAnalysis()
                                effectChannel.trySend(SleepLabEffect.RefreshFailed)
                            }
                        }
                    }
                } finally {
                    userWaiting = false
                    _state.update { it.copy(refreshing = false) }
                }
            }
    }

    companion object {
        fun factory(deps: SleepLabDependencies): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { SleepLabViewModel(deps) }
            }
    }
}
