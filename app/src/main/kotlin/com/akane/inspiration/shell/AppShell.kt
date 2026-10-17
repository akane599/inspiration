package com.akane.inspiration.shell

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.akane.inspiration.R

@StringRes
internal fun Destination.labelRes(): Int = when (this) {
    Destination.Cockpit -> R.string.nav_cockpit
    Destination.XRay -> R.string.nav_xray
    Destination.SleepLab -> R.string.nav_sleep_lab
    Destination.Health -> R.string.nav_health
    Destination.Toolkit -> R.string.nav_toolkit
}

internal fun Destination.icon(): ImageVector = when (this) {
    Destination.Cockpit -> ShellIcons.Cockpit
    Destination.XRay -> ShellIcons.XRay
    Destination.SleepLab -> ShellIcons.SleepLab
    Destination.Health -> ShellIcons.Health
    Destination.Toolkit -> ShellIcons.Toolkit
}

/**
 * The app frame (H1): a bottom [NavigationBar] on phones and a [NavigationRail] from
 * [ShellNavigation.RAIL_MIN_WIDTH_DP] up, five flat destinations, no navigation library.
 *
 * Only the selected destination is composed, so a screen that leaves composition stops its own work
 * (H13). Each destination keeps its scroll and input state in its own `SaveableStateHolder` slot while
 * it is away. Back from any other destination returns to Cockpit; Back on Cockpit leaves the app.
 *
 * [content] draws one destination into the area the shell hands it (window insets already applied).
 */
@Composable
fun AppShell(
    content: @Composable (destination: Destination, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
    state: ShellState = rememberShellState(),
) {
    val stateHolder = rememberSaveableStateHolder()
    BackHandler(enabled = state.selected != Destination.Cockpit) { state.onBack() }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val destinationArea: @Composable (Modifier) -> Unit = { areaModifier ->
                Box(areaModifier) {
                    stateHolder.SaveableStateProvider(state.selected.key) {
                        content(state.selected, Modifier.fillMaxSize())
                    }
                }
            }
            if (ShellNavigation.usesRail(maxWidth.value)) {
                Row(Modifier.fillMaxSize()) {
                    ShellRail(state)
                    destinationArea(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End),
                            ),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    destinationArea(
                        Modifier
                            .weight(1f)
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                            ),
                    )
                    ShellBar(state)
                }
            }
        }
    }
}

/**
 * Navigation labels stop growing at [NAV_MAX_FONT_SCALE]: five items share a 360 dp bar, and a label wider
 * than its slot is clipped. Every item keeps its icon, so nothing becomes unreadable; the screens themselves
 * follow the system font scale in full.
 */
@Composable
private fun WithCappedFontScale(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val capped = Density(density.density, minOf(density.fontScale, NAV_MAX_FONT_SCALE))
    CompositionLocalProvider(LocalDensity provides capped, content = content)
}

private const val NAV_MAX_FONT_SCALE = 1.2f

@Composable
private fun NavLabel(destination: Destination) {
    Text(stringResource(destination.labelRes()), maxLines = 2, textAlign = TextAlign.Center)
}

@Composable
private fun ShellBar(state: ShellState) {
    WithCappedFontScale {
        NavigationBar {
            Destination.entries.forEach { destination ->
                NavigationBarItem(
                    selected = state.selected == destination,
                    onClick = { state.select(destination) },
                    icon = { Icon(destination.icon(), contentDescription = null) },
                    label = { NavLabel(destination) },
                )
            }
        }
    }
}

@Composable
private fun ShellRail(state: ShellState) {
    WithCappedFontScale {
        NavigationRail {
            Destination.entries.forEach { destination ->
                NavigationRailItem(
                    selected = state.selected == destination,
                    onClick = { state.select(destination) },
                    icon = { Icon(destination.icon(), contentDescription = null) },
                    label = { NavLabel(destination) },
                )
            }
        }
    }
}
