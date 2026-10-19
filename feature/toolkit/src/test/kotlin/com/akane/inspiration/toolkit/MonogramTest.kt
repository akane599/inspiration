package com.akane.inspiration.toolkit

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class MonogramTest {
    private val turkish = Locale.forLanguageTag("tr")

    @Test
    fun `turkish dotted i becomes the dotted capital`() {
        assertEquals("İ", monogram("instagram", turkish))
    }

    @Test
    fun `english i becomes the plain capital`() {
        assertEquals("I", monogram("instagram", Locale.ENGLISH))
    }

    @Test
    fun `leading symbols and spaces are skipped`() {
        assertEquals("9", monogram("  @9gag", turkish))
    }

    @Test
    fun `a label without a letter or digit has no monogram`() {
        assertEquals("", monogram("…", turkish))
    }
}
