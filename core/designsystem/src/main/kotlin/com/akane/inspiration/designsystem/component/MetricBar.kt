package com.akane.inspiration.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The one horizontal progress bar for a ratio ([progress], 0..1, clamped): a rounded fill over a track. It draws
 * no stop-indicator dot, leaves no gap between fill and track, and its track is `surfaceContainerHighest`, which
 * stays visible on the dark card where `outlineVariant` disappears. The fill takes [color] (the primary role by
 * default; pass a telemetry hue when the bar stands for one). Screens use this instead of a raw
 * `LinearProgressIndicator`. Label it in the surrounding text: the bar adds no text of its own.
 */
@Composable
fun MetricBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth(),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
