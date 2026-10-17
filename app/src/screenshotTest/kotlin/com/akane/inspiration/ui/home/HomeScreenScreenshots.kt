package com.akane.inspiration.ui.home

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.akane.inspiration.ui.theme.AppTheme
import kotlinx.collections.immutable.persistentListOf

// Rendered on the JVM: ./gradlew :app:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff)
private val sample = HomeUiState(items = persistentListOf("Item 1", "Item 2", "Item 3"))

@PreviewTest @Preview(name = "light", showBackground = true)
@Composable fun HomeLight() = AppTheme(darkTheme = false) { HomeScreen(sample, onAdd = {}) }

@PreviewTest @Preview(name = "dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable fun HomeDark() = AppTheme(darkTheme = true) { HomeScreen(sample, onAdd = {}) }

@PreviewTest @Preview(name = "large-font", showBackground = true, fontScale = 1.5f)
@Composable fun HomeLargeFont() = AppTheme { HomeScreen(sample, onAdd = {}) }

@PreviewTest @Preview(name = "empty", showBackground = true)
@Composable fun HomeEmpty() = AppTheme { HomeScreen(HomeUiState(), onAdd = {}) }

@PreviewTest @Preview(name = "tablet", showBackground = true, widthDp = 840, heightDp = 600)
@Composable fun HomeTablet() = AppTheme { HomeScreen(sample, onAdd = {}) }
