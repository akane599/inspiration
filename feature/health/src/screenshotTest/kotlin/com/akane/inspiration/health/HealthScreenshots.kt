package com.akane.inspiration.health

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

// Rendered on the JVM: ./gradlew :feature:health:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff)
// Heights are explicit: the renderer caps unspecified heights, and the screen scrolls, so each preview is tall
// enough to show the whole column.

@PreviewTest @Preview(name = "charging-live-light", showBackground = true, widthDp = 412, heightDp = 1900)
@Composable fun HealthLight() = HealthPreviewHost(darkTheme = false, state = HealthPreviewData.charging)

@PreviewTest @Preview(name = "charging-live-dark", showBackground = true, widthDp = 412, heightDp = 1900)
@Composable fun HealthDark() = HealthPreviewHost(darkTheme = true, state = HealthPreviewData.charging)

@PreviewTest @Preview(name = "large-font", showBackground = true, widthDp = 412, heightDp = 3100, fontScale = 1.5f)
@Composable fun HealthLargeFont() = HealthPreviewHost(darkTheme = true, state = HealthPreviewData.charging)

@PreviewTest @Preview(name = "narrow-360", showBackground = true, widthDp = 360, heightDp = 2000)
@Composable fun HealthNarrow() = HealthPreviewHost(darkTheme = true, state = HealthPreviewData.charging)

@PreviewTest @Preview(name = "wide-840", showBackground = true, widthDp = 840, heightDp = 1250)
@Composable fun HealthWide() = HealthPreviewHost(darkTheme = true, state = HealthPreviewData.charging)

@PreviewTest @Preview(name = "history-only", showBackground = true, widthDp = 412, heightDp = 1800)
@Composable fun HealthHistoryOnly() = HealthPreviewHost(darkTheme = true, state = HealthPreviewData.historyOnly)

@PreviewTest @Preview(name = "history-only-light", showBackground = true, widthDp = 412, heightDp = 1800)
@Composable fun HealthHistoryOnlyLight() = HealthPreviewHost(darkTheme = false, state = HealthPreviewData.historyOnly)

@PreviewTest @Preview(name = "quiet", showBackground = true, widthDp = 412, heightDp = 1500)
@Composable fun HealthQuiet() = HealthPreviewHost(darkTheme = true, state = HealthPreviewData.quiet)

@PreviewTest @Preview(name = "empty", showBackground = true, widthDp = 412, heightDp = 560)
@Composable fun HealthEmpty() = HealthPreviewHost(darkTheme = true, state = HealthPreviewData.empty)

@PreviewTest @Preview(name = "error", showBackground = true, widthDp = 412, heightDp = 560)
@Composable fun HealthError() = HealthPreviewHost(darkTheme = true, state = HealthPreviewData.error)

@PreviewTest @Preview(name = "loading", showBackground = true, widthDp = 412, heightDp = 360)
@Composable fun HealthLoading() = HealthPreviewHost(darkTheme = true, state = HealthPreviewData.loading)

@PreviewTest @Preview(name = "turkish", showBackground = true, widthDp = 412, heightDp = 2000, locale = "tr")
@Composable fun HealthTurkish() = HealthPreviewHost(darkTheme = true, state = HealthPreviewData.charging)
