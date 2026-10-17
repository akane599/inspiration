package com.akane.inspiration.shell

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.akane.inspiration.dashboard.CockpitRoute
import com.akane.inspiration.designsystem.theme.InspirationTheme
import com.akane.inspiration.health.HealthRoute
import com.akane.inspiration.sleeplab.SleepLabRoute
import com.akane.inspiration.toolkit.ToolkitRoute
import com.akane.inspiration.xray.XRayRoute
import com.android.tools.screenshot.PreviewTest

// Rendered on the JVM: ./gradlew :app:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff).
// Heights are explicit: the renderer caps unspecified heights. The routes are the stub placeholders for now;
// the fake dependencies reach no Shizuku, database or clock.

@Composable
private fun ShellContent(selected: Destination = Destination.Cockpit) {
    val state = remember { ShellState(selected) }
    AppShell(
        state = state,
        content = { destination, modifier -> PreviewRoute(destination, modifier) },
    )
}

@Composable
private fun PreviewRoute(destination: Destination, modifier: Modifier) {
    val deps = PreviewDependencies
    when (destination) {
        Destination.Cockpit -> CockpitRoute(deps, modifier)
        Destination.XRay -> XRayRoute(deps, modifier)
        Destination.SleepLab -> SleepLabRoute(deps, modifier)
        Destination.Health -> HealthRoute(deps, modifier)
        Destination.Toolkit -> ToolkitRoute(deps, modifier)
    }
}

@PreviewTest
@Preview(name = "light", showBackground = true, widthDp = 412, heightDp = 800)
@Composable
fun ShellLight() = InspirationTheme(darkTheme = false) { ShellContent() }

@PreviewTest
@Preview(name = "dark", showBackground = true, widthDp = 412, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ShellDark() = InspirationTheme(darkTheme = true) { ShellContent() }

@PreviewTest
@Preview(name = "large-font", showBackground = true, widthDp = 412, heightDp = 800, fontScale = 1.5f)
@Composable
fun ShellLargeFont() = InspirationTheme { ShellContent(Destination.SleepLab) }

@PreviewTest
@Preview(name = "narrow-360", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun ShellNarrow() = InspirationTheme { ShellContent(Destination.Health) }

@PreviewTest
@Preview(name = "narrow-360-large-font", showBackground = true, widthDp = 360, heightDp = 640, fontScale = 1.5f)
@Composable
fun ShellNarrowLargeFont() = InspirationTheme { ShellContent(Destination.Toolkit) }

@PreviewTest
@Preview(name = "turkish", showBackground = true, widthDp = 360, heightDp = 640, locale = "tr")
@Composable
fun ShellTurkish() = InspirationTheme { ShellContent(Destination.SleepLab) }

@PreviewTest
@Preview(name = "rail-840", showBackground = true, widthDp = 840, heightDp = 600)
@Composable
fun ShellRail() = InspirationTheme { ShellContent(Destination.XRay) }

@PreviewTest
@Preview(name = "rail-840-large-font", showBackground = true, widthDp = 840, heightDp = 600, fontScale = 1.5f)
@Composable
fun ShellRailLargeFont() = InspirationTheme(darkTheme = false) { ShellContent(Destination.Cockpit) }
