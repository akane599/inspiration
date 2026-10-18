package com.akane.inspiration.sleeplab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.analysis.IntervalAnalysis
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.diff.ResolvedReference
import kotlinx.coroutines.CancellationException
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
    private val labelCache = HashMap<String, String>()
    private val uidPackageCache = HashMap<Int, String?>()

    init {
        viewModelScope.launch {
            combine(deps.analysis, deps.selection.selected, visible) { analysis, kind, isVisible ->
                Triple(analysis, kind, isVisible)
            }.collectLatest { (analysis, kind, isVisible) ->
                // collectLatest cancels the previous shaping (label lookups, the timeline read) on a new value.
                if (isVisible) {
                    val slice = sliceUi(kind, analysis)
                    when (analysis) {
                        AnalysisState.Loading -> showNotReady(AnalysisUi.Loading, slice)
                        AnalysisState.Empty -> showNotReady(AnalysisUi.Empty, slice)
                        is AnalysisState.Failed -> showNotReady(AnalysisUi.Error, slice)
                        is AnalysisState.Ready -> showReady(analysis.analysis, slice)
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

    /** batterystats often reports an app UID without a package; PackageManager knows which one owns it. */
    private suspend fun withOwnerPackages(analysis: IntervalAnalysis): IntervalAnalysis {
        if (analysis.diff.uids.none { it.packageName == null }) return analysis
        val uids = analysis.diff.uids.map { uid ->
            if (uid.packageName != null) {
                uid
            } else {
                val pkg = uidPackageCache.getOrPut(uid.uid) { attempt { deps.appLabels.packageForUid(uid.uid) } }
                if (pkg == null) uid else uid.copy(packageName = pkg)
            }
        }
        return analysis.copy(diff = analysis.diff.copy(uids = uids))
    }

    /** Labels are cached for the ViewModel's life; only the packages the tabs will show are looked up. */
    private suspend fun labelsFor(analysis: IntervalAnalysis): Map<String, String> {
        val packages = visiblePackages(analysis)
        for (pkg in packages) {
            if (pkg !in labelCache) attempt { deps.appLabels.label(pkg) }?.let { labelCache[pkg] = it }
        }
        return packages.mapNotNull { pkg -> labelCache[pkg]?.let { pkg to it } }.toMap()
    }

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
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            if (userInitiated) _state.update { it.copy(refreshing = true) }
            try {
                when (attempt { deps.refresh() }) {
                    is RefreshResult.Refreshed, is RefreshResult.Debounced -> Unit
                    is RefreshResult.Failed, null -> if (userInitiated) effectChannel.trySend(SleepLabEffect.RefreshFailed)
                }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    /** Containers answer with values, but a throwing data source must degrade a card, not crash the screen. */
    private suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    companion object {
        fun factory(deps: SleepLabDependencies): ViewModelProvider.Factory = viewModelFactory {
            initializer { SleepLabViewModel(deps) }
        }
    }
}
