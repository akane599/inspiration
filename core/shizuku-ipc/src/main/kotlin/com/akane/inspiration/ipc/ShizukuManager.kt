package com.akane.inspiration.ipc

import kotlinx.coroutines.flow.StateFlow

interface ShizukuManager {
    val state: StateFlow<ShizukuState>
    val telemetryService: StateFlow<ITelemetryUserService?>

    fun requestPermission()

    fun start()

    fun stop()
}
