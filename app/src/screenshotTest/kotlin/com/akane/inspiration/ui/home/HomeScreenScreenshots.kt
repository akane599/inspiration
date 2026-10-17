package com.akane.inspiration.ui.home

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.akane.inspiration.designsystem.theme.InspirationTheme
import kotlinx.collections.immutable.persistentListOf

// Rendered on the JVM: ./gradlew :app:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff)
// Heights are explicit: the renderer caps unspecified heights.
private val sample = HomeUiState(items = persistentListOf("Item 1", "Item 2", "Item 3"))

@PreviewTest @Preview(name = "light", showBackground = true, widthDp = 412, heightDp = 800)
@Composable fun HomeLight() = InspirationTheme(darkTheme = false) { HomeScreen(sample, onAdd = {}) }

@PreviewTest @Preview(name = "dark", showBackground = true, widthDp = 412, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable fun HomeDark() = InspirationTheme(darkTheme = true) { HomeScreen(sample, onAdd = {}) }

@PreviewTest @Preview(name = "large-font", showBackground = true, widthDp = 412, heightDp = 800, fontScale = 1.5f)
@Composable fun HomeLargeFont() = InspirationTheme { HomeScreen(sample, onAdd = {}) }

@PreviewTest @Preview(name = "empty", showBackground = true, widthDp = 412, heightDp = 800)
@Composable fun HomeEmpty() = InspirationTheme { HomeScreen(HomeUiState(), onAdd = {}) }

@PreviewTest @Preview(name = "tablet", showBackground = true, widthDp = 840, heightDp = 600)
@Composable fun HomeTablet() = InspirationTheme { HomeScreen(sample, onAdd = {}) }
