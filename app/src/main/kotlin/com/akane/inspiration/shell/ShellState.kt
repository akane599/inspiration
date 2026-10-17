package com.akane.inspiration.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * The five top-level destinations, in navigation order. [key] is the persisted form (it survives process
 * death and must never change); there are no deep links, so nothing else names a destination.
 */
enum class Destination(val key: String) {
    Cockpit("cockpit"),
    XRay("xray"),
    SleepLab("sleeplab"),
    Health("health"),
    Toolkit("toolkit"),
    ;

    companion object {
        /** Unknown or missing keys land on Cockpit. */
        fun fromKey(key: String?): Destination = entries.firstOrNull { it.key == key } ?: Cockpit
    }
}

/** The shell's pure layout and back rules (H1), kept free of Compose so a plain JUnit test covers them. */
object ShellNavigation {
    /** From this width on the shell uses a navigation rail instead of the bottom bar. */
    const val RAIL_MIN_WIDTH_DP = 600

    fun usesRail(widthDp: Float): Boolean = widthDp >= RAIL_MIN_WIDTH_DP

    /** Where Back goes from [current]: Cockpit, or null on Cockpit itself so the system leaves the app. */
    fun backTarget(current: Destination): Destination? = if (current == Destination.Cockpit) null else Destination.Cockpit
}

/** The selected destination. Saved across configuration changes and process death by [rememberShellState]. */
@Stable
class ShellState(initial: Destination = Destination.Cockpit) {
    var selected: Destination by mutableStateOf(initial)
        private set

    fun select(destination: Destination) {
        selected = destination
    }

    /** Returns true when Back was consumed (moved to Cockpit), false when the system should handle it. */
    fun onBack(): Boolean {
        val target = ShellNavigation.backTarget(selected) ?: return false
        selected = target
        return true
    }

    companion object {
        val Saver: Saver<ShellState, String> = Saver(
            save = { it.selected.key },
            restore = { ShellState(Destination.fromKey(it)) },
        )
    }
}

@Composable
fun rememberShellState(initial: Destination = Destination.Cockpit): ShellState =
    rememberSaveable(saver = ShellState.Saver) { ShellState(initial) }
