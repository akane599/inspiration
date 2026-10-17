package com.akane.inspiration.toolkit

import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.telemetry.app.AppEntry
import com.akane.inspiration.telemetry.app.AppLabels
import com.akane.inspiration.telemetry.toolkit.ToolkitActions
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the Toolkit screen needs from the app, pinned by docs/plans/phase-5.md ("Feature dependencies").
 * `:app` implements it in AppContainer; the screen never sees the container, the raw gateway or an Intent.
 * Changing a member needs a story-log DECISION.
 */
interface ToolkitDependencies {
    val toolkit: ToolkitActions
    val appLabels: AppLabels
    val shizukuState: StateFlow<ShizukuState>

    /** Shows the Shizuku permission dialog; this is the only way to grant it from the app. */
    fun requestShizukuPermission()

    /** Launchable packages plus the ones the user already restricted. */
    suspend fun installedApps(): List<AppEntry>

    /** Keeps the Shizuku service bound (a running bg-dexopt job needs it); close it when done. */
    fun holdBinding(): AutoCloseable
}
