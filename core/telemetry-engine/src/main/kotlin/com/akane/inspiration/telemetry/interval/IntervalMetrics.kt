package com.akane.inspiration.telemetry.interval

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.store.HISTORY_HORIZON_GRACE_MS
import com.akane.inspiration.telemetry.store.knownStateEndWallMs

/**
 * Screen/charging time split and attributed level drops over `(fromWallMs, toWallMs]`
 * (docs/plans/phase-3.md F3). [unknownMs] is non-charging time with no SCREEN event since the start or since the
 * last START/RESET, plus all time after the stored history's horizon (the last successful ingest), plugged or not.
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
     *
     * [historyHorizonWallMs] is the wall time up to which the stored history is known to be complete
     * (the last successful ingest). The last known state is carried only up to it, or to the newest event
     * when that is later; the rest of the interval is [IntervalMetrics.unknownMs]. A gap of at most
     * [HISTORY_HORIZON_GRACE_MS] before [toWallMs] still carries the last state; a longer one is unknown from
     * the horizon on. The default, `toWallMs`, and any horizon at or after it carry the last state to [toWallMs].
     */
    fun compute(
        events: List<HistoryEvent>,
        fromWallMs: Long,
        toWallMs: Long,
        historyHorizonWallMs: Long = toWallMs,
    ): IntervalMetrics {
        require(toWallMs >= fromWallMs) { "to ($toWallMs) before from ($fromWallMs)" }
        // Stable sort: a LEVEL runs after the SCREEN/PLUG transitions that share its timestamp.
        val ordered =
            events
                .filter { it.wallMs <= toWallMs }
                .sortedWith(compareBy<HistoryEvent> { it.wallMs }.thenBy { it.kind == HistoryKind.LEVEL })
        val walk = Walk(fromWallMs)
        ordered.forEach(walk::apply)
        if (ordered.isNotEmpty()) {
            val knownTo = maxOf(historyHorizonWallMs, ordered.last().wallMs)
            walk.advanceTo(knownStateEndWallMs(knownTo, toWallMs).coerceIn(walk.cursorMs, toWallMs))
            walk.advanceUnknownTo(toWallMs)
        }
        return walk.toMetrics(toWallMs)
    }

    /**
     * Whether [seeds] (every event of each kind at that kind's newest wall time before an interval, in stored
     * order) leave the walk in the state the whole history before it would: null when they do, else the wall time
     * to replay the history from. Screen, plug and level-at-start state follow the newest event of a kind, which
     * the seeds hold. The level baseline does too after a LEVEL, RESET or START with no SHUTDOWN after it; a
     * SHUTDOWN clears it and the next event of any kind sets it again, so the history is replayed from that
     * SHUTDOWN, which resets every field the walk keeps (or from the start, when neither kind is stored).
     */
    internal fun seedReplayFromWallMs(seeds: List<HistoryEvent>): Long? {
        val ordered = seeds.sortedWith(compareBy<HistoryEvent> { it.wallMs }.thenBy { it.kind == HistoryKind.LEVEL })
        val baseline = ordered.indexOfLast { it.kind == HistoryKind.LEVEL || it.kind == HistoryKind.RESET || it.kind == HistoryKind.START }
        val shutdown = ordered.indexOfLast { it.kind == HistoryKind.SHUTDOWN }
        return when {
            baseline > shutdown -> null
            shutdown >= 0 -> ordered[shutdown].wallMs
            else -> 0L
        }
    }

    private class Walk(
        private val fromWallMs: Long,
    ) {
        var cursorMs = fromWallMs
            private set
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
                HistoryKind.SCREEN_ON -> {
                    screenOn = true
                }

                HistoryKind.SCREEN_OFF -> {
                    screenOn = false
                }

                HistoryKind.PLUGGED -> {
                    plugged = true
                }

                HistoryKind.UNPLUGGED -> {
                    plugged = false
                }

                HistoryKind.LEVEL -> {
                    onLevel(event.levelPct, inside)
                }

                HistoryKind.SHUTDOWN -> {
                    screenOn = null
                    plugged = false
                    prevLevel = null
                    if (inside) discontinuities += event.wallMs
                }

                HistoryKind.RESET, HistoryKind.START -> {
                    // The state before the boundary is stale (batterystats drops pre-reset records that were never
                    // ingested). A RESET's own screen state follows it at the same instant; a bare one leaves it unknown.
                    screenOn = null
                    plugged = event.plugType != 0
                    prevLevel = event.levelPct
                    if (inside) discontinuities += event.wallMs
                }
            }
            // Only LEVEL moves the drop baseline: other events in the same history record already
            // carry the new level, and the LEVEL that follows them must still see the old one.
            if (prevLevel == null && event.kind != HistoryKind.SHUTDOWN) prevLevel = event.levelPct
            if (!inside) startLevel = event.levelPct
            endLevel = event.levelPct
        }

        private fun onLevel(
            level: Int,
            inside: Boolean,
        ) {
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

        /** Past the history horizon nothing is known, whatever the last state was. */
        fun advanceUnknownTo(wallMs: Long) {
            unknownMs += wallMs - cursorMs
            cursorMs = wallMs
        }

        fun toMetrics(toWallMs: Long) =
            IntervalMetrics(
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

        private fun rate(
            drop: Int,
            stateMs: Long,
        ): Double? = if (stateMs >= MIN_RATE_WINDOW_MS) drop / (stateMs / HOUR_MS) else null
    }
}
