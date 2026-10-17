package com.akane.inspiration.designsystem.chart.math

import com.akane.inspiration.designsystem.chart.model.TimeRange
import kotlin.math.roundToLong

/** Maps time in [window] to x pixels in `0..widthPx` and back. A zero-length window maps to x = 0. */
class TimeScale(val window: TimeRange, val widthPx: Float) {
    fun toX(tMs: Long): Float {
        val duration = window.durationMs
        if (duration <= 0L) return 0f
        return ((tMs - window.startMs).toDouble() / duration * widthPx).toFloat()
    }

    fun toTime(x: Float): Long {
        if (widthPx <= 0f) return window.startMs
        return window.startMs + (x.toDouble() / widthPx * window.durationMs).roundToLong()
    }
}

/** Maps a value in `min..max` to y pixels, with `max` at the top (y = 0). A flat range maps to the middle. */
class ValueScale(val min: Float, val max: Float, val heightPx: Float) {
    fun toY(v: Float): Float {
        val span = max - min
        if (span <= 0f) return heightPx / 2f
        return heightPx - (v - min) / span * heightPx
    }
}
