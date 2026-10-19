package com.akane.inspiration.designsystem.chart.scrub

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.akane.inspiration.designsystem.chart.model.ScrubChartData
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.component.InspirationCard
import com.akane.inspiration.designsystem.theme.InspirationTheme
import com.akane.inspiration.designsystem.theme.spacing
import com.android.tools.screenshot.PreviewTest

// Rendered on the JVM: ./gradlew :core:designsystem:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff)
// Heights are explicit: the renderer caps unspecified heights.

@Composable
private fun ScrubChartSheet(
    darkTheme: Boolean,
    data: ScrubChartData = ScrubPreviewData.full,
    selection: TimeRange? = null,
    fixedCursorMs: Long? = null,
) = InspirationTheme(darkTheme = darkTheme) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        // The Column keeps Surface's min constraints from stretching the card to the full preview height.
        Column {
            InspirationCard(modifier = Modifier.padding(MaterialTheme.spacing.md).fillMaxWidth()) {
                SynchronizedScrubbingChart(
                    data = data,
                    selection = selection,
                    onSelectionChange = {},
                    fixedCursorMs = fixedCursorMs,
                    zone = ScrubPreviewData.zone,
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "dark", showBackground = true, widthDp = 412, heightDp = 512)
@Composable
fun ScrubChartDark() = ScrubChartSheet(darkTheme = true)

@PreviewTest
@Preview(name = "light", showBackground = true, widthDp = 412, heightDp = 512)
@Composable
fun ScrubChartLight() = ScrubChartSheet(darkTheme = false)

@PreviewTest
@Preview(name = "large-font", showBackground = true, widthDp = 412, heightDp = 600, fontScale = 1.5f)
@Composable
fun ScrubChartLargeFont() = ScrubChartSheet(darkTheme = true)

@PreviewTest
@Preview(name = "narrow-360", showBackground = true, widthDp = 360, heightDp = 544)
@Composable
fun ScrubChartNarrow() = ScrubChartSheet(darkTheme = true)

@PreviewTest
@Preview(name = "narrow-360-large-font", showBackground = true, widthDp = 360, heightDp = 600, fontScale = 1.5f)
@Composable
fun ScrubChartNarrowLargeFont() = ScrubChartSheet(darkTheme = true)

@PreviewTest
@Preview(name = "wide-840", showBackground = true, widthDp = 840, heightDp = 480)
@Composable
fun ScrubChartWide() = ScrubChartSheet(darkTheme = true)

@PreviewTest
@Preview(name = "selection-dark", showBackground = true, widthDp = 412, heightDp = 640)
@Composable
fun ScrubChartSelectionDark() = ScrubChartSheet(darkTheme = true, selection = ScrubPreviewData.selection)

@PreviewTest
@Preview(name = "selection-light", showBackground = true, widthDp = 412, heightDp = 640)
@Composable
fun ScrubChartSelectionLight() = ScrubChartSheet(darkTheme = false, selection = ScrubPreviewData.selection)

@PreviewTest
@Preview(name = "cursor", showBackground = true, widthDp = 412, heightDp = 512)
@Composable
fun ScrubChartCursor() = ScrubChartSheet(darkTheme = true, fixedCursorMs = ScrubPreviewData.cursorMs)

@PreviewTest
@Preview(name = "empty", showBackground = true, widthDp = 412, heightDp = 448)
@Composable
fun ScrubChartEmpty() = ScrubChartSheet(darkTheme = true, data = ScrubPreviewData.empty)

@PreviewTest
@Preview(name = "single-point", showBackground = true, widthDp = 412, heightDp = 512)
@Composable
fun ScrubChartSinglePoint() = ScrubChartSheet(darkTheme = true, data = ScrubPreviewData.singlePoint)

@PreviewTest
@Preview(name = "selection-narrow-large-font", showBackground = true, widthDp = 360, heightDp = 840, fontScale = 1.5f)
@Composable
fun ScrubChartSelectionNarrowLargeFont() = ScrubChartSheet(darkTheme = true, selection = ScrubPreviewData.selection)

@PreviewTest
@Preview(name = "selection-narrow-font-2", showBackground = true, widthDp = 360, heightDp = 1120, fontScale = 2f)
@Composable
fun ScrubChartSelectionNarrowFont2() = ScrubChartSheet(darkTheme = true, selection = ScrubPreviewData.selection)

@PreviewTest
@Preview(name = "selection-narrow-font-2-turkish", showBackground = true, widthDp = 360, heightDp = 1120, fontScale = 2f, locale = "tr")
@Composable
fun ScrubChartSelectionNarrowFont2Turkish() = ScrubChartSheet(darkTheme = true, selection = ScrubPreviewData.selection)

@PreviewTest
@Preview(name = "minimum-selection", showBackground = true, widthDp = 412, heightDp = 640)
@Composable
fun ScrubChartMinimumSelection() =
    ScrubChartSheet(
        darkTheme = true,
        selection = TimeRange.of(ScrubPreviewData.window.endMs - 5 * 60_000L, ScrubPreviewData.window.endMs),
    )

/** The window slid on: the slice now starts before it, so only its end (04:00) has a handle and the start has none. */
@PreviewTest
@Preview(name = "drifted-selection", showBackground = true, widthDp = 412, heightDp = 640)
@Composable
fun ScrubChartDriftedSelection() =
    ScrubChartSheet(darkTheme = true, selection = TimeRange.of(ScrubPreviewData.atHour(-3.0), ScrubPreviewData.atHour(4.0)))

/** A day-old slice wholly before the window: nothing is dimmed, no handle is drawn, the buttons offer only new or clear. */
@PreviewTest
@Preview(name = "stale-selection", showBackground = true, widthDp = 412, heightDp = 640)
@Composable
fun ScrubChartStaleSelection() =
    ScrubChartSheet(darkTheme = true, selection = TimeRange.of(ScrubPreviewData.atHour(-26.0), ScrubPreviewData.atHour(-25.0)))
