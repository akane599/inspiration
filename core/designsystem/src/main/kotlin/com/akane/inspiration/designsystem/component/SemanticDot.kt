package com.akane.inspiration.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private object DotMetrics {
    val Diameter = 10.dp
}

/**
 * A small dot whose hue carries data meaning (pass a `MaterialTheme.telemetryColors` value).
 * Colour alone never conveys meaning: pass [contentDescription] when the dot stands without an adjacent
 * text label, or leave it null when a label already names it.
 */
@Composable
fun SemanticDot(
    color: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .then(semanticsModifier)
            .size(DotMetrics.Diameter)
            .background(color, CircleShape),
    )
}
