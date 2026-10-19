package com.akane.inspiration.ipc

sealed interface ShizukuState {
    data object NotInstalled : ShizukuState
    data object NotRunning : ShizukuState

    /**
     * Shizuku runs, but its server API [apiVersion] is older than v13: it builds the telemetry service without a
     * Context, which rejects every call. Updating Shizuku is the only fix, so there is nothing to grant.
     */
    data class UnsupportedServer(val apiVersion: Int) : ShizukuState

    data object PermissionRequired : ShizukuState
    data object PermissionDenied : ShizukuState
    data class Ready(val uid: Int, val apiVersion: Int) : ShizukuState
}
