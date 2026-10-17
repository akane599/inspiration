package com.akane.inspiration

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.akane.inspiration.designsystem.theme.InspirationTheme
import com.akane.inspiration.ui.home.HomeRoute

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
        setContent {
            InspirationTheme {
                HomeRoute()
            }
        }
    }
}
