package com.akane.inspiration.sleeplab

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import com.akane.inspiration.designsystem.component.CardTitle
import com.akane.inspiration.designsystem.component.InspirationCard
import com.akane.inspiration.designsystem.component.MetricBar
import com.akane.inspiration.designsystem.component.MetricValue
import com.akane.inspiration.designsystem.component.SemanticDot
import com.akane.inspiration.designsystem.component.format.TelemetryFormat
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.statusColors
import com.akane.inspiration.designsystem.theme.telemetryColors
import com.akane.inspiration.diagnostics.dictionary.EntryKind
import com.akane.inspiration.diagnostics.dictionary.Explanation
import com.akane.inspiration.diagnostics.model.CulpritScope
import com.akane.inspiration.diagnostics.model.Severity
import com.akane.inspiration.diagnostics.rules.AWAKE_AMBER_RATIO
import com.akane.inspiration.diagnostics.rules.AWAKE_RED_RATIO
import com.akane.inspiration.diagnostics.rules.MIN_SCREEN_OFF_MS

private const val PERCENT = 100.0

/**
 * The culprit card ("Suçlu Kartı", TEMPLATE.md §4 Rule 2). A violation shows the awake share of screen-off time with
 * its severity, which window the culprits were counted over, and up to three culprits with the dictionary's words and
 * the Toolkit control that acts on them. A check that could not run says why. Nothing is shown when the period was
 * checked and stayed within the limit.
 */
@Composable
internal fun CulpritCard(
    awake: AwakeCheckUi,
    explain: (EntryKind, String) -> Explanation?,
    modifier: Modifier = Modifier,
) {
    when (awake) {
        AwakeCheckUi.WithinLimit -> Unit
        is AwakeCheckUi.NotEvaluable -> NotEvaluableCard(awake.reason, modifier)
        is AwakeCheckUi.Violation -> ViolationCard(awake, explain, modifier)
    }
}

@Composable
private fun NotEvaluableCard(
    reason: AwakeSkipReason,
    modifier: Modifier = Modifier,
) {
    val format = rememberTelemetryFormat()
    InspirationCard(modifier = modifier.fillMaxWidth()) {
        CardHeader(
            title = stringResource(R.string.sleeplab_culprit_skipped_title),
            subtitle =
                when (reason) {
                    AwakeSkipReason.ShortScreenOff -> stringResource(R.string.sleeplab_culprit_skipped_short, format.duration(MIN_SCREEN_OFF_MS))
                    AwakeSkipReason.StatsReset -> stringResource(R.string.sleeplab_culprit_skipped_stats_reset)
                    AwakeSkipReason.NotCaptured -> stringResource(R.string.sleeplab_culprit_skipped_not_captured)
                    AwakeSkipReason.NoAwakeTime -> stringResource(R.string.sleeplab_culprit_skipped_no_awake)
                    AwakeSkipReason.Other -> stringResource(R.string.sleeplab_culprit_skipped_other)
                },
        )
    }
}

@Composable
private fun ViolationCard(
    violation: AwakeCheckUi.Violation,
    explain: (EntryKind, String) -> Explanation?,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val format = rememberTelemetryFormat()
    InspirationCard(modifier = modifier.fillMaxWidth()) {
        CardTitle(text = stringResource(R.string.sleeplab_culprit_title))
        AwakeShare(violation, format, Modifier.padding(top = spacing.sm))
        Text(
            text =
                stringResource(
                    when (violation.scope) {
                        CulpritScope.SCREEN_OFF_ONLY -> R.string.sleeplab_culprit_scope_screen_off
                        CulpritScope.WHOLE_INTERVAL_CONTRIBUTORS -> R.string.sleeplab_culprit_scope_whole
                    },
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.sm),
        )
        HorizontalDivider(modifier = Modifier.padding(top = spacing.md), color = MaterialTheme.colorScheme.outlineVariant)
        CulpritList(violation, explain, format)
        if (violation.skippedApps > 0) {
            Text(
                text = pluralStringResource(R.plurals.sleeplab_culprit_skipped_apps, violation.skippedApps, format.number(violation.skippedApps.toDouble())),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.sm),
            )
        }
    }
}

/** The awake share as the card's figure, its severity named in words beside the dot, and how much time that was. */
@Composable
private fun AwakeShare(
    violation: AwakeCheckUi.Violation,
    format: TelemetryFormat,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val color = severityColor(violation.severity)
    val awakeMs = (violation.awakeRatio * violation.screenOffMs).toLong()
    Column(modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            MetricValue(
                value = format.number(violation.awakeRatio * PERCENT, decimals = 1),
                unit = stringResource(R.string.sleeplab_unit_percent),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                SemanticDot(color = color)
                Text(
                    text =
                        when (violation.severity) {
                            Severity.RED -> stringResource(R.string.sleeplab_culprit_severity_red, format.number(AWAKE_RED_RATIO * PERCENT))
                            Severity.AMBER -> stringResource(R.string.sleeplab_culprit_severity_amber, format.number(AWAKE_AMBER_RATIO * PERCENT))
                        },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        MetricBar(progress = violation.awakeRatio.toFloat(), color = color)
        Text(
            text = stringResource(R.string.sleeplab_culprit_awake_detail, format.duration(awakeMs), format.duration(violation.screenOffMs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun severityColor(severity: Severity): Color =
    when (severity) {
        Severity.RED -> MaterialTheme.telemetryColors.critical
        Severity.AMBER -> MaterialTheme.statusColors.warning
    }

/** The culprits with dividers, or a line saying the rule named none. */
@Composable
private fun CulpritList(
    violation: AwakeCheckUi.Violation,
    explain: (EntryKind, String) -> Explanation?,
    format: TelemetryFormat,
) {
    Column(modifier = Modifier.padding(top = MaterialTheme.spacing.xs)) {
        violation.culprits.forEachIndexed { index, culprit ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CulpritItem(culprit, explain, format)
        }
        if (violation.culprits.isEmpty()) {
            Text(
                text = stringResource(R.string.sleeplab_culprit_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = MaterialTheme.spacing.md),
            )
        }
    }
}

@Composable
private fun CulpritItem(
    culprit: CulpritRow,
    explain: (EntryKind, String) -> Explanation?,
    format: TelemetryFormat,
) {
    val explanation = remember(culprit.kind, culprit.name, explain) { explain(culprit.kind, culprit.name) }
    val count = culprit.count
    val duration = culprit.durationMs
    val owner = culprit.uid?.let { ownerTitle(culprit.appLabel, it) }
    val action = explanation?.action?.let(::actionGuidanceRes)
    WakeItem(
        title = owner ?: culprit.name,
        subtitle = culprit.name.takeIf { owner != null },
        primary =
            when {
                duration != null -> formatSpan(format, duration, stringResource(R.string.sleeplab_unit_second))
                count != null -> pluralStringResource(R.plurals.sleeplab_wakeups, count.asQuantity(), format.number(count.toDouble()))
                else -> format.number(null)
            },
        secondary = if (duration != null && count != null) timesText(format, count) else null,
        explanation = explanation,
        overline = stringResource(culpritKindRes(culprit.kind)),
        footer =
            action?.let { res ->
                {
                    Text(
                        text = stringResource(res),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
    )
}

@StringRes
private fun culpritKindRes(kind: EntryKind): Int =
    when (kind) {
        EntryKind.PARTIAL -> R.string.sleeplab_culprit_kind_partial
        EntryKind.KERNEL -> R.string.sleeplab_culprit_kind_kernel
        EntryKind.ALARM -> R.string.sleeplab_culprit_kind_alarm
        EntryKind.WAKEUP_REASON -> R.string.sleeplab_culprit_kind_wakeup_reason
    }

/**
 * Where the dictionary's one-tap fix lives today: the Toolkit control it names, as guidance. Sleep Lab adds no
 * privileged write of its own. Null for an action this build does not know.
 */
@StringRes
internal fun actionGuidanceRes(action: String): Int? =
    when (action) {
        "standby_bucket" -> R.string.sleeplab_culprit_action_standby_bucket
        "appops_wake_lock" -> R.string.sleeplab_culprit_action_wake_lock
        "bg_dexopt" -> R.string.sleeplab_culprit_action_dexopt
        else -> null
    }
