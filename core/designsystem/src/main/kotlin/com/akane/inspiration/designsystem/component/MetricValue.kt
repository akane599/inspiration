package com.akane.inspiration.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import com.akane.inspiration.designsystem.theme.numericTypography
import com.akane.inspiration.designsystem.theme.spacing

/**
 * A telemetry value with a smaller unit, baseline-aligned. Uses tabular figures so digits don't jitter.
 * Value and unit are announced as one node. With [shrinkToFit] the value stays on one line and shrinks from
 * [style] down to `labelLarge` instead of wrapping mid-word when space is narrow (large font, 360 dp).
 */
@Composable
fun MetricValue(
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.numericTypography.title,
    shrinkToFit: Boolean = false,
) {
    Row(modifier = modifier.semantics(mergeDescendants = true) {}) {
        if (shrinkToFit) {
            Text(
                text = value,
                style = style,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = MaterialTheme.typography.labelLarge.fontSize,
                    maxFontSize = style.fontSize,
                ),
                modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
            )
        } else {
            Text(
                text = value,
                style = style,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.alignByBaseline(),
            )
        }
        if (unit.isNotEmpty()) {
            Text(
                text = unit,
                style = MaterialTheme.numericTypography.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alignByBaseline().padding(start = MaterialTheme.spacing.xs),
            )
        }
    }
}
