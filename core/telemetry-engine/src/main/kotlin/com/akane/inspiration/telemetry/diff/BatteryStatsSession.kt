package com.akane.inspiration.telemetry.diff

import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import kotlin.math.abs

/**
 * The single battery-stats session rule shared by the differ, the capturer and reference lookups.
 * `BatteryStatsImpl` shifts its reported start time on wall-clock steps, so starts within
 * [START_TOLERANCE_MS] belong to one session; a session's duration never decreases.
 */
object BatteryStatsSession {
    const val START_TOLERANCE_MS = 2_000L

    fun sameStart(
        a: Long?,
        b: Long?,
    ): Boolean = a != null && b != null && abs(a - b) <= START_TOLERANCE_MS

    fun same(
        ref: SnapshotClock,
        now: SnapshotClock,
    ): Boolean {
        val refDuration = ref.statsDurationMs ?: return false
        val nowDuration = now.statsDurationMs ?: return false
        return sameStart(ref.statsStartMs, now.statsStartMs) && nowDuration >= refDuration
    }
}
