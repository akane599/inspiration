package com.akane.inspiration.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.akane.inspiration.designsystem.theme.InspirationTheme
import com.android.tools.screenshot.PreviewTest

// Rendered on the JVM: ./gradlew :core:designsystem:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff)
// Heights are explicit: the renderer caps unspecified heights.

@PreviewTest @Preview(name = "dark", showBackground = true, widthDp = 412, heightDp = 460)
@Composable fun ComponentsDark() = InspirationTheme(darkTheme = true) { ComponentSheet() }

@PreviewTest @Preview(name = "light", showBackground = true, widthDp = 412, heightDp = 460)
@Composable fun ComponentsLight() = InspirationTheme(darkTheme = false) { ComponentSheet() }

@PreviewTest @Preview(name = "large-font", showBackground = true, widthDp = 412, heightDp = 580, fontScale = 1.5f)
@Composable fun ComponentsLargeFont() = InspirationTheme(darkTheme = true) { ComponentSheet() }
