package com.akane.inspiration.ipc

/**
 * Which privileged server handed this process its binder. Sui (root, zygisk) and the Shizuku app speak the same
 * API, and while Sui is active ShizukuProvider ignores the Shizuku app's binder, so a live binder with
 * `Sui.isSui()` came from Sui.
 */
enum class ShizukuBackend { SHIZUKU, SUI }

sealed interface ShizukuState {
    /**
     * The server whose binder this state was read from. Only the binder-backed states know it: with no binder the
     * app cannot tell which one the user meant, so their text names both.
     */
    val backend: ShizukuBackend? get() = null

    data object NotInstalled : ShizukuState
    data object NotRunning : ShizukuState

    /**
     * Shizuku runs, but its server API [apiVersion] is older than v13: it builds the telemetry service without a
     * Context, which rejects every call. Updating Shizuku is the only fix, so there is nothing to grant.
     */
    data class UnsupportedServer(val apiVersion: Int) : ShizukuState

    data class PermissionRequired(override val backend: ShizukuBackend = ShizukuBackend.SHIZUKU) : ShizukuState
    data class PermissionDenied(override val backend: ShizukuBackend = ShizukuBackend.SHIZUKU) : ShizukuState
    data class Ready(
        val uid: Int,
        val apiVersion: Int,
        override val backend: ShizukuBackend = ShizukuBackend.SHIZUKU,
    ) : ShizukuState
}
