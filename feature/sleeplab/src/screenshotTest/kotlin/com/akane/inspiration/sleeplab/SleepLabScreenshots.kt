package com.akane.inspiration.sleeplab

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

// Rendered on the JVM: ./gradlew :feature:sleeplab:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff)
// Heights are explicit: the renderer caps unspecified heights, and the screen scrolls, so each preview is tall
// enough to show the whole column.

@PreviewTest @Preview(name = "light", showBackground = true, widthDp = 412, heightDp = 1800)
@Composable fun SleepLabLight() = SleepLabPreviewHost(darkTheme = false, state = SleepLabPreviewData.apps)

@PreviewTest @Preview(name = "dark", showBackground = true, widthDp = 412, heightDp = 1800)
@Composable fun SleepLabDark() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.apps)

@PreviewTest @Preview(name = "large-font", showBackground = true, widthDp = 412, heightDp = 2600, fontScale = 1.5f)
@Composable fun SleepLabLargeFont() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.apps)

@PreviewTest @Preview(name = "narrow-360", showBackground = true, widthDp = 360, heightDp = 1900)
@Composable fun SleepLabNarrow() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.apps)

@PreviewTest @Preview(name = "narrow-360-large-font", showBackground = true, widthDp = 360, heightDp = 2900, fontScale = 1.5f)
@Composable fun SleepLabNarrowLargeFont() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.apps)

@PreviewTest @Preview(name = "wide-840", showBackground = true, widthDp = 840, heightDp = 1300)
@Composable fun SleepLabWide() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.apps)

@PreviewTest @Preview(name = "kernel", showBackground = true, widthDp = 412, heightDp = 1800)
@Composable fun SleepLabKernelTab() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.kernelTab)

@PreviewTest @Preview(name = "alarms", showBackground = true, widthDp = 412, heightDp = 1800)
@Composable fun SleepLabAlarmsTab() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.alarmsTab)

@PreviewTest @Preview(name = "alarms-light", showBackground = true, widthDp = 412, heightDp = 1800)
@Composable fun SleepLabAlarmsTabLight() = SleepLabPreviewHost(darkTheme = false, state = SleepLabPreviewData.alarmsTab)

@PreviewTest @Preview(name = "needs-full-snapshots", showBackground = true, widthDp = 412, heightDp = 1700)
@Composable fun SleepLabNeedsFull() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.needsFull)

@PreviewTest @Preview(name = "apps-unchecked", showBackground = true, widthDp = 412, heightDp = 1700)
@Composable fun SleepLabAppsUnchecked() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.appsUnchecked)

@PreviewTest @Preview(name = "apps-unchecked-alarms", showBackground = true, widthDp = 412, heightDp = 1700)
@Composable fun SleepLabAppsUncheckedAlarms() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.appsUncheckedAlarms)

@PreviewTest @Preview(name = "counter-reset", showBackground = true, widthDp = 412, heightDp = 1700)
@Composable fun SleepLabCounterReset() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.counterReset)

@PreviewTest @Preview(name = "not-captured", showBackground = true, widthDp = 412, heightDp = 2000)
@Composable fun SleepLabNotCaptured() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.notCaptured)

@PreviewTest @Preview(name = "not-captured-turkish", showBackground = true, widthDp = 412, heightDp = 2200, locale = "tr")
@Composable fun SleepLabNotCapturedTurkish() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.notCaptured)

@PreviewTest @Preview(name = "stats-reset", showBackground = true, widthDp = 412, heightDp = 2000)
@Composable fun SleepLabStatsReset() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.statsReset)

@PreviewTest @Preview(name = "hypnogram-empty", showBackground = true, widthDp = 412, heightDp = 1800)
@Composable fun SleepLabHypnogramEmpty() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.hypnogramEmpty)

@PreviewTest @Preview(name = "hypnogram-gap", showBackground = true, widthDp = 412, heightDp = 1800)
@Composable fun SleepLabHypnogramGap() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.hypnogramGap)

@PreviewTest @Preview(name = "hypnogram-failed", showBackground = true, widthDp = 412, heightDp = 1800)
@Composable fun SleepLabHypnogramFailed() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.hypnogramFailed)

@PreviewTest @Preview(name = "time-slice", showBackground = true, widthDp = 412, heightDp = 1900)
@Composable fun SleepLabTimeSlice() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.slice)

@PreviewTest @Preview(name = "culprit", showBackground = true, widthDp = 412, heightDp = 2500)
@Composable fun SleepLabCulprit() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.culprit)

@PreviewTest @Preview(name = "culprit-light", showBackground = true, widthDp = 412, heightDp = 2500)
@Composable fun SleepLabCulpritLight() = SleepLabPreviewHost(darkTheme = false, state = SleepLabPreviewData.culprit)

@PreviewTest @Preview(name = "culprit-whole-interval", showBackground = true, widthDp = 412, heightDp = 2500)
@Composable fun SleepLabCulpritWholeInterval() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.culpritWholeInterval)

@PreviewTest @Preview(name = "culprit-needs-full-snapshots", showBackground = true, widthDp = 412, heightDp = 2300)
@Composable fun SleepLabCulpritNeedsFull() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.culpritNeedsFull)

@PreviewTest @Preview(name = "culprit-not-evaluable", showBackground = true, widthDp = 412, heightDp = 2000)
@Composable fun SleepLabCulpritNotEvaluable() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.culpritNotEvaluable)

@PreviewTest @Preview(name = "culprit-turkish-narrow-large-font", showBackground = true, widthDp = 360, heightDp = 4000, fontScale = 1.5f, locale = "tr")
@Composable fun SleepLabCulpritTurkishNarrowLargeFont() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.culprit)

@PreviewTest @Preview(name = "empty", showBackground = true, widthDp = 412, heightDp = 800)
@Composable fun SleepLabEmpty() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.empty)

@PreviewTest @Preview(name = "error", showBackground = true, widthDp = 412, heightDp = 800)
@Composable fun SleepLabError() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.error)

@PreviewTest @Preview(name = "loading", showBackground = true, widthDp = 412, heightDp = 800)
@Composable fun SleepLabLoading() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.loading)

@PreviewTest @Preview(name = "turkish", showBackground = true, widthDp = 412, heightDp = 1900, locale = "tr")
@Composable fun SleepLabTurkish() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.apps)

@PreviewTest @Preview(name = "turkish-alarms-narrow-large-font", showBackground = true, widthDp = 360, heightDp = 2900, fontScale = 1.5f, locale = "tr")
@Composable fun SleepLabTurkishAlarmsNarrowLargeFont() = SleepLabPreviewHost(darkTheme = true, state = SleepLabPreviewData.alarmsTab)
