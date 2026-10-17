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

@PreviewTest @Preview(name = "dark", showBackground = true, widthDp = 412, heightDp = 400)
@Composable fun ScrubChartDark() = ScrubChartSheet(darkTheme = true)

@PreviewTest @Preview(name = "light", showBackground = true, widthDp = 412, heightDp = 400)
@Composable fun ScrubChartLight() = ScrubChartSheet(darkTheme = false)

@PreviewTest @Preview(name = "large-font", showBackground = true, widthDp = 412, heightDp = 452, fontScale = 1.5f)
@Composable fun ScrubChartLargeFont() = ScrubChartSheet(darkTheme = true)

@PreviewTest @Preview(name = "wide-840", showBackground = true, widthDp = 840, heightDp = 400)
@Composable fun ScrubChartWide() = ScrubChartSheet(darkTheme = true)

@PreviewTest @Preview(name = "selection-dark", showBackground = true, widthDp = 412, heightDp = 400)
@Composable fun ScrubChartSelectionDark() = ScrubChartSheet(darkTheme = true, selection = ScrubPreviewData.selection)

@PreviewTest @Preview(name = "selection-light", showBackground = true, widthDp = 412, heightDp = 400)
@Composable fun ScrubChartSelectionLight() = ScrubChartSheet(darkTheme = false, selection = ScrubPreviewData.selection)

@PreviewTest @Preview(name = "cursor", showBackground = true, widthDp = 412, heightDp = 400)
@Composable fun ScrubChartCursor() = ScrubChartSheet(darkTheme = true, fixedCursorMs = ScrubPreviewData.cursorMs)

@PreviewTest @Preview(name = "empty", showBackground = true, widthDp = 412, heightDp = 400)
@Composable fun ScrubChartEmpty() = ScrubChartSheet(darkTheme = true, data = ScrubPreviewData.empty)
