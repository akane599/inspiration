package com.akane.inspiration.telemetry.status

import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger

/**
 * Battery use since the device was last unplugged, derived from stored snapshots only (no Shizuku read).
 * Rates are `%/h` and [Discharging.deepSleepRatio] is suspended time over screen-off time; each is null
 * with under 15 min of screen-on (respectively screen-off) time in the interval.
 * [Discharging.anchorIsUnplug] is false when the device was never seen plugged in nor unplugged, so the
 * interval starts at the oldest snapshot instead of at an unplug.
 */
sealed interface UnplugSummary {
    data class Charging(
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

    /**
     * [snapshots] is the stored history (any order; snapshots at or after [now] are ignored) and [now] a fresh
     * read of the battery. The anchor is the first unplugged snapshot after the last plugged one, so a missing
     * POWER_DISCONNECTED capture does not move it.
     */
    fun compute(
        snapshots: List<BatterySnapshot>,
        now: BatterySnapshot,
    ): UnplugSummary {
        if (now.battery.plugged == true) return UnplugSummary.Charging(now.battery.levelPct)
        val points = snapshots.filter { it.clock.wallMs < now.clock.wallMs }.sortedBy { it.clock.wallMs } + now
        val lastPlugged = points.indexOfLast { it.battery.plugged == true }
        // With no plugged snapshot stored (fresh install, pruned history) a disconnect capture still marks the unplug.
        val lastDisconnect = points.indexOfLast { it.trigger == CaptureTrigger.POWER_DISCONNECTED }
        val anchorIsUnplug = lastPlugged >= 0 || lastDisconnect >= 0
        val inferredAnchor =
            if (lastPlugged < 0) {
                lastDisconnect.coerceAtLeast(0)
            } else {
                (lastPlugged + 1 until points.size).firstOrNull { points[it].battery.plugged == false } ?: points.lastIndex
            }
        val anchor = maxOf(inferredAnchor, lastDisconnect)
        val tally = tally(points, anchor)
        val dropPct = tally.dropOn + tally.dropOff + tally.dropUnknown
        return UnplugSummary.Discharging(
            levelNow = now.battery.levelPct,
            dropPct = dropPct,
            elapsedMs = now.clock.wallMs - points[anchor].clock.wallMs,
            screenOnPctPerHour = rate(tally.dropOn, tally.onMs),
            screenOffPctPerHour = rate(tally.dropOff, tally.offMs),
            deepSleepRatio =
                if (tally.offMs >= MIN_RATE_WINDOW_MS) {
                    (tally.suspendMs.toDouble() / tally.offMs).coerceIn(0.0, 1.0)
                } else {
                    null
                },
            anchorIsUnplug = anchorIsUnplug,
            anchorWallMs = points[anchor].clock.wallMs,
        )
    }

    private fun rate(
        drop: Int,
        stateMs: Long,
    ): Double? = if (stateMs >= MIN_RATE_WINDOW_MS) drop / (stateMs / HOUR_MS) else null

    private fun tally(
        points: List<BatterySnapshot>,
        anchor: Int,
    ): Tally {
        var screen: Screen? = null
        val tally = Tally()
        for (i in points.indices) {
            val point = points[i]
            // Only a new boot forgets the screen state, never a trigger: 0.2.3 stored an app update (same boot)
            // as BOOT, and a capture that ran before BOOT_COMPLETED already opened the new boot.
            if (i > 0 && points[i - 1].clock.bootCount != point.clock.bootCount) {
                screen = null
            }
            screen = screenAfter(screen, point)
            if (i >= anchor && i < points.lastIndex) tally.add(point, points[i + 1], screen)
        }
        return tally
    }

    private fun screenAfter(
        current: Screen?,
        snapshot: BatterySnapshot,
    ): Screen? =
        when (snapshot.trigger) {
            CaptureTrigger.SCREEN_ON -> Screen.ON
            CaptureTrigger.SCREEN_OFF -> Screen.OFF
            else -> current
        }

    private enum class Screen { ON, OFF }

    private class Tally {
        var onMs = 0L
        var offMs = 0L
        var dropOn = 0
        var dropOff = 0
        var dropUnknown = 0
        var suspendMs = 0L

        /** Adds the segment [from] to [to], spent in [screen] state (null: unknown, only its drop is counted). */
        fun add(
            from: BatterySnapshot,
            to: BatterySnapshot,
            screen: Screen?,
        ) {
            val drop = drop(from, to)
            // A different boot cannot establish the screen state of this segment.
            when (screen.takeIf { from.clock.bootCount == to.clock.bootCount }) {
                Screen.ON -> {
                    // Same boot: elapsedRealtime, like screen-off, so a wall-clock change cannot stretch the segment.
                    onMs += (to.clock.elapsedRealtimeMs - from.clock.elapsedRealtimeMs).coerceAtLeast(0L)
                    dropOn += drop
                }

                Screen.OFF -> {
                    dropOff += drop
                    if (from.clock.bootCount == to.clock.bootCount) {
                        val elapsedMs = to.clock.elapsedRealtimeMs - from.clock.elapsedRealtimeMs
                        offMs += elapsedMs.coerceAtLeast(0L)
                        suspendMs += (elapsedMs - (to.clock.uptimeMs - from.clock.uptimeMs)).coerceAtLeast(0L)
                    }
                }

                null -> {
                    dropUnknown += drop
                }
            }
        }

        private fun drop(
            from: BatterySnapshot,
            to: BatterySnapshot,
        ): Int {
            val start = from.battery.levelPct ?: return 0
            val end = to.battery.levelPct ?: return 0
            return (start - end).coerceAtLeast(0)
        }
    }
}
