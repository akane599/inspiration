package com.akane.inspiration.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.akane.inspiration.designsystem.R
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.telemetryColors

private object BannerMetrics {
    val BorderWidth = 1.dp
}

/**
 * Tells the user that the figures below describe a slice of time: the [requested] range, the range [covered] by
 * data, and a [gapText] when the two differ (null when the data covers the whole request). [onClear] removes the
 * slice. Pass the ranges already formatted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeSliceBanner(
    requested: String,
    covered: String,
    gapText: String?,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(BannerMetrics.BorderWidth, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(start = spacing.md, top = spacing.sm, end = spacing.sm, bottom = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Column(
                modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                RangeLine(stringResource(R.string.slice_requested), requested)
                RangeLine(stringResource(R.string.slice_covered), covered)
                if (gapText != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SemanticDot(color = MaterialTheme.telemetryColors.awake)
                        Text(
                            text = gapText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = spacing.sm),
                        )
                    }
                }
            }
            TextButton(onClick = onClear) {
                Text(text = stringResource(R.string.slice_clear))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RangeLine(label: String, value: String) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alignByBaseline(),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
