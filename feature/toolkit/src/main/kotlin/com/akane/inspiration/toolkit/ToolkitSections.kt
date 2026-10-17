package com.akane.inspiration.toolkit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.akane.inspiration.designsystem.component.InspirationCard
import com.akane.inspiration.designsystem.component.SemanticDot
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.designsystem.theme.numericTypography
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.telemetryColors
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import com.akane.inspiration.telemetry.toolkit.BgDexoptStatus
import kotlinx.collections.immutable.ImmutableList
import java.util.TimeZone

// ---- doze ----------------------------------------------------------------------------------------

@Composable
internal fun DozeCard(
    section: DozeSection,
    busy: Boolean,
    onIntent: (ToolkitIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    InspirationCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                SectionHeading(stringResource(R.string.toolkit_doze_title), modifier = Modifier.weight(1f, fill = false))
                ExperimentalBadge()
            }
            InfoText(stringResource(R.string.toolkit_doze_intro))
            when (val load = section.load) {
                DozeLoad.Loading -> LoadingLine(stringResource(R.string.toolkit_doze_loading))
                is DozeLoad.Unavailable -> {
                    InfoText(stringResource(R.string.toolkit_doze_unavailable))
                    InfoText(problemText(load.problem), small = true)
                    RetryButton(label = stringResource(R.string.toolkit_error_action), onClick = { onIntent(ToolkitIntent.Reload) })
                }
                is DozeLoad.Loaded -> DozeLoaded(info = load.info, busy = busy, onIntent = onIntent)
            }
            section.notice?.let { NoticeLine(it) }
        }
    }
}

@Composable
private fun ExperimentalBadge() {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.telemetryColors.awakeContainer) {
        Text(
            text = stringResource(R.string.toolkit_doze_experimental),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.sm, vertical = MaterialTheme.spacing.xs),
        )
    }
}

@Composable
private fun DozeLoaded(info: DozeInfo, busy: Boolean, onIntent: (ToolkitIntent) -> Unit) {
    val spacing = MaterialTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        val current = info.profile
        Text(
            text = if (current != null) {
                stringResource(R.string.toolkit_doze_current, stringResource(profileLabelRes(current)))
            } else {
                stringResource(R.string.toolkit_doze_current_custom)
            },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Column(modifier = Modifier.filterTouchesWhenObscured(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            DozeProfile.entries.forEach { profile ->
                ProfileRow(
                    profile = profile,
                    active = profile == current,
                    enabled = !busy,
                    onApply = { onIntent(ToolkitIntent.Act(ToolkitRequest.ApplyDoze(profile))) },
                )
            }
        }
        EffectiveValues(info)
    }
}

@Composable
private fun ProfileRow(profile: DozeProfile, active: Boolean, enabled: Boolean, onApply: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                text = stringResource(profileLabelRes(profile)),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            InfoText(stringResource(profileBodyRes(profile)), small = true)
        }
        if (active) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                SemanticDot(color = MaterialTheme.telemetryColors.deepSleep)
                Text(
                    text = stringResource(R.string.toolkit_doze_active),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            TextButton(onClick = onApply, enabled = enabled) { Text(stringResource(R.string.toolkit_doze_apply)) }
        }
    }
}

@Composable
private fun EffectiveValues(info: DozeInfo) {
    if (info.effective.isEmpty()) return
    val spacing = MaterialTheme.spacing
    val format = rememberTelemetryFormat()
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            text = stringResource(R.string.toolkit_doze_effective_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        info.effective.forEach { value ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text(
                    text = dozeKeyLabel(value.key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = msText(format, value.ms),
                    style = MaterialTheme.numericTypography.label,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// ---- bg-dexopt -----------------------------------------------------------------------------------

@Composable
internal fun DexoptCard(
    section: DexoptSection,
    onIntent: (ToolkitIntent) -> Unit,
    timeZone: TimeZone,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val status = section.status
    InspirationCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SectionHeading(stringResource(R.string.toolkit_dexopt_title))
            InfoText(stringResource(R.string.toolkit_dexopt_body))
            if (status == null) {
                LoadingLine(stringResource(R.string.toolkit_dexopt_loading))
            } else {
                DexoptStatusLine(status = status, timeZone = timeZone)
                // Unknown means the service restarted or the state was lost: it may still be running, so Cancel stays reachable.
                val mayBeRunning = status is BgDexoptStatus.Running || status == BgDexoptStatus.Unknown
                Row(
                    modifier = Modifier.filterTouchesWhenObscured(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    if (mayBeRunning) {
                        OutlinedButton(onClick = { onIntent(ToolkitIntent.CancelDexopt) }, enabled = !section.busy) {
                            Text(stringResource(R.string.toolkit_dexopt_cancel))
                        }
                    } else {
                        Button(onClick = { onIntent(ToolkitIntent.StartDexopt) }, enabled = !section.busy) {
                            Text(stringResource(R.string.toolkit_dexopt_start))
                        }
                    }
                }
            }
            section.notice?.let { NoticeLine(it) }
        }
    }
}

@Composable
private fun DexoptStatusLine(status: BgDexoptStatus, timeZone: TimeZone) {
    val telemetry = MaterialTheme.telemetryColors
    val color = when (status) {
        is BgDexoptStatus.Running -> telemetry.charging
        is BgDexoptStatus.Finished -> if (status.exitCode == 0) telemetry.deepSleep else telemetry.critical
        is BgDexoptStatus.Cancelled -> telemetry.awake
        BgDexoptStatus.Unknown -> telemetry.awake
        BgDexoptStatus.Idle -> MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
    ) {
        SemanticDot(color = color, modifier = Modifier.padding(top = MaterialTheme.spacing.sm))
        Text(
            text = dexoptStatusText(status, timeFormatter(timeZone)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = MaterialTheme.spacing.sm),
        )
    }
}

// ---- overrides -----------------------------------------------------------------------------------

@Composable
internal fun OverridesCard(
    state: ToolkitState,
    onIntent: (ToolkitIntent) -> Unit,
    timeZone: TimeZone,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val formatter = timeFormatter(timeZone)
    InspirationCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SectionHeading(stringResource(R.string.toolkit_overrides_title))
            if (state.overrides.isEmpty()) {
                InfoText(stringResource(R.string.toolkit_overrides_empty))
            } else {
                Column(modifier = Modifier.filterTouchesWhenObscured(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    state.overrides.forEach { item ->
                        OverrideRow(item = item, since = formatter.at(item.sinceMs), enabled = !state.busy, onIntent = onIntent)
                    }
                }
            }
            state.overridesNotice?.let { NoticeLine(it) }
        }
    }
}

@Composable
private fun OverrideRow(item: OverrideItem, since: String, enabled: Boolean, onIntent: (ToolkitIntent) -> Unit) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                text = overrideText(item),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            InfoText(stringResource(R.string.toolkit_override_since, since), small = true)
            if (!item.restorable) InfoText(stringResource(R.string.toolkit_override_not_restorable), small = true)
        }
        if (item.restorable) {
            TextButton(
                onClick = { onIntent(ToolkitIntent.Act(ToolkitRequest.Restore(item.actionId))) },
                enabled = enabled,
            ) {
                Text(stringResource(R.string.toolkit_override_restore))
            }
        }
    }
}

// ---- reset report --------------------------------------------------------------------------------

/** What "Reset all" did, item by item: a failed item stays listed with its reason. */
@Composable
internal fun ResetReportCard(report: ResetReport, onIntent: (ToolkitIntent) -> Unit, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    val telemetry = MaterialTheme.telemetryColors
    InspirationCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SectionHeading(stringResource(R.string.toolkit_reset_report_title))
            Text(
                text = stringResource(R.string.toolkit_reset_report_summary, report.restored, report.lines.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            report.lines.forEach { line ->
                val ok = line.result == ResetResult.Restored
                Row(verticalAlignment = Alignment.Top) {
                    SemanticDot(
                        color = if (ok) telemetry.deepSleep else telemetry.critical,
                        modifier = Modifier.padding(top = spacing.sm),
                    )
                    Column(modifier = Modifier.padding(start = spacing.sm)) {
                        Text(
                            text = overrideText(line.item),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val result = line.result
                        InfoText(
                            text = if (result is ResetResult.NotRestored) {
                                stringResource(R.string.toolkit_reset_not_restored, problemText(result.problem))
                            } else {
                                stringResource(R.string.toolkit_status_restored)
                            },
                            small = true,
                        )
                    }
                }
            }
            if (report.failed > 0) InfoText(stringResource(R.string.toolkit_reset_report_failed_note), small = true)
            TextButton(onClick = { onIntent(ToolkitIntent.DismissResetReport) }) {
                Text(stringResource(R.string.toolkit_reset_report_dismiss))
            }
        }
    }
}

// ---- history -------------------------------------------------------------------------------------

@Composable
internal fun HistoryCard(history: ImmutableList<HistoryItem>, timeZone: TimeZone, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    val formatter = timeFormatter(timeZone)
    InspirationCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SectionHeading(stringResource(R.string.toolkit_history_title))
            if (history.isEmpty()) {
                InfoText(stringResource(R.string.toolkit_history_empty))
            } else {
                history.forEach { item -> HistoryRow(item = item, time = formatter.at(item.startedAtMs)) }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, time: String) {
    val spacing = MaterialTheme.spacing
    Row(verticalAlignment = Alignment.Top) {
        SemanticDot(color = item.status.dotColor(), modifier = Modifier.padding(top = spacing.sm))
        Column(modifier = Modifier.padding(start = spacing.sm)) {
            Text(
                text = historyText(item),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            InfoText(
                text = stringResource(R.string.toolkit_history_meta, stringResource(statusRes(item.status)), time),
                small = true,
            )
        }
    }
}

@Composable
private fun ToolkitActionStatus.dotColor(): Color {
    val telemetry = MaterialTheme.telemetryColors
    return when (this) {
        ToolkitActionStatus.DONE, ToolkitActionStatus.RESTORED -> telemetry.deepSleep
        ToolkitActionStatus.REFUSED, ToolkitActionStatus.FAILED -> telemetry.critical
        ToolkitActionStatus.PENDING, ToolkitActionStatus.CANCELLED -> telemetry.awake
    }
}
