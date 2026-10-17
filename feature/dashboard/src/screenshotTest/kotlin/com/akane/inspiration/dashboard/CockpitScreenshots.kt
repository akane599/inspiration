package com.akane.inspiration.dashboard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.akane.inspiration.designsystem.theme.InspirationTheme
import com.android.tools.screenshot.PreviewTest

// Rendered on the JVM: ./gradlew :feature:dashboard:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff)
// Heights are explicit: the renderer caps unspecified heights, and the screen scrolls, so each preview is tall
// enough to show the whole column.

@PreviewTest @Preview(name = "light", showBackground = true, widthDp = 412, heightDp = 1300)
@Composable fun CockpitLight() = CockpitPreviewHost(darkTheme = false, state = CockpitPreviewData.ready)

@PreviewTest @Preview(name = "dark", showBackground = true, widthDp = 412, heightDp = 1300)
@Composable fun CockpitDark() = CockpitPreviewHost(darkTheme = true, state = CockpitPreviewData.ready)

@PreviewTest @Preview(name = "large-font", showBackground = true, widthDp = 412, heightDp = 1900, fontScale = 1.5f)
@Composable fun CockpitLargeFont() = CockpitPreviewHost(darkTheme = true, state = CockpitPreviewData.ready)

@PreviewTest @Preview(name = "narrow-360", showBackground = true, widthDp = 360, heightDp = 1450)
@Composable fun CockpitNarrow() = CockpitPreviewHost(darkTheme = true, state = CockpitPreviewData.ready)

@PreviewTest @Preview(name = "narrow-360-large-font", showBackground = true, widthDp = 360, heightDp = 2150, fontScale = 1.5f)
@Composable fun CockpitNarrowLargeFont() = CockpitPreviewHost(darkTheme = true, state = CockpitPreviewData.ready)

@PreviewTest @Preview(name = "wide-840", showBackground = true, widthDp = 840, heightDp = 940)
@Composable fun CockpitWide() = CockpitPreviewHost(darkTheme = true, state = CockpitPreviewData.ready)

@PreviewTest @Preview(name = "empty", showBackground = true, widthDp = 412, heightDp = 1500)
@Composable fun CockpitEmpty() = CockpitPreviewHost(darkTheme = true, state = CockpitPreviewData.empty)

@PreviewTest @Preview(name = "error", showBackground = true, widthDp = 412, heightDp = 1300)
@Composable fun CockpitError() = CockpitPreviewHost(darkTheme = true, state = CockpitPreviewData.error)

@PreviewTest @Preview(name = "loading", showBackground = true, widthDp = 412, heightDp = 1300)
@Composable fun CockpitLoading() = CockpitPreviewHost(darkTheme = true, state = CockpitPreviewData.loading)

@PreviewTest @Preview(name = "time-slice", showBackground = true, widthDp = 412, heightDp = 1500)
@Composable fun CockpitTimeSlice() = CockpitPreviewHost(darkTheme = true, state = CockpitPreviewData.slice)

@PreviewTest @Preview(name = "time-slice-light", showBackground = true, widthDp = 412, heightDp = 1500)
@Composable fun CockpitTimeSliceLight() = CockpitPreviewHost(darkTheme = false, state = CockpitPreviewData.slice)

@PreviewTest @Preview(name = "turkish", showBackground = true, widthDp = 412, heightDp = 1600, locale = "tr")
@Composable fun CockpitTurkish() = CockpitPreviewHost(darkTheme = true, state = CockpitPreviewData.ready)

@PreviewTest @Preview(name = "markers", showBackground = true, widthDp = 412, heightDp = 560)
@Composable fun CockpitMarkerSheetPreview() = InspirationTheme(darkTheme = true) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        MarkerSheetContent(sheet = CockpitPreviewData.markers, onIntent = {}, zone = CockpitPreviewData.zone)
    }
}
