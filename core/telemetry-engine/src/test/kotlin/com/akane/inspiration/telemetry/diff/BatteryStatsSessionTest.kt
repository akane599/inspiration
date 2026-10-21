package com.akane.inspiration.telemetry.diff

import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryStatsSessionTest {
    private val ref = clock(wall = 1_000_000, elapsed = 10_000, start = 900_000, duration = 1_000)

    @Test
    fun `a start moved by the wall-clock step between the clocks is one session`() {
        // The wall clock gained 5 s on the elapsed clock and the start moved by the same 5 s.
        val now = clock(wall = 1_000_000 + HOUR_MS + 5_000, elapsed = 10_000 + HOUR_MS, start = 905_000, duration = 2_000)

        assertFalse(BatteryStatsSession.sameStart(ref.statsStartMs, now.statsStartMs))
        assertTrue(BatteryStatsSession.sameStart(ref, now))
        assertTrue(BatteryStatsSession.same(ref, now))
    }

    @Test
    fun `the step correction keeps the start tolerance`() {
        // The clock stepped back 6 s, so the start should read 894_000; these are 2 s and 2.001 s off it.
        val withinTolerance = clock(wall = 1_000_000 + HOUR_MS - 6_000, elapsed = 10_000 + HOUR_MS, start = 896_000, duration = 2_000)
        val pastTolerance = clock(wall = 1_000_000 + HOUR_MS - 6_000, elapsed = 10_000 + HOUR_MS, start = 896_001, duration = 2_000)

        assertFalse(BatteryStatsSession.sameStart(ref.statsStartMs, withinTolerance.statsStartMs))
        assertTrue(BatteryStatsSession.sameStart(ref, withinTolerance))
        assertFalse(BatteryStatsSession.sameStart(ref, pastTolerance))
    }

    @Test
    fun `the step correction needs one boot with elapsed time increasing`() {
        val stepped = clock(wall = 1_000_000 + HOUR_MS + 5_000, elapsed = 10_000 + HOUR_MS, start = 905_000, duration = 2_000)

        assertFalse(BatteryStatsSession.sameStart(ref, stepped.copy(bootCount = 2)))
        assertFalse(BatteryStatsSession.sameStart(stepped, ref))
        assertFalse(BatteryStatsSession.sameStart(ref, ref.copy(wallMs = ref.wallMs + 5_000, statsStartMs = 905_000)))
    }

    @Test
    fun `an unchanged start within tolerance is one session across a reboot`() {
        val rebooted = clock(wall = 1_000_000 + HOUR_MS, elapsed = 5_000, start = 901_500, duration = 2_000).copy(bootCount = 2)

        assertTrue(BatteryStatsSession.sameStart(ref, rebooted))
    }

    @Test
    fun `a shorter duration or a missing start is never one session`() {
        val now = clock(wall = 1_000_000 + HOUR_MS + 5_000, elapsed = 10_000 + HOUR_MS, start = 905_000, duration = 999)

        assertFalse(BatteryStatsSession.same(ref, now))
        assertFalse(BatteryStatsSession.sameStart(ref, now.copy(statsStartMs = null)))
        assertNull(BatteryStatsSession.startElapsedMs(now.copy(statsStartMs = null)))
        assertEquals(-90_000L, BatteryStatsSession.startElapsedMs(ref))
    }

    private fun clock(
        wall: Long,
        elapsed: Long,
        start: Long,
        duration: Long,
    ) = SnapshotClock(wall, elapsed, elapsed / 2, 1, start, duration)

    private companion object {
        const val HOUR_MS = 60L * 60_000L
    }
}
