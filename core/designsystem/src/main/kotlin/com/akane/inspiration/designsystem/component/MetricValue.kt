package com.akane.inspiration.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
 * Value and unit are announced as one node.
 */
@Composable
fun MetricValue(
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.numericTypography.title,
) {
    Row(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = value,
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.alignByBaseline(),
        )
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
