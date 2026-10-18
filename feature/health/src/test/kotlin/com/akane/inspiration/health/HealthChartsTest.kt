package com.akane.inspiration.health

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthChartsTest {
    @Test
    fun `a label is kept inside the room it has`() {
        assertEquals(50f, labelStart(desired = 50f, min = 10f, end = 200f, labelSize = 40f), 0f)
        assertEquals(10f, labelStart(desired = -5f, min = 10f, end = 200f, labelSize = 40f), 0f)
        assertEquals(160f, labelStart(desired = 190f, min = 10f, end = 200f, labelSize = 40f), 0f)
    }

    @Test
    fun `a label wider than its room starts at the minimum instead of throwing`() {
        // The device crash: maximum -215 (end - labelSize) below minimum 303 (plotLeft).
        assertEquals(303f, labelStart(desired = 120f, min = 303f, end = 400f, labelSize = 615f), 0f)
        assertEquals(0f, labelStart(desired = -20f, min = 0f, end = 30f, labelSize = 48f), 0f)
    }
}
