package com.akane.inspiration.designsystem.chart.scrub

import com.akane.inspiration.designsystem.R
import com.akane.inspiration.designsystem.chart.model.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the production targets rendered by the standard buttons, not a duplicate selection reducer. */
class SliceControlsTest {
    private val minute = 60_000L
    private val minimum = 5 * minute
    private val window = TimeRange.of(10 * minute, 30 * minute)

    @Test
    fun `create offers the latest minimum interval without a pointer selection`() {
        val controls = sliceControls(window, selection = null, minSelectionMs = minimum, hasData = true)

        assertEquals(1, controls.size)
        assertEquals(R.string.chart_select_interval, controls.single().labelRes)
        assertEquals(TimeRange.of(25 * minute, 30 * minute), controls.single().selection)
        assertTrue(controls.single().enabled)
    }

    @Test
    fun `each endpoint action changes only its named endpoint by one minute`() {
        val active = TimeRange.of(15 * minute, 25 * minute)
        val controls = sliceControls(window, active, minimum, hasData = true)

        assertEquals(
            listOf(
                R.string.chart_start_earlier,
                R.string.chart_start_later,
                R.string.chart_end_earlier,
                R.string.chart_end_later,
                R.string.chart_clear_selection,
            ),
            controls.map { it.labelRes },
        )
        assertEquals(TimeRange.of(14 * minute, 25 * minute), controls[0].selection)
        assertEquals(TimeRange.of(16 * minute, 25 * minute), controls[1].selection)
        assertEquals(TimeRange.of(15 * minute, 24 * minute), controls[2].selection)
        assertEquals(TimeRange.of(15 * minute, 26 * minute), controls[3].selection)
        assertTrue(controls.all { it.enabled })
        assertNull(controls.last().selection)
    }

    @Test
    fun `expanding clamps to the window with partial steps and disables at its edges`() {
        val active = TimeRange.of(window.startMs + 17_000, window.endMs - 23_000)
        val controls = sliceControls(window, active, minimum, hasData = true)

        assertEquals(TimeRange.of(window.startMs, active.endMs), controls[0].selection)
        assertEquals(TimeRange.of(active.startMs, window.endMs), controls[3].selection)
        assertTrue(controls[0].enabled)
        assertTrue(controls[3].enabled)

        val atEdges = sliceControls(window, window, minimum, hasData = true)
        assertEquals(window, atEdges[0].selection)
        assertEquals(window, atEdges[3].selection)
        assertFalse(atEdges[0].enabled)
        assertFalse(atEdges[3].enabled)
        assertTrue(atEdges[1].enabled)
        assertTrue(atEdges[2].enabled)
    }

    @Test
    fun `shrinking uses a partial step to reach the minimum and then disables`() {
        val active = TimeRange.of(15 * minute, 20 * minute + 13_000)
        val controls = sliceControls(window, active, minimum, hasData = true)

        assertEquals(TimeRange.of(15 * minute + 13_000, active.endMs), controls[1].selection)
        assertEquals(TimeRange.of(active.startMs, 20 * minute), controls[2].selection)
        assertTrue(controls[1].enabled)
        assertTrue(controls[2].enabled)

        val atMinimum = sliceControls(window, controls[1].selection, minimum, hasData = true)
        assertFalse(atMinimum[1].enabled)
        assertFalse(atMinimum[2].enabled)
        assertEquals(minimum, atMinimum[1].selection?.durationMs)
        assertEquals(minimum, atMinimum[2].selection?.durationMs)
    }

    @Test
    fun `creation followed by adjustment uses the hoisted selection and clear stays available`() {
        val created = sliceControls(window, null, minimum, hasData = true).single().selection
        val expanded = sliceControls(window, created, minimum, hasData = true)[0].selection
        val controls = sliceControls(window, expanded, minimum, hasData = true)

        assertEquals(TimeRange.of(24 * minute, 30 * minute), expanded)
        assertEquals(TimeRange.of(25 * minute, 30 * minute), controls[1].selection)
        assertEquals(TimeRange.of(24 * minute, 29 * minute), controls[2].selection)
        assertFalse(controls[3].enabled)
        assertTrue(controls.last().enabled)
        assertNull(controls.last().selection)
        assertEquals(R.string.chart_select_interval, sliceControls(window, controls.last().selection, minimum, true).single().labelRes)
    }

    @Test
    fun `out of window or undersized external selection is normalized before adjusting`() {
        val pastWindow = sliceControls(window, TimeRange.of(0, 40 * minute), minimum, hasData = true)
        assertFalse(pastWindow[0].enabled)
        assertFalse(pastWindow[3].enabled)
        assertEquals(TimeRange.of(11 * minute, 30 * minute), pastWindow[1].selection)

        val tooShort = sliceControls(window, TimeRange.of(29 * minute, 30 * minute), minimum, hasData = true)
        assertFalse(tooShort[1].enabled)
        assertFalse(tooShort[2].enabled)
        assertEquals(TimeRange.of(24 * minute, 30 * minute), tooShort[0].selection)
    }

    @Test
    fun `empty data or a window below the minimum offers no creation or adjustment`() {
        assertTrue(sliceControls(window, null, minimum, hasData = false).isEmpty())
        assertTrue(sliceControls(window, window, minimum, hasData = false).isEmpty())
        assertTrue(sliceControls(TimeRange.of(0, minimum - 1), null, minimum, hasData = true).isEmpty())
        assertTrue(sliceControls(TimeRange.of(0, 0), null, 0, hasData = true).isEmpty())
    }

    @Test
    fun `an exactly minimum window can be selected but not adjusted`() {
        val exact = TimeRange.of(0, minimum)
        val created = sliceControls(exact, null, minimum, hasData = true).single().selection
        val controls = sliceControls(exact, created, minimum, hasData = true)

        assertEquals(exact, created)
        assertTrue(controls.dropLast(1).all { !it.enabled })
        assertTrue(controls.last().enabled)
    }

    @Test
    fun `nonpositive minimum still creates a nonempty bounded interval`() {
        for (requestedMinimum in listOf(0L, -minute)) {
            val created = sliceControls(window, null, requestedMinimum, hasData = true).single().selection
            assertEquals(TimeRange.of(window.endMs - 1, window.endMs), created)
            val controls = sliceControls(window, created, requestedMinimum, hasData = true)
            assertFalse(controls[1].enabled)
            assertFalse(controls[2].enabled)
        }
    }
}
