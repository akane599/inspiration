package com.akane.inspiration.di

import com.akane.inspiration.telemetry.app.RefreshResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The single refresh entry point behind `refresh()` (H7). A refresh is a FULL capture plus a history
 * ingest ([capture] returns [RefreshResult.Refreshed] or [RefreshResult.Failed]).
 *
 * - **Mutex.** Calls run one at a time, so two concurrent callers never run two captures: the second
 *   waits and then sees the first one's result.
 * - **Debounce.** A call less than [debounceMs] after the last successful refresh returns
 *   [RefreshResult.Debounced] without capturing. A failed capture does not count towards that 60 s
 *   debounce, but it is not retried at once either: for [failureBackoffMs] after it, callers get the
 *   same [RefreshResult.Failed] back without a capture, so a burst of Visible/onStart calls against a
 *   hung service does not queue a deadline-long capture each. A wall clock that moved backwards never
 *   debounces or backs off.
 * - **Cancellation.** The capture is bounded by its own deadline, so it runs to its end even when the
 *   caller (a screen that left composition) is cancelled; the half-written result would be lost otherwise.
 *
 * Nothing here schedules itself: [lastRefreshWallMs] changes only when someone asked for a refresh.
 */
class RefreshCoordinator(
    private val nowWallMs: () -> Long,
    private val debounceMs: Long,
    private val failureBackoffMs: Long = FAILURE_BACKOFF_MS,
    private val capture: suspend () -> RefreshResult,
) {
    private val mutex = Mutex()
    private val mutableLastRefreshWallMs = MutableStateFlow<Long?>(null)
    @Volatile
    private var lastFailure: Pair<Long, RefreshResult.Failed>? = null

    /** Bumped by [clearFailureBackoff]; a capture that started before the bump does not record its failure. */
    @Volatile
    private var backoffEpoch = 0

    /** Wall time of the last successful refresh in this process; null until one has happened. */
    val lastRefreshWallMs: StateFlow<Long?> = mutableLastRefreshWallMs.asStateFlow()

    /**
     * Forgets the failure backoff so the next [refresh] captures at once. Call it when what made the capture
     * fail has changed (Shizuku became Ready): the cached [RefreshResult.Failed] describes the old state. The
     * 60 s success debounce is untouched. Never blocks, so it is safe from any thread, mid-capture included.
     */
    fun clearFailureBackoff() {
        backoffEpoch += 1
        lastFailure = null
    }

    suspend fun refresh(): RefreshResult = mutex.withLock {
        val last = mutableLastRefreshWallMs.value
        if (last != null && nowWallMs() - last in 0 until debounceMs) {
            return@withLock RefreshResult.Debounced(last)
        }
        val failure = lastFailure
        if (failure != null && nowWallMs() - failure.first in 0 until failureBackoffMs) {
            return@withLock failure.second
        }
        val epoch = backoffEpoch
        val result = withContext(NonCancellable) { capture() }
        lastFailure = (result as? RefreshResult.Failed)?.takeIf { epoch == backoffEpoch }?.let { nowWallMs() to it }
        if (result is RefreshResult.Refreshed) mutableLastRefreshWallMs.value = result.wallMs
        result
    }

    private companion object {
        /** Long enough that Visible/onStart bursts on a hung service do not queue 10 s captures back to back. */
        const val FAILURE_BACKOFF_MS = 12_000L
    }
}
