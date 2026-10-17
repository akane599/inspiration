package com.akane.inspiration.sleeplab

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.akane.inspiration.designsystem.component.EmptyState
import com.akane.inspiration.designsystem.component.InspirationCard
import com.akane.inspiration.designsystem.component.format.TelemetryFormat
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.designsystem.theme.numericTypography
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.diagnostics.dictionary.EntryKind
import com.akane.inspiration.diagnostics.dictionary.Explanation
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

private const val MAX_TAG_LINES = 3
private const val MAX_TITLE_LINES = 3

/**
 * The shared `SegmentedToggle` keeps its label to one line beside a check mark, which clips a third-of-the-width label
 * at 360 dp with large fonts. Three short tab names here instead drop the check mark (the filled segment and the
 * selected role carry the state), tighten the label padding, use the smaller label style and let a label take two
 * lines, so none is cut at 360 dp and a 1.5 font scale.
 */
@Composable
private fun TabSwitch(
    options: ImmutableList<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selected,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                icon = {},
                contentPadding = PaddingValues(horizontal = MaterialTheme.spacing.xs),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (index == selected) FontWeight.Bold else null,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The three tabs under the hypnogram: app wakelocks, kernel wakelocks, and alarms with their wake-ups. */
@Composable
internal fun DiagnosticsCard(
    analysis: AnalysisUi.Ready,
    tab: SleepTab,
    onIntent: (SleepLabIntent) -> Unit,
    explain: (EntryKind, String) -> Explanation?,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val apps = stringResource(R.string.sleeplab_tab_apps)
    val kernel = stringResource(R.string.sleeplab_tab_kernel)
    val alarms = stringResource(R.string.sleeplab_tab_alarms)
    val options = remember(apps, kernel, alarms) { listOf(apps, kernel, alarms).toImmutableList() }
    InspirationCard(modifier = modifier.fillMaxWidth()) {
        CardTitle(title = stringResource(R.string.sleeplab_diagnostics_title), subtitle = null)
        TabSwitch(
            options = options,
            selected = tab.ordinal,
            onSelect = { onIntent(SleepLabIntent.SelectTab(SleepTab.entries[it])) },
            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
        )
        Text(
            text = stringResource(
                when (tab) {
                    SleepTab.AppWakelocks -> R.string.sleeplab_caption_apps
                    SleepTab.KernelWakelocks -> R.string.sleeplab_caption_kernel
                    SleepTab.Alarms -> R.string.sleeplab_caption_alarms
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.sm),
        )
        when (tab) {
            SleepTab.AppWakelocks -> RankedList(
                list = analysis.appWakelocks,
                unavailableTitle = R.string.sleeplab_apps_unavailable_title,
                unavailableBody = R.string.sleeplab_apps_unavailable_body,
            ) { row -> AppWakelockItem(row, explain) }
            SleepTab.KernelWakelocks -> RankedList(
                list = analysis.kernelWakelocks,
                unavailableTitle = R.string.sleeplab_kernel_unavailable_title,
                unavailableBody = R.string.sleeplab_kernel_unavailable_body,
            ) { row -> KernelWakelockItem(row, explain) }
            SleepTab.Alarms -> RankedList(
                list = analysis.alarms,
                unavailableTitle = R.string.sleeplab_apps_unavailable_title,
                unavailableBody = R.string.sleeplab_apps_unavailable_body,
            ) { row -> AlarmItem(row, explain) }
        }
    }
}

/** A tab's rows with dividers, or the designed state that explains why there are none. */
@Composable
private fun <T> RankedList(
    list: ListUi<T>,
    @StringRes unavailableTitle: Int,
    @StringRes unavailableBody: Int,
    item: @Composable (T) -> Unit,
) {
    when (list) {
        ListUi.NeedsFullSnapshots -> EmptyState(
            title = stringResource(R.string.sleeplab_needs_full_title),
            body = stringResource(R.string.sleeplab_needs_full_body),
            action = null,
        )
        ListUi.Unavailable -> EmptyState(
            title = stringResource(unavailableTitle),
            body = stringResource(unavailableBody),
            action = null,
        )
        is ListUi.Rows -> if (list.rows.isEmpty()) {
            Text(
                text = stringResource(R.string.sleeplab_list_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = MaterialTheme.spacing.md),
            )
        } else {
            Column(modifier = Modifier.padding(top = MaterialTheme.spacing.xs)) {
                list.rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    item(row)
                }
                if (list.hidden > 0) {
                    val format = rememberTelemetryFormat()
                    Text(
                        text = pluralStringResource(R.plurals.sleeplab_rows_hidden, list.hidden, format.number(list.hidden.toDouble())),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = MaterialTheme.spacing.sm),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppWakelockItem(row: AppWakelockRow, explain: (EntryKind, String) -> Explanation?) {
    val format = rememberTelemetryFormat()
    WakeItem(
        title = row.name,
        subtitle = row.appLabel ?: stringResource(R.string.sleeplab_owner_uid, row.uid),
        primary = formatSpan(format, row.durationMs, stringResource(R.string.sleeplab_unit_second)),
        secondary = timesText(format, row.count),
        explanation = remember(row.name, explain) { explain(EntryKind.PARTIAL, row.name) },
    )
}

@Composable
private fun KernelWakelockItem(row: KernelWakelockRow, explain: (EntryKind, String) -> Explanation?) {
    val format = rememberTelemetryFormat()
    WakeItem(
        title = row.name,
        subtitle = null,
        primary = formatSpan(format, row.durationMs, stringResource(R.string.sleeplab_unit_second)),
        secondary = timesText(format, row.count),
        explanation = remember(row.name, explain) { explain(EntryKind.KERNEL, row.name) },
    )
}

/** One app's alarms: the total, the busiest tags, and the dictionary's words for the busiest one. */
@Composable
private fun AlarmItem(row: AlarmRow, explain: (EntryKind, String) -> Explanation?) {
    val format = rememberTelemetryFormat()
    val busiest = row.tags.firstOrNull()?.tag
    WakeItem(
        title = row.appLabel ?: stringResource(R.string.sleeplab_owner_uid, row.uid),
        subtitle = null,
        primary = pluralStringResource(R.plurals.sleeplab_wakeups, row.wakeups.asQuantity(), format.number(row.wakeups.toDouble())),
        secondary = null,
        explanation = remember(busiest, explain) { busiest?.let { explain(EntryKind.ALARM, it) } },
    ) {
        row.tags.take(MAX_TAG_LINES).forEach { tag ->
            Text(
                text = stringResource(R.string.sleeplab_tag_line, tag.tag, timesText(format, tag.count)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val more = row.tags.size - MAX_TAG_LINES
        if (more > 0) {
            Text(
                text = pluralStringResource(R.plurals.sleeplab_more_tags, more, format.number(more.toDouble())),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun timesText(format: TelemetryFormat, count: Long): String =
    pluralStringResource(R.plurals.sleeplab_times, count.asQuantity(), format.number(count.toDouble()))

/** One ranked entry: a title and what owns it on the left, the figures on the right, then the explanation. */
@Composable
private fun WakeItem(
    title: String,
    subtitle: String?,
    primary: String,
    secondary: String?,
    explanation: Explanation?,
    extra: @Composable (() -> Unit)? = null,
) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.sm).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = MAX_TITLE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(text = subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = primary, style = MaterialTheme.numericTypography.label, color = MaterialTheme.colorScheme.onSurface)
                if (secondary != null) {
                    Text(text = secondary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        extra?.invoke()
        if (explanation != null) {
            Text(text = explanation.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(text = explanation.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
