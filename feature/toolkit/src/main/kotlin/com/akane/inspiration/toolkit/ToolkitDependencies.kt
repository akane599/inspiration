package com.akane.inspiration.toolkit

import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.telemetry.app.AppEntry
import com.akane.inspiration.telemetry.app.AppLabels
import com.akane.inspiration.telemetry.toolkit.ToolkitActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /**
     * Launchable packages. The screen adds the ones with an override still in effect, and sets every restricted
     * flag, from its own `toolkit.activeOverrides()` read; an implementation does not read overrides (F-19).
     */
    suspend fun installedApps(): List<AppEntry>

    /** The status-notification switch. Defaults keep an implementation that has no such notification compiling. */
    val statusNotificationEnabled: StateFlow<Boolean> get() = StatusNotificationDefaults.OFF

    fun setStatusNotificationEnabled(enabled: Boolean) {}

    /** True when the system will not show this app's notifications. Read again every time the screen starts. */
    fun notificationsBlocked(): Boolean = false
}

/** Shared "off" flow behind the [ToolkitDependencies] defaults. */
internal object StatusNotificationDefaults {
    val OFF: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
}
