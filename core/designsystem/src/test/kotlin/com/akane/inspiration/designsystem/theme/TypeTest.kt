package com.akane.inspiration.designsystem.theme

import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypeTest {
    private val proseStyles: Map<String, TextStyle> = InspirationTypography.let {
        mapOf(
            "titleLarge" to it.titleLarge, "titleMedium" to it.titleMedium, "titleSmall" to it.titleSmall,
            "labelLarge" to it.labelLarge, "labelMedium" to it.labelMedium, "labelSmall" to it.labelSmall,
            "bodyLarge" to it.bodyLarge, "bodyMedium" to it.bodyMedium, "bodySmall" to it.bodySmall,
        )
    }

    @Test
    fun proseStylesHaveNoTabularFigures() {
        // tnum widens hyphens: "X-Ray", "Screen-off", "wake-ups".
        for ((name, style) in proseStyles) assertNull("$name must not carry tnum", style.fontFeatureSettings)
    }

    @Test
    fun numericStylesKeepTabularFigures() {
        val numeric = InspirationNumericTypography
        for (style in listOf(numeric.display, numeric.headline, numeric.title, numeric.body, numeric.label)) {
            assertEquals("tnum", style.fontFeatureSettings)
        }
    }

    @Test
    fun withTabularFiguresAddsOnlyTheFeature() {
        val tabular = InspirationTypography.labelSmall.withTabularFigures()
        assertEquals("tnum", tabular.fontFeatureSettings)
        assertEquals(InspirationTypography.labelSmall.fontSize, tabular.fontSize)
    }
}
