package com.akane.inspiration.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

// Rendered on the JVM: ./gradlew :core:designsystem:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff)
// Heights are explicit: the renderer caps unspecified heights, which clipped the sheets.

@PreviewTest @Preview(name = "dark", showBackground = true, widthDp = 412, heightDp = 880)
@Composable fun TokenColorsDark() = InspirationTheme(darkTheme = true) { TokenSheetColors() }

@PreviewTest @Preview(name = "light", showBackground = true, widthDp = 412, heightDp = 880)
@Composable fun TokenColorsLight() = InspirationTheme(darkTheme = false) { TokenSheetColors() }

@PreviewTest @Preview(name = "large-font", showBackground = true, widthDp = 412, heightDp = 1200, fontScale = 1.5f)
@Composable fun TokenColorsLargeFont() = InspirationTheme(darkTheme = true) { TokenSheetColors() }

@PreviewTest @Preview(name = "dark", showBackground = true, widthDp = 412, heightDp = 1450)
@Composable fun TokenTypeDark() = InspirationTheme(darkTheme = true) { TokenSheetTypeAndShape() }

@PreviewTest @Preview(name = "light", showBackground = true, widthDp = 412, heightDp = 1450)
@Composable fun TokenTypeLight() = InspirationTheme(darkTheme = false) { TokenSheetTypeAndShape() }

@PreviewTest @Preview(name = "large-font", showBackground = true, widthDp = 412, heightDp = 1800, fontScale = 1.5f)
@Composable fun TokenTypeLargeFont() = InspirationTheme(darkTheme = true) { TokenSheetTypeAndShape() }
