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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the last load read. A null read failed; an empty list is a real "nothing recorded". */
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
}

/**
 * Charge & Health state holder (H5). It is Activity-scoped, so it outlives the tab; everything that reads the device
 * is therefore tied to [HealthIntent.Visible] / [HealthIntent.Hidden] and the plug state, never to the ViewModel's
 * lifetime (H13): the live readings job and the Shizuku binding exist only while the screen is visible *and* the
 * phone is charging. Background charging is not recorded here; it shows up as battery-history events (H9).
 */
class HealthViewModel(
    private val deps: HealthDependencies,
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
        startRefresh(userInitiated = false)
    }

    private fun onHidden() {
        if (!visible) return
        visible = false
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
        if (!nowCharging && visible) viewModelScope.launch { load() }
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
                        .catch { /* A failed live read leaves the curve as it was; it must not take the screen down. */ }
                        .collect { reading -> if (visible && charging) onReading(reading) }
                }
        } else if (!wanted && liveJob != null) {
            liveJob?.cancel()
            liveJob = null
            closeBinding()
        }
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
        render()
    }

    private fun startRefresh(userInitiated: Boolean) {
        // A user refresh during a running one (e.g. the on-enter refresh) joins it: it shows progress and
        // reports that refresh's failure.
        if (userInitiated && visible) {
            userWaiting = true
            _state.update { it.copy(refreshing = true) }
        }
        if (refreshJob?.isActive == true) return
        refreshJob =
            viewModelScope.launch {
                try {
                    load()
                    when (attempt { deps.refresh() }) {
                        is RefreshResult.Refreshed -> {
                            load()
                        }

                        is RefreshResult.Debounced -> {
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

    private suspend fun load() {
        val to = deps.clocks.wallMs()
        val from = to - DATA_WINDOW_MS
        val kernel = attempt { deps.kernelBattery() }
        inputs =
            HealthInputs(
                kernel = kernel,
                healthSamples = attempt { deps.healthSamples() },
                sessions = attempt { deps.chargeSessions() },
                history = attempt { deps.historyBetween(from, to) },
                samples = attempt { deps.samplesBetween(from, to) },
                loadedAtMs = to,
            )
        render()
    }

    private fun render() {
        val loaded = inputs
        if (loaded == null) {
            _state.update { it.copy(charging = charging) }
            return
        }
        val nowMs = deps.clocks.wallMs()
        val liveActive = liveJob != null
        val sampled =
            (loaded.samples.orEmpty().mapNotNull { it.toCurvePoint() } + live)
                .distinctBy { it.wallMs }
                .sortedBy { it.wallMs }
        val historyPoints = loaded.history.orEmpty().mapNotNull { it.toCurvePoint() }
        val inferredSpan = effectiveChargeSpan(loaded.history?.let(::lastChargeSpan), sampled, hasLive = live.isNotEmpty())
        // Observed edges outrank stale history and gap heuristics, for persisted points as well as live ones.
        val observedSpan =
            observedPlugMs?.let { start ->
                ChargeSpan(start, observedUnplugMs?.coerceAtLeast(start))
            } ?: observedUnplugMs?.let { end ->
                inferredSpan?.let { ChargeSpan(minOf(it.startMs, end), end) }
            }
        val span = observedSpan ?: inferredSpan
        val session = chargeSession(span, sampled, historyPoints, hasLive = observedSpan == null && live.isNotEmpty())
        val curve = curveUi(session, liveActive)
        val card = healthCard(loaded.kernel, loaded.healthSamples.orEmpty(), loaded.sessions.orEmpty())
        val wear = wearUi(loaded.healthSamples.orEmpty())
        val analysis = if (curve is CurveUi.Ready) wearAnalysis(session, span, nowMs, liveActive) else null
        val nothing =
            loaded.kernel == null && loaded.healthSamples.orEmpty().isEmpty() &&
                loaded.sessions.orEmpty().isEmpty() && curve is CurveUi.None && !charging
        _state.update {
            it.copy(
                phase =
                    when {
                        loaded.everyReadFailed && live.isEmpty() -> HealthPhase.Error
                        nothing -> HealthPhase.Empty
                        else -> HealthPhase.Ready
                    },
                charging = charging,
                curve = curve,
                card = card,
                wear = wear,
                analysis = analysis,
            )
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
