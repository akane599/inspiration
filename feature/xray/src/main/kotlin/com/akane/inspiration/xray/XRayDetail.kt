package com.akane.inspiration.xray

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.akane.inspiration.designsystem.component.CardTitle
import com.akane.inspiration.designsystem.component.InspirationCard
import com.akane.inspiration.designsystem.component.LegendItem
import com.akane.inspiration.designsystem.component.LegendRow
import com.akane.inspiration.designsystem.component.MetricValue
import com.akane.inspiration.designsystem.component.SemanticDot
import com.akane.inspiration.designsystem.component.color
import com.akane.inspiration.designsystem.component.filterTouchesWhenObscured
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.designsystem.theme.numericTypography
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.statusColors
import com.akane.inspiration.diagnostics.dictionary.EntryKind
import com.akane.inspiration.ipc.ShizukuBackend
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import kotlinx.collections.immutable.toImmutableList

/**
 * The app detail sheet. Closing it (scrim, swipe, back) only closes it; nothing is applied by dismissing. The sheet
 * is a window of its own over the screen, so it shows the screen's snackbars (the blocked-touch notice among them).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppDetailSheet(
    detail: AppDetail,
    onIntent: (XRayIntent) -> Unit,
    explain: Explainer,
    loadIcon: AppIconLoader,
    snackbarHostState: SnackbarHostState?,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { onIntent(XRayIntent.CloseApp) }, sheetState = sheetState) {
        Box {
            AppDetailContent(detail = detail, onIntent = onIntent, explain = explain, loadIcon = loadIcon)
            if (snackbarHostState != null) {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(MaterialTheme.spacing.md),
                )
            }
        }
    }
}

/** The body of the sheet, on its own so screenshots can render it without a window. */
@Composable
fun AppDetailContent(
    detail: AppDetail,
    onIntent: (XRayIntent) -> Unit,
    explain: Explainer,
    modifier: Modifier = Modifier,
    loadIcon: AppIconLoader = NoAppIcons,
) {
    val spacing = MaterialTheme.spacing
    val app = detail.app
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = spacing.md, end = spacing.md, bottom = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        DetailHeader(app, loadIcon)
        ComponentSection(app)
        ProcessStateSection(app.processStates)
        ActivitySection(app)
        WakelockSection(app, explain)
        AlarmSection(app, explain)
        QuickActions(detail = detail, onIntent = onIntent)
    }
}

@Composable
private fun DetailHeader(
    app: AppRow,
    loadIcon: AppIconLoader,
) {
    val spacing = MaterialTheme.spacing
    val name = displayName(app)
    Column(verticalArrangement = Arrangement.spacedBy(spacing.ms)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.ms)) {
            AppIcon(app = app, name = name, loadIcon = loadIcon)
            Column(modifier = Modifier.weight(1f)) {
                SectionHeading(name)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = mahText(app.totalMah),
                    style = MaterialTheme.numericTypography.title,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = shareText(app.sharePct),
                    style = MaterialTheme.numericTypography.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // On its own line so a long package name has the full width, even at large font scales.
        if (app.packageName != null && app.packageName != name) {
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GroupBar(slices = app.slices, total = app.totalMah.toFloat())
        val items =
            ComponentGroup.entries.filter { group -> app.slices.any { it.group == group } }.map { group ->
                LegendItem(color = group.role().color(), label = stringResource(groupLabelRes(group)))
            }
        LegendRow(items = items.toImmutableList())
    }
}

@Composable
private fun ComponentSection(app: AppRow) {
    if (app.components.isEmpty()) return
    val showsRadios = app.components.any { it.key == "MOBILE_RADIO" || it.key == "WIFI" }
    Section(title = stringResource(R.string.xray_detail_components)) {
        app.components.forEach { row ->
            ValueRow(label = componentLabel(row.key), value = mahText(row.mah), detail = shareText(row.sharePct))
        }
        if (showsRadios) {
            Text(
                text = stringResource(R.string.xray_detail_network_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProcessStateSection(states: ProcessStateMah) {
    if (states.isEmpty) return
    Section(title = stringResource(R.string.xray_detail_states)) {
        states.foreground?.let { ValueRow(stringResource(R.string.xray_detail_state_foreground), mahText(it)) }
        states.background?.let { ValueRow(stringResource(R.string.xray_detail_state_background), mahText(it)) }
        states.foregroundService?.let { ValueRow(stringResource(R.string.xray_detail_state_foreground_service), mahText(it)) }
        states.cached?.let { ValueRow(stringResource(R.string.xray_detail_state_cached), mahText(it)) }
    }
}

@Composable
private fun ActivitySection(app: AppRow) {
    val format = rememberTelemetryFormat()
    Section(title = stringResource(R.string.xray_detail_activity)) {
        ValueRow(
            stringResource(R.string.xray_detail_cpu_time),
            if (app.dumpCountersAvailable) durationText(format, app.cpuMs) else stringResource(R.string.xray_value_unavailable),
        )
        ValueRow(
            stringResource(R.string.xray_detail_foreground_time),
            if (app.dumpCountersAvailable) durationText(format, app.foregroundMs) else stringResource(R.string.xray_value_unavailable),
        )
    }
}

@Composable
private fun WakelockSection(
    app: AppRow,
    explain: Explainer,
) {
    val format = rememberTelemetryFormat()
    Section(title = stringResource(R.string.xray_detail_wakelocks)) {
        if (!app.dumpCountersAvailable) {
            Text(stringResource(R.string.xray_value_unavailable))
        } else if (app.wakelocks.isEmpty()) {
            NoneRecorded()
        }
        app.wakelocks.forEach { wakelock ->
            val explanation = remember(wakelock.name, explain) { explain(EntryKind.PARTIAL, wakelock.name) }
            NamedEntry(
                name = wakelock.name,
                facts =
                    stringResource(R.string.xray_detail_wakelock_line, durationText(format, wakelock.durationMs)) +
                        " · " + timesText(wakelock.count),
                title = explanation?.title,
                body = explanation?.body,
            )
        }
    }
}

@Composable
private fun AlarmSection(
    app: AppRow,
    explain: Explainer,
) {
    Section(title = stringResource(R.string.xray_detail_alarms)) {
        if (!app.alarmsAvailable) {
            Text(stringResource(R.string.xray_value_unavailable))
        } else if (app.alarms.isEmpty()) {
            NoneRecorded()
        }
        app.alarms.forEach { alarm ->
            val explanation = remember(alarm.tag, explain) { explain(EntryKind.ALARM, alarm.tag) }
            NamedEntry(
                name = alarm.tag,
                facts = timesText(alarm.count),
                title = explanation?.title,
                body = explanation?.body,
            )
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        CardTitle(title)
        content()
    }
}

@Composable
private fun ValueRow(
    label: String,
    value: String,
    detail: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.numericTypography.body, color = MaterialTheme.colorScheme.onSurface)
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.numericTypography.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = MaterialTheme.spacing.xs),
            )
        }
    }
}

@Composable
private fun NoneRecorded() {
    Text(
        text = stringResource(R.string.xray_detail_none_recorded),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One wakelock or alarm: its name, the numbers, and the dictionary's plain-language explanation when there is one. */
@Composable
private fun NamedEntry(
    name: String,
    facts: String,
    title: String?,
    body: String?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        Text(text = name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(text = facts, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (title != null) {
            Text(text = title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        if (body != null) {
            Text(text = body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- quick actions --------------------------------------------------------------------------

/**
 * Every control here is a privileged write, so the whole area refuses touches while another window covers
 * it. Switches show the read-back state; a tap only asks, and the state changes when the system confirms.
 */
@Composable
private fun QuickActions(
    detail: AppDetail,
    onIntent: (XRayIntent) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier.fillMaxWidth().filterTouchesWhenObscured(),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        CardTitle(stringResource(R.string.xray_actions_title))
        when (val actions = detail.actions) {
            is ActionsState.Blocked -> {
                BlockedCard(actions.reason, actions.backend)
            }

            ActionsState.NoPackage -> {
                InfoText(stringResource(R.string.xray_actions_no_package))
            }

            ActionsState.UnsupportedUser -> {
                InfoText(stringResource(R.string.xray_actions_unsupported_user))
            }

            ActionsState.Loading -> {
                LoadingLine(stringResource(R.string.xray_actions_loading))
            }

            is ActionsState.Unavailable -> {
                InfoText(problemText(actions.problem))
                TextButton(onClick = { onIntent(XRayIntent.ReloadActions) }) {
                    Text(stringResource(R.string.xray_actions_retry))
                }
            }

            is ActionsState.Loaded -> {
                Controls(actions.controls, enabled = !detail.busy, onIntent = onIntent)
            }
        }
        if (detail.busy) LoadingLine(stringResource(R.string.xray_actions_busy))
        detail.notice?.let { NoticeLine(it) }
        if (detail.overrides.isNotEmpty() && detail.actions !is ActionsState.Blocked && detail.actions != ActionsState.UnsupportedUser) {
            Overrides(detail, onIntent)
        }
    }
}

@Composable
private fun BlockedCard(
    reason: BlockedReason,
    backend: ShizukuBackend?,
) {
    val spacing = MaterialTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SemanticDot(color = MaterialTheme.statusColors.warning)
                Text(
                    text = stringResource(blockedTitleRes(backend)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = spacing.sm),
                )
            }
            Text(
                text = stringResource(blockedBodyRes(reason, backend)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoText(
    text: String,
    small: Boolean = false,
) {
    val style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    Text(text = text, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LoadingLine(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
        InfoText(text)
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun NoticeLine(notice: ActionNotice) {
    val color = if (notice is ActionNotice.Applied) MaterialTheme.statusColors.ok else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
    ) {
        SemanticDot(color = color, modifier = Modifier.padding(top = MaterialTheme.spacing.sm))
        Text(
            text = noticeText(notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = MaterialTheme.spacing.sm),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(
    controls: AppControls,
    enabled: Boolean,
    onIntent: (XRayIntent) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
        RestrictRow(controls = controls, enabled = enabled, onIntent = onIntent)

        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text = stringResource(R.string.xray_action_bucket_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            InfoText(stringResource(R.string.xray_action_bucket_body), small = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                StandbyBucket.entries.forEach { bucket ->
                    val selected = controls.bucket == bucket
                    FilterChip(
                        selected = selected,
                        onClick = { if (!selected) onIntent(XRayIntent.Act(ActionRequest.SetBucket(bucket))) },
                        label = { Text(stringResource(bucketLabelRes(bucket))) },
                        enabled = enabled,
                        // The selected bucket is unmistakable: a check, a stronger fill and an outline.
                        leadingIcon = if (selected) ({ SelectedCheck() }) else null,
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        border =
                            FilterChipDefaults.filterChipBorder(
                                enabled = enabled,
                                selected = selected,
                                selectedBorderColor = MaterialTheme.colorScheme.primary,
                                selectedBorderWidth = MaterialTheme.spacing.xs / 4, // the 1 dp hairline of an unselected chip
                            ),
                    )
                }
            }
            if (controls.bucket == null && controls.bucketRaw != null) {
                InfoText(stringResource(R.string.xray_action_bucket_other, controls.bucketRaw))
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            FilledTonalButton(
                onClick = { onIntent(XRayIntent.Act(ActionRequest.ForceStop)) },
                enabled = enabled,
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
            ) {
                Text(stringResource(R.string.xray_action_force_stop))
            }
            InfoText(stringResource(R.string.xray_action_force_stop_body), small = true)
        }
    }
}

/** The check of the selected chip, in the chip's own icon colour (the app has no icon font to take one from). */
@Composable
private fun SelectedCheck() {
    val color = LocalContentColor.current
    val strokeWidth = MaterialTheme.spacing.xs / 2
    Canvas(modifier = Modifier.size(FilterChipDefaults.IconSize)) {
        val path =
            Path().apply {
                CHECK_POINTS.forEachIndexed { i, (x, y) ->
                    val point = Offset(x * size.width, y * size.height)
                    if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                }
            }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private val CHECK_POINTS = listOf(0.12f to 0.55f, 0.40f to 0.80f, 0.88f to 0.22f)

@Composable
private fun RestrictRow(
    controls: AppControls,
    enabled: Boolean,
    onIntent: (XRayIntent) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val editable = enabled && controls.restrictionEditable
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = controls.restricted,
                        enabled = editable,
                        role = Role.Switch,
                        onValueChange = { onIntent(XRayIntent.Act(ActionRequest.SetRestricted(it))) },
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(
                    text = stringResource(R.string.xray_action_restrict_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                InfoText(stringResource(R.string.xray_action_restrict_body), small = true)
            }
            // Read-back state, drawn by the row's toggleable: the switch itself takes no input.
            Switch(checked = controls.restricted, onCheckedChange = null, enabled = editable)
        }
        if (!controls.restrictionEditable) InfoText(stringResource(R.string.xray_action_restrict_custom), small = true)
    }
}

@Composable
private fun Overrides(
    detail: AppDetail,
    onIntent: (XRayIntent) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    InspirationCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            CardTitle(stringResource(R.string.xray_overrides_title))
            detail.overrides.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = overrideText(row),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        row.unverified?.let { reason ->
                            Text(
                                text = stringResource(R.string.xray_override_unverified, stringResource(refusalRes(reason))),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!row.restorable) {
                            Text(
                                text = stringResource(R.string.xray_override_not_restorable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (row.restorable) {
                        TextButton(
                            onClick = { onIntent(XRayIntent.Act(ActionRequest.Restore(row.actionId))) },
                            enabled = !detail.busy,
                        ) {
                            Text(stringResource(R.string.xray_override_restore))
                        }
                    }
                }
            }
        }
    }
}

// ---- confirmation ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfirmationDialog(
    confirmation: Confirmation,
    onIntent: (XRayIntent) -> Unit,
) {
    BasicAlertDialog(onDismissRequest = { onIntent(XRayIntent.DismissConfirmation) }) {
        ConfirmationPanel(
            confirmation = confirmation,
            onConfirm = { onIntent(XRayIntent.ConfirmPending) },
            onDismiss = { onIntent(XRayIntent.DismissConfirmation) },
        )
    }
}

/** The dialog's content: the typed warnings in words, and a yes that is never the default focus target. */
@Composable
fun ConfirmationPanel(
    confirmation: Confirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val destructive = confirmation.request == ActionRequest.ForceStop
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
        Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            Text(
                text = confirmationTitle(confirmation),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            confirmation.warnings.forEach { warning ->
                Row(verticalAlignment = Alignment.Top) {
                    SemanticDot(
                        color = MaterialTheme.statusColors.warning,
                        modifier = Modifier.padding(top = spacing.xs, end = spacing.sm),
                    )
                    Text(
                        text = stringResource(warningRes(warning)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Cancel stays outside the guard: an overlay can't do harm with it, and a dimmer mustn't trap the user here.
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.xray_confirm_cancel)) }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.filterTouchesWhenObscured(),
                    colors =
                        if (destructive) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                ) {
                    Text(stringResource(confirmActionRes(confirmation.request)))
                }
            }
        }
    }
}
