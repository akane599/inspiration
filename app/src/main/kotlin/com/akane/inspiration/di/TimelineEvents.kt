package com.akane.inspiration.di

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind

/**
 * Builds the event list [com.akane.inspiration.diagnostics.analysis.TimelineBuilder] needs for a window
 * starting at `from`. The builder treats every state as off before its first event, so a window that
 * opens while the screen is on, the device is plugged in or Doze is active would otherwise lose those
 * spans. Its KDoc asks the caller to include the last event before `from`; one event only seeds one
 * state, so this keeps the last transitions of each state track (screen, plug, idle, shutdown). The seeds are
 * older than the window, so the builder clamps everything it emits to [from, to].
 */
object TimelineEvents {
    private val screen = setOf(HistoryKind.SCREEN_ON, HistoryKind.SCREEN_OFF)
    private val plug = setOf(HistoryKind.PLUGGED, HistoryKind.UNPLUGGED)
    private val idle = setOf(
        HistoryKind.DEEP_IDLE_ON,
        HistoryKind.DEEP_IDLE_OFF,
        HistoryKind.LIGHT_IDLE_ON,
        HistoryKind.LIGHT_IDLE_OFF,
    )

    /**
     * [before] are events older than the window (any order); [inWindow] are the window's own events.
     * The last two idle events are kept: an `*_IDLE_OFF` only opens a maintenance gap when the matching
     * `*_IDLE_ON` came first.
     * Screen and plug tracks keep the last event of each kind: their preceding closed span may
     * overlap that idle-exit gap, making it awake rather than maintenance even before the window.
     */
    fun withSeeds(before: List<HistoryEvent>, inWindow: List<HistoryEvent>): List<HistoryEvent> {
        val ordered = before.sortedBy { it.wallMs }
        val seeds = (screen + plug).mapNotNull { kind -> ordered.lastOrNull { it.kind == kind } } + listOfNotNull(
            ordered.lastOrNull { it.kind == HistoryKind.SHUTDOWN },
        ) + ordered.filter { it.kind in idle }.takeLast(IDLE_SEEDS)
        return seeds.sortedBy { it.wallMs } + inWindow
    }

    private const val IDLE_SEEDS = 2
}
