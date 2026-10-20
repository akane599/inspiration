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
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.diff.ResolvedReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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

    private var visible = false

    /** Follows the analysis and the selection while visible, shaping each one. */
    private var visibleJob: Job? = null
    private var refreshJob: Job? = null

    /** Covered measurement endpoints of the retained chart, independent of the selected reference kind. */
    private var hypnogramWindow: Pair<Long, Long>? = null

    /** The interval whose timeline the hypnogram shows (or failed to read), so its Retry can read it again. */
    private var hypnogramSource: ResolvedReference? = null
    private var hypnogramRetry: Job? = null

    /** This visible visit explicitly joined the running refresh (main thread only, like every intent). */
    private var userWaiting = false

    fun onIntent(intent: SleepLabIntent) {
        when (intent) {
            SleepLabIntent.Visible -> {
                if (!visible) {
                    visible = true
                    // The shared session computes only while observed, so this visit's subscription is what recomputes
                    // an interval whose "now" went stale while nothing showed it, even when the on-enter refresh fails
                    // or is debounced. Hidden ends it, so nothing keeps the session computing in the background.
                    visibleJob =
                        viewModelScope.launch {
                            combine(deps.analysis, deps.selection.selected, ::Pair).collectLatest { (analysis, kind) ->
                                // collectLatest cancels the previous shaping (label lookups, the timeline read) on a new value.
                                show(analysis, kind)
                            }
                        }
                }
                startRefresh(userInitiated = false)
            }

            SleepLabIntent.Hidden -> {
                visible = false
                // Cancels any shaping in flight too: nothing is looked up or read for a screen that is not there.
                visibleJob?.cancel()
                visibleJob = null
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

    private suspend fun show(
        analysis: AnalysisState,
        kind: ReferenceKind,
    ) {
        // Until the session has recomputed after a selection change, its result belongs to the old one.
        val current = analysis.forSelection(kind)
        val slice = sliceUi(kind, current)
        when (current) {
            AnalysisState.Loading -> showNotReady(AnalysisUi.Loading, slice)
            is AnalysisState.Empty -> showNotReady(AnalysisUi.Empty, slice)
            is AnalysisState.Failed -> showNotReady(AnalysisUi.Error, slice)
            is AnalysisState.Ready -> showReady(current.analysis, slice)
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
     *
     * Only the UIDs that the tabs and the culprit card show are looked up (B-17), and a round's lookups run at once.
     * Nothing is cached: [SleepLabDependencies.appLabels] caches labels and icons but reads UID membership fresh, so
     * every emission asks again. The alarm list breaks ties by package, so naming the shown rows can bring an unnamed row
     * into view; the next round names it, until every shown row is named. A UID left out of every list keeps its
     * reported name, which nothing shows.
     */
    private suspend fun withOwnerPackages(analysis: IntervalAnalysis): IntervalAnalysis {
        val reported = analysis.diff.uids.associate { it.uid to it.packageName }
        val owners = HashMap<Int, String?>()
        var named = analysis
        while (true) {
            val unnamed = shownUids(named).filter { it in reported && it !in owners }
            if (unnamed.isEmpty()) return named
            owners +=
                coroutineScope {
                    unnamed
                        .map { uid -> async { uid to attempt { deps.appLabels.packageForUid(uid, reported = reported[uid]) } } }
                        .awaitAll()
                }
            val uids = analysis.diff.uids.map { uid -> if (uid.uid in owners) uid.copy(packageName = owners[uid.uid]) else uid }
            named = analysis.copy(diff = analysis.diff.copy(uids = uids))
        }
    }

    /** The UIDs of the rows the per-app tabs and the culprit card show for [analysis], as it names them now. */
    private fun shownUids(analysis: IntervalAnalysis): Set<Int> {
        val shown = sleepLabAnalysis(analysis, emptyMap())
        return buildSet {
            shown.appWakelocks.rowsOrEmpty().mapTo(this) { it.uid }
            shown.alarms.rowsOrEmpty().mapTo(this) { it.uid }
            (shown.awake as? AwakeCheckUi.Violation)?.culprits.orEmpty().mapNotNullTo(this) { it.uid }
        }
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
        if (userInitiated && visible) {
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
                            if (userWaiting && visible) deps.recomputeAnalysis()
                        }

                        is RefreshResult.Failed, null -> {
                            if (userWaiting && visible) {
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
