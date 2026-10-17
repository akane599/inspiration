package com.akane.inspiration.designsystem.chart.draw

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.akane.inspiration.designsystem.chart.math.TimeScale

/** A tick position with its label, measured once in the cache block (never per frame). */
class TimeTick(val tMs: Long, val label: TextLayoutResult)

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
 * (translate before calling to place it). Labels are kept inside `0..scale.widthPx`; ticks outside
 * the window are skipped. Allocates nothing: [ticks] and their layouts are prepared by the caller.
 */
fun DrawScope.drawTimeAxis(ticks: List<TimeTick>, scale: TimeScale, style: AxisStyle) {
    val labelTop = style.tickLengthPx + style.labelGapPx
    for (i in ticks.indices) {
        val tick = ticks[i]
        val x = scale.toX(tick.tMs)
        if (x < 0f || x > scale.widthPx) continue
        drawLine(
            color = style.tickColor,
            start = Offset(x, 0f),
            end = Offset(x, style.tickLengthPx),
            strokeWidth = style.strokePx,
        )
        val labelWidth = tick.label.size.width.toFloat()
        val labelX = (x - labelWidth / 2f).coerceIn(0f, maxOf(0f, scale.widthPx - labelWidth))
        drawText(tick.label, color = style.labelColor, topLeft = Offset(labelX, labelTop))
    }
}
