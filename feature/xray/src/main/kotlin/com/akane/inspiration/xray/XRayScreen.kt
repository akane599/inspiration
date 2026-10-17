package com.akane.inspiration.xray

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.akane.inspiration.designsystem.component.BarRole
import com.akane.inspiration.designsystem.component.BarSegment
import com.akane.inspiration.designsystem.component.EmptyState
import com.akane.inspiration.designsystem.component.EmptyStateAction
import com.akane.inspiration.designsystem.component.InspirationCard
import com.akane.inspiration.designsystem.component.LegendItem
import com.akane.inspiration.designsystem.component.LegendRow
import com.akane.inspiration.designsystem.component.SegmentedToggle
import com.akane.inspiration.designsystem.component.StackedBar
import com.akane.inspiration.designsystem.component.TimeSliceBanner
import com.akane.inspiration.designsystem.component.format.NO_VALUE
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.designsystem.theme.numericTypography
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.telemetryColors
import com.akane.inspiration.diagnostics.dictionary.EntryKind
import com.akane.inspiration.diagnostics.dictionary.Explanation
import java.util.TimeZone
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Looks a name up in the diagnostic dictionary for the current locale. Null when nothing matches. */
typealias Explainer = (EntryKind, String) -> Explanation?

/**
 * The X-Ray screen, stateless: everything it shows is in [state] and everything the user does leaves through
 * [onIntent]. [explain] words the wakelock and alarm names of the detail sheet. [timeZone] is a parameter only
 * so screenshots are the same on every machine.
 */
@Composable
fun XRayScreen(
    state: XRayState,
    onIntent: (XRayIntent) -> Unit,
    explain: Explainer,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    timeZone: TimeZone = TimeZone.getDefault(),
) {
    Box(modifier = modifier.fillMaxSize()) {
        XRayBody(state = state, onIntent = onIntent, timeZone = timeZone)
        if (state.refreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
        if (snackbarHostState != null) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(MaterialTheme.spacing.md),
            )
        }
    }
    state.detail?.let { detail ->
        AppDetailSheet(detail = detail, onIntent = onIntent, explain = explain)
    }
    state.confirmation?.let { confirmation ->
        ConfirmationDialog(confirmation = confirmation, onIntent = onIntent)
    }
}

@Composable
private fun XRayBody(state: XRayState, onIntent: (XRayIntent) -> Unit, timeZone: TimeZone) {
    val spacing = MaterialTheme.spacing
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = spacing.xxl * ADAPTIVE_COLUMN_UNITS),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        fullWidth { Header() }
        state.slice?.let { slice -> fullWidth { SliceBanner(slice, onIntent, timeZone) } }
        when (state.status) {
            XRayStatus.LOADING -> fullWidth { LoadingBlock() }
            XRayStatus.EMPTY -> fullWidth { EmptyBlock(onIntent) }
            XRayStatus.FAILED -> fullWidth { ErrorBlock(onIntent) }
            XRayStatus.READY -> ready(state, onIntent)
        }
    }
}

/** An item that spans every column of the adaptive grid. */
private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

private fun LazyGridScope.ready(state: XRayState, onIntent: (XRayIntent) -> Unit) {
    fullWidth {
        SegmentedToggle(
            options = rememberTabLabels(),
            selected = state.tab.ordinal,
            onSelect = { onIntent(XRayIntent.SelectTab(XRayTab.entries[it])) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    when (state.tab) {
        XRayTab.COMPONENTS -> componentsTab(state)
        XRayTab.APPS -> appsTab(state, onIntent)
    }
}

@Composable
private fun rememberTabLabels(): ImmutableList<String> {
    val components = stringResource(R.string.xray_tab_components)
    val apps = stringResource(R.string.xray_tab_apps)
    return remember(components, apps) { persistentListOf(components, apps) }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
        Text(
            text = stringResource(R.string.xray_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.xray_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SliceBanner(slice: SliceInfo, onIntent: (XRayIntent) -> Unit, timeZone: TimeZone) {
    val formatter = timeFormatter(timeZone)
    val format = rememberTelemetryFormat()
    val covered = if (slice.coveredFromMs != null && slice.coveredToMs != null) {
        rangeText(formatter, slice.coveredFromMs, slice.coveredToMs)
    } else {
        NO_VALUE
    }
    TimeSliceBanner(
        requested = rangeText(formatter, slice.requestedFromMs, slice.requestedToMs),
        covered = covered,
        gapText = slice.gapMs?.let { stringResource(R.string.xray_slice_gap, format.duration(it)) },
        onClear = { onIntent(XRayIntent.ClearSlice) },
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---- loading, empty, error ------------------------------------------------------------------

@Composable
private fun LoadingBlock() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        EmptyState(
            title = stringResource(R.string.xray_loading_title),
            body = stringResource(R.string.xray_loading_body),
            action = null,
        )
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun EmptyBlock(onIntent: (XRayIntent) -> Unit) {
    EmptyState(
        title = stringResource(R.string.xray_empty_title),
        body = stringResource(R.string.xray_empty_body),
        action = EmptyStateAction(stringResource(R.string.xray_empty_action)) { onIntent(XRayIntent.Capture) },
        modifier = Modifier.padding(top = MaterialTheme.spacing.xl),
    )
}

@Composable
private fun ErrorBlock(onIntent: (XRayIntent) -> Unit) {
    EmptyState(
        title = stringResource(R.string.xray_error_title),
        body = stringResource(R.string.xray_error_body),
        action = EmptyStateAction(stringResource(R.string.xray_error_action)) { onIntent(XRayIntent.Capture) },
        modifier = Modifier.padding(top = MaterialTheme.spacing.xl),
    )
}

// ---- components tab -------------------------------------------------------------------------

private fun LazyGridScope.componentsTab(state: XRayState) {
    fullWidth { SectionHeading(stringResource(R.string.xray_components_heading)) }
    if (!state.breakdownAvailable || state.components.isEmpty()) {
        fullWidth {
            EmptyState(
                title = stringResource(R.string.xray_components_unavailable_title),
                body = stringResource(R.string.xray_components_unavailable_body),
                action = null,
            )
        }
        return
    }
    fullWidth { ComponentsCard(state.components) }
}

@Composable
private fun ComponentsCard(components: ImmutableList<ComponentRow>) {
    val total = remember(components) { components.sumOf { it.mah ?: 0.0 } }
    InspirationCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            components.forEach { row -> ComponentItem(row, total) }
        }
    }
}

@Composable
private fun ComponentItem(row: ComponentRow, total: Double) {
    val label = componentLabel(row.key)
    val mah = mahText(row.mah)
    // One dash for a missing figure, not two.
    val share = if (row.mah == null) "" else shareText(row.sharePct)
    val unavailable = stringResource(R.string.xray_value_unavailable)
    val summary = if (row.mah == null) "$label, $unavailable" else "$label, $mah, $share"
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = summary },
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(text = mah, style = MaterialTheme.numericTypography.body, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = share,
                style = MaterialTheme.numericTypography.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = MaterialTheme.spacing.xxl),
            )
        }
        val segments = remember(row, label, mah) {
            persistentListOf(
                BarSegment(
                    value = (row.mah ?: 0.0).toFloat(),
                    role = if (row.key == "SCREEN") BarRole.SCREEN_ON else BarRole.NEUTRAL,
                    label = label,
                    valueText = mah,
                ),
            )
        }
        StackedBar(segments = segments, total = total.toFloat())
    }
}

// ---- apps tab -------------------------------------------------------------------------------

private fun LazyGridScope.appsTab(state: XRayState, onIntent: (XRayIntent) -> Unit) {
    fullWidth { SectionHeading(stringResource(R.string.xray_apps_heading)) }
    when {
        !state.perUidAvailable -> fullWidth {
            EmptyState(
                title = stringResource(R.string.xray_apps_needs_snapshots_title),
                body = stringResource(R.string.xray_apps_needs_snapshots_body),
                action = null,
            )
        }
        !state.breakdownAvailable -> fullWidth {
            EmptyState(
                title = stringResource(R.string.xray_apps_unavailable_title),
                body = stringResource(R.string.xray_apps_unavailable_body),
                action = null,
            )
        }
        state.apps.isEmpty() -> fullWidth {
            EmptyState(
                title = stringResource(R.string.xray_apps_none_title),
                body = stringResource(R.string.xray_apps_none_body),
                action = null,
            )
        }
        else -> {
            fullWidth { GroupLegend() }
            val top = state.apps.first().totalMah.toFloat()
            items(items = state.apps, key = { it.uid }) { app ->
                AppCard(app = app, top = top, onClick = { onIntent(XRayIntent.OpenApp(app.uid)) })
            }
        }
    }
}

@Composable
private fun GroupLegend() {
    val items = ComponentGroup.entries.map { group ->
        LegendItem(color = group.legendColor(), label = stringResource(groupLabelRes(group)))
    }
    LegendRow(items = items.toImmutableList())
}

@Composable
private fun AppCard(app: AppRow, top: Float, onClick: () -> Unit) {
    val name = displayName(app)
    val mah = mahText(app.totalMah)
    val share = shareText(app.sharePct)
    InspirationCard(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.ms),
        ) {
            AppIcon(app = app, name = name)
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(text = mah, style = MaterialTheme.numericTypography.body, color = MaterialTheme.colorScheme.onSurface)
                Text(text = share, style = MaterialTheme.numericTypography.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        GroupBar(slices = app.slices, total = top, modifier = Modifier.padding(top = MaterialTheme.spacing.ms))
    }
}

/** The per-app bar: each slice is one component group, drawn on the scale of the biggest app. */
@Composable
internal fun GroupBar(
    slices: ImmutableList<GroupSlice>,
    total: Float,
    modifier: Modifier = Modifier,
) {
    val labels = ComponentGroup.entries.associateWith { stringResource(groupLabelRes(it)) }
    val roles = ComponentGroup.entries.associateWith { it.role() }
    val segments = slices.map { slice ->
        BarSegment(
            value = slice.mah.toFloat(),
            role = roles.getValue(slice.group),
            label = labels.getValue(slice.group),
            valueText = mahText(slice.mah),
        )
    }
    StackedBar(segments = segments.toImmutableList(), total = total, modifier = modifier)
}

@Composable
internal fun AppIcon(app: AppRow, name: String, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.small
    val size = MaterialTheme.spacing.xl
    val icon = app.icon
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size).clip(shape),
        )
    } else {
        // No icon (unresolved, or an entry without a package): the initial of the name on a quiet tile.
        Box(
            modifier = modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString().orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { heading() },
    )
}

internal fun ComponentGroup.role(): BarRole = when (this) {
    ComponentGroup.SCREEN -> BarRole.SCREEN_ON
    ComponentGroup.CPU -> BarRole.AWAKE
    ComponentGroup.RADIOS -> BarRole.CHARGING
    ComponentGroup.OTHER -> BarRole.NEUTRAL
}

/** The colour [StackedBar] draws [role] in, for the legend. */
@Composable
private fun ComponentGroup.legendColor(): Color {
    val telemetry = MaterialTheme.telemetryColors
    return when (role()) {
        BarRole.SCREEN_ON -> telemetry.screenOn
        BarRole.AWAKE -> telemetry.awake
        BarRole.CHARGING -> telemetry.charging
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** A grid column is at least this many `xxl` spacing units wide, so a phone gets one column and a tablet two. */
private const val ADAPTIVE_COLUMN_UNITS = 7
