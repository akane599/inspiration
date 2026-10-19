package com.akane.inspiration.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.statusColors
import com.akane.inspiration.designsystem.theme.telemetryColors

/** State of the Shizuku connection, as a status indicator shows it. */
enum class PulseStatus { ACTIVE, CONNECTING, BLOCKED }

private object PulseMetrics {
    /** The slot is sized for the halo at full scale, so the pulse never changes layout. */
    val Slot = 16.dp
    const val PERIOD_MS = 2400
    const val MAX_SCALE = 1.6f
    const val PEAK_ALPHA = 0.4f

    /** A bounded pulse: ACTIVE is the steady state, so an endless halo would request a frame every vsync. */
    const val CYCLES = 3
}

/**
 * A status dot with its [label]. Hue carries the state (active, connecting, blocked) and the label names it, so
 * colour is never the only signal. On becoming ACTIVE a slow, faint halo pulses [PulseMetrics.CYCLES] times and
 * then fades out for good, so an idle screen stops drawing frames; it is skipped under reduced motion and in
 * previews. The halo animates in the graphics layer, so the animation never recomposes.
 */
@Composable
fun ShizukuPulse(
    status: PulseStatus,
    label: String,
    modifier: Modifier = Modifier,
) {
    val telemetry = MaterialTheme.telemetryColors
    val hues = MaterialTheme.statusColors
    val color =
        when (status) {
            PulseStatus.ACTIVE -> hues.ok
            PulseStatus.CONNECTING -> hues.warning
            PulseStatus.BLOCKED -> telemetry.critical
        }
    val pulsing = status == PulseStatus.ACTIVE && !rememberReducedMotion() && !LocalInspectionMode.current
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(PulseMetrics.Slot), contentAlignment = Alignment.Center) {
            if (pulsing) PulseHalo(color)
            SemanticDot(color = color)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = MaterialTheme.spacing.sm),
        )
    }
}

@Composable
private fun PulseHalo(color: Color) {
    // Composed only while ACTIVE, so this runs once per transition to ACTIVE. Progress 1 has zero alpha,
    // so the halo is invisible once the last cycle ends and no further frames are requested.
    val phase = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        repeat(PulseMetrics.CYCLES) {
            phase.snapTo(0f)
            phase.animateTo(1f, tween(durationMillis = PulseMetrics.PERIOD_MS, easing = LinearOutSlowInEasing))
        }
    }
    Box(
        modifier =
            Modifier
                .size(DotMetrics.Diameter)
                // The state is read inside the layer block: it invalidates drawing only, never composition.
                .graphicsLayer {
                    val progress = phase.value
                    val scale = 1f + progress * (PulseMetrics.MAX_SCALE - 1f)
                    scaleX = scale
                    scaleY = scale
                    alpha = (1f - progress) * PulseMetrics.PEAK_ALPHA
                }.background(color, CircleShape),
    )
}
