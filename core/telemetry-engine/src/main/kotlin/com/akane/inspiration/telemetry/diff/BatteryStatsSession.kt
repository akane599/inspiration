package com.akane.inspiration.telemetry.diff

import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import kotlin.math.abs

/**
 * The single battery-stats session rule shared by the differ, the capturer and reference lookups.
 *
 * On every TIME_SET `BatteryStatsImpl` moves its reported start by the full wall-clock step (`adjustStartClockTime`),
 * and the time detector steps the clock only for corrections of 2 s or more, so a start shift alone cannot tell a
 * correction from a reset. Two snapshots are one session when their starts agree within [START_TOLERANCE_MS], or,
 * within one boot with elapsed time increasing, when their [startElapsedMs] (the start on the boot's elapsed clock,
 * which a step leaves unchanged) agree within it. A session's duration never decreases. A session spanning a reboot
 * stays unprovable after its first step: the elapsed clocks of two boots are not comparable.
 */
object BatteryStatsSession {
    const val START_TOLERANCE_MS = 2_000L

    /** Raw start comparison, for two reads of the same moment (the dump and usage starts of one capture). */
    fun sameStart(
        a: Long?,
        b: Long?,
    ): Boolean = a != null && b != null && abs(a - b) <= START_TOLERANCE_MS

    /**
     * Whether [ref] and the later [now] report one session's start: equal within the tolerance, or moved by the
     * wall-clock step between them, i.e. `abs((now.start - ref.start) - (now.wall - now.elapsed - (ref.wall -
     * ref.elapsed))) <= START_TOLERANCE_MS` in one boot with elapsed time increasing. The Room store's
     * session lookup mirrors this predicate in SQL.
     */
    fun sameStart(
        ref: SnapshotClock,
        now: SnapshotClock,
    ): Boolean {
        if (sameStart(ref.statsStartMs, now.statsStartMs)) return true
        val refStart = startElapsedMs(ref) ?: return false
        val nowStart = startElapsedMs(now) ?: return false
        return ref.bootCount == now.bootCount &&
            now.elapsedRealtimeMs > ref.elapsedRealtimeMs &&
            abs(nowStart - refStart) <= START_TOLERANCE_MS
    }

    /** The stats start on [clock]'s boot-relative elapsed clock: `statsStart - (wall - elapsed)`. */
    fun startElapsedMs(clock: SnapshotClock): Long? = clock.statsStartMs?.let { it - clock.wallMs + clock.elapsedRealtimeMs }

    fun same(
        ref: SnapshotClock,
        now: SnapshotClock,
    ): Boolean {
        val refDuration = ref.statsDurationMs ?: return false
        val nowDuration = now.statsDurationMs ?: return false
        return sameStart(ref, now) && nowDuration >= refDuration
    }
}
