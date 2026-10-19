package com.akane.inspiration.designsystem.chart.draw

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.akane.inspiration.designsystem.chart.math.TimeScale

/** A tick position with its label, measured once in the cache block (never per frame). */
class TimeTick(
    val tMs: Long,
    val label: TextLayoutResult,
)

/**
 * @property tickColor color of the tick marks.
 * @property labelColor color of the labels.
 * @property tickLengthPx tick mark length below the axis origin.
 * @property strokePx tick mark stroke width.
 * @property labelGapPx gap between the tick marks' end and the label top.
 */
@Immutable
data class AxisStyle(
    val tickColor: Color,
    val labelColor: Color,
    val tickLengthPx: Float,
    val strokePx: Float,
    val labelGapPx: Float,
)

/**
 * Draws tick marks and centered labels with the axis origin at y = 0 of the current draw scope
 * (translate before calling to place it). Where each label goes is [placeTimeAxisLabels].
 * Allocates nothing: [ticks] and their layouts are prepared by the caller.
 */
fun DrawScope.drawTimeAxis(
    ticks: List<TimeTick>,
    scale: TimeScale,
    style: AxisStyle,
) {
    val labelTop = style.tickLengthPx + style.labelGapPx
    placeTimeAxisLabels(
        count = ticks.size,
        widthPx = scale.widthPx,
        tickX = { scale.toX(ticks[it].tMs) },
        labelWidth = {
            ticks[it]
                .label.size.width
                .toFloat()
        },
    ) { index, x, labelLeft ->
        drawLine(
            color = style.tickColor,
            start = Offset(x, 0f),
            end = Offset(x, style.tickLengthPx),
            strokeWidth = style.strokePx,
        )
        if (!labelLeft.isNaN()) {
            drawText(ticks[index].label, color = style.labelColor, topLeft = Offset(labelLeft, labelTop))
        }
    }
}

/**
 * The time-axis label layout, as one pure pass over [count] ticks in time order. [tickX] is tick i's x in the plot
 * and [labelWidth] its label's width. [place] is called, in order, for every tick inside `0..widthPx` (outside ticks
 * are skipped) with the label's left edge, or `Float.NaN` when the label is dropped; its tick mark stays either way.
 *
 * - A label is centred on its tick and kept inside `0..widthPx`.
 * - A label pushed inward at the window edge is dropped when it would come within half its own width of its
 *   neighbour: the previous drawn label, or (for the left edge) the next tick's label, which is not placed yet.
 *   So edge labels never crowd or clip.
 * - Interior labels are dropped only when they would overlap the previous drawn label.
 *
 * Inline so the draw pass allocates nothing.
 */
internal inline fun placeTimeAxisLabels(
    count: Int,
    widthPx: Float,
    tickX: (Int) -> Float,
    labelWidth: (Int) -> Float,
    place: (index: Int, x: Float, labelLeft: Float) -> Unit,
) {
    var prevLabelEnd = Float.NEGATIVE_INFINITY
    for (i in 0 until count) {
        val x = tickX(i)
        if (x < 0f || x > widthPx) continue
        val width = labelWidth(i)
        val left = clampedLabelLeft(x, width, widthPx)
        val pushedInward = left != x - width / 2f
        // An inward-shifted edge label keeps half its own width of clear space; interior labels only must not overlap.
        val minGap = if (pushedInward) width / 2f else 0f
        val crowdsPrevious = left < prevLabelEnd + minGap
        var crowdsNext = false
        if (pushedInward) {
            // Left edge of the next in-window tick's label; none means nothing to crowd.
            for (j in i + 1 until count) {
                val nextX = tickX(j)
                if (nextX < 0f || nextX > widthPx) continue
                crowdsNext = left + width + minGap > clampedLabelLeft(nextX, labelWidth(j), widthPx)
                break
            }
        }
        if (crowdsPrevious || crowdsNext) {
            place(i, x, Float.NaN)
        } else {
            place(i, x, left)
            prevLabelEnd = left + width
        }
    }
}

/** A label of [labelWidth] centred on [x], moved inward to stay inside `0..widthPx`. */
internal fun clampedLabelLeft(
    x: Float,
    labelWidth: Float,
    widthPx: Float,
): Float = (x - labelWidth / 2f).coerceIn(0f, maxOf(0f, widthPx - labelWidth))
