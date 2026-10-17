package com.akane.inspiration.ipc

import kotlinx.coroutines.flow.StateFlow

interface ShizukuManager {
    val state: StateFlow<ShizukuState>
    val telemetryService: StateFlow<ITelemetryUserService?>

    fun requestPermission()

    fun start()

    suspend fun <T> withTelemetryService(
        timeoutMs: Long,
        block: suspend (ITelemetryUserService) -> T,
    ): T?

    fun holdBinding(): AutoCloseable

    fun stop()
}
