package com.akane.inspiration.toolkit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.akane.inspiration.designsystem.component.CardTitle
import com.akane.inspiration.designsystem.component.EmptyState
import com.akane.inspiration.designsystem.component.EmptyStateAction
import com.akane.inspiration.designsystem.component.SemanticDot
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.statusColors
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.toolkit.ToolkitWarning

// ---- picker --------------------------------------------------------------------------------------

internal fun LazyGridScope.appsSection(apps: AppsSection, onIntent: (ToolkitIntent) -> Unit, loadIcon: AppIconLoader) {
    fullWidth { AppsHeader(query = apps.query, onIntent = onIntent) }
    when (apps.phase) {
        AppsPhase.LOADING -> fullWidth { LoadingLine(stringResource(R.string.toolkit_apps_loading)) }
        AppsPhase.FAILED -> fullWidth {
            EmptyState(
                title = stringResource(R.string.toolkit_apps_failed_title),
                body = stringResource(R.string.toolkit_apps_failed_body),
                action = EmptyStateAction(stringResource(R.string.toolkit_error_action)) { onIntent(ToolkitIntent.Reload) },
            )
        }
        AppsPhase.READY -> if (apps.items.isEmpty()) {
            fullWidth { NoApps(apps) }
        } else {
            fullWidth {
                InfoText(pluralStringResource(R.plurals.toolkit_apps_count, apps.items.size, apps.items.size), small = true)
            }
            items(items = apps.items, key = { it.packageName }, span = { GridItemSpan(maxLineSpan) }) { app ->
                AppRow(app = app, loadIcon = loadIcon, onOpen = { onIntent(ToolkitIntent.OpenApp(app.packageName)) })
            }
        }
    }
}

@Composable
private fun AppsHeader(query: String, onIntent: (ToolkitIntent) -> Unit) {
    val spacing = MaterialTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        CardTitle(stringResource(R.string.toolkit_apps_title))
        InfoText(stringResource(R.string.toolkit_apps_body))
        OutlinedTextField(
            value = query,
            onValueChange = { onIntent(ToolkitIntent.SetQuery(it)) },
            label = { Text(stringResource(R.string.toolkit_apps_search)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NoApps(apps: AppsSection) {
    EmptyState(
        title = stringResource(R.string.toolkit_apps_empty_title),
        body = if (apps.query.isBlank()) {
            stringResource(R.string.toolkit_apps_empty_none)
        } else {
            stringResource(R.string.toolkit_apps_empty_no_match, apps.query)
        },
        action = null,
    )
}

@Composable
private fun AppRow(app: AppItem, loadIcon: AppIconLoader, onOpen: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onOpen)
            .heightIn(min = spacing.xxl)
            .padding(horizontal = spacing.sm, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.ms),
    ) {
        AppIcon(app = app, loadIcon = loadIcon)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (app.restricted) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                SemanticDot(color = MaterialTheme.statusColors.warning)
                Text(
                    text = stringResource(R.string.toolkit_app_restricted),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The icon is loaded when the row is composed (lazily), with the app's initial on a quiet tile until then or when there is none. */
@Composable
internal fun AppIcon(app: AppItem, loadIcon: AppIconLoader, modifier: Modifier = Modifier) {
    val icon by produceState<ImageBitmap?>(initialValue = null, app.packageName) { value = loadIcon(app.packageName) }
    val shape = MaterialTheme.shapes.small
    val size = MaterialTheme.spacing.xl
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier.size(size).clip(shape))
    } else {
        Box(
            modifier = modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = app.label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString().orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- detail sheet --------------------------------------------------------------------------------

/** The app sheet. Closing it (scrim, swipe, back) only closes it; nothing is applied by dismissing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppDetailSheet(detail: AppDetail, busy: Boolean, loadIcon: AppIconLoader, onIntent: (ToolkitIntent) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { onIntent(ToolkitIntent.CloseApp) }, sheetState = sheetState) {
        AppDetailContent(detail = detail, busy = busy, loadIcon = loadIcon, onIntent = onIntent)
    }
}

/** The body of the sheet, on its own so screenshots can render it without a window. */
@Composable
fun AppDetailContent(
    detail: AppDetail,
    busy: Boolean,
    loadIcon: AppIconLoader,
    onIntent: (ToolkitIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = spacing.md, end = spacing.md, bottom = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.ms)) {
            AppIcon(app = detail.app, loadIcon = loadIcon)
            Column(modifier = Modifier.weight(1f)) {
                SectionHeading(detail.app.label)
                InfoText(detail.app.packageName, small = true)
            }
        }
        detail.notice?.let { NoticeLine(it) }
        if (busy) LoadingLine(stringResource(R.string.toolkit_busy))
        when (val controls = detail.controls) {
            ControlsState.Loading -> LoadingLine(stringResource(R.string.toolkit_controls_loading))
            is ControlsState.Unavailable -> {
                InfoText(problemText(controls.problem))
                RetryButton(label = stringResource(R.string.toolkit_error_action), onClick = { onIntent(ToolkitIntent.ReloadApp) })
            }
            is ControlsState.Loaded -> Controls(controls = controls.controls, enabled = !busy, onIntent = onIntent)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(controls: AppControls, enabled: Boolean, onIntent: (ToolkitIntent) -> Unit) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier.filterTouchesWhenObscured(),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        controls.ops.forEach { op -> OpSwitchRow(control = op, enabled = enabled, onIntent = onIntent) }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            CardTitle(stringResource(R.string.toolkit_bucket_title))
            InfoText(stringResource(R.string.toolkit_bucket_body), small = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                StandbyBucket.entries.forEach { bucket ->
                    FilterChip(
                        selected = controls.bucket == bucket,
                        onClick = { if (controls.bucket != bucket) onIntent(ToolkitIntent.Act(ToolkitRequest.SetBucket(bucket))) },
                        label = { Text(stringResource(bucketLabelRes(bucket))) },
                        enabled = enabled,
                        leadingIcon = if (controls.bucket == bucket) ({ CheckMark(modifier = Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                    )
                }
            }
            if (controls.bucket == null && controls.bucketRaw != null) {
                InfoText(stringResource(R.string.toolkit_bucket_other, controls.bucketRaw), small = true)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            FilledTonalButton(
                onClick = { onIntent(ToolkitIntent.Act(ToolkitRequest.ForceStop)) },
                enabled = enabled,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(stringResource(R.string.toolkit_force_stop))
            }
            InfoText(stringResource(R.string.toolkit_force_stop_body), small = true)
        }

        if (controls.updatedSystemApp) {
            WarningLine(stringResource(warningRes(ToolkitWarning.UPDATED_SYSTEM_APP)))
        }
    }
}

/** One app op. The switch is on while the system reports the op as ignored: it shows what was read back, never a guess. */
@Composable
private fun OpSwitchRow(control: OpControl, enabled: Boolean, onIntent: (ToolkitIntent) -> Unit) {
    val spacing = MaterialTheme.spacing
    val editable = enabled && control.editable
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = spacing.xxl)
                .toggleable(
                    value = control.ignored,
                    enabled = editable,
                    role = Role.Switch,
                    onValueChange = { onIntent(ToolkitIntent.Act(ToolkitRequest.SetAppOp(control.op, it))) },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(
                    text = stringResource(opTitleRes(control.op)),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                InfoText(stringResource(opBodyRes(control.op)), small = true)
            }
            // Drawn by the row's toggleable: the switch itself takes no input.
            Switch(checked = control.ignored, onCheckedChange = null, enabled = editable)
        }
        if (control.op == AppOp.WAKE_LOCK) WarningLine(stringResource(R.string.toolkit_op_wake_lock_warning))
        if (!control.editable) InfoText(stringResource(R.string.toolkit_op_custom), small = true)
    }
}

@Composable
private fun WarningLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        LineDot(color = MaterialTheme.statusColors.warning, style = MaterialTheme.typography.bodySmall)
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = MaterialTheme.spacing.sm),
        )
    }
}

// The check as a polyline in a unit square: short stroke down-right, long stroke up-right.
private val CheckPoints = listOf(0.12f to 0.55f, 0.40f to 0.80f, 0.88f to 0.22f)
private const val CHECK_STROKE_FRACTION = 0.11f

/** The selected-chip check, drawn in the current content colour (the project has no icon set). */
@Composable
private fun CheckMark(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier = modifier) {
        val path = Path().apply {
            CheckPoints.forEachIndexed { i, (x, y) ->
                val point = Offset(x * size.width, y * size.height)
                if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = size.minDimension * CHECK_STROKE_FRACTION, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
