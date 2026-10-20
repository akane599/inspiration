package com.akane.inspiration.di

import com.akane.inspiration.telemetry.app.RefreshResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

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
 * - **Store tick.** A capture that came back [RefreshResult.Refreshed] has stored its history and snapshot,
 *   so [onStored] fires once for it, after [afterStored] however that ends: a failing retention prune
 *   still fails the refresh, but it can no longer hide stored history from the screens that re-read on it.
 *
 * Nothing here schedules itself: [lastRefreshWallMs] changes only when someone asked for a refresh.
 */
class RefreshCoordinator(
    private val nowWallMs: () -> Long,
    private val debounceMs: Long,
    private val failureBackoffMs: Long = FAILURE_BACKOFF_MS,
    private val onFailure: (Exception) -> Unit = {},
    /** Upkeep after a stored capture (retention prune, status); if it throws, the refresh fails. */
    private val afterStored: suspend () -> Unit = {},
    /** The store tick, once per capture that came back [RefreshResult.Refreshed], after [afterStored] however it ends. */
    private val onStored: () -> Unit,
    private val capture: suspend () -> RefreshResult,
) {
    private val mutex = Mutex()
    private val mutableLastRefreshWallMs = MutableStateFlow<Long?>(null)

    /**
     * The last failure, tagged with the [backoffEpoch] its capture started in. Only a failure of the current
     * epoch backs off, so a [clearFailureBackoff] that lands while a failure is being recorded still wins.
     */
    @Volatile
    private var lastFailure: FailureMark? = null

    /** Bumped by [clearFailureBackoff]; a failure from a capture that started before the bump does not back off. */
    private val backoffEpoch = AtomicInteger()

    /** Whether the last capture that ran failed; unlike [lastFailure]'s backoff it survives [clearFailureBackoff]. */
    @Volatile
    private var lastCaptureFailed = false

    /** Wall time of the last successful refresh in this process; null until one has happened. */
    val lastRefreshWallMs: StateFlow<Long?> = mutableLastRefreshWallMs.asStateFlow()

    /**
     * Forgets the failure backoff so the next [refresh] captures at once. Call it when what made the capture
     * fail has changed (Shizuku became Ready): the cached [RefreshResult.Failed] describes the old state. The
     * 60 s success debounce is untouched. Never blocks, so it is safe from any thread, mid-capture included:
     * the epoch is the only thing it writes, so it cannot be lost to a failure being recorded at the same time.
     */
    fun clearFailureBackoff() {
        backoffEpoch.incrementAndGet()
    }

    suspend fun refresh(): RefreshResult =
        mutex.withLock {
            val last = mutableLastRefreshWallMs.value
            if (last != null && nowWallMs() - last in 0 until debounceMs) {
                return@withLock RefreshResult.Debounced(last)
            }
            val failure = lastFailure?.takeIf { it.epoch == backoffEpoch.get() }
            if (failure != null && nowWallMs() - failure.atWallMs in 0 until failureBackoffMs) {
                return@withLock failure.result
            }
            val epoch = backoffEpoch.get()
            val result =
                try {
                    withContext(NonCancellable) {
                        capture().also { captured ->
                            if (captured is RefreshResult.Refreshed) {
                                try {
                                    afterStored()
                                } finally {
                                    onStored()
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onFailure(error)
                    RefreshResult.Failed("capture_failed")
                }
            lastFailure = (result as? RefreshResult.Failed)?.let { FailureMark(epoch, nowWallMs(), it) }
            lastCaptureFailed = result is RefreshResult.Failed
            if (result is RefreshResult.Refreshed) mutableLastRefreshWallMs.value = result.wallMs
            result
        }

    /**
     * Runs [refresh] again if the last capture failed, else returns null without capturing. Call it after
     * [clearFailureBackoff] once what made the capture fail has changed (Shizuku became Ready): a cold-process
     * refresh that ran before the Shizuku binder arrived is otherwise not repeated until the next app open. A
     * capture still in flight is waited for first, since it may be the one that fails.
     */
    suspend fun refreshIfLastFailed(): RefreshResult? {
        if (!mutex.withLock { lastCaptureFailed }) return null
        return refresh()
    }

    private class FailureMark(
        val epoch: Int,
        val atWallMs: Long,
        val result: RefreshResult.Failed,
    )

    private companion object {
        /** Long enough that Visible/onStart bursts on a hung service do not queue 10 s captures back to back. */
        const val FAILURE_BACKOFF_MS = 12_000L
    }
}
