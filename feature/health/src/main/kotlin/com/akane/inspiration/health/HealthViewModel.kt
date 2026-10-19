package com.akane.inspiration.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.telemetry.app.KernelCapacitySnapshot
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.app.attempt
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.TelemetrySample
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How long the curve stays live after its newest point: three periods plus the service bind timeout (5 s) a single
 * failed read may take, the rule the Cockpit gauge uses, so one transient miss never drops the "Live" badge while
 * stopped reads end it within ~14 s.
 */
internal const val LIVE_STALE_MS = 3 * CURVE_PERIOD_MS + 5_000L

/**
 * What the last load read, and everything derived from it alone. A null read failed; an empty list is a real "nothing
 * recorded". The derived values are computed on construction, which happens once per load and off the main thread, so
 * a live tick never maps, sorts or summarises the stored day again.
 */
private class HealthInputs(
    val kernel: KernelCapacitySnapshot?,
    val healthSamples: List<HealthSample>?,
    val sessions: List<ChargeSessionRecord>?,
    val history: List<HistoryEvent>?,
    val samples: List<TelemetrySample>?,
    val loadedAtMs: Long,
) {
    val everyReadFailed: Boolean
        get() = kernel == null && healthSamples == null && sessions == null && history == null && samples == null

    /** The persisted charging points in time order, one per time (the first stored wins). */
    val persistedPoints: List<CurvePoint> =
        samples
            .orEmpty()
            .mapNotNull { it.toCurvePoint() }
            .distinctBy { it.wallMs }
            .sortedBy { it.wallMs }
    val historyPoints: List<CurvePoint> = history.orEmpty().mapNotNull { it.toCurvePoint() }
    val historySpan: ChargeSpan? = history?.let(::lastChargeSpan)
    val card: HealthCardUi = healthCard(kernel, healthSamples.orEmpty(), sessions.orEmpty())
    val wear: WearUi = wearUi(healthSamples.orEmpty())
}

/**
 * One frame from the last load and a snapshot of the screen's own state (taken on the main thread). It reads nothing
 * mutable, so it runs on the shaping dispatcher.
 */
private fun shapeFrame(
    loaded: HealthInputs,
    live: List<CurvePoint>,
    charging: Boolean,
    observedPlugMs: Long?,
    observedUnplugMs: Long?,
    liveActive: Boolean,
    nowMs: Long,
): (HealthState) -> HealthState {
    val sampled = mergeByTime(loaded.persistedPoints, live)
    val inferredSpan = effectiveChargeSpan(loaded.historySpan, sampled, hasLive = live.isNotEmpty())
    // Observed edges outrank stale history and gap heuristics, for persisted points as well as live ones.
    val observedSpan =
        observedPlugMs?.let { start ->
            ChargeSpan(start, observedUnplugMs?.coerceAtLeast(start))
        } ?: observedUnplugMs?.let { end ->
            inferredSpan?.let { ChargeSpan(minOf(it.startMs, end), end) }
        }
    val span = observedSpan ?: inferredSpan
    val session = chargeSession(span, sampled, loaded.historyPoints, hasLive = observedSpan == null && live.isNotEmpty())
    val curve = curveUi(session, liveActive)
    val analysis = if (curve is CurveUi.Ready) wearAnalysis(session, span, nowMs, liveActive) else null
    val nothing =
        loaded.kernel == null && loaded.healthSamples.orEmpty().isEmpty() &&
            loaded.sessions.orEmpty().isEmpty() && curve is CurveUi.None && !charging
    val phase =
        when {
            loaded.everyReadFailed && live.isEmpty() -> HealthPhase.Error
            nothing -> HealthPhase.Empty
            else -> HealthPhase.Ready
        }
    return { state ->
        state.copy(
            phase = phase,
            charging = charging,
            curve = curve,
            card = loaded.card,
            wear = loaded.wear,
            analysis = analysis,
        )
    }
}

/**
 * Charge & Health state holder (H5). It is Activity-scoped, so it outlives the tab; everything that reads the device
 * is therefore tied to [HealthIntent.Visible] / [HealthIntent.Hidden] and the plug state, never to the ViewModel's
 * lifetime (H13): the live readings job and the Shizuku binding exist only while the screen is visible *and* the
 * phone is charging. Background charging is not recorded here; it shows up as battery-history events (H9). The stored
 * data is read on enter, on a pull, after an observed unplug and on every refresh tick while visible, and shaped on
 * [shapingDispatcher]: once per load for the stored day, then per frame (each live tick) merging the live points in.
 */
class HealthViewModel(
    private val deps: HealthDependencies,
    private val shapingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(HealthState.Initial)
    val state: StateFlow<HealthState> = _state.asStateFlow()

    private val effectChannel = Channel<HealthEffect>(Channel.BUFFERED)
    val effects: Flow<HealthEffect> = effectChannel.receiveAsFlow()

    private var visible = false
    private var charging = false
    private var plugStateKnown = false
    private var observedPlugMs: Long? = null
    private var observedUnplugMs: Long? = null
    private var liveJob: Job? = null
    private var refreshJob: Job? = null

    /** A live point arrived within [LIVE_STALE_MS]. Only then is the curve live (a running live job is not enough). */
    private var liveFresh = false
    private var staleJob: Job? = null

    /** Follows the refresh tick while visible, reloading on each one. */
    private var ticksJob: Job? = null

    /** The load in flight. A newer load replaces it, so an older read can never land after a newer one. */
    private var loadJob: Job? = null

    /** The frame being shaped. A newer render replaces it, so an older frame can never land after a newer one. */
    private var renderJob: Job? = null

    /** The user asked for the running refresh (main thread only, like every intent). */
    private var userWaiting = false
    private var binding: AutoCloseable? = null
    private var inputs: HealthInputs? = null

    /** Readings for the current or just-completed observed charge. Persisted by the source as well. */
    private val live = ArrayList<CurvePoint>()

    fun onIntent(intent: HealthIntent) {
        when (intent) {
            HealthIntent.Visible -> onVisible()
            HealthIntent.Hidden -> onHidden()
            is HealthIntent.PlugChanged -> onPlugChanged(intent.charging)
            HealthIntent.Refresh -> startRefresh(userInitiated = true)
        }
    }

    private fun onVisible() {
        if (visible) return
        visible = true
        syncLive()
        // The first value loads this visit's data. Every later one is a capture that stored history, samples or health
        // records after that load: this screen's own refresh, the app-open capture its on-enter refresh joined (which
        // then comes back Debounced), the re-run when Shizuku became ready, a marker, or the charge job's pass.
        ticksJob = viewModelScope.launch { deps.lastRefreshWallMs.collect { reload() } }
        startRefresh(userInitiated = false)
    }

    private fun onHidden() {
        if (!visible) return
        visible = false
        ticksJob?.cancel()
        ticksJob = null
        // The plug listener goes away with the screen; the next Visible brings a fresh plug state.
        charging = false
        syncLive()
        live.clear()
        plugStateKnown = false
        observedPlugMs = null
        observedUnplugMs = null
        // End this visit's refresh ownership without cancelling the shared capture: a failure that lands (or was
        // queued) after the user left must not be shown on the next visit.
        userWaiting = false
        while (effectChannel.tryReceive().isSuccess) Unit
        _state.update { it.copy(refreshing = false) }
        render()
    }

    private fun onPlugChanged(nowCharging: Boolean) {
        val hadPlugState = plugStateKnown
        plugStateKnown = true
        if (charging == nowCharging) return
        // The first sticky state can arrive mid-charge; only an observed transition is a boundary.
        if (hadPlugState) {
            val at = deps.clocks.wallMs()
            if (nowCharging) {
                observedPlugMs = at
                observedUnplugMs = null
                live.clear()
            } else {
                observedUnplugMs = at
            }
        }
        charging = nowCharging
        syncLive()
        // The charge that just ended is in storage now; pick it up without waiting for a pull.
        if (!nowCharging && visible) reload()
        render()
    }

    /** Starts or stops the live job and the binding so they exist exactly while visible and charging. */
    private fun syncLive() {
        val wanted = visible && charging
        if (wanted && liveJob == null) {
            binding = deps.holdBinding()
            liveJob =
                viewModelScope.launch {
                    deps
                        .liveReadings(CURVE_PERIOD_MS)
                        .catch {
                            // A failed live read leaves the curve as it was; it must not take the screen down. It ends
                            // the readings, though, so the curve is no longer live.
                            endLive()
                            render()
                        }.collect { reading -> if (visible && charging) onReading(reading) }
                }
        } else if (!wanted && liveJob != null) {
            liveJob?.cancel()
            liveJob = null
            endLive()
            closeBinding()
        }
    }

    /**
     * The source emits only successful reads, so silence means the reads stopped (Shizuku gone, every read failing):
     * once no newer point arrives within [LIVE_STALE_MS], the curve is not live and the charge stops counting up.
     */
    private fun markLive() {
        liveFresh = true
        staleJob?.cancel()
        staleJob =
            viewModelScope.launch {
                delay(LIVE_STALE_MS)
                liveFresh = false
                render()
            }
    }

    private fun endLive() {
        staleJob?.cancel()
        staleJob = null
        liveFresh = false
    }

    private fun closeBinding() {
        val held = binding
        binding = null
        held?.close()
    }

    private fun onReading(reading: LiveReading) {
        val point = reading.toCurvePoint() ?: return
        if (live.lastOrNull()?.let { it.wallMs >= point.wallMs } == true) return
        live += point
        markLive()
        render()
    }

    private fun startRefresh(userInitiated: Boolean) {
        // A user refresh during a running one (e.g. the on-enter refresh) joins it: it shows progress and
        // reports that refresh's failure.
        if (userInitiated && visible) {
            userWaiting = true
            _state.update { it.copy(refreshing = true) }
        }
        // A pull re-reads the store at once, and a capture it starts or joins reloads again through its refresh tick.
        if (userInitiated) reload()
        if (refreshJob?.isActive == true) return
        refreshJob =
            viewModelScope.launch {
                try {
                    when (attempt { deps.refresh() }) {
                        // A stored capture reaches the screen through its refresh tick.
                        is RefreshResult.Refreshed, is RefreshResult.Debounced -> {
                            Unit
                        }

                        is RefreshResult.Failed, null -> {
                            if (userWaiting && visible) effectChannel.trySend(HealthEffect.RefreshFailed)
                        }
                    }
                } finally {
                    userWaiting = false
                    _state.update { it.copy(refreshing = false) }
                }
            }
    }

    private fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val to = deps.clocks.wallMs()
        val from = to - DATA_WINDOW_MS
        val kernel = attempt { deps.kernelBattery() }
        val healthSamples = attempt { deps.healthSamples() }
        val sessions = attempt { deps.chargeSessions() }
        val history = attempt { deps.historyBetween(from, to) }
        val samples = attempt { deps.samplesBetween(from, to) }
        inputs =
            withContext(shapingDispatcher) {
                HealthInputs(
                    kernel = kernel,
                    healthSamples = healthSamples,
                    sessions = sessions,
                    history = history,
                    samples = samples,
                    loadedAtMs = to,
                )
            }
        render()
    }

    private fun render() {
        val loaded = inputs
        if (loaded == null) {
            _state.update { it.copy(charging = charging) }
            return
        }
        // The screen's own state is read here, on the main thread; only immutable snapshots go to the shaping.
        val live = live.toList()
        val charging = charging
        val observedPlugMs = observedPlugMs
        val observedUnplugMs = observedUnplugMs
        val liveActive = liveFresh
        val nowMs = deps.clocks.wallMs()
        renderJob?.cancel()
        renderJob =
            viewModelScope.launch {
                val frame =
                    withContext(shapingDispatcher) {
                        shapeFrame(loaded, live, charging, observedPlugMs, observedUnplugMs, liveActive, nowMs)
                    }
                _state.update(frame)
            }
    }

    override fun onCleared() {
        liveJob?.cancel()
        liveJob = null
        visible = false
        closeBinding()
    }

    companion object {
        fun factory(deps: HealthDependencies): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { HealthViewModel(deps) }
            }
    }
}
