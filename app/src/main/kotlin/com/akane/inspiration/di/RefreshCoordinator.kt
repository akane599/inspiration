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
 *   [RefreshResult.Debounced] without capturing. A failed capture is not remembered, so a retry
 *   (after granting Shizuku, say) runs at once. A wall clock that moved backwards never debounces.
 * - **Cancellation.** The capture is bounded by its own deadline, so it runs to its end even when the
 *   caller (a screen that left composition) is cancelled; the half-written result would be lost otherwise.
 *
 * Nothing here schedules itself: [lastRefreshWallMs] changes only when someone asked for a refresh.
 */
class RefreshCoordinator(
    private val nowWallMs: () -> Long,
    private val debounceMs: Long,
    private val capture: suspend () -> RefreshResult,
) {
    private val mutex = Mutex()
    private val mutableLastRefreshWallMs = MutableStateFlow<Long?>(null)

    /** Wall time of the last successful refresh in this process; null until one has happened. */
    val lastRefreshWallMs: StateFlow<Long?> = mutableLastRefreshWallMs.asStateFlow()

    suspend fun refresh(): RefreshResult = mutex.withLock {
        val last = mutableLastRefreshWallMs.value
        if (last != null && nowWallMs() - last in 0 until debounceMs) {
            return@withLock RefreshResult.Debounced(last)
        }
        val result = withContext(NonCancellable) { capture() }
        if (result is RefreshResult.Refreshed) mutableLastRefreshWallMs.value = result.wallMs
        result
    }
}
