package com.akane.inspiration.di

import com.akane.inspiration.ipc.ShizukuState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Runs [reconcile] on every transition into [ShizukuState.Ready], not only the first: the read-back it
 * needs goes through the service, so a run that found the service unreachable leaves rows PENDING until
 * the next time Shizuku becomes ready. Reconciling is idempotent, so repeating it is safe. Never returns
 * while [state] is collected.
 */
suspend fun reconcileOnEachReady(state: Flow<ShizukuState>, reconcile: suspend () -> Unit) {
    state.map { it is ShizukuState.Ready }
        .distinctUntilChanged()
        .filter { it }
        .collect { reconcile() }
}
