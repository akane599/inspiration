package com.akane.inspiration

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.akane.inspiration.dashboard.CockpitRoute
import com.akane.inspiration.designsystem.theme.InspirationTheme
import com.akane.inspiration.health.HealthRoute
import com.akane.inspiration.shell.AppShell
import com.akane.inspiration.shell.Destination
import com.akane.inspiration.sleeplab.SleepLabRoute
import com.akane.inspiration.toolkit.ToolkitRoute
import com.akane.inspiration.xray.XRayRoute

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        (application as InspirationApplication).captureAppOpen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The theme is forced dark (AMOLED), so system bars use light icons on a transparent scrim.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val container = (application as InspirationApplication).appContainer
        setContent {
            InspirationTheme {
                AppShell(content = { destination, modifier -> DestinationRoute(destination, container, modifier) })
            }
        }
    }
}

/** Each route receives only its own pinned dependencies interface, never the container itself. */
@Composable
private fun DestinationRoute(destination: Destination, container: AppContainer, modifier: Modifier) {
    when (destination) {
        Destination.Cockpit -> CockpitRoute(container, modifier)
        Destination.XRay -> XRayRoute(container, modifier)
        Destination.SleepLab -> SleepLabRoute(container, modifier)
        Destination.Health -> HealthRoute(container, modifier)
        Destination.Toolkit -> ToolkitRoute(container, modifier)
    }
}
