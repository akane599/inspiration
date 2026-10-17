package com.akane.inspiration.telemetry.interval

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind

/**
 * Screen/charging time split and attributed level drops over `(fromWallMs, toWallMs]`
 * (docs/plans/phase-3.md F3). [unknownMs] is non-charging time before any SCREEN event was seen.
 * [levelUsedPct] is the sum of attributed drops, so it can exceed `startLevel - endLevel` when the
 * interval contains charging. Rates are `%/h`, null for a state with under 15 min of time.
 */
data class IntervalMetrics(
    val fromWallMs: Long,
    val toWallMs: Long,
    val screenOnMs: Long,
    val screenOffMs: Long,
    val unknownMs: Long,
    val chargingMs: Long,
    val startLevel: Int?,
    val endLevel: Int?,
    val levelUsedPct: Int,
    val levelDropScreenOn: Int,
    val levelDropScreenOff: Int,
    val screenOnPctPerHour: Double?,
    val screenOffPctPerHour: Double?,
    val discontinuities: List<Long>,
)

object IntervalMetricsCalculator {
    private const val MIN_RATE_WINDOW_MS = 15 * 60_000L
    private const val HOUR_MS = 3_600_000.0

    /**
     * [events] must be sorted by wall time and start from wall 0: events at or before [fromWallMs]
     * only seed state, events after [toWallMs] are ignored. With no event at or before
     * [toWallMs] there is nothing to measure and everything is zero/null.
     */
    fun compute(events: List<HistoryEvent>, fromWallMs: Long, toWallMs: Long): IntervalMetrics {
        require(toWallMs >= fromWallMs) { "to ($toWallMs) before from ($fromWallMs)" }
        // Stable sort: a LEVEL runs after the SCREEN/PLUG transitions that share its timestamp.
        val ordered = events
            .filter { it.wallMs <= toWallMs }
            .sortedWith(compareBy<HistoryEvent> { it.wallMs }.thenBy { it.kind == HistoryKind.LEVEL })
        val walk = Walk(fromWallMs)
        ordered.forEach(walk::apply)
        if (ordered.isNotEmpty()) walk.advanceTo(toWallMs)
        return walk.toMetrics(toWallMs)
    }

    private class Walk(private val fromWallMs: Long) {
        private var cursorMs = fromWallMs
        private var screenOn: Boolean? = null
        private var plugged = false
        private var prevLevel: Int? = null
        private var startLevel: Int? = null
        private var endLevel: Int? = null
        private var onMs = 0L
        private var offMs = 0L
        private var unknownMs = 0L
        private var chargingMs = 0L
        private var dropOn = 0
        private var dropOff = 0
        private val discontinuities = ArrayList<Long>()

        fun apply(event: HistoryEvent) {
            val inside = event.wallMs > fromWallMs
            if (inside) advanceTo(event.wallMs)
            when (event.kind) {
                HistoryKind.SCREEN_ON -> screenOn = true
                HistoryKind.SCREEN_OFF -> screenOn = false
                HistoryKind.PLUGGED -> plugged = true
                HistoryKind.UNPLUGGED -> plugged = false
                HistoryKind.LEVEL -> onLevel(event.levelPct, inside)
                HistoryKind.RESET, HistoryKind.START -> {
                    prevLevel = event.levelPct
                    if (inside) discontinuities += event.wallMs
                }
            }
            // Only LEVEL moves the drop baseline: other events in the same history record already
            // carry the new level, and the LEVEL that follows them must still see the old one.
            if (prevLevel == null) prevLevel = event.levelPct
            if (!inside) startLevel = event.levelPct
            endLevel = event.levelPct
        }

        private fun onLevel(level: Int, inside: Boolean) {
            val previous = prevLevel
            prevLevel = level
            if (!inside || previous == null || level >= previous || plugged) return
            when (screenOn) {
                true -> dropOn += previous - level
                false -> dropOff += previous - level
                null -> Unit
            }
        }

        fun advanceTo(wallMs: Long) {
            val span = wallMs - cursorMs
            cursorMs = wallMs
            when {
                plugged -> chargingMs += span
                screenOn == true -> onMs += span
                screenOn == false -> offMs += span
                else -> unknownMs += span
            }
        }

        fun toMetrics(toWallMs: Long) = IntervalMetrics(
            fromWallMs = fromWallMs,
            toWallMs = toWallMs,
            screenOnMs = onMs,
            screenOffMs = offMs,
            unknownMs = unknownMs,
            chargingMs = chargingMs,
            startLevel = startLevel,
            endLevel = endLevel,
            levelUsedPct = dropOn + dropOff,
            levelDropScreenOn = dropOn,
            levelDropScreenOff = dropOff,
            screenOnPctPerHour = rate(dropOn, onMs),
            screenOffPctPerHour = rate(dropOff, offMs),
            discontinuities = discontinuities,
        )

        private fun rate(drop: Int, stateMs: Long): Double? =
            if (stateMs >= MIN_RATE_WINDOW_MS) drop / (stateMs / HOUR_MS) else null
    }
}
