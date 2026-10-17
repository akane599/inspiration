package com.akane.inspiration.ipc

sealed interface ShizukuState {
    data object NotInstalled : ShizukuState
    data object NotRunning : ShizukuState
    data object PermissionRequired : ShizukuState
    data object PermissionDenied : ShizukuState
    data class Ready(val uid: Int, val apiVersion: Int) : ShizukuState
}
