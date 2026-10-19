package com.akane.inspiration.xray

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.akane.inspiration.designsystem.theme.InspirationTheme
import com.akane.inspiration.designsystem.theme.spacing
import com.android.tools.screenshot.PreviewTest
import java.util.TimeZone

// Rendered on the JVM: ./gradlew :feature:xray:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff).
// Heights are explicit: the renderer caps unspecified heights. The time zone is fixed so times never move.
// The sheet and the dialog are windows the JVM renderer does not draw, so their bodies are rendered directly.

private val Utc: TimeZone = TimeZone.getTimeZone("UTC")

@Composable
private fun Screen(state: XRayState) {
    Surface(color = MaterialTheme.colorScheme.background) {
        XRayScreen(state = state, onIntent = {}, explain = XRayPreviewData.explain, timeZone = Utc)
    }
}

@Composable
private fun SheetBody(detail: AppDetail) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
        AppDetailContent(detail = detail, onIntent = {
        }, explain = XRayPreviewData.explain, modifier = Modifier.padding(top = MaterialTheme.spacing.md))
    }
}

@Composable
private fun DialogBody(confirmation: Confirmation) {
    Surface(color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)) {
        Box(modifier = Modifier.padding(MaterialTheme.spacing.lg), contentAlignment = Alignment.Center) {
            ConfirmationPanel(confirmation = confirmation, onConfirm = {}, onDismiss = {})
        }
    }
}

private val Components = XRayPreviewData.ready
private val Apps = XRayPreviewData.ready.copy(tab = XRayTab.APPS)

// ---- components tab: themes, font scale, widths -----------------------------------------------

@PreviewTest
@Preview(name = "components-light", showBackground = true, widthDp = 412, heightDp = 720)
@Composable
fun XRayComponentsLight() = InspirationTheme(darkTheme = false) { Screen(Components) }

@PreviewTest
@Preview(name = "components-dark", showBackground = true, widthDp = 412, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun XRayComponentsDark() = InspirationTheme(darkTheme = true) { Screen(Components) }

@PreviewTest
@Preview(name = "components-large-font", showBackground = true, widthDp = 412, heightDp = 800, fontScale = 1.5f)
@Composable
fun XRayComponentsLargeFont() = InspirationTheme(darkTheme = false) { Screen(Components) }

@PreviewTest
@Preview(name = "components-narrow-360", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
fun XRayComponentsNarrow() = InspirationTheme(darkTheme = false) { Screen(Components) }

@PreviewTest
@Preview(name = "components-wide-840", showBackground = true, widthDp = 840, heightDp = 700)
@Composable
fun XRayComponentsWide() = InspirationTheme(darkTheme = false) { Screen(Components) }

// ---- apps tab ---------------------------------------------------------------------------------

@PreviewTest
@Preview(name = "apps-light", showBackground = true, widthDp = 412, heightDp = 820)
@Composable
fun XRayAppsLight() = InspirationTheme(darkTheme = false) { Screen(Apps) }

@PreviewTest
@Preview(name = "apps-dark", showBackground = true, widthDp = 412, heightDp = 820, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun XRayAppsDark() = InspirationTheme(darkTheme = true) { Screen(Apps) }

@PreviewTest
@Preview(name = "apps-wide-840", showBackground = true, widthDp = 840, heightDp = 620)
@Composable
fun XRayAppsWide() = InspirationTheme(darkTheme = false) { Screen(Apps) }

@PreviewTest
@Preview(name = "apps-large-font", showBackground = true, widthDp = 412, heightDp = 980, fontScale = 1.5f)
@Composable
fun XRayAppsLargeFont() = InspirationTheme(darkTheme = false) { Screen(Apps) }

@PreviewTest
@Preview(name = "apps-needs-snapshots-slice", showBackground = true, widthDp = 412, heightDp = 580)
@Composable
fun XRayAppsNeedSnapshots() = InspirationTheme(darkTheme = false) { Screen(XRayPreviewData.needsSnapshots) }

@PreviewTest
@Preview(name = "apps-turkish", showBackground = true, widthDp = 360, heightDp = 820, locale = "tr")
@Composable
fun XRayAppsTurkish() = InspirationTheme(darkTheme = false) { Screen(Apps) }

@PreviewTest
@Preview(name = "apps-no-readings-small", showBackground = true, widthDp = 360, heightDp = 560)
@Composable
fun XRayAppsNoReadingsSmall() = InspirationTheme(darkTheme = false) { Screen(XRayPreviewData.noAppReadings) }

@PreviewTest
@Preview(
    name = "apps-no-readings-turkish-large-font",
    showBackground = true,
    widthDp = 360,
    heightDp = 820,
    locale = "tr",
    fontScale = 1.5f,
)
@Composable
fun XRayAppsNoReadingsTurkishLargeFont() = InspirationTheme(darkTheme = false) { Screen(XRayPreviewData.noAppReadings) }

@PreviewTest
@Preview(name = "components-turkish", showBackground = true, widthDp = 360, heightDp = 720, locale = "tr")
@Composable
fun XRayComponentsTurkish() = InspirationTheme(darkTheme = false) { Screen(Components) }

// ---- loading, empty, error --------------------------------------------------------------------

@PreviewTest
@Preview(name = "loading", showBackground = true, widthDp = 412, heightDp = 420)
@Composable
fun XRayLoading() = InspirationTheme(darkTheme = false) { Screen(XRayState(status = XRayStatus.LOADING)) }

@PreviewTest
@Preview(name = "empty", showBackground = true, widthDp = 412, heightDp = 480)
@Composable
fun XRayEmpty() = InspirationTheme(darkTheme = false) { Screen(XRayState(status = XRayStatus.EMPTY)) }

@PreviewTest
@Preview(name = "error-dark", showBackground = true, widthDp = 412, heightDp = 480, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun XRayError() = InspirationTheme(darkTheme = true) { Screen(XRayState(status = XRayStatus.FAILED)) }

// ---- app detail sheet body ----------------------------------------------------------------------

@PreviewTest
@Preview(name = "detail-light", showBackground = true, widthDp = 412, heightDp = 1480)
@Composable
fun XRayDetailLight() = InspirationTheme(darkTheme = false) { SheetBody(XRayPreviewData.detail) }

@PreviewTest
@Preview(name = "detail-dark", showBackground = true, widthDp = 412, heightDp = 1640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun XRayDetailDark() = InspirationTheme(darkTheme = true) { SheetBody(XRayPreviewData.detailWithOverride) }

@PreviewTest
@Preview(name = "detail-allowed-override", showBackground = true, widthDp = 412, heightDp = 1640)
@Composable
fun XRayDetailAllowedOverride() =
    InspirationTheme(darkTheme = false) {
        SheetBody(XRayPreviewData.detailWithOverride.copy(actions = XRayPreviewData.loaded, notice = null))
    }

@PreviewTest
@Preview(name = "detail-blocked", showBackground = true, widthDp = 360, heightDp = 1160)
@Composable
fun XRayDetailBlocked() = InspirationTheme(darkTheme = false) { SheetBody(XRayPreviewData.detailBlocked) }

@PreviewTest
@Preview(name = "detail-refused-large-font", showBackground = true, widthDp = 412, heightDp = 2100, fontScale = 1.5f)
@Composable
fun XRayDetailRefused() = InspirationTheme(darkTheme = false) { SheetBody(XRayPreviewData.detailRefused) }

@PreviewTest
@Preview(name = "detail-no-package", showBackground = true, widthDp = 412, heightDp = 720)
@Composable
fun XRayDetailNoPackage() = InspirationTheme(darkTheme = false) { SheetBody(XRayPreviewData.detailNoPackage) }

@PreviewTest
@Preview(name = "detail-turkish", showBackground = true, widthDp = 360, heightDp = 1640, locale = "tr")
@Composable
fun XRayDetailTurkish() = InspirationTheme(darkTheme = false) { SheetBody(XRayPreviewData.detailWithOverride) }

// ---- unsupported profile actions: analytics stay visible, no owner controls or restores ----------

@PreviewTest
@Preview(name = "detail-unsupported-user-light", showBackground = true, widthDp = 412, heightDp = 1160)
@Composable
fun XRayDetailUnsupportedUserLight() = InspirationTheme(darkTheme = false) { SheetBody(XRayPreviewData.detailUnsupportedUser) }

@PreviewTest
@Preview(
    name = "detail-unsupported-user-dark",
    showBackground = true,
    widthDp = 360,
    heightDp = 1160,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun XRayDetailUnsupportedUserDark() =
    InspirationTheme(darkTheme = true) {
        // An unsupported state must hide restore buttons even if a stale owner override somehow survives.
        SheetBody(XRayPreviewData.detailUnsupportedUser.copy(overrides = XRayPreviewData.detailWithOverride.overrides))
    }

@PreviewTest
@Preview(
    name = "detail-unsupported-user-turkish-large-font",
    showBackground = true,
    widthDp = 360,
    heightDp = 1800,
    locale = "tr",
    fontScale = 1.5f,
)
@Composable
fun XRayDetailUnsupportedUserTurkishLargeFont() = InspirationTheme(darkTheme = false) { SheetBody(XRayPreviewData.detailUnsupportedUser) }

// ---- confirmation -------------------------------------------------------------------------------

@PreviewTest
@Preview(name = "confirm-force-stop", showBackground = true, widthDp = 412, heightDp = 420)
@Composable
fun XRayConfirmForceStop() = InspirationTheme(darkTheme = false) { DialogBody(XRayPreviewData.confirmForceStop) }

@PreviewTest
@Preview(name = "confirm-restrict-dark", showBackground = true, widthDp = 360, heightDp = 520, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun XRayConfirmRestrict() = InspirationTheme(darkTheme = true) { DialogBody(XRayPreviewData.confirmRestrict) }
