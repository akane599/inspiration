package com.akane.inspiration.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * The title of a card or section inside a screen: `titleSmall` in `onSurfaceVariant`. This is the single card-title
 * style, so Cockpit, Health, Sleep Lab and the rest read alike; a screen's own title is a [ScreenHeader]. The
 * title is announced as a heading unless [isHeading] is false (a tile that is one merged node, like [MetricTile]).
 */
@Composable
fun CardTitle(
    text: String,
    modifier: Modifier = Modifier,
    isHeading: Boolean = true,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (isHeading) modifier.semantics { heading() } else modifier,
    )
}
