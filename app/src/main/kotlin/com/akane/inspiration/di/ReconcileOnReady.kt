package com.akane.inspiration.di

import android.util.Log
import com.akane.inspiration.ipc.ShizukuState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Runs [reconcile] on every transition into [ShizukuState.Ready], not only the first: the read-back it
 * needs goes through the service, so a run that found the service unreachable leaves rows PENDING until
 * the next time Shizuku becomes ready. Reconciling is idempotent, so repeating it is safe. Never returns
 * while [state] is collected.
 *
 * The transitions are observed in their own coroutine (the unlimited buffer): [state] conflates, so a
 * Ready -> down -> Ready flip while [reconcile] is suspended would otherwise reach the collector as the
 * same `true` and be dropped by [distinctUntilChanged].
 *
 * A [reconcile] that throws (a Room error, say) is handed to [onFailure] and the next Ready runs it again:
 * one failure must not end the collector for the life of the process. Cancellation still ends it.
 */
suspend fun reconcileOnEachReady(
    state: Flow<ShizukuState>,
    onFailure: (Throwable) -> Unit = { Log.w("ReconcileOnReady", "reconcile on Shizuku Ready failed", it) },
    reconcile: suspend () -> Unit,
) {
    state.map { it is ShizukuState.Ready }
        .distinctUntilChanged()
        .buffer(Channel.UNLIMITED)
        .filter { it }
        .collect {
            try {
                reconcile()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure(e)
            }
        }
}
