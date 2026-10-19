package com.akane.inspiration

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.akane.inspiration.dashboard.CockpitRoute
import com.akane.inspiration.designsystem.theme.InspirationTheme
import com.akane.inspiration.health.HealthRoute
import com.akane.inspiration.shell.AppShell
import com.akane.inspiration.shell.Destination
import com.akane.inspiration.status.StatusNotificationController
import com.akane.inspiration.sleeplab.SleepLabRoute
import com.akane.inspiration.toolkit.ToolkitRoute
import com.akane.inspiration.xray.XRayRoute

class MainActivity : ComponentActivity() {
    // The result is not needed: whichever way the user answered, the sync re-reads whether notifications can show.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            StatusNotificationController.sync(this)
        }

    override fun onStart() {
        super.onStart()
        val app = application as InspirationApplication
        // Access granted in the Shizuku app sends no callback; re-read it before the app-open capture needs it.
        app.appContainer.shizukuManager.refresh()
        app.captureAppOpen()
        syncStatusNotification(app.appContainer)
    }

    /** Asks for POST_NOTIFICATIONS once (API 33+) while the switch is on, then starts or stops the service. */
    private fun syncStatusNotification(container: AppContainer) {
        val needsPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            container.statusNotificationEnabled.value &&
            !container.postPromptShown &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPrompt) {
            container.markPostPromptShown()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            StatusNotificationController.sync(this)
        }
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
