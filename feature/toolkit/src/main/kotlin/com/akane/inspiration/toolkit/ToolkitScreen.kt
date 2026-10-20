package com.akane.inspiration.toolkit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import com.akane.inspiration.designsystem.component.EmptyState
import com.akane.inspiration.designsystem.component.EmptyStateAction
import com.akane.inspiration.designsystem.component.LocalObscuredTouchNotice
import com.akane.inspiration.designsystem.component.ScreenHeader
import com.akane.inspiration.designsystem.component.SemanticDot
import com.akane.inspiration.designsystem.component.rememberObscuredTouchNotice
import com.akane.inspiration.designsystem.component.format.rememberSystemTimeZone
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.statusColors
import com.akane.inspiration.designsystem.theme.telemetryColors
import com.akane.inspiration.ipc.ShizukuBackend
import java.util.TimeZone

/** Loads an app icon for the picker. Called per visible row; the real one is cached by `AppLabels`. */
typealias AppIconLoader = suspend (packageName: String) -> ImageBitmap?

/**
 * The Toolkit screen, stateless: everything it shows is in [state] and everything the user does leaves through
 * [onIntent]. [loadIcon] resolves icons lazily so the list stays cheap. [timeZone] is a parameter only so
 * screenshots are the same on every machine.
 */
@Composable
fun ToolkitScreen(
    state: ToolkitState,
    onIntent: (ToolkitIntent) -> Unit,
    loadIcon: AppIconLoader,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    timeZone: TimeZone = rememberSystemTimeZone(),
) {
    val obscuredNotice = rememberObscuredTouchNotice(snackbarHostState, stringResource(R.string.toolkit_touch_obscured))
    CompositionLocalProvider(LocalObscuredTouchNotice provides obscuredNotice) {
        Box(modifier = modifier.fillMaxSize()) {
            ToolkitBody(state = state, onIntent = onIntent, loadIcon = loadIcon, timeZone = timeZone)
            // While the app sheet is open it shows the snackbars itself: this host would be hidden behind it.
            if (snackbarHostState != null && state.detail == null) {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(MaterialTheme.spacing.md),
                )
            }
        }
        state.detail?.let { detail ->
            AppDetailSheet(
                detail = detail,
                busy = state.busy,
                loadIcon = loadIcon,
                onIntent = onIntent,
                snackbarHostState = snackbarHostState,
            )
        }
        state.confirmation?.let { confirmation ->
            ConfirmationDialog(confirmation = confirmation, onIntent = onIntent)
        }
    }
}

@Composable
private fun ToolkitBody(
    state: ToolkitState,
    onIntent: (ToolkitIntent) -> Unit,
    loadIcon: AppIconLoader,
    timeZone: TimeZone,
) {
    val spacing = MaterialTheme.spacing
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = spacing.xxl * ADAPTIVE_COLUMN_UNITS),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        fullWidth { ScreenHeader(title = stringResource(R.string.toolkit_title), subtitle = stringResource(R.string.toolkit_subtitle)) }
        fullWidth { PersistWarning() }
        fullWidth { StatusNotificationCard(state.statusNotification, onIntent) }
        when (state.status) {
            ToolkitPhase.LOADING -> fullWidth { LoadingBlock() }
            ToolkitPhase.FAILED -> fullWidth { ErrorBlock(onIntent) }
            ToolkitPhase.BLOCKED -> fullWidth { BlockedCard(state.blocked, state.blockedBackend, onIntent) }
            ToolkitPhase.READY -> ready(state, onIntent, loadIcon, timeZone)
        }
        if (state.status == ToolkitPhase.BLOCKED || state.status == ToolkitPhase.READY) {
            fullWidth { HistoryCard(state.history, timeZone) }
        }
    }
}

/** An item that spans every column of the adaptive grid. */
internal fun LazyGridScope.fullWidth(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

private fun LazyGridScope.ready(
    state: ToolkitState,
    onIntent: (ToolkitIntent) -> Unit,
    loadIcon: AppIconLoader,
    timeZone: TimeZone,
) {
    state.resetReport?.let { report -> fullWidth { ResetReportCard(report, onIntent) } }
    item { DozeCard(section = state.doze, busy = state.busy, onIntent = onIntent) }
    item { DexoptCard(section = state.dexopt, onIntent = onIntent, timeZone = timeZone) }
    fullWidth { OverridesCard(state = state, onIntent = onIntent, timeZone = timeZone) }
    appsSection(state.apps, enabled = !state.busy, onIntent = onIntent, loadIcon = loadIcon)
}

/** Every change here outlives the app, so the warning is always on screen, next to the list that undoes it. */
@Composable
private fun PersistWarning() {
    val spacing = MaterialTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.Top) {
                LineDot(color = MaterialTheme.statusColors.warning, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.toolkit_persist_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = spacing.sm),
                )
            }
            Text(
                text = stringResource(R.string.toolkit_persist_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- loading, error, blocked ---------------------------------------------------------------------

@Composable
private fun LoadingBlock() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        EmptyState(
            title = stringResource(R.string.toolkit_loading_title),
            body = stringResource(R.string.toolkit_loading_body),
            action = null,
        )
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ErrorBlock(onIntent: (ToolkitIntent) -> Unit) {
    EmptyState(
        title = stringResource(R.string.toolkit_error_title),
        body = stringResource(R.string.toolkit_error_body),
        action = EmptyStateAction(stringResource(R.string.toolkit_error_action)) { onIntent(ToolkitIntent.Reload) },
        modifier = Modifier.padding(top = MaterialTheme.spacing.lg),
    )
}

/** Shizuku is not Ready: the reason in words and, while the permission is undecided, the Grant action. */
@Composable
internal fun BlockedCard(
    reason: BlockedReason?,
    backend: ShizukuBackend?,
    onIntent: (ToolkitIntent) -> Unit,
) {
    if (reason == null) return
    EmptyState(
        title = stringResource(blockedTitleRes(backend)),
        body = stringResource(blockedBodyRes(reason, backend)),
        action =
            if (reason.canGrant) {
                EmptyStateAction(stringResource(R.string.toolkit_blocked_grant)) { onIntent(ToolkitIntent.Grant) }
            } else {
                null
            },
        modifier = Modifier.padding(top = MaterialTheme.spacing.md),
    )
}

// ---- small shared pieces -------------------------------------------------------------------------

/** The title of the app sheet; the cards use the shared `CardTitle`. */
@Composable
internal fun SectionHeading(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
internal fun InfoText(
    text: String,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    val style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    Text(text = text, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
internal fun LoadingLine(
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
        InfoText(text)
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/**
 * A status dot centred on the first line of the text that follows it, so it sits on the text's middle at any
 * font scale. [style] is the style of that text.
 */
@Composable
internal fun LineDot(
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    Box(modifier = modifier.height(lineHeight), contentAlignment = Alignment.Center) {
        SemanticDot(color = color)
    }
}

/** The result line of the last action: green dot for done, red for refused. A polite live region for TalkBack. */
@Composable
internal fun NoticeLine(
    notice: ToolkitNotice,
    modifier: Modifier = Modifier,
) {
    val color = if (notice is ToolkitNotice.Applied) MaterialTheme.statusColors.ok else MaterialTheme.telemetryColors.critical
    Row(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
    ) {
        LineDot(color = color)
        Text(
            text = noticeText(notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = MaterialTheme.spacing.sm),
        )
    }
}

@Composable
internal fun RetryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(onClick = onClick, modifier = modifier) { Text(label) }
}

/** A grid column is at least this many `xxl` spacing units wide, so a phone gets one column and a tablet two. */
private const val ADAPTIVE_COLUMN_UNITS = 7
