package com.akane.inspiration.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.akane.inspiration.designsystem.theme.spacing

/** The single optional button of an [EmptyState]. */
@Immutable
data class EmptyStateAction(val label: String, val onClick: () -> Unit)

/**
 * The one empty/unavailable/error placeholder every screen uses: a [title], a [body] that says what to do next
 * and an optional [action]. Centered and padded; give it the width (and height) of the area it replaces.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    action: EmptyStateAction?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(MaterialTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm, Alignment.CenterVertically),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            FilledTonalButton(
                onClick = action.onClick,
                modifier = Modifier.padding(top = MaterialTheme.spacing.sm),
            ) {
                Text(text = action.label)
            }
        }
    }
}
