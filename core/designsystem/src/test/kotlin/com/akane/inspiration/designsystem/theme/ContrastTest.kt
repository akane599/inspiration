package com.akane.inspiration.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG 2.x contrast checks over the pinned token pairs. */
class ContrastTest {

    @Test
    fun darkTextOnSurfaces() = assertTextPairs("dark", InspirationDarkColorScheme)

    @Test
    fun lightTextOnSurfaces() = assertTextPairs("light", InspirationLightColorScheme)

    @Test
    fun darkOnSurfaceVariantMeetsPlanTarget() {
        val ratio = contrast(InspirationDarkColorScheme.onSurfaceVariant, Color(0xFF0E1116))
        println("dark onSurfaceVariant on #0E1116 = %.2f".format(ratio))
        assertTrue("onSurfaceVariant on #0E1116 was $ratio", ratio >= 4.5)
    }

    @Test
    fun darkTelemetryColorsOnBackground() =
        assertTelemetry("dark", DarkTelemetryColors, InspirationDarkColorScheme.background)

    @Test
    fun lightTelemetryColorsOnBackground() =
        assertTelemetry("light", LightTelemetryColors, InspirationLightColorScheme.background)

    @Test
    fun contrastFormulaMatchesKnownValues() {
        val blackOnWhite = contrast(Color.Black, Color.White)
        assertTrue("black on white was $blackOnWhite", kotlin.math.abs(blackOnWhite - 21.0) < 0.001)
        assertTrue("identical colors were ${contrast(Color.Red, Color.Red)}", contrast(Color.Red, Color.Red) == 1.0)
    }

    private fun assertTextPairs(theme: String, scheme: ColorScheme) {
        val surfaces = mapOf("surfaceContainer" to scheme.surfaceContainer, "background" to scheme.background)
        val texts = mapOf("onSurface" to scheme.onSurface, "onSurfaceVariant" to scheme.onSurfaceVariant)
        for ((textName, text) in texts) {
            for ((surfaceName, surface) in surfaces) {
                val ratio = contrast(text, surface)
                println("$theme $textName on $surfaceName = %.2f".format(ratio))
                assertTrue("$theme $textName on $surfaceName was $ratio, needs >= 4.5", ratio >= 4.5)
            }
        }
    }

    // chartGrid (hairline) and the translucent *Container fills are decorative, not data marks.
    private fun assertTelemetry(theme: String, colors: TelemetryColors, background: Color) {
        val marks = mapOf(
            "deepSleep" to colors.deepSleep,
            "lightSleep" to colors.lightSleep,
            "screenOn" to colors.screenOn,
            "awake" to colors.awake,
            "critical" to colors.critical,
            "charging" to colors.charging,
            "batteryLine" to colors.batteryLine,
            "temperatureLine" to colors.temperatureLine,
            "chartAxisLabel" to colors.chartAxisLabel,
        )
        for ((name, color) in marks) {
            val ratio = contrast(color, background)
            println("$theme $name on background = %.2f".format(ratio))
            assertTrue("$theme $name on background was $ratio, needs >= 3.0", ratio >= 3.0)
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    private fun linear(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}
