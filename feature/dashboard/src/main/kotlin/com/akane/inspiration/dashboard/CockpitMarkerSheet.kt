package com.akane.inspiration.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.akane.inspiration.designsystem.theme.spacing
import java.time.ZoneId
import java.util.Locale

/** The marker sheet of H10, opened from the pill's "Custom marker" item. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarkerSheet(
    sheet: MarkerSheetUi,
    onIntent: (CockpitIntent) -> Unit,
    zone: ZoneId,
) {
    ModalBottomSheet(
        onDismissRequest = { onIntent(CockpitIntent.DismissMarkers) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        MarkerSheetContent(sheet = sheet, onIntent = onIntent, zone = zone)
    }
}

/** The sheet's body, apart from the sheet window so previews can render it. */
@Composable
internal fun MarkerSheetContent(
    sheet: MarkerSheetUi,
    onIntent: (CockpitIntent) -> Unit,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val locale: Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    var label by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    Column(
        modifier = modifier.navigationBarsPadding().imePadding().padding(horizontal = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.cockpit_markers_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.cockpit_markers_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.cockpit_marker_label)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    // The default label carries the time of the tap, not of the last recomposition.
                    val named = label.trim().ifEmpty {
                        context.getString(R.string.cockpit_marker_default_label, formatTime(System.currentTimeMillis(), locale, zone))
                    }
                    onIntent(CockpitIntent.MarkNow(named))
                    label = ""
                },
                enabled = !sheet.creating,
            ) {
                Text(stringResource(R.string.cockpit_marker_mark_now))
            }
        }
        if (sheet.failed) {
            Text(
                text = stringResource(R.string.cockpit_markers_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        MarkerList(sheet = sheet, onIntent = onIntent, locale = locale, zone = zone, modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun MarkerList(
    sheet: MarkerSheetUi,
    onIntent: (CockpitIntent) -> Unit,
    locale: Locale,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    when {
        sheet.loading -> MarkerNote(R.string.cockpit_markers_loading, modifier)
        sheet.markers.isEmpty() -> MarkerNote(R.string.cockpit_markers_empty, modifier)
        else -> {
            Text(
                text = pluralStringResource(R.plurals.cockpit_markers_count, sheet.markers.size, sheet.markers.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(modifier = modifier) {
                items(sheet.markers, key = MarkerUi::id) { marker ->
                    val inUse = marker.id == sheet.selectedId
                    ListItem(
                        headlineContent = { Text(marker.label) },
                        supportingContent = { Text(formatDateTime(marker.wallMs, locale, zone)) },
                        trailingContent = if (inUse) {
                            {
                                Text(
                                    text = stringResource(R.string.cockpit_marker_in_use),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        } else {
                            null
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { selected = inUse }
                            .clickable { onIntent(CockpitIntent.SelectMarker(marker.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkerNote(textRes: Int, modifier: Modifier) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = MaterialTheme.spacing.md),
    )
}
