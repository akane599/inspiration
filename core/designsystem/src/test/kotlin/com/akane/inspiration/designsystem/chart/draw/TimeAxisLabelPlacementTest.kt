package com.akane.inspiration.designsystem.chart.draw

import org.junit.Assert.assertEquals
import org.junit.Test

/** The time-axis label layout on a 1000 px plot. Results: the label's left edge, DROPPED, or OUTSIDE (no tick). */
class TimeAxisLabelPlacementTest {
    private sealed interface Placed

    private data class At(
        val left: Float,
    ) : Placed

    private data object Dropped : Placed

    private data object Outside : Placed

    private fun place(
        xs: List<Float>,
        widths: List<Float>,
        widthPx: Float = 1000f,
    ): List<Placed> {
        val out = MutableList<Placed>(xs.size) { Outside }
        val ticks = mutableListOf<Float>()
        placeTimeAxisLabels(xs.size, widthPx, { xs[it] }, { widths[it] }) { i, x, left ->
            ticks += x
            out[i] = if (left.isNaN()) Dropped else At(left)
        }
        // Every in-window tick keeps its mark, labelled or not.
        assertEquals(xs.filter { it in 0f..widthPx }, ticks)
        return out
    }

    @Test
    fun interiorLabelsAreCentredOnTheirTicks() {
        assertEquals(
            listOf(At(170f), At(470f), At(770f)),
            place(listOf(200f, 500f, 800f), listOf(60f, 60f, 60f)),
        )
    }

    @Test
    fun ticksOutsideThePlotHaveNoMarkAndNoLabel() {
        assertEquals(
            listOf(Outside, At(0f), At(940f), Outside),
            place(listOf(-5f, 0f, 1000f, 1005f), listOf(60f, 60f, 60f, 60f)),
        )
    }

    @Test
    fun aLeftEdgeLabelIsPushedInsideWhenItHasRoom() {
        assertEquals(listOf(At(0f), At(170f)), place(listOf(10f, 200f), listOf(60f, 60f)))
    }

    @Test
    fun aLeftEdgeLabelYieldsToTheNextLabelInsteadOfCrowdingIt() {
        // Pushed to 0..60, it would come within 30 px (half its width) of the next label at 50.
        assertEquals(listOf(Dropped, At(50f)), place(listOf(10f, 80f), listOf(60f, 60f)))
    }

    @Test
    fun aLeftEdgeLabelLooksPastTicksOutsideThePlotForItsNeighbour() {
        assertEquals(listOf(At(0f), Outside), place(listOf(10f, 1200f), listOf(60f, 60f)))
    }

    @Test
    fun aRightEdgeLabelYieldsToThePreviousLabel() {
        // Pushed to 940, it would start within 30 px of the previous label's end at 930.
        assertEquals(listOf(At(870f), Dropped), place(listOf(900f, 990f), listOf(60f, 60f)))
    }

    @Test
    fun aRightEdgeLabelIsPushedInsideWhenItHasRoom() {
        assertEquals(listOf(At(770f), At(940f)), place(listOf(800f, 990f), listOf(60f, 60f)))
    }

    @Test
    fun overlappingInteriorLabelsKeepTheFirstAndMeasureFromTheLastDrawn() {
        // 520's label (490..550) overlaps 500's (470..530); 600's (570..630) is clear of the last drawn label.
        assertEquals(
            listOf(At(470f), Dropped, At(570f)),
            place(listOf(500f, 520f, 600f), listOf(60f, 60f, 60f)),
        )
    }

    @Test
    fun touchingInteriorLabelsAreBothKept() {
        assertEquals(listOf(At(470f), At(530f)), place(listOf(500f, 560f), listOf(60f, 60f)))
    }

    @Test
    fun aLabelWiderThanThePlotStartsAtItsLeftEdge() {
        assertEquals(listOf(At(0f)), place(listOf(500f), listOf(1200f)))
    }

    @Test
    fun clampedLabelLeftKeepsTheLabelInsideThePlot() {
        assertEquals(470f, clampedLabelLeft(500f, 60f, 1000f))
        assertEquals(0f, clampedLabelLeft(10f, 60f, 1000f))
        assertEquals(940f, clampedLabelLeft(990f, 60f, 1000f))
    }
}
