package com.akane.inspiration.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.akane.inspiration.designsystem.theme.spacing
import kotlinx.collections.immutable.ImmutableList

/** One legend entry: the series/lane [color] and the [label] naming it. */
@Immutable
data class LegendItem(val color: Color, val label: String)

/** A wrapping row of [SemanticDot] + label pairs. Wraps instead of truncating at large font scales. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LegendRow(
    items: ImmutableList<LegendItem>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        items.forEach { item ->
            // The label names the dot, so the dot itself stays decorative.
            Row(verticalAlignment = Alignment.CenterVertically) {
                SemanticDot(color = item.color)
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = MaterialTheme.spacing.sm),
                )
            }
        }
    }
}
