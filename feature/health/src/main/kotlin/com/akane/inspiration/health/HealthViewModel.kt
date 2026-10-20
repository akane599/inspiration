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
import com.akane.inspiration.telemetry.store.RETENTION_WINDOW_MS
import com.akane.inspiration.telemetry.store.TelemetrySample
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long the curve stays live after its newest point: three periods plus the service bind timeout (5 s) a single
 * failed read may take, the rule the Cockpit gauge uses, so one transient miss never drops the "Live" badge while
 * stopped reads end it within ~14 s.
 */
internal const val LIVE_STALE_MS = 3 * CURVE_PERIOD_MS + 5_000L

/**
 * How long a load waits for the kernel read before it shows the stored day without it (B-14). A kernel answer that
 * comes within it lands in the same frame as the stored day, so the card does not first show the recorded figures;
 * a read that takes its whole 2 s deadline (Shizuku installed but not running) no longer holds back what Room has.
 */
internal const val KERNEL_GRACE_MS = 300L

/**
 * What the last load read from storage, and everything derived from it alone. A null read failed; an empty list is a
 * real "nothing recorded". The derived values are computed on construction, which happens once per load and off the
 * main thread, so a live tick never maps, de-duplicates or summarises the stored day again; it only merges the live
 * points in.
 */
private class StoredDay(
    val healthSamples: List<HealthSample>?,
    val sessions: List<ChargeSessionRecord>?,
    val history: List<HistoryEvent>?,
    val samples: List<TelemetrySample>?,
    /** The previous load's [persistedPoints], drawn again when this load's samples read failed. */
    previousPoints: List<CurvePoint>,
    /** [history]'s last charge, with a plug-in older than the window already applied ([LastCharge.startingAt]). */
    val historySpan: ChargeSpan?,
) {
    val everyReadFailed: Boolean
        get() = healthSamples == null && sessions == null && history == null && samples == null

    /**
     * The persisted charging points in time order, one per time (the first stored wins). When the samples read failed
     * they are the previous load's: the live points that load read back are no longer held (see [LiveBuffer]), so no
     * points at all would cut them, and the stored part of the charge, out of the curve. [everyReadFailed] still sees
     * the failed read.
     */
    val persistedPoints: List<CurvePoint> =
        samples
            ?.mapNotNull { it.toCurvePoint() }
            ?.distinctBy { it.wallMs }
            ?.sortedBy { it.wallMs }
            ?: previousPoints
    val historyPoints: List<CurvePoint> = history.orEmpty().mapNotNull { it.toCurvePoint() }
    val wear: WearUi = wearUi(healthSamples.orEmpty())
}

/**
 * A load's [stored] day with its kernel read. The stored reads do not wait for the kernel read, which can take its
 * whole deadline (B-14): while [kernelPending], the read is still running and [kernel] is the previous load's answer
 * (null on the first load), standing in so the stored parts can be shown. Built off the main thread, like [StoredDay].
 */
private class HealthInputs(
    val stored: StoredDay,
    val kernel: KernelCapacitySnapshot?,
    val kernelPending: Boolean,
) {
    /** Every read failed, the kernel's included; never while the kernel read is still running. */
    val everyReadFailed: Boolean
        get() = !kernelPending && kernel == null && stored.everyReadFailed
    val card: HealthCardUi = healthCard(kernel, stored.healthSamples.orEmpty(), stored.sessions.orEmpty())
}

/** When the newest accepted live reading was taken, on each clock. */
private class ReadingTime(
    val bootCount: Int,
    val elapsedMs: Long,
    val wallMs: Long,
)

/** What [LiveBuffer.add] made of a reading. */
private sealed interface LiveAdd {
    /** Taken at or before the newest reading of the same boot: dropped. */
    data object Rejected : LiveAdd

    /** Held after the points already held. */
    data object Added : LiveAdd

    /**
     * Taken after the newest reading but timed at or before it: the wall clock was set back. The held points, timed on
     * the old clock, were dropped and the reading is the first one held. [stepMs] is how far the wall clock went back
     * against the elapsed clock; null when a boot lies between the two readings and the step cannot be told.
     */
    data class ClockSetBack(
        val stepMs: Long?,
    ) : LiveAdd
}

/**
 * This visit's live points that storage has not given back yet. Every live tick copies them on the main thread, so a
 * load, once its stored day is shown, drops each one that day holds at the same time: the merge drops such a point
 * from every frame of that load anyway (a stored point wins a tie), and later loads read it from storage (a load whose
 * samples read fails keeps the previous load's points, see [StoredDay.persistedPoints]). What is held is then bounded
 * by the readings since the last load, not by the length of the visit. The facts a frame needs about the whole visit
 * outlive that trim: whether any live point arrived (it still re-derives a charge the history has not caught up with)
 * and when the newest reading was taken (an earlier or repeated reading is still rejected).
 *
 * Readings are ordered on the elapsed clock of their boot, which only moves forward; the wall clock can be set back at
 * any time (B-15). A reading from another boot is later than every reading before it.
 */
private class LiveBuffer {
    private val unstored = ArrayList<CurvePoint>()
    private var newest: ReadingTime? = null

    /** A live point arrived since the last [clear], whether or not it is still held. */
    val any: Boolean get() = newest != null

    val size: Int get() = unstored.size

    /**
     * Holds [point], made from [reading], unless the reading was taken at or before the newest one. The held points
     * stay in strictly increasing wall time, which [mergeByTime] needs, so a reading timed at or before the newest held
     * point (the wall clock was set back) starts the held points over.
     */
    fun add(
        reading: LiveReading,
        point: CurvePoint,
    ): LiveAdd {
        val last = newest
        val sameBoot = last != null && reading.bootCount == last.bootCount
        if (last != null && sameBoot && reading.elapsedRealtimeMs <= last.elapsedMs) return LiveAdd.Rejected
        val added =
            if (last == null || point.wallMs > last.wallMs) {
                LiveAdd.Added
            } else {
                unstored.clear()
                // The wall clock's lead over the elapsed clock shrank by the step.
                val step = (last.wallMs - last.elapsedMs) - (point.wallMs - reading.elapsedRealtimeMs)
                LiveAdd.ClockSetBack(stepMs = step.takeIf { sameBoot })
            }
        unstored += point
        newest = ReadingTime(reading.bootCount, reading.elapsedRealtimeMs, point.wallMs)
        return added
    }

    /** Drops each held point [stored] (in time order, one point per time) has a point for at the same time. */
    fun dropStored(stored: List<CurvePoint>) {
        if (stored.isEmpty()) return
        unstored.removeAll { point -> stored.binarySearchBy(point.wallMs) { it.wallMs } >= 0 }
    }

    fun snapshot(): List<CurvePoint> = unstored.toList()

    fun clear() {
        unstored.clear()
        newest = null
    }
}

/**
 * One frame from the last load and a snapshot of the screen's own state (taken on the main thread). It reads nothing
 * mutable, so it runs on the shaping dispatcher. [live] holds only the points the load had not read back from storage;
 * [hasLive] says whether this visit had any live point at all.
 */
private fun shapeFrame(
    loaded: HealthInputs,
    live: List<CurvePoint>,
    hasLive: Boolean,
    charging: Boolean,
    observedPlugMs: Long?,
    observedUnplugMs: Long?,
    liveActive: Boolean,
    nowMs: Long,
): (HealthState) -> HealthState {
    val stored = loaded.stored
    val sampled = mergeByTime(stored.persistedPoints, live)
    val inferredSpan = effectiveChargeSpan(stored.historySpan, sampled, hasLive)
    // Observed edges outrank stale history and gap heuristics, for persisted points as well as live ones.
    val observedSpan =
        observedPlugMs?.let { start ->
            ChargeSpan(start, observedUnplugMs?.coerceAtLeast(start))
        } ?: observedUnplugMs?.let { end ->
            inferredSpan?.let { ChargeSpan(minOf(it.startMs, end), end) }
        }
    val span = observedSpan ?: inferredSpan
    val session = chargeSession(span, sampled, stored.historyPoints, hasLive = observedSpan == null && hasLive)
    val curve = curveUi(session, liveActive)
    val analysis = if (curve is CurveUi.Ready) wearAnalysis(session, span, nowMs, liveActive) else null
    val nothing =
        loaded.kernel == null && stored.healthSamples.orEmpty().isEmpty() &&
            stored.sessions.orEmpty().isEmpty() && curve is CurveUi.None && !charging
    // While the kernel read runs, a frame its answer could still turn from Error or Empty into Ready waits for it: the
    // screen keeps what it shows (Loading on the first load), as it did while a load was reading.
    if (loaded.kernelPending && (nothing || (stored.everyReadFailed && !hasLive))) {
        return { state -> state.copy(charging = charging) }
    }
    val phase =
        when {
            loaded.everyReadFailed && !hasLive -> HealthPhase.Error
            nothing -> HealthPhase.Empty
            else -> HealthPhase.Ready
        }
    return { state ->
        state.copy(
            phase = phase,
            charging = charging,
            curve = curve,
            card = loaded.card,
            wear = stored.wear,
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
    private val live = LiveBuffer()

    /** The live points a frame copies on the main thread: only those no load has read back from storage yet. */
    internal val heldLivePoints: Int get() = live.size

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
        when (val added = live.add(reading, point)) {
            LiveAdd.Rejected -> return
            LiveAdd.Added -> Unit
            is LiveAdd.ClockSetBack -> onClockSetBack(added.stepMs)
        }
        markLive()
        render()
    }

    /**
     * The wall clock went back by [stepMs] during the charge, while the elapsed clock moved on (B-15). The observed
     * edges go back with it, so the charge keeps its real start and length on the new clock, and the stored day is read
     * again up to the new clock's now: the points the old clock stamped later than that are left out of the frame
     * instead of being drawn after the newest reading.
     */
    private fun onClockSetBack(stepMs: Long?) {
        if (stepMs != null) {
            observedPlugMs = observedPlugMs?.minus(stepMs)
            observedUnplugMs = observedUnplugMs?.minus(stepMs)
        }
        reload()
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

    /**
     * Reads the stored day alongside the kernel read, which can take its whole deadline while Room answers at once
     * (B-14). Once the stored day is shaped, a kernel read still running after [KERNEL_GRACE_MS] no longer holds it
     * back: it is shown with the previous kernel answer standing in, then again with the kernel's own. Cancelling the
     * load (a newer one replaces it) cancels the kernel read with it.
     */
    private suspend fun load() =
        coroutineScope {
            val to = deps.clocks.wallMs()
            val from = to - DATA_WINDOW_MS
            val kernel = async { attempt { deps.kernelBattery() } }
            val healthSamples = attempt { deps.healthSamples() }
            val sessions = attempt { deps.chargeSessions() }
            val history = attempt { deps.historyBetween(from, to) }
            val samples = attempt { deps.samplesBetween(from, to) }
            val previous = inputs
            val previousPoints = previous?.stored?.persistedPoints.orEmpty()
            val stored =
                withContext(shapingDispatcher) {
                    val lastCharge = history?.let(::lastCharge)
                    // Only a charge whose plug-in is older than the window reads further back; a failed read keeps the
                    // window's own start.
                    val plugInMs =
                        if (lastCharge?.startsBeforeWindow == true) attempt { plugInBefore(from, to) } else null
                    StoredDay(
                        healthSamples = healthSamples,
                        sessions = sessions,
                        history = history,
                        samples = samples,
                        previousPoints = previousPoints,
                        historySpan = lastCharge?.startingAt(plugInMs),
                    )
                }
            if (!kernel.isCompleted) withTimeoutOrNull(KERNEL_GRACE_MS) { kernel.join() }
            if (!kernel.isCompleted) {
                val standIn = previous?.kernel
                publish { HealthInputs(stored, standIn, kernelPending = true) }
            }
            val answer = kernel.await()
            publish { HealthInputs(stored, answer, kernelPending = false) }
        }

    /**
     * Builds [next] (its card) off the main thread, then makes it the inputs every frame is shaped from. Only from then
     * on do frames draw the stored twins of the live points its stored day read back, so only then does the buffer let
     * them go: a frame shaped while the load still waited for the kernel draws them from the buffer.
     */
    private suspend fun publish(next: () -> HealthInputs) {
        val published = withContext(shapingDispatcher) { next() }
        inputs = published
        live.dropStored(published.stored.persistedPoints)
        render()
    }

    /**
     * The plug-in of the charge that was already going at [windowStartMs], read back one window at a time and no
     * further than the retention keeps history (counted from [nowMs]). Null when the history does not record it.
     */
    private suspend fun plugInBefore(
        windowStartMs: Long,
        nowMs: Long,
    ): Long? {
        val floorMs = nowMs - RETENTION_WINDOW_MS
        var pluggedSinceMs: Long? = null
        var endMs = windowStartMs
        while (endMs > floorMs) {
            val startMs = maxOf(floorMs, endMs - DATA_WINDOW_MS)
            val scan = scanPlugIn(deps.historyBetween(startMs, endMs - 1), pluggedSinceMs)
            if (scan.settled) return scan.startMs
            pluggedSinceMs = scan.startMs
            endMs = startMs
        }
        return pluggedSinceMs
    }

    private fun render() {
        val loaded = inputs
        if (loaded == null) {
            _state.update { it.copy(charging = charging) }
            return
        }
        // The screen's own state is read here, on the main thread; only immutable snapshots go to the shaping.
        val heldLive = live.snapshot()
        val hasLive = live.any
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
                        shapeFrame(loaded, heldLive, hasLive, charging, observedPlugMs, observedUnplugMs, liveActive, nowMs)
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
