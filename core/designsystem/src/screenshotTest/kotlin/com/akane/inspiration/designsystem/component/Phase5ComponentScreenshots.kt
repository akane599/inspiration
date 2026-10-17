package com.akane.inspiration.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.akane.inspiration.designsystem.theme.InspirationTheme
import com.android.tools.screenshot.PreviewTest

// Rendered on the JVM: ./gradlew :core:designsystem:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff)
// Heights are explicit: the renderer caps unspecified heights.

@PreviewTest @Preview(name = "dark", showBackground = true, widthDp = 412, heightDp = 800)
@Composable fun ControlsDark() = InspirationTheme(darkTheme = true) { ControlsSheet() }

@PreviewTest @Preview(name = "light", showBackground = true, widthDp = 412, heightDp = 800)
@Composable fun ControlsLight() = InspirationTheme(darkTheme = false) { ControlsSheet() }

@PreviewTest @Preview(name = "large-font", showBackground = true, widthDp = 412, heightDp = 960, fontScale = 1.5f)
@Composable fun ControlsLargeFont() = InspirationTheme(darkTheme = true) { ControlsSheet() }

@PreviewTest @Preview(name = "narrow-360", showBackground = true, widthDp = 360, heightDp = 860)
@Composable fun ControlsNarrow() = InspirationTheme(darkTheme = true) { ControlsSheet() }

@PreviewTest @Preview(name = "wide-840", showBackground = true, widthDp = 840, heightDp = 480)
@Composable fun ControlsWide() = InspirationTheme(darkTheme = true) { ControlsSheet() }

@PreviewTest @Preview(name = "turkish", showBackground = true, widthDp = 412, heightDp = 800, locale = "tr")
@Composable fun ControlsTurkish() = InspirationTheme(darkTheme = true) { ControlsSheet() }

@PreviewTest @Preview(name = "dark", showBackground = true, widthDp = 412, heightDp = 750)
@Composable fun TelemetryDark() = InspirationTheme(darkTheme = true) { TelemetrySheet() }

@PreviewTest @Preview(name = "light", showBackground = true, widthDp = 412, heightDp = 750)
@Composable fun TelemetryLight() = InspirationTheme(darkTheme = false) { TelemetrySheet() }

@PreviewTest @Preview(name = "large-font", showBackground = true, widthDp = 412, heightDp = 1010, fontScale = 1.5f)
@Composable fun TelemetryLargeFont() = InspirationTheme(darkTheme = true) { TelemetrySheet() }

@PreviewTest @Preview(name = "narrow-360", showBackground = true, widthDp = 360, heightDp = 780)
@Composable fun TelemetryNarrow() = InspirationTheme(darkTheme = true) { TelemetrySheet() }

@PreviewTest @Preview(name = "wide-840", showBackground = true, widthDp = 840, heightDp = 480)
@Composable fun TelemetryWide() = InspirationTheme(darkTheme = true) { TelemetrySheet() }

@PreviewTest @Preview(name = "turkish", showBackground = true, widthDp = 412, heightDp = 750, locale = "tr")
@Composable fun TelemetryTurkish() = InspirationTheme(darkTheme = true) { TelemetrySheet() }
