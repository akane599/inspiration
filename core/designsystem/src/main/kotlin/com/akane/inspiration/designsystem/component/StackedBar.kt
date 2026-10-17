package com.akane.inspiration.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.telemetryColors
import kotlinx.collections.immutable.ImmutableList

/** Which theme colour a [BarSegment] takes. Features name a role; they never pass raw colours. */
enum class BarRole { SCREEN_ON, DEEP_SLEEP, LIGHT_SLEEP, AWAKE, CRITICAL, CHARGING, NEUTRAL }

/**
 * One slice of a [StackedBar]: [value] in the same unit as the bar's total, drawn in [role]'s colour.
 * [label] and [valueText] ("Screen on", "1 h 05 min") feed the accessibility summary only; format [valueText]
 * with `TelemetryFormat`.
 */
@Immutable
data class BarSegment(
    val value: Float,
    val role: BarRole,
    val label: String,
    val valueText: String = "",
)

private object BarMetrics {
    val Height = 12.dp
    val Separator = 2.dp
}

@Composable
@ReadOnlyComposable
private fun BarRole.color(): Color {
    val telemetry = MaterialTheme.telemetryColors
    return when (this) {
        BarRole.SCREEN_ON -> telemetry.screenOn
        BarRole.DEEP_SLEEP -> telemetry.deepSleep
        BarRole.LIGHT_SLEEP -> telemetry.lightSleep
        BarRole.AWAKE -> telemetry.awake
        BarRole.CRITICAL -> telemetry.critical
        BarRole.CHARGING -> telemetry.charging
        BarRole.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * A single horizontal bar split into proportional segments of [total]. The part of [total] that no segment
 * covers stays as the empty track. If the segments add up to more than [total], they are scaled to fill it.
 * The bar is one accessibility node whose description lists every segment; pair it with a [LegendRow].
 */
@Composable
fun StackedBar(
    segments: ImmutableList<BarSegment>,
    total: Float,
    modifier: Modifier = Modifier,
) {
    val colors = segments.map { it.role.color() }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val separator = MaterialTheme.colorScheme.surfaceContainer
    val summary = remember(segments) {
        segments.joinToString(separator = ", ") { "${it.label} ${it.valueText}".trim() }
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(BarMetrics.Height)
            .clip(CircleShape)
            .semantics { contentDescription = summary },
    ) {
        drawRect(color = track, size = size)
        val sum = segments.sumOf { it.value.coerceAtLeast(0f).toDouble() }.toFloat()
        val scaleBase = maxOf(total, sum)
        if (scaleBase <= 0f) return@Canvas
        val separatorPx = BarMetrics.Separator.toPx()
        var x = 0f
        segments.forEachIndexed { index, segment ->
            val width = size.width * segment.value.coerceAtLeast(0f) / scaleBase
            if (width <= 0f) return@forEachIndexed
            drawRect(color = colors[index], topLeft = Offset(x, 0f), size = Size(width, size.height))
            // A hairline in the card colour keeps neighbouring hues apart without relying on contrast between them.
            if (index < segments.lastIndex) {
                drawRect(color = separator, topLeft = Offset(x + width - separatorPx, 0f), size = Size(separatorPx, size.height))
            }
            x += width
        }
    }
}
