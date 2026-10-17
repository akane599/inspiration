package com.akane.inspiration.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.akane.inspiration.designsystem.theme.spacing

private object CardMetrics {
    val BorderWidth = 1.dp
    val MinClickableHeight = 48.dp
}

/**
 * A discrete, glanceable unit: surfaceContainer fill, large shape, hairline outlineVariant border, no elevation.
 * With [onClick] the whole card is one tap target of at least 48 dp.
 */
@Composable
fun InspirationCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val color = MaterialTheme.colorScheme.surfaceContainer
    val shape = MaterialTheme.shapes.large
    val border = BorderStroke(CardMetrics.BorderWidth, MaterialTheme.colorScheme.outlineVariant)
    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.md), content = content)
    }
    if (onClick == null) {
        Surface(modifier = modifier, shape = shape, color = color, border = border, content = body)
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier.heightIn(min = CardMetrics.MinClickableHeight),
            shape = shape,
            color = color,
            border = border,
            content = body,
        )
    }
}
