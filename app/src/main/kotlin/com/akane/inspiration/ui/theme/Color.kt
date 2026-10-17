package com.akane.inspiration.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// PLACEHOLDER brand palette — neutral on purpose. Replace via /ui-overhaul (compose-design skill).
// Screens never use these directly: they read MaterialTheme.colorScheme.*.
internal object Brand {
    val Ink = Color(0xFF1D2327)
    val Paper = Color(0xFFF7F7F5)
    val Primary = Color(0xFF2F5D62)
    val PrimarySoft = Color(0xFFCFE5E6)
    val PrimaryDark = Color(0xFF8CC7C9)
    val Accent = Color(0xFFB5654A)
    val AccentSoft = Color(0xFFF6DDD3)
    val Error = Color(0xFFB3261E)
}

// Every role is set explicitly: anything left unset falls back to Material's baseline purple.
internal val LightColors = lightColorScheme(
    primary = Brand.Primary, onPrimary = Color.White,
    primaryContainer = Brand.PrimarySoft, onPrimaryContainer = Color(0xFF0E2E31),
    secondary = Brand.Accent, onSecondary = Color.White,
    secondaryContainer = Brand.AccentSoft, onSecondaryContainer = Color(0xFF3E1A0E),
    tertiary = Color(0xFF55606B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE3EA), onTertiaryContainer = Color(0xFF1A232B),
    background = Brand.Paper, onBackground = Brand.Ink,
    surface = Brand.Paper, onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFE3E6E6), onSurfaceVariant = Color(0xFF444A4C),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F2F0),
    surfaceContainer = Color(0xFFEBECEA), surfaceContainerHigh = Color(0xFFE5E6E4),
    surfaceContainerHighest = Color(0xFFDFE0DE),
    outline = Color(0xFF747A7C), outlineVariant = Color(0xFFC4C8C9),
    error = Brand.Error, onError = Color.White,
)

internal val DarkColors = darkColorScheme(
    primary = Brand.PrimaryDark, onPrimary = Color(0xFF00363A),
    primaryContainer = Color(0xFF1F4B4F), onPrimaryContainer = Brand.PrimarySoft,
    secondary = Color(0xFFF0B59E), onSecondary = Color(0xFF4E2111),
    secondaryContainer = Color(0xFF6B3825), onSecondaryContainer = Brand.AccentSoft,
    tertiary = Color(0xFFB9C4CF), onTertiary = Color(0xFF232E37),
    tertiaryContainer = Color(0xFF3A4550), onTertiaryContainer = Color(0xFFDCE3EA),
    background = Color(0xFF14181B), onBackground = Color(0xFFE4E6E7),
    surface = Color(0xFF14181B), onSurface = Color(0xFFE4E6E7),
    surfaceVariant = Color(0xFF3F4648), onSurfaceVariant = Color(0xFFC0C7C9),
    surfaceContainerLowest = Color(0xFF0F1214), surfaceContainerLow = Color(0xFF1B1F22),
    surfaceContainer = Color(0xFF1F2427), surfaceContainerHigh = Color(0xFF2A2F32),
    surfaceContainerHighest = Color(0xFF34393C),
    outline = Color(0xFF8A9193), outlineVariant = Color(0xFF3F4648),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
)
