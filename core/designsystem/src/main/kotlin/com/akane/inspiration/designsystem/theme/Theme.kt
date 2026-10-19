package com.akane.inspiration.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * The Inspiration theme: AMOLED-black Material 3.
 *
 * material3 1.4.0 keeps `MaterialExpressiveTheme`, `MotionScheme` and `MaterialTheme.motionScheme`
 * Kotlin-internal, so this wraps the plain `MaterialTheme`. Switch to `MaterialExpressiveTheme`
 * when a material3 release exposes it. Charts animate with explicit `tween`/`spring` specs.
 *
 * - [darkTheme] defaults to true regardless of the system setting (battery-app ethos: black pixels are off).
 * - [dynamicAccent] takes only primary/secondary/tertiary and their on* / *Container roles from the
 *   wallpaper scheme; background and surfaces stay AMOLED. Off by default so semantic hues stay stable.
 */
@Composable
fun InspirationTheme(
    darkTheme: Boolean = true,
    dynamicAccent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) InspirationDarkColorScheme else InspirationLightColorScheme
    val colorScheme =
        if (dynamicAccent) {
            val context = LocalContext.current
            val dynamic = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            base.withAccentFrom(dynamic)
        } else {
            base
        }
    CompositionLocalProvider(
        LocalTelemetryColors provides if (darkTheme) DarkTelemetryColors else LightTelemetryColors,
        LocalStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors,
        LocalNumericTypography provides InspirationNumericTypography,
        LocalSpacing provides Spacing(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = InspirationShapes,
            typography = InspirationTypography,
            content = content,
        )
    }
}

/**
 * This scheme with only the accent roles (primary, secondary, tertiary and their on* / *Container roles) taken from
 * [dynamic]. No screen turns [InspirationTheme]'s `dynamicAccent` on yet. Both stay as the pinned phase-4 G2 theme
 * option (reviewed in audit §3), and AccentTest pins which roles are taken.
 */
internal fun ColorScheme.withAccentFrom(dynamic: ColorScheme): ColorScheme =
    copy(
        primary = dynamic.primary,
        onPrimary = dynamic.onPrimary,
        primaryContainer = dynamic.primaryContainer,
        onPrimaryContainer = dynamic.onPrimaryContainer,
        secondary = dynamic.secondary,
        onSecondary = dynamic.onSecondary,
        secondaryContainer = dynamic.secondaryContainer,
        onSecondaryContainer = dynamic.onSecondaryContainer,
        tertiary = dynamic.tertiary,
        onTertiary = dynamic.onTertiary,
        tertiaryContainer = dynamic.tertiaryContainer,
        onTertiaryContainer = dynamic.onTertiaryContainer,
    )
