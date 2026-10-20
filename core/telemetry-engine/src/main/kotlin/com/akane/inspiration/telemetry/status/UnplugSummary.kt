package com.akane.inspiration.telemetry.status

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.diff.ReferenceResolver.Companion.CAPTURE_SLACK_MS
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotClock

/**
 * Battery use since the device was last unplugged, derived from stored snapshots and ingested battery history only (no
 * Shizuku read). Rates are `%/h` and [Discharging.deepSleepRatio] is suspended time over screen-off time; each is null
 * with under 15 min of screen-on (respectively measurable screen-off) time in the interval.
 * [Discharging.anchorIsUnplug] is false when the device was never seen plugged in nor unplugged, so the
 * interval starts at the oldest snapshot instead of at an unplug.
 */
sealed interface UnplugSummary {
    data class Charging(
        val levelNow: Int?,
    ) : UnplugSummary

    /** On a charger but not charging: full, held at a charge limit, or losing to the load ([ChargeState]). */
    data class PluggedNotCharging(
        val levelNow: Int?,
    ) : UnplugSummary

    data class Discharging(
        val levelNow: Int?,
        val dropPct: Int,
        val elapsedMs: Long,
        val screenOnPctPerHour: Double?,
        val screenOffPctPerHour: Double?,
        val deepSleepRatio: Double?,
        val anchorIsUnplug: Boolean,
        val anchorWallMs: Long,
    ) : UnplugSummary
}

object UnplugSummaryCalculator {
    private const val MIN_RATE_WINDOW_MS = 15 * 60_000L
    private const val HOUR_MS = 3_600_000.0

    /** The battery-history kinds [compute] reads: the screen and plug transitions. */
    val HISTORY_KINDS: Set<Int> =
        setOf(HistoryKind.SCREEN_ON, HistoryKind.SCREEN_OFF, HistoryKind.PLUGGED, HistoryKind.UNPLUGGED)

    /** The capture each history kind's broadcast triggers. */
    private val OWN_TRIGGER =
        mapOf(
            HistoryKind.SCREEN_ON to CaptureTrigger.SCREEN_ON,
            HistoryKind.SCREEN_OFF to CaptureTrigger.SCREEN_OFF,
            HistoryKind.PLUGGED to CaptureTrigger.POWER_CONNECTED,
            HistoryKind.UNPLUGGED to CaptureTrigger.POWER_DISCONNECTED,
        )

    /**
     * [snapshots] is the stored history (any order; snapshots at or after [now] are ignored) and [now] a fresh
     * read of the battery. The anchor is the first unplugged point after the last plugged one, so a missing
     * POWER_DISCONNECTED capture does not move it. Whether [now] is charging is the gauge's [chargeState] rule (status
     * and current, never the plug alone), so its current must already be calibrated.
     *
     * [history] is the ingested battery history (other kinds than [HISTORY_KINDS], unknown wall times and events at or
     * after [now] are ignored). From API 34 the screen and power broadcasts reach a cached app late, and only the
     * newest of each pair, so a capture's trigger can be hours past its transition while the other transition left no
     * capture at all. The history times both precisely ([CAPTURE_SLACK_MS] rule): a capture within the slack after an
     * event of its kind is that event's own and marks it; otherwise the event's wall time and level mark the
     * transition. A screen capture between the history's first and last screen events is then a late delivery and
     * sets no state, and a POWER_DISCONNECTED capture after a history unplug, with nothing plugged between, is that
     * unplug delivered late and moves no anchor. Without history this is the capture-only summary.
     */
    fun compute(
        snapshots: List<BatterySnapshot>,
        now: BatterySnapshot,
        history: List<HistoryEvent> = emptyList(),
    ): UnplugSummary {
        when (chargeState(now.battery.plugged, now.battery.status, now.battery.currentMa)) {
            ChargeState.Charging -> return UnplugSummary.Charging(now.battery.levelPct)
            ChargeState.PluggedNotCharging -> return UnplugSummary.PluggedNotCharging(now.battery.levelPct)
            ChargeState.Discharging -> Unit
        }
        val captures = snapshots.filter { it.clock.wallMs < now.clock.wallMs }.sortedBy { it.clock.wallMs } + now
        val events =
            history
                .filter { it.kind in HISTORY_KINDS && it.wallMs >= 0 && it.wallMs < now.clock.wallMs }
                .sortedBy { it.wallMs }
        val points = timeline(captures, events)
        val lastPlugged = points.indexOfLast { it.plugged == true }
        // With no plugged point stored (fresh install, pruned history) an unplug mark still anchors.
        val lastUnplug = points.indexOfLast { it.marksUnplug }
        val anchorIsUnplug = lastPlugged >= 0 || lastUnplug >= 0
        val inferredAnchor =
            when {
                lastPlugged >= 0 -> {
                    (lastPlugged + 1 until points.size).firstOrNull { points[it].plugged == false } ?: points.lastIndex
                }

                lastUnplug >= 0 -> {
                    lastUnplug
                }

                // Never seen plugged nor unplugged: the oldest snapshot, not an older history event.
                else -> {
                    points.indexOfFirst { it.capture != null }
                }
            }
        val anchor = maxOf(inferredAnchor, lastUnplug)
        val tally = tally(points, anchor)
        val dropPct = tally.dropOn + tally.dropOff + tally.dropUnknown
        return UnplugSummary.Discharging(
            levelNow = now.battery.levelPct,
            dropPct = dropPct,
            elapsedMs = now.clock.wallMs - points[anchor].wallMs,
            screenOnPctPerHour = rate(tally.dropOn, tally.onMs),
            screenOffPctPerHour = rate(tally.dropOff, tally.offMs),
            deepSleepRatio =
                if (tally.sleepOffMs >= MIN_RATE_WINDOW_MS) {
                    (tally.suspendMs / tally.sleepOffMs).coerceIn(0.0, 1.0)
                } else {
                    null
                },
            anchorIsUnplug = anchorIsUnplug,
            anchorWallMs = points[anchor].wallMs,
        )
    }

    private fun rate(
        drop: Int,
        stateMs: Long,
    ): Double? = if (stateMs >= MIN_RATE_WINDOW_MS) drop / (stateMs / HOUR_MS) else null

    /**
     * [captures] (by wall time, `now` last) merged with the [events] (by wall time) that no capture is the own of; an
     * event goes after a capture at its wall time.
     */
    private fun timeline(
        captures: List<BatterySnapshot>,
        events: List<HistoryEvent>,
    ): List<Point> {
        val own = HashSet<Int>()
        val kept =
            events.filter { event ->
                val ownCapture = ownCapture(captures, event)
                if (ownCapture != null) own += ownCapture
                ownCapture == null
            }
        val screenWalls = events.filter { it.kind == HistoryKind.SCREEN_ON || it.kind == HistoryKind.SCREEN_OFF }
        // Strictly after the history's first screen event and at or before its last: a time the history covers.
        val covered = screenWalls.firstOrNull()?.let { first -> first.wallMs + 1..screenWalls.last().wallMs }
        val points = ArrayList<Point>(captures.size + kept.size)
        var next = 0
        // Whether the newest plug evidence so far is a history unplug, which a later disconnect capture repeats.
        var historyUnplugged = false
        for ((i, capture) in captures.withIndex()) {
            val before = captures.getOrNull(i - 1)?.clock
            while (next < kept.size && kept[next].wallMs < capture.clock.wallMs) {
                val event = kept[next++]
                points += historyPoint(event, historyClock(event.wallMs, before, capture.clock))
                if (event.kind == HistoryKind.UNPLUGGED) historyUnplugged = true
                if (event.kind == HistoryKind.PLUGGED) historyUnplugged = false
            }
            val isOwn = i in own
            val late = !isOwn && covered != null && capture.clock.wallMs in covered
            val marksUnplug = capture.trigger == CaptureTrigger.POWER_DISCONNECTED && (isOwn || !historyUnplugged)
            if (marksUnplug || capture.battery.plugged == true) historyUnplugged = false
            points += capturePoint(capture, screen = if (late) null else screenOf(capture.trigger), marksUnplug)
        }
        return points
    }

    /** The index of the capture of [event]'s kind within [CAPTURE_SLACK_MS] at or after it, if any. */
    private fun ownCapture(
        captures: List<BatterySnapshot>,
        event: HistoryEvent,
    ): Int? {
        val trigger = OWN_TRIGGER.getValue(event.kind)
        var i = firstAtOrAfter(captures, event.wallMs)
        while (i < captures.size && captures[i].clock.wallMs <= event.wallMs + CAPTURE_SLACK_MS) {
            if (captures[i].trigger == trigger) return i
            i++
        }
        return null
    }

    private fun firstAtOrAfter(
        captures: List<BatterySnapshot>,
        wallMs: Long,
    ): Int {
        var low = 0
        var high = captures.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (captures[mid].clock.wallMs < wallMs) low = mid + 1 else high = mid
        }
        return low
    }

    /**
     * A clock for a history event at [wallMs] between the captures [before] (null: none) and [after], in the boot of
     * the capture beside it: elapsedRealtime is the wall time since [before] (capped at [after]) when both captures ran
     * in one boot, else the wall time before [after] when [after]'s boot had started by then, else the wall time since
     * [before]. Null when no capture places it in a boot. Its uptime is never read: only captures measure suspension.
     */
    private fun historyClock(
        wallMs: Long,
        before: SnapshotClock?,
        after: SnapshotClock,
    ): SnapshotClock? =
        when {
            before != null && after.sameBootAs(before) -> {
                val elapsedMs = before.elapsedRealtimeMs + (wallMs - before.wallMs)
                before.at(wallMs, elapsedMs.coerceIn(before.elapsedRealtimeMs, after.elapsedRealtimeMs))
            }

            wallMs >= after.wallMs - after.elapsedRealtimeMs -> {
                after.at(wallMs, (after.elapsedRealtimeMs - (after.wallMs - wallMs)).coerceIn(0L, after.elapsedRealtimeMs))
            }

            before != null -> {
                before.at(wallMs, before.elapsedRealtimeMs + (wallMs - before.wallMs))
            }

            else -> {
                null
            }
        }

    private fun SnapshotClock.at(
        wallMs: Long,
        elapsedMs: Long,
    ) = copy(wallMs = wallMs, elapsedRealtimeMs = elapsedMs, uptimeMs = elapsedMs)

    private fun capturePoint(
        capture: BatterySnapshot,
        screen: Screen?,
        marksUnplug: Boolean,
    ) = Point(
        wallMs = capture.clock.wallMs,
        levelPct = capture.battery.levelPct,
        plugged = capture.battery.plugged,
        screen = screen,
        marksUnplug = marksUnplug,
        clock = capture.clock,
        capture = capture,
    )

    private fun historyPoint(
        event: HistoryEvent,
        clock: SnapshotClock?,
    ) = Point(
        wallMs = event.wallMs,
        levelPct = event.levelPct.takeIf { it in 0..100 },
        plugged =
            when (event.kind) {
                HistoryKind.PLUGGED -> true
                HistoryKind.UNPLUGGED -> false
                else -> null
            },
        screen =
            when (event.kind) {
                HistoryKind.SCREEN_ON -> Screen.ON
                HistoryKind.SCREEN_OFF -> Screen.OFF
                else -> null
            },
        marksUnplug = event.kind == HistoryKind.UNPLUGGED,
        clock = clock,
        capture = null,
    )

    private fun screenOf(trigger: CaptureTrigger): Screen? =
        when (trigger) {
            CaptureTrigger.SCREEN_ON -> Screen.ON
            CaptureTrigger.SCREEN_OFF -> Screen.OFF
            else -> null
        }

    private fun tally(
        points: List<Point>,
        anchor: Int,
    ): Tally {
        // The screen state from each point on. Only a new boot forgets it, never a trigger: 0.2.3 stored an app update
        // (same boot) as BOOT, and a capture that ran before BOOT_COMPLETED already opened the new boot.
        val screens = arrayOfNulls<Screen>(points.size)
        var screen: Screen? = null
        for (i in points.indices) {
            if (i > 0 && !sameBoot(points[i - 1], points[i])) screen = null
            screen = points[i].screen ?: screen
            screens[i] = screen
        }
        val tally = Tally()
        for (i in anchor until points.lastIndex) tally.add(points[i], points[i + 1], screens[i])
        var open = -1
        for (i in points.indices) {
            if (points[i].capture == null) continue
            if (open >= 0) tally.addSuspended(points, screens, open, i, anchor)
            open = i
        }
        return tally
    }

    /**
     * Whether [to], the point after [from] in wall-clock order, is in the same boot ([SnapshotClock.sameBootAs]).
     * A backward wall-clock step can also put a later capture of one boot first; the state between the two is then
     * unknown as well, which only drops what could not be attributed.
     */
    private fun sameBoot(
        from: Point,
        to: Point,
    ): Boolean = from.clock != null && to.clock != null && to.clock.sameBootAs(from.clock)

    private enum class Screen { ON, OFF }

    /**
     * A capture, or a history transition no capture is the own of. [screen] is the state it sets (null: it carries
     * the state on), [marksUnplug] whether it marks an unplug, [clock] the capture's or [historyClock]'s.
     */
    private class Point(
        val wallMs: Long,
        val levelPct: Int?,
        val plugged: Boolean?,
        val screen: Screen?,
        val marksUnplug: Boolean,
        val clock: SnapshotClock?,
        val capture: BatterySnapshot?,
    )

    private class Tally {
        var onMs = 0L
        var offMs = 0L
        var dropOn = 0
        var dropOff = 0
        var dropUnknown = 0
        var suspendMs = 0.0
        var sleepOffMs = 0L

        /** Adds the segment [from] to [to], spent in [screen] state (null: unknown, only its drop is counted). */
        fun add(
            from: Point,
            to: Point,
            screen: Screen?,
        ) {
            val drop = drop(from, to)
            // A different boot cannot establish the screen state of this segment.
            when (screen.takeIf { sameBoot(from, to) }) {
                Screen.ON -> {
                    // Same boot: elapsedRealtime, like screen-off, so a wall-clock change cannot stretch the segment.
                    onMs += elapsedMs(from, to)
                    dropOn += drop
                }

                Screen.OFF -> {
                    dropOff += drop
                    offMs += elapsedMs(from, to)
                }

                null -> {
                    dropUnknown += drop
                }
            }
        }

        /**
         * Adds the suspended time of the same-boot capture span [open] to [close] ([points] indices) and its screen-off
         * time from [anchor] on. Suspension needs the screen off, so the span's suspended time is spread over the
         * part that may have been off (off or unknown); a span that history events split, or the anchor cuts, keeps
         * the off part's share of it. A span with no capture before it, or across a boot, measures none.
         */
        fun addSuspended(
            points: List<Point>,
            screens: Array<Screen?>,
            open: Int,
            close: Int,
            anchor: Int,
        ) {
            val from = points[open].clock!!
            val to = points[close].clock!!
            if (!to.sameBootAs(from)) return
            var mayBeOffMs = 0L
            var offMs = 0L
            for (j in open until close) {
                val ms = elapsedMs(points[j], points[j + 1])
                if (screens[j] != Screen.ON) mayBeOffMs += ms
                if (j >= anchor && screens[j] == Screen.OFF) offMs += ms
            }
            if (offMs == 0L) return
            val suspended = (to.elapsedRealtimeMs - from.elapsedRealtimeMs - (to.uptimeMs - from.uptimeMs)).coerceAtLeast(0L)
            suspendMs += if (offMs == mayBeOffMs) suspended.toDouble() else suspended.toDouble() * offMs / mayBeOffMs
            sleepOffMs += offMs
        }

        private fun elapsedMs(
            from: Point,
            to: Point,
        ): Long = to.clock!!.elapsedRealtimeMs - from.clock!!.elapsedRealtimeMs

        private fun drop(
            from: Point,
            to: Point,
        ): Int {
            val start = from.levelPct ?: return 0
            val end = to.levelPct ?: return 0
            return (start - end).coerceAtLeast(0)
        }
    }
}
