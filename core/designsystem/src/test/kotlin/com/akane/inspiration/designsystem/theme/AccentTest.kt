package com.akane.inspiration.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** [withAccentFrom] takes only the accent roles from a wallpaper scheme; background and surfaces stay AMOLED. */
class AccentTest {
    /** A stand-in wallpaper scheme with a distinct color in every role checked below. */
    private val dynamic =
        darkColorScheme(
            primary = distinct(1),
            onPrimary = distinct(2),
            primaryContainer = distinct(3),
            onPrimaryContainer = distinct(4),
            secondary = distinct(5),
            onSecondary = distinct(6),
            secondaryContainer = distinct(7),
            onSecondaryContainer = distinct(8),
            tertiary = distinct(9),
            onTertiary = distinct(10),
            tertiaryContainer = distinct(11),
            onTertiaryContainer = distinct(12),
            background = distinct(13),
            onBackground = distinct(14),
            surface = distinct(15),
            onSurface = distinct(16),
            surfaceVariant = distinct(17),
            onSurfaceVariant = distinct(18),
            surfaceTint = distinct(19),
            inverseSurface = distinct(20),
            inverseOnSurface = distinct(21),
            inversePrimary = distinct(22),
            error = distinct(23),
            onError = distinct(24),
            errorContainer = distinct(25),
            onErrorContainer = distinct(26),
            outline = distinct(27),
            outlineVariant = distinct(28),
            scrim = distinct(29),
        )

    private val accentRoles: List<Pair<String, (ColorScheme) -> Color>> =
        listOf(
            "primary" to { it.primary },
            "onPrimary" to { it.onPrimary },
            "primaryContainer" to { it.primaryContainer },
            "onPrimaryContainer" to { it.onPrimaryContainer },
            "secondary" to { it.secondary },
            "onSecondary" to { it.onSecondary },
            "secondaryContainer" to { it.secondaryContainer },
            "onSecondaryContainer" to { it.onSecondaryContainer },
            "tertiary" to { it.tertiary },
            "onTertiary" to { it.onTertiary },
            "tertiaryContainer" to { it.tertiaryContainer },
            "onTertiaryContainer" to { it.onTertiaryContainer },
        )

    private val keptRoles: List<Pair<String, (ColorScheme) -> Color>> =
        listOf(
            "background" to { it.background },
            "onBackground" to { it.onBackground },
            "surface" to { it.surface },
            "onSurface" to { it.onSurface },
            "surfaceVariant" to { it.surfaceVariant },
            "onSurfaceVariant" to { it.onSurfaceVariant },
            "surfaceTint" to { it.surfaceTint },
            "inverseSurface" to { it.inverseSurface },
            "inverseOnSurface" to { it.inverseOnSurface },
            "inversePrimary" to { it.inversePrimary },
            "error" to { it.error },
            "onError" to { it.onError },
            "errorContainer" to { it.errorContainer },
            "onErrorContainer" to { it.onErrorContainer },
            "outline" to { it.outline },
            "outlineVariant" to { it.outlineVariant },
            "scrim" to { it.scrim },
        )

    @Test
    fun darkSchemeTakesOnlyTheAccentRoles() = assertAccentOnly(InspirationDarkColorScheme)

    @Test
    fun lightSchemeTakesOnlyTheAccentRoles() = assertAccentOnly(InspirationLightColorScheme)

    private fun assertAccentOnly(base: ColorScheme) {
        val themed = base.withAccentFrom(dynamic)
        for ((name, role) in accentRoles) {
            assertEquals("$name comes from the wallpaper", role(dynamic), role(themed))
        }
        for ((name, role) in keptRoles) {
            assertNotEquals("$name must differ for the check to mean anything", role(dynamic), role(base))
            assertEquals("$name stays the Inspiration token", role(base), role(themed))
        }
    }

    private fun distinct(n: Int): Color = Color(0xFF7A3400L + n)
}
