package com.akane.inspiration.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic hues for telemetry. They carry data meaning only and never decorate.
 * The `*Container` variants are the hue at 16 % alpha, for chips and badges over a surface.
 */
@Immutable
data class TelemetryColors(
    val deepSleep: Color,
    val lightSleep: Color,
    val screenOn: Color,
    val awake: Color,
    val critical: Color,
    val charging: Color,
    val batteryLine: Color,
    val temperatureLine: Color,
    val chartGrid: Color,
    val chartAxisLabel: Color,
    val deepSleepContainer: Color,
    val screenOnContainer: Color,
    val awakeContainer: Color,
    val criticalContainer: Color,
    val chargingContainer: Color,
)

private const val CONTAINER_ALPHA = 0.16f

internal val DarkTelemetryColors = TelemetryColors(
    deepSleep = Color(0xFF10B981),
    lightSleep = Color(0xFF6EE7B7),
    screenOn = Color(0xFF3B82F6),
    awake = Color(0xFFF59E0B),
    critical = Color(0xFFEF4444),
    charging = Color(0xFF22D3EE),
    batteryLine = Color(0xFFE6EAF0),
    temperatureLine = Color(0xFF98A2B3),
    chartGrid = Color(0xFF1C222B),
    chartAxisLabel = Color(0xFF98A2B3),
    deepSleepContainer = Color(0xFF10B981).copy(alpha = CONTAINER_ALPHA),
    screenOnContainer = Color(0xFF3B82F6).copy(alpha = CONTAINER_ALPHA),
    awakeContainer = Color(0xFFF59E0B).copy(alpha = CONTAINER_ALPHA),
    criticalContainer = Color(0xFFEF4444).copy(alpha = CONTAINER_ALPHA),
    chargingContainer = Color(0xFF22D3EE).copy(alpha = CONTAINER_ALPHA),
)

/** Same hues, darkened where contrast against the light background (>= 3:1) needs it. */
internal val LightTelemetryColors = TelemetryColors(
    deepSleep = Color(0xFF065F46),
    lightSleep = Color(0xFF0D9F77),
    screenOn = Color(0xFF2563EB),
    awake = Color(0xFFC2620A),
    critical = Color(0xFFDC2626),
    charging = Color(0xFF0891B2),
    batteryLine = Color(0xFF0E1116),
    temperatureLine = Color(0xFF4A5463),
    chartGrid = Color(0xFFDDE2E8),
    chartAxisLabel = Color(0xFF4A5463),
    deepSleepContainer = Color(0xFF065F46).copy(alpha = CONTAINER_ALPHA),
    screenOnContainer = Color(0xFF2563EB).copy(alpha = CONTAINER_ALPHA),
    awakeContainer = Color(0xFFC2620A).copy(alpha = CONTAINER_ALPHA),
    criticalContainer = Color(0xFFDC2626).copy(alpha = CONTAINER_ALPHA),
    chargingContainer = Color(0xFF0891B2).copy(alpha = CONTAINER_ALPHA),
)

internal val LocalTelemetryColors = staticCompositionLocalOf { DarkTelemetryColors }

val MaterialTheme.telemetryColors: TelemetryColors
    @Composable @ReadOnlyComposable
    get() = LocalTelemetryColors.current
