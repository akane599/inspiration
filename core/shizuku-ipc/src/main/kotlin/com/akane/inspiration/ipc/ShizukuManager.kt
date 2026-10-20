package com.akane.inspiration.ipc

import kotlinx.coroutines.flow.StateFlow

/**
 * Reading Shizuku's state is binder calls into the Shizuku server, so no method here makes them on the caller's
 * thread: [start] and [requestPermission] return at once and the read follows on IO, and [refresh] suspends while
 * it runs there. Observe [state] (or call [refresh] first) instead of reading it right after [start].
 */
interface ShizukuManager {
    val state: StateFlow<ShizukuState>
    val telemetryService: StateFlow<ITelemetryUserService?>

    /**
     * Re-reads the state and shows Shizuku's permission dialog when that read says it is still required. Returns at
     * once; the outcome arrives through [state].
     */
    fun requestPermission()

    /**
     * Reads the state from Shizuku again and returns once that read is done, its state published unless a newer read
     * had already published one. Shizuku sends no callback for a change made in its own app (such as granting access
     * there), so the app calls this whenever it comes back to the foreground. Managers whose state is pushed to them
     * (test doubles) need nothing here.
     */
    suspend fun refresh() = Unit

    fun start()

    suspend fun <T> withTelemetryService(
        timeoutMs: Long,
        block: suspend (ITelemetryUserService) -> T,
    ): T?

    fun holdBinding(): AutoCloseable

    /**
     * Removes the Shizuku listeners and unbinds the user service; a later [start] registers them again. The app's
     * manager lives as long as its process, so only tests call this, to leave no listener or binding behind (kept,
     * audit §3).
     */
    fun stop()
}
