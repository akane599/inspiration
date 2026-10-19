package com.akane.inspiration.ipc

import kotlinx.coroutines.flow.StateFlow

interface ShizukuManager {
    val state: StateFlow<ShizukuState>
    val telemetryService: StateFlow<ITelemetryUserService?>

    fun requestPermission()

    /**
     * Reads the state from Shizuku again. Shizuku sends no callback for a change made in its own app (such as
     * granting access there), so the app calls this whenever it comes back to the foreground. Managers whose
     * state is pushed to them (test doubles) need nothing here.
     */
    fun refresh() = Unit

    fun start()

    suspend fun <T> withTelemetryService(
        timeoutMs: Long,
        block: suspend (ITelemetryUserService) -> T,
    ): T?

    fun holdBinding(): AutoCloseable

    fun stop()
}
