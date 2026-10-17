package com.akane.inspiration.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.akane.inspiration.designsystem.theme.InspirationTheme
import com.android.tools.screenshot.PreviewTest

// Rendered on the JVM: ./gradlew :core:designsystem:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff)
// Heights are explicit: the renderer caps unspecified heights.

@PreviewTest @Preview(name = "dark", showBackground = true, widthDp = 412, heightDp = 960)
@Composable fun ControlsDark() = InspirationTheme(darkTheme = true) { ControlsSheet() }

@PreviewTest @Preview(name = "light", showBackground = true, widthDp = 412, heightDp = 960)
@Composable fun ControlsLight() = InspirationTheme(darkTheme = false) { ControlsSheet() }

@PreviewTest @Preview(name = "large-font", showBackground = true, widthDp = 412, heightDp = 1400, fontScale = 1.5f)
@Composable fun ControlsLargeFont() = InspirationTheme(darkTheme = true) { ControlsSheet() }

@PreviewTest @Preview(name = "narrow-360", showBackground = true, widthDp = 360, heightDp = 1060)
@Composable fun ControlsNarrow() = InspirationTheme(darkTheme = true) { ControlsSheet() }

@PreviewTest @Preview(name = "wide-840", showBackground = true, widthDp = 840, heightDp = 620)
@Composable fun ControlsWide() = InspirationTheme(darkTheme = true) { ControlsSheet() }

@PreviewTest @Preview(name = "turkish", showBackground = true, widthDp = 412, heightDp = 960, locale = "tr")
@Composable fun ControlsTurkish() = InspirationTheme(darkTheme = true) { ControlsSheet() }

@PreviewTest @Preview(name = "dark", showBackground = true, widthDp = 412, heightDp = 1000)
@Composable fun TelemetryDark() = InspirationTheme(darkTheme = true) { TelemetrySheet() }

@PreviewTest @Preview(name = "light", showBackground = true, widthDp = 412, heightDp = 1000)
@Composable fun TelemetryLight() = InspirationTheme(darkTheme = false) { TelemetrySheet() }

@PreviewTest @Preview(name = "large-font", showBackground = true, widthDp = 412, heightDp = 1500, fontScale = 1.5f)
@Composable fun TelemetryLargeFont() = InspirationTheme(darkTheme = true) { TelemetrySheet() }

@PreviewTest @Preview(name = "narrow-360", showBackground = true, widthDp = 360, heightDp = 1000)
@Composable fun TelemetryNarrow() = InspirationTheme(darkTheme = true) { TelemetrySheet() }

@PreviewTest @Preview(name = "wide-840", showBackground = true, widthDp = 840, heightDp = 720)
@Composable fun TelemetryWide() = InspirationTheme(darkTheme = true) { TelemetrySheet() }

@PreviewTest @Preview(name = "turkish", showBackground = true, widthDp = 412, heightDp = 1000, locale = "tr")
@Composable fun TelemetryTurkish() = InspirationTheme(darkTheme = true) { TelemetrySheet() }

@PreviewTest @Preview(name = "dark", showBackground = true, widthDp = 412, heightDp = 700)
@Composable fun ChromeDark() = InspirationTheme(darkTheme = true) { ChromeSheet() }

@PreviewTest @Preview(name = "light", showBackground = true, widthDp = 412, heightDp = 700)
@Composable fun ChromeLight() = InspirationTheme(darkTheme = false) { ChromeSheet() }

@PreviewTest @Preview(name = "large-font", showBackground = true, widthDp = 412, heightDp = 1100, fontScale = 1.5f)
@Composable fun ChromeLargeFont() = InspirationTheme(darkTheme = true) { ChromeSheet() }

@PreviewTest @Preview(name = "narrow-360", showBackground = true, widthDp = 360, heightDp = 760)
@Composable fun ChromeNarrow() = InspirationTheme(darkTheme = true) { ChromeSheet() }

@PreviewTest @Preview(name = "turkish", showBackground = true, widthDp = 412, heightDp = 700, locale = "tr")
@Composable fun ChromeTurkish() = InspirationTheme(darkTheme = true) { ChromeSheet() }

@PreviewTest @Preview(name = "dark", showBackground = true, widthDp = 412, heightDp = 560)
@Composable fun ToggleDark() = InspirationTheme(darkTheme = true) { ToggleSheet() }

@PreviewTest @Preview(name = "light", showBackground = true, widthDp = 412, heightDp = 560)
@Composable fun ToggleLight() = InspirationTheme(darkTheme = false) { ToggleSheet() }

// The case S6 fixes: Turkish labels at the narrowest width and the largest font scale.
@PreviewTest @Preview(name = "turkish-360-large-font", showBackground = true, widthDp = 360, heightDp = 860, fontScale = 1.5f, locale = "tr")
@Composable fun ToggleTurkishNarrowLargeFont() = InspirationTheme(darkTheme = true) { ToggleSheet() }

// The case SQ-107 fixes: a NBSP-joined duration in a MetricTile at the narrowest width and the largest font scale.
@PreviewTest @Preview(name = "narrow-360-large-font", showBackground = true, widthDp = 360, heightDp = 560, fontScale = 1.5f)
@Composable fun DurationTilesNarrowLargeFont() = InspirationTheme(darkTheme = true) { DurationTilesSheet() }
