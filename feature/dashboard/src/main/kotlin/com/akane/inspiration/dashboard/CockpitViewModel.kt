package com.akane.inspiration.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.analysis.forSelection
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.app.attempt
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.snapshot.LiveReading
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Period of the live gauge while the Cockpit is on screen (spec: every 1.5 s, never otherwise). */
internal const val LIVE_PERIOD_MS = 1_500L

/**
 * How long the gauge keeps a reading with no newer one: three periods plus the service bind timeout (5 s) a single
 * failed read may take, so one transient miss never flashes dashes while stopped reads clear within ~10 s.
 */
internal const val LIVE_STALE_MS = 3 * LIVE_PERIOD_MS + 5_000L

/** The chart always shows the last day; a committed brush selects inside it. */
internal const val CHART_WINDOW_MS = 24L * 3_600_000

/**
 * Cockpit state holder (H5). It is Activity-scoped, so it outlives the tab; everything that reads from the device
 * is therefore tied to [CockpitIntent.Visible] / [CockpitIntent.Hidden] and never to the ViewModel's lifetime (H13):
 * the live readings job and the Shizuku binding exist only between the two. The in-process flows (analysis,
 * selection, Shizuku state) are passive and are collected for the ViewModel's whole life. The chart's timeline is
 * read on enter, on a pull and on every refresh tick while visible, and read and shaped on [shapingDispatcher],
 * never on the main thread.
 */
class CockpitViewModel(
    private val deps: CockpitDependencies,
    private val shapingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(CockpitState.Initial)
    val state: StateFlow<CockpitState> = _state.asStateFlow()

    private val effectChannel = Channel<CockpitEffect>(Channel.BUFFERED)
    val effects: Flow<CockpitEffect> = effectChannel.receiveAsFlow()

    private var visible = false
    private var liveJob: Job? = null
    private var refreshJob: Job? = null

    /** Follows the refresh tick while visible, re-reading the chart on each one. */
    private var ticksJob: Job? = null

    /** The chart read in flight. A newer read replaces it, so an older read can never land after a newer one. */
    private var timelineJob: Job? = null

    /** The user asked for the running refresh (main thread only, like every intent). */
    private var userWaiting = false
    private var binding: AutoCloseable? = null

    /** The system's discharge estimate from the newest analysed snapshot, with the wall time it was taken at. */
    private var systemEstimate: SystemEstimate? = null

    private class SystemEstimate(
        val wallMs: Long,
        val remainingMs: Long,
    )

    init {
        viewModelScope.launch {
            combine(deps.analysis, deps.selection.selected, deps.shizukuState) { analysis, kind, shizuku ->
                Triple(analysis, kind, shizuku)
            }.collect { (state, kind, shizuku) ->
                // Until the session has recomputed after a selection change, its result belongs to the old selection.
                val analysis = state.forSelection(kind)
                systemEstimate =
                    (analysis as? AnalysisState.Ready)?.analysis?.resolved?.now?.let { snapshot ->
                        snapshot.system?.dischargeTimeRemainingMs?.let { SystemEstimate(snapshot.clock.wallMs, it) }
                    }
                val markerId = (kind as? ReferenceKind.CustomMarker)?.markerId
                _state.update {
                    it.copy(
                        reference = referenceUi(kind),
                        shizuku = shizukuUi(shizuku),
                        // Without Shizuku there are no live reads, so the last one must not stay on the gauge.
                        live = if (shizuku is ShizukuState.Ready) it.live else null,
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
        liveJob =
            viewModelScope.launch {
                deps
                    .liveReadings(LIVE_PERIOD_MS)
                    // A failed live read ends the readings: back to dashes, without taking the screen down.
                    .catch { clearLive() }
                    .collectLatest { reading ->
                        if (!visible) return@collectLatest
                        onReading(reading)
                        // The source emits only successful reads, so silence means the reads stopped (service
                        // gone, every read failing): once no newer reading arrives in time, this one is not live.
                        delay(LIVE_STALE_MS)
                        clearLive()
                    }
            }
        // The first value reads this visit's chart. Every later one is a refresh that stored history and moved the
        // history horizon after that read: this screen's own, the app-open capture its on-enter refresh joined (which
        // then comes back Debounced), or the re-run when Shizuku became ready, which this screen never asked for.
        ticksJob = viewModelScope.launch { deps.lastRefreshWallMs.collect { reloadTimeline() } }
        startRefresh(userInitiated = false)
    }

    private fun onHidden() {
        if (!visible) return
        visible = false
        liveJob?.cancel()
        liveJob = null
        ticksJob?.cancel()
        ticksJob = null
        closeBinding()
        // End this visit's refresh ownership without cancelling the shared capture: a failure that lands (or was
        // queued) after the user left must not be shown on the next visit.
        userWaiting = false
        while (effectChannel.tryReceive().isSuccess) Unit
        // A reading from before the screen was hidden must not be shown as live when it comes back.
        _state.update { it.copy(live = null, refreshing = false) }
    }

    private fun closeBinding() {
        val held = binding
        binding = null
        held?.close()
    }

    private fun clearLive() {
        _state.update { it.copy(live = null) }
    }

    private fun onReading(reading: LiveReading) {
        // Live reads go through the Shizuku service; one finishing as Shizuku went away is not shown as live.
        if (deps.shizukuState.value !is ShizukuState.Ready) return
        val estimate =
            systemEstimate
                ?.takeIf { reading.wallMs - it.wallMs <= SYSTEM_ESTIMATE_MAX_AGE_MS }
                ?.remainingMs
        _state.update { it.copy(live = liveUi(reading, estimate)) }
    }

    private fun startRefresh(userInitiated: Boolean) {
        // A user refresh during a running one (e.g. the on-enter refresh) joins it: it shows progress and
        // reports that refresh's failure.
        if (userInitiated) {
            // Only a visible visit owns the refresh's progress and failure notice.
            if (visible) {
                userWaiting = true
                _state.update { it.copy(refreshing = true) }
            }
            // The analysis reads only stored data, so Retry on a failed one need not wait for (or get) a capture.
            if (deps.analysis.value is AnalysisState.Failed) deps.recomputeAnalysis()
            // So does the chart: a pull re-reads it at once (a failed one included), and a capture the pull starts
            // or joins re-reads it again through its refresh tick once it is stored.
            reloadTimeline()
        }
        if (refreshJob?.isActive == true) return
        refreshJob =
            viewModelScope.launch {
                try {
                    when (attempt { deps.refresh() }) {
                        // A stored capture reaches the chart through its refresh tick, and the analysis through the same tick.
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
                                effectChannel.trySend(CockpitEffect.RefreshFailed)
                            }
                        }
                    }
                } finally {
                    userWaiting = false
                    _state.update { it.copy(refreshing = false) }
                }
            }
    }

    private fun reloadTimeline() {
        timelineJob?.cancel()
        timelineJob = viewModelScope.launch { loadTimeline() }
    }

    private suspend fun loadTimeline() {
        val to = deps.clocks.wallMs()
        val chart =
            withContext(shapingDispatcher) {
                attempt { deps.timeline(to - CHART_WINDOW_MS, to) }?.let(::scrubChartData)
            }
        _state.update {
            when {
                chart != null -> it.copy(chart = ChartUi.Ready(chart))
                it.chart is ChartUi.Ready -> it
                else -> it.copy(chart = ChartUi.Failed)
            }
        }
    }

    private fun commitBrush(
        aMs: Long,
        bMs: Long,
    ) {
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
                    markerSheet =
                        sheet.copy(
                            loading = false,
                            failed = markers == null,
                            markers =
                                markers
                                    .orEmpty()
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
                // The marker's FULL capture ingested history and moved the horizon without a refresh tick.
                reloadTimeline()
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

    companion object {
        fun factory(deps: CockpitDependencies): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { CockpitViewModel(deps) }
            }
    }
}
