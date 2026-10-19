package com.akane.inspiration.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoreTicksTest {
    @Test
    fun `every tick is a new value, within one millisecond or after the clock was set back`() {
        var now = 1_000L
        val ticks = StoreTicks { now }
        assertNull(ticks.lastWallMs.value)

        ticks.tick()
        assertEquals(1_000L, ticks.lastWallMs.value)
        ticks.tick()
        assertEquals("same millisecond", 1_001L, ticks.lastWallMs.value)
        now = 500L
        ticks.tick()
        assertEquals("clock set back", 1_002L, ticks.lastWallMs.value)
        now = 5_000L
        ticks.tick()
        assertEquals(5_000L, ticks.lastWallMs.value)
    }
}
