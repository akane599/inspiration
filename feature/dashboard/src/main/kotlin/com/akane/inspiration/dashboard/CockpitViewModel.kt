package com.akane.inspiration.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.snapshot.LiveReading
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Period of the live gauge while the Cockpit is on screen (spec: every 1.5 s, never otherwise). */
internal const val LIVE_PERIOD_MS = 1_500L

/** The chart always shows the last day; a committed brush selects inside it. */
internal const val CHART_WINDOW_MS = 24L * 3_600_000

/**
 * Cockpit state holder (H5). It is Activity-scoped, so it outlives the tab; everything that reads from the device
 * is therefore tied to [CockpitIntent.Visible] / [CockpitIntent.Hidden] and never to the ViewModel's lifetime (H13):
 * the live readings job and the Shizuku binding exist only between the two. The in-process flows (analysis,
 * selection, Shizuku state) are passive and are collected for the ViewModel's whole life.
 */
class CockpitViewModel(private val deps: CockpitDependencies) : ViewModel() {

    private val _state = MutableStateFlow(CockpitState.Initial)
    val state: StateFlow<CockpitState> = _state.asStateFlow()

    private val effectChannel = Channel<CockpitEffect>(Channel.BUFFERED)
    val effects: Flow<CockpitEffect> = effectChannel.receiveAsFlow()

    private var visible = false
    private var liveJob: Job? = null
    private var refreshJob: Job? = null

    /** The user asked for the running refresh (main thread only, like every intent). */
    private var userWaiting = false
    private var binding: AutoCloseable? = null

    /** The system's discharge estimate from the newest analysed snapshot, with the wall time it was taken at. */
    private var systemEstimate: SystemEstimate? = null

    private class SystemEstimate(val wallMs: Long, val remainingMs: Long)

    init {
        viewModelScope.launch {
            combine(deps.analysis, deps.selection.selected, deps.shizukuState) { analysis, kind, shizuku ->
                Triple(analysis, kind, shizuku)
            }.collect { (analysis, kind, shizuku) ->
                systemEstimate = (analysis as? AnalysisState.Ready)?.analysis?.resolved?.now?.let { snapshot ->
                    snapshot.system?.dischargeTimeRemainingMs?.let { SystemEstimate(snapshot.clock.wallMs, it) }
                }
                val markerId = (kind as? ReferenceKind.CustomMarker)?.markerId
                _state.update {
                    it.copy(
                        reference = referenceUi(kind),
                        shizuku = shizukuUi(shizuku),
                        analysis = analysisUi(analysis),
                        slice = sliceUi(kind, analysis),
                        markerSheet = it.markerSheet?.copy(selectedId = markerId),
                    )
                }
            }
        }
    }

    fun onIntent(intent: CockpitIntent) {
        when (intent) {
            CockpitIntent.Visible -> onVisible()
            CockpitIntent.Hidden -> onHidden()
            CockpitIntent.Refresh -> startRefresh(userInitiated = true)
            is CockpitIntent.SelectReference -> deps.selection.select(referenceKind(intent.choice))
            CockpitIntent.OpenMarkers -> openMarkers()
            CockpitIntent.DismissMarkers -> _state.update { it.copy(markerSheet = null) }
            is CockpitIntent.SelectMarker -> selectMarker(intent.markerId)
            is CockpitIntent.MarkNow -> markNow(intent.label)
            is CockpitIntent.CommitBrush -> commitBrush(intent.fromMs, intent.toMs)
            CockpitIntent.ClearSlice -> deps.selection.clearSlice()
            CockpitIntent.GrantShizuku -> deps.requestShizukuPermission()
        }
    }

    private fun onVisible() {
        if (visible) return
        visible = true
        binding = deps.holdBinding()
        liveJob = viewModelScope.launch {
            deps.liveReadings(LIVE_PERIOD_MS)
                .catch { /* A failed live read leaves the gauge on dashes; it must not take the screen down. */ }
                .collect { reading -> if (visible) onReading(reading) }
        }
        startRefresh(userInitiated = false)
    }

    private fun onHidden() {
        if (!visible) return
        visible = false
        liveJob?.cancel()
        liveJob = null
        closeBinding()
        // A reading from before the screen was hidden must not be shown as live when it comes back.
        _state.update { it.copy(live = null) }
    }

    private fun closeBinding() {
        val held = binding
        binding = null
        held?.close()
    }

    private fun onReading(reading: LiveReading) {
        val estimate = systemEstimate
            ?.takeIf { reading.wallMs - it.wallMs <= SYSTEM_ESTIMATE_MAX_AGE_MS }
            ?.remainingMs
        _state.update { it.copy(live = liveUi(reading, estimate)) }
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
                loadTimeline()
                when (val result = attempt { deps.refresh() }) {
                    is RefreshResult.Refreshed -> loadTimeline()
                    is RefreshResult.Debounced -> Unit
                    is RefreshResult.Failed, null ->
                        if (userWaiting) effectChannel.trySend(CockpitEffect.RefreshFailed)
                }
            } finally {
                userWaiting = false
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    private suspend fun loadTimeline() {
        val to = deps.clocks.wallMs()
        val timeline = attempt { deps.timeline(to - CHART_WINDOW_MS, to) }
        _state.update {
            when {
                timeline != null -> it.copy(chart = ChartUi.Ready(scrubChartData(timeline)))
                it.chart is ChartUi.Ready -> it
                else -> it.copy(chart = ChartUi.Failed)
            }
        }
    }

    private fun commitBrush(aMs: Long, bMs: Long) {
        val range = TimeRange.of(aMs, bMs)
        deps.selection.select(ReferenceKind.TimeSlice(range.startMs, range.endMs))
    }

    private fun openMarkers() {
        val selected = (deps.selection.selected.value as? ReferenceKind.CustomMarker)?.markerId
        _state.update { it.copy(markerSheet = MarkerSheetUi.loading(selected)) }
        viewModelScope.launch { loadMarkers() }
    }

    private suspend fun loadMarkers() {
        val markers = attempt { deps.markers() }
        _state.update { state ->
            state.markerSheet?.let { sheet ->
                state.copy(
                    markerSheet = sheet.copy(
                        loading = false,
                        failed = markers == null,
                        markers = markers.orEmpty()
                            .sortedByDescending { it.wallMs }
                            .map { MarkerUi(it.id, it.label, it.wallMs) }
                            .toImmutableList(),
                    ),
                )
            } ?: state
        }
    }

    private fun markNow(label: String) {
        val sheet = _state.value.markerSheet ?: return
        if (sheet.creating) return
        _state.update { it.copy(markerSheet = it.markerSheet?.copy(creating = true, failed = false)) }
        viewModelScope.launch {
            val id = attempt { deps.createMarker(label) }
            if (id == null) {
                _state.update { it.copy(markerSheet = it.markerSheet?.copy(creating = false, failed = true)) }
            } else {
                _state.update { it.copy(markerSheet = it.markerSheet?.copy(creating = false)) }
                loadMarkers()
            }
        }
    }

    private fun selectMarker(markerId: Long) {
        deps.selection.select(ReferenceKind.CustomMarker(markerId))
        _state.update { it.copy(markerSheet = null) }
    }

    override fun onCleared() {
        liveJob?.cancel()
        visible = false
        closeBinding()
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
        fun factory(deps: CockpitDependencies): ViewModelProvider.Factory = viewModelFactory {
            initializer { CockpitViewModel(deps) }
        }
    }
}
