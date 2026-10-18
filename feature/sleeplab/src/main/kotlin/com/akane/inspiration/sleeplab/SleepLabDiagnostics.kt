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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.akane.inspiration.designsystem.component.CardTitle
import com.akane.inspiration.designsystem.component.EmptyState
import com.akane.inspiration.designsystem.component.InspirationCard
import com.akane.inspiration.designsystem.component.SegmentedToggle
import com.akane.inspiration.designsystem.component.format.TelemetryFormat
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.designsystem.theme.numericTypography
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.diagnostics.dictionary.EntryKind
import com.akane.inspiration.diagnostics.dictionary.Explanation
import kotlinx.collections.immutable.toImmutableList

private const val MAX_TAG_LINES = 3
private const val MAX_TITLE_LINES = 3
private const val TAG_LINES = 1

/**
 * The dictionary's words for each alarm row's busiest tag, in row order. Rows commonly share one explanation (every
 * "*walarm*:" tag reads "Wake-up alarm"), so a repeat of one an earlier row already shows is null: it is said once.
 */
internal fun alarmExplanationsOnce(rows: List<AlarmRow>, explain: (EntryKind, String) -> Explanation?): List<Explanation?> {
    val shown = HashSet<String>()
    return rows.map { row ->
        row.tags.firstOrNull()?.tag?.let { explain(EntryKind.ALARM, it) }?.takeIf { shown.add(it.id) }
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
        CardTitle(text = stringResource(R.string.sleeplab_diagnostics_title))
        SegmentedToggle(
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
            ) { _, row -> AppWakelockItem(row, explain) }
            SleepTab.KernelWakelocks -> RankedList(
                list = analysis.kernelWakelocks,
                unavailableTitle = R.string.sleeplab_kernel_unavailable_title,
                unavailableBody = R.string.sleeplab_kernel_unavailable_body,
            ) { _, row -> KernelWakelockItem(row, explain) }
            SleepTab.Alarms -> {
                val explanations = remember(analysis.alarms, explain) { alarmExplanationsOnce(analysis.alarms.rowsOrEmpty(), explain) }
                RankedList(
                    list = analysis.alarms,
                    unavailableTitle = R.string.sleeplab_apps_unavailable_title,
                    unavailableBody = R.string.sleeplab_apps_unavailable_body,
                ) { index, row -> AlarmItem(row, explanations.getOrNull(index)) }
            }
        }
    }
}

/** A tab's rows with dividers, or the designed state that explains why there are none. */
@Composable
private fun <T> RankedList(
    list: ListUi<T>,
    @StringRes unavailableTitle: Int,
    @StringRes unavailableBody: Int,
    item: @Composable (index: Int, row: T) -> Unit,
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
                    item(index, row)
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
private fun ownerTitle(appLabel: String?, uid: Int): String =
    appLabel
        ?: systemUidLabelRes(uid)?.let { stringResource(it) }
        ?: stringResource(R.string.sleeplab_owner_uid, uid)

private const val PER_USER_UID_RANGE = 100_000

/** A readable name for a well-known shared platform UID (in any user), or null for everything else. */
@StringRes
internal fun systemUidLabelRes(uid: Int): Int? = when (uid % PER_USER_UID_RANGE) {
    0 -> R.string.sleeplab_uid_root
    1_000 -> R.string.sleeplab_uid_system
    1_001 -> R.string.sleeplab_uid_phone
    1_002 -> R.string.sleeplab_uid_bluetooth
    1_010 -> R.string.sleeplab_uid_wifi
    1_013 -> R.string.sleeplab_uid_media
    1_017 -> R.string.sleeplab_uid_keystore
    1_021 -> R.string.sleeplab_uid_gps
    1_027 -> R.string.sleeplab_uid_nfc
    1_041 -> R.string.sleeplab_uid_audio
    1_047 -> R.string.sleeplab_uid_camera
    2_000 -> R.string.sleeplab_uid_shell
    else -> null
}

@Composable
private fun AppWakelockItem(row: AppWakelockRow, explain: (EntryKind, String) -> Explanation?) {
    val format = rememberTelemetryFormat()
    WakeItem(
        title = ownerTitle(row.appLabel, row.uid),
        subtitle = row.name,
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

/** One app's alarms: the total, the busiest tags, and [explanation] of the busiest one (null once it was said above). */
@Composable
private fun AlarmItem(row: AlarmRow, explanation: Explanation?) {
    val format = rememberTelemetryFormat()
    WakeItem(
        title = ownerTitle(row.appLabel, row.uid),
        subtitle = null,
        primary = pluralStringResource(R.plurals.sleeplab_wakeups, row.wakeups.asQuantity(), format.number(row.wakeups.toDouble())),
        secondary = null,
        explanation = explanation,
    ) {
        row.tags.take(MAX_TAG_LINES).forEach { tag ->
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                // A tag is one long token; ellipsise its middle so the start and the end, which tell tags apart, stay.
                Text(
                    text = tag.tag,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = TAG_LINES,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = timesText(format, tag.count),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = TAG_LINES,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
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
