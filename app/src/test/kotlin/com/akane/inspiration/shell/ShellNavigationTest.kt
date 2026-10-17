package com.akane.inspiration.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellNavigationTest {
    @Test
    fun `there are five destinations with Cockpit first`() {
        assertEquals(
            listOf(Destination.Cockpit, Destination.XRay, Destination.SleepLab, Destination.Health, Destination.Toolkit),
            Destination.entries,
        )
        assertEquals("keys are unique", 5, Destination.entries.map { it.key }.toSet().size)
    }

    @Test
    fun `selecting destinations switches the selection and starts on Cockpit`() {
        val state = ShellState()
        assertEquals(Destination.Cockpit, state.selected)

        state.select(Destination.SleepLab)
        assertEquals(Destination.SleepLab, state.selected)
        state.select(Destination.Toolkit)
        assertEquals(Destination.Toolkit, state.selected)
    }

    @Test
    fun `Back from any other destination returns to Cockpit and Back on Cockpit is left to the system`() {
        Destination.entries.filter { it != Destination.Cockpit }.forEach { destination ->
            val state = ShellState(destination)
            assertTrue("Back is consumed on $destination", state.onBack())
            assertEquals(Destination.Cockpit, state.selected)
        }

        val onCockpit = ShellState()
        assertFalse(onCockpit.onBack())
        assertEquals(Destination.Cockpit, onCockpit.selected)
    }

    @Test
    fun `the rail starts at 600 dp`() {
        assertFalse(ShellNavigation.usesRail(360f))
        assertFalse(ShellNavigation.usesRail(599.9f))
        assertTrue(ShellNavigation.usesRail(600f))
        assertTrue(ShellNavigation.usesRail(840f))
    }

    @Test
    fun `the persisted key round trips and anything unknown falls back to Cockpit`() {
        Destination.entries.forEach { assertEquals(it, Destination.fromKey(it.key)) }
        assertEquals(Destination.Cockpit, Destination.fromKey(null))
        assertEquals(Destination.Cockpit, Destination.fromKey("removed_screen"))
    }
}
