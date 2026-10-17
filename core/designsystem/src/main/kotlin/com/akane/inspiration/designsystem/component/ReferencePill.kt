package com.akane.inspiration.designsystem.component

import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics

private const val DROPDOWN_GLYPH = "▾"

/**
 * A reference selector chip ("Since last charge ▾"): an M3 assist chip whose trailing triangle says it opens a
 * choice. The visible chip is compact but the touch target is at least 48 dp. The glyph is decorative.
 */
@Composable
fun ReferencePill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(text = label, style = MaterialTheme.typography.labelLarge) },
        modifier = modifier.minimumInteractiveComponentSize(),
        trailingIcon = {
            Text(
                text = DROPDOWN_GLYPH,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
    )
}
