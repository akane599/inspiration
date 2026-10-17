package com.akane.inspiration.designsystem.chart.hypnogram

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.theme.InspirationTheme
import com.android.tools.screenshot.PreviewTest

// Rendered on the JVM: ./gradlew :core:designsystem:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff)
// Heights are explicit: the renderer caps unspecified heights.

private val Selection = TimeRange.of(nightMs(120), nightMs(270))
private val Cursor = nightMs(195)

@PreviewTest @Preview(name = "dark", showBackground = true, widthDp = 412, heightDp = 240)
@Composable fun HypnogramDark() = InspirationTheme(darkTheme = true) { HypnogramSheet(syntheticNight()) }

@PreviewTest @Preview(name = "light", showBackground = true, widthDp = 412, heightDp = 240)
@Composable fun HypnogramLight() = InspirationTheme(darkTheme = false) { HypnogramSheet(syntheticNight()) }

@PreviewTest @Preview(name = "large-font", showBackground = true, widthDp = 412, heightDp = 270, fontScale = 1.5f)
@Composable fun HypnogramLargeFont() = InspirationTheme(darkTheme = true) { HypnogramSheet(syntheticNight()) }

@PreviewTest @Preview(name = "narrow-360", showBackground = true, widthDp = 360, heightDp = 240)
@Composable fun HypnogramNarrow() = InspirationTheme(darkTheme = true) { HypnogramSheet(syntheticNight()) }

@PreviewTest @Preview(name = "narrow-360-large-font", showBackground = true, widthDp = 360, heightDp = 290, fontScale = 1.5f)
@Composable fun HypnogramNarrowLargeFont() = InspirationTheme(darkTheme = true) { HypnogramSheet(syntheticNight()) }

@PreviewTest @Preview(name = "wide", showBackground = true, widthDp = 840, heightDp = 240)
@Composable fun HypnogramWide() = InspirationTheme(darkTheme = true) { HypnogramSheet(syntheticNight()) }

@PreviewTest @Preview(name = "selection", showBackground = true, widthDp = 412, heightDp = 240)
@Composable fun HypnogramSelection() = InspirationTheme(darkTheme = true) {
    HypnogramSheet(syntheticNight(), selection = Selection)
}

@PreviewTest @Preview(name = "selection-light", showBackground = true, widthDp = 412, heightDp = 240)
@Composable fun HypnogramSelectionLight() = InspirationTheme(darkTheme = false) {
    HypnogramSheet(syntheticNight(), selection = Selection)
}

@PreviewTest @Preview(name = "cursor", showBackground = true, widthDp = 412, heightDp = 240)
@Composable fun HypnogramCursor() = InspirationTheme(darkTheme = true) {
    HypnogramSheet(syntheticNight(), cursorMs = Cursor)
}

@PreviewTest @Preview(name = "gaps", showBackground = true, widthDp = 412, heightDp = 240)
@Composable fun HypnogramGaps() = InspirationTheme(darkTheme = true) { HypnogramSheet(partialNight()) }

@PreviewTest @Preview(name = "gaps-light", showBackground = true, widthDp = 412, heightDp = 240)
@Composable fun HypnogramGapsLight() = InspirationTheme(darkTheme = false) { HypnogramSheet(partialNight()) }

@PreviewTest @Preview(name = "empty", showBackground = true, widthDp = 412, heightDp = 240)
@Composable fun HypnogramEmpty() = InspirationTheme(darkTheme = true) { HypnogramSheet(emptyNight()) }
