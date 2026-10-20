package com.akane.inspiration.toolkit

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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

// Rendered on the JVM: ./gradlew :feature:toolkit:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff).
// Heights are explicit: the renderer caps unspecified heights. The time zone is fixed so times never move.
// The sheet and the dialog are windows the JVM renderer does not draw, so their bodies are rendered directly.
// Previews use fakes only: no icon loader result, no Shizuku, no package manager.

private val Utc: TimeZone = TimeZone.getTimeZone("UTC")

@Composable
private fun Screen(state: ToolkitState) {
    Surface(color = MaterialTheme.colorScheme.background) {
        ToolkitScreen(state = state, onIntent = {}, loadIcon = { null }, timeZone = Utc)
    }
}

@Composable
private fun SheetBody(
    detail: AppDetail,
    busy: Boolean = false,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
        AppDetailContent(
            detail = detail,
            busy = busy,
            loadIcon = { null },
            onIntent = {},
            modifier = Modifier.padding(top = MaterialTheme.spacing.md),
        )
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

@Composable
private fun DexoptBody(state: ToolkitState) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.padding(MaterialTheme.spacing.md)) {
            DexoptCard(section = state.dexopt, onIntent = {}, timeZone = Utc)
        }
    }
}

@Composable
private fun OverridesBody(state: ToolkitState) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.padding(MaterialTheme.spacing.md)) {
            OverridesCard(state = state, onIntent = {}, timeZone = Utc)
        }
    }
}

@Composable
private fun AppsBody(state: ToolkitState) {
    Surface(color = MaterialTheme.colorScheme.background) {
        LazyVerticalGrid(columns = GridCells.Fixed(1), contentPadding = PaddingValues(MaterialTheme.spacing.md)) {
            appsSection(state.apps, enabled = !state.busy, onIntent = {}, loadIcon = { null })
        }
    }
}

@Composable
private fun NotificationBody(state: StatusNotificationState) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.padding(MaterialTheme.spacing.md)) {
            StatusNotificationCard(state = state, onIntent = {})
        }
    }
}

// ---- status notification card ---------------------------------------------------------------------

@PreviewTest
@Preview(name = "status-notification-on-360", showBackground = true, widthDp = 360, heightDp = 160)
@Composable
fun ToolkitNotificationOn() = InspirationTheme(darkTheme = false) { NotificationBody(ToolkitPreviewData.notificationOn) }

@PreviewTest
@Preview(
    name = "status-notification-on-840-dark",
    showBackground = true,
    widthDp = 840,
    heightDp = 140,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ToolkitNotificationOnWide() = InspirationTheme(darkTheme = true) { NotificationBody(ToolkitPreviewData.notificationOn) }

@PreviewTest
@Preview(
    name = "status-notification-off-360-dark",
    showBackground = true,
    widthDp = 360,
    heightDp = 160,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ToolkitNotificationOff() = InspirationTheme(darkTheme = true) { NotificationBody(ToolkitPreviewData.notificationOff) }

@PreviewTest
@Preview(name = "status-notification-off-840", showBackground = true, widthDp = 840, heightDp = 140)
@Composable
fun ToolkitNotificationOffWide() = InspirationTheme(darkTheme = false) { NotificationBody(ToolkitPreviewData.notificationOff) }

@PreviewTest
@Preview(name = "status-notification-blocked-360", showBackground = true, widthDp = 360, heightDp = 220)
@Composable
fun ToolkitNotificationBlocked() = InspirationTheme(darkTheme = false) { NotificationBody(ToolkitPreviewData.notificationBlocked) }

@PreviewTest
@Preview(
    name = "status-notification-blocked-840-dark",
    showBackground = true,
    widthDp = 840,
    heightDp = 180,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ToolkitNotificationBlockedWide() = InspirationTheme(darkTheme = true) { NotificationBody(ToolkitPreviewData.notificationBlocked) }

@PreviewTest
@Preview(name = "status-notification-on-large-font", showBackground = true, widthDp = 360, heightDp = 300, fontScale = 1.5f)
@Composable
fun ToolkitNotificationLargeFont() = InspirationTheme(darkTheme = false) { NotificationBody(ToolkitPreviewData.notificationOn) }

@PreviewTest
@Preview(
    name = "status-notification-blocked-large-font-dark",
    showBackground = true,
    widthDp = 360,
    heightDp = 460,
    fontScale = 1.5f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ToolkitNotificationBlockedLargeFont() = InspirationTheme(darkTheme = true) { NotificationBody(ToolkitPreviewData.notificationBlocked) }

// ---- ready: themes, font scale, widths ----------------------------------------------------------

@PreviewTest
@Preview(name = "ready-light", showBackground = true, widthDp = 412, heightDp = 2420)
@Composable
fun ToolkitReadyLight() = InspirationTheme(darkTheme = false) { Screen(ToolkitPreviewData.ready) }

@PreviewTest
@Preview(name = "ready-dark", showBackground = true, widthDp = 412, heightDp = 2420, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ToolkitReadyDark() = InspirationTheme(darkTheme = true) { Screen(ToolkitPreviewData.ready) }

@PreviewTest
@Preview(name = "ready-large-font", showBackground = true, widthDp = 412, heightDp = 3800, fontScale = 1.5f)
@Composable
fun ToolkitReadyLargeFont() = InspirationTheme(darkTheme = false) { Screen(ToolkitPreviewData.ready) }

@PreviewTest
@Preview(name = "ready-narrow-360", showBackground = true, widthDp = 360, heightDp = 2520)
@Composable
fun ToolkitReadyNarrow() = InspirationTheme(darkTheme = false) { Screen(ToolkitPreviewData.ready) }

@PreviewTest
@Preview(name = "ready-wide-840", showBackground = true, widthDp = 840, heightDp = 1620)
@Composable
fun ToolkitReadyWide() = InspirationTheme(darkTheme = false) { Screen(ToolkitPreviewData.ready) }

@PreviewTest
@Preview(name = "ready-turkish", showBackground = true, widthDp = 360, heightDp = 2640, locale = "tr")
@Composable
fun ToolkitReadyTurkish() = InspirationTheme(darkTheme = false) { Screen(ToolkitPreviewData.ready) }

@PreviewTest
@Preview(name = "doze-custom-dark", showBackground = true, widthDp = 412, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ToolkitDozeCustom() = InspirationTheme(darkTheme = true) { Screen(ToolkitPreviewData.customDoze) }

@PreviewTest
@Preview(name = "apps-busy-360", showBackground = true, widthDp = 360, heightDp = 460)
@Composable
fun ToolkitAppsBusy() = InspirationTheme(darkTheme = false) { AppsBody(ToolkitPreviewData.busy) }

@PreviewTest
@Preview(name = "apps-busy-dark", showBackground = true, widthDp = 412, heightDp = 460, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ToolkitAppsBusyDark() = InspirationTheme(darkTheme = true) { AppsBody(ToolkitPreviewData.busy) }

@PreviewTest
@Preview(name = "reset-report", showBackground = true, widthDp = 412, heightDp = 1500)
@Composable
fun ToolkitResetReport() = InspirationTheme(darkTheme = false) { Screen(ToolkitPreviewData.resetReport) }

@PreviewTest
@Preview(name = "overrides-unverified-360", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun ToolkitOverridesUnverified() = InspirationTheme(darkTheme = false) { OverridesBody(ToolkitPreviewData.unverifiedOverrides) }

// ---- loading, empty, error, blocked -------------------------------------------------------------

@PreviewTest
@Preview(name = "loading", showBackground = true, widthDp = 412, heightDp = 640)
@Composable
fun ToolkitLoading() = InspirationTheme(darkTheme = false) { Screen(ToolkitPreviewData.loading) }

@PreviewTest
@Preview(name = "empty", showBackground = true, widthDp = 412, heightDp = 1900)
@Composable
fun ToolkitEmpty() = InspirationTheme(darkTheme = false) { Screen(ToolkitPreviewData.noChanges) }

@PreviewTest
@Preview(name = "error", showBackground = true, widthDp = 412, heightDp = 1500)
@Composable
fun ToolkitError() = InspirationTheme(darkTheme = false) { Screen(ToolkitPreviewData.failed) }

@PreviewTest
@Preview(name = "doze-and-apps-error-dark", showBackground = true, widthDp = 412, heightDp = 2000, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ToolkitPartialError() = InspirationTheme(darkTheme = true) { Screen(ToolkitPreviewData.dozeFailed) }

@PreviewTest
@Preview(name = "blocked-grant", showBackground = true, widthDp = 412, heightDp = 1300)
@Composable
fun ToolkitBlockedGrant() = InspirationTheme(darkTheme = false) { Screen(ToolkitPreviewData.blockedGrant) }

@PreviewTest
@Preview(name = "blocked-denied-dark", showBackground = true, widthDp = 360, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ToolkitBlockedDenied() = InspirationTheme(darkTheme = true) { Screen(ToolkitPreviewData.blockedDenied) }

// ---- bg-dexopt ----------------------------------------------------------------------------------

@PreviewTest
@Preview(name = "dexopt-running", showBackground = true, widthDp = 412, heightDp = 380)
@Composable
fun ToolkitDexoptRunning() = InspirationTheme(darkTheme = false) { DexoptBody(ToolkitPreviewData.dexoptRunning) }

@PreviewTest
@Preview(name = "dexopt-unknown-dark", showBackground = true, widthDp = 412, heightDp = 380, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ToolkitDexoptUnknown() = InspirationTheme(darkTheme = true) { DexoptBody(ToolkitPreviewData.dexoptUnknown) }

@PreviewTest
@Preview(name = "dexopt-refused-turkish", showBackground = true, widthDp = 360, heightDp = 460, locale = "tr")
@Composable
fun ToolkitDexoptRefused() = InspirationTheme(darkTheme = false) { DexoptBody(ToolkitPreviewData.dexoptUnplugged) }

@PreviewTest
@Preview(name = "dexopt-unsupported-turkish", showBackground = true, widthDp = 360, heightDp = 380, locale = "tr")
@Composable
fun ToolkitDexoptUnsupported() = InspirationTheme(darkTheme = false) { DexoptBody(ToolkitPreviewData.dexoptUnsupported) }

// ---- app detail sheet body ----------------------------------------------------------------------

@PreviewTest
@Preview(name = "detail-light", showBackground = true, widthDp = 412, heightDp = 1060)
@Composable
fun ToolkitDetailLight() = InspirationTheme(darkTheme = false) { SheetBody(ToolkitPreviewData.detail) }

@PreviewTest
@Preview(name = "detail-dark", showBackground = true, widthDp = 412, heightDp = 1060, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ToolkitDetailDark() = InspirationTheme(darkTheme = true) { SheetBody(ToolkitPreviewData.detail) }

@PreviewTest
@Preview(name = "detail-custom-narrow", showBackground = true, widthDp = 360, heightDp = 1260)
@Composable
fun ToolkitDetailCustom() = InspirationTheme(darkTheme = false) { SheetBody(ToolkitPreviewData.detailCustom, busy = true) }

@PreviewTest
@Preview(name = "detail-large-font", showBackground = true, widthDp = 412, heightDp = 1900, fontScale = 1.5f)
@Composable
fun ToolkitDetailLargeFont() = InspirationTheme(darkTheme = false) { SheetBody(ToolkitPreviewData.detail) }

@PreviewTest
@Preview(name = "detail-loading", showBackground = true, widthDp = 412, heightDp = 240)
@Composable
fun ToolkitDetailLoading() = InspirationTheme(darkTheme = false) { SheetBody(ToolkitPreviewData.detailLoading) }

@PreviewTest
@Preview(name = "detail-failed", showBackground = true, widthDp = 412, heightDp = 300)
@Composable
fun ToolkitDetailFailed() = InspirationTheme(darkTheme = false) { SheetBody(ToolkitPreviewData.detailFailed) }

@PreviewTest
@Preview(name = "detail-turkish", showBackground = true, widthDp = 360, heightDp = 1160, locale = "tr")
@Composable
fun ToolkitDetailTurkish() = InspirationTheme(darkTheme = false) { SheetBody(ToolkitPreviewData.detail) }

// ---- confirmation -------------------------------------------------------------------------------

@PreviewTest
@Preview(name = "confirm-wake-lock", showBackground = true, widthDp = 412, heightDp = 400)
@Composable
fun ToolkitConfirmWakeLock() = InspirationTheme(darkTheme = false) { DialogBody(ToolkitPreviewData.confirmWakeLock) }

@PreviewTest
@Preview(name = "confirm-force-stop-dark", showBackground = true, widthDp = 360, heightDp = 520, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ToolkitConfirmForceStop() = InspirationTheme(darkTheme = true) { DialogBody(ToolkitPreviewData.confirmForceStop) }

@PreviewTest
@Preview(name = "confirm-doze-stale", showBackground = true, widthDp = 412, heightDp = 440)
@Composable
fun ToolkitConfirmDozeStale() = InspirationTheme(darkTheme = false) { DialogBody(ToolkitPreviewData.confirmDozeStale) }

@PreviewTest
@Preview(name = "confirm-reset-turkish", showBackground = true, widthDp = 360, heightDp = 520, locale = "tr")
@Composable
fun ToolkitConfirmResetTurkish() = InspirationTheme(darkTheme = false) { DialogBody(ToolkitPreviewData.confirmReset) }

@PreviewTest
@Preview(name = "confirm-reset-some", showBackground = true, widthDp = 412, heightDp = 400)
@Composable
fun ToolkitConfirmResetSome() = InspirationTheme(darkTheme = false) { DialogBody(ToolkitPreviewData.confirmResetSome) }

@PreviewTest
@Preview(name = "confirm-reset-some-turkish", showBackground = true, widthDp = 360, heightDp = 460, locale = "tr")
@Composable
fun ToolkitConfirmResetSomeTurkish() = InspirationTheme(darkTheme = false) { DialogBody(ToolkitPreviewData.confirmResetSome) }

@PreviewTest
@Preview(name = "confirm-reset-try", showBackground = true, widthDp = 412, heightDp = 400)
@Composable
fun ToolkitConfirmResetTry() = InspirationTheme(darkTheme = false) { DialogBody(ToolkitPreviewData.confirmResetTry) }
