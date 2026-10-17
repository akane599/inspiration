package com.akane.inspiration.designsystem.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import com.akane.inspiration.designsystem.theme.numericTypography
import com.akane.inspiration.designsystem.theme.spacing

/**
 * One cell of the 2x2 bento grid: a [title], the headline reading [primary] (with an optional [primaryUnit]),
 * an optional [secondary] line and an optional [progress] bar (0..1, drawn as a [MetricBar]). With [onClick] the
 * whole tile is one tap target. Place two per row with equal weights; the tile fills the height it is given.
 */
@Composable
fun MetricTile(
    title: String,
    primary: String,
    secondary: String?,
    progress: Float?,
    modifier: Modifier = Modifier,
    primaryUnit: String = "",
    onClick: (() -> Unit)? = null,
) {
    InspirationCard(modifier = modifier.semantics(mergeDescendants = true) {}, onClick = onClick) {
        CardTitle(text = title, isHeading = false)
        MetricValue(
            value = primary,
            unit = primaryUnit,
            modifier = Modifier.padding(top = MaterialTheme.spacing.xs),
            style = MaterialTheme.numericTypography.headline,
        )
        if (secondary != null) {
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MaterialTheme.spacing.xs),
            )
        }
        if (progress != null) {
            MetricBar(progress = progress, modifier = Modifier.padding(top = MaterialTheme.spacing.sm))
        }
    }
}
