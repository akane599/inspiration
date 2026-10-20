package com.akane.inspiration.capture

import android.content.Intent
import com.akane.inspiration.diagnostics.rules.MIN_SCREEN_OFF_MS
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePolicyTest {
    /** Audit R-03: the screen-off interval needs batterystats and per-UID data at its start. */
    @Test
    fun `screen off is full, binding the service on demand when shizuku is ready`() {
        val full = CapturePlan.Full(CaptureTrigger.SCREEN_OFF, CapturePolicy.BROADCAST_FULL_DEADLINE_MS)
        assertEquals(full, CapturePolicy.forBroadcast(Intent.ACTION_SCREEN_OFF, serviceWarm = true))
        assertEquals(full, CapturePolicy.forBroadcast(Intent.ACTION_SCREEN_OFF, serviceWarm = true, shizukuReady = true))
        assertEquals(
            CapturePlan.Full(CaptureTrigger.SCREEN_OFF, CapturePolicy.BROADCAST_BIND_FULL_DEADLINE_MS),
            CapturePolicy.forBroadcast(Intent.ACTION_SCREEN_OFF, serviceWarm = false, shizukuReady = true),
        )
        // Without a granted, running Shizuku a bind can only time out: no wait, no miss row.
        assertEquals(
            CapturePlan.Light(CaptureTrigger.SCREEN_OFF),
            CapturePolicy.forBroadcast(Intent.ACTION_SCREEN_OFF, serviceWarm = false, shizukuReady = false),
        )
    }

    /** Audit R-03: the end anchor needs batterystats too, but only a night the rules evaluate is worth a bind. */
    @Test
    fun `screen on binds on demand only after a screen-off period the rules evaluate`() {
        fun plan(
            warm: Boolean,
            ready: Boolean,
            offForMs: Long?,
        ) = CapturePolicy.forBroadcast(Intent.ACTION_SCREEN_ON, serviceWarm = warm, shizukuReady = ready, screenOffForMs = offForMs)

        val bind = CapturePlan.Full(CaptureTrigger.SCREEN_ON, CapturePolicy.BROADCAST_BIND_FULL_DEADLINE_MS)
        val light = CapturePlan.Light(CaptureTrigger.SCREEN_ON)
        assertEquals(bind, plan(warm = false, ready = true, offForMs = MIN_SCREEN_OFF_MS))
        assertEquals(bind, plan(warm = false, ready = true, offForMs = 8 * 60 * 60_000L))
        assertEquals(light, plan(warm = false, ready = true, offForMs = MIN_SCREEN_OFF_MS - 1))
        assertEquals(light, plan(warm = false, ready = true, offForMs = null))
        assertEquals(light, plan(warm = false, ready = false, offForMs = 8 * 60 * 60_000L))
        // Warm, as before: the service is bound already, so a FULL read costs no bind.
        assertEquals(CapturePlan.Full(CaptureTrigger.SCREEN_ON, CapturePolicy.BROADCAST_FULL_DEADLINE_MS), plan(warm = true, ready = true, offForMs = 1L))

        assertTrue(CapturePolicy.needsScreenOffDuration(Intent.ACTION_SCREEN_ON, serviceWarm = false, shizukuReady = true))
        assertFalse(CapturePolicy.needsScreenOffDuration(Intent.ACTION_SCREEN_ON, serviceWarm = true, shizukuReady = true))
        assertFalse(CapturePolicy.needsScreenOffDuration(Intent.ACTION_SCREEN_ON, serviceWarm = false, shizukuReady = false))
        assertFalse(CapturePolicy.needsScreenOffDuration(Intent.ACTION_SCREEN_OFF, serviceWarm = false, shizukuReady = true))
    }

    @Test
    fun `screen-off duration comes from the newest screen-off capture of this boot after the newest screen-on one`() {
        fun clock(elapsedMs: Long, boot: Int = 3) = SnapshotClock(1_000_000L + elapsedMs, elapsedMs, elapsedMs / 2, boot, null, null)
        val off = clock(10_000L)
        val on = clock(5_000L)

        assertEquals(40_000L, CapturePolicy.screenOffForMs(off, on, nowElapsedMs = 50_000L, bootCount = 3))
        assertEquals(40_000L, CapturePolicy.screenOffForMs(off, null, nowElapsedMs = 50_000L, bootCount = 3))
        // A screen-on capture of an earlier boot says nothing about this one.
        assertEquals(40_000L, CapturePolicy.screenOffForMs(off, clock(20_000L, boot = 2), nowElapsedMs = 50_000L, bootCount = 3))
        // A screen-on capture after it: its screen-off capture was missed (or is still in flight), so unknown.
        assertNull(CapturePolicy.screenOffForMs(off, clock(20_000L), nowElapsedMs = 50_000L, bootCount = 3))
        assertNull(CapturePolicy.screenOffForMs(null, on, nowElapsedMs = 50_000L, bootCount = 3))
        // A screen-off capture of another boot: elapsed time is not comparable.
        assertNull(CapturePolicy.screenOffForMs(clock(10_000L, boot = 2), null, nowElapsedMs = 50_000L, bootCount = 3))
        assertNull(CapturePolicy.screenOffForMs(off, null, nowElapsedMs = 9_000L, bootCount = 3))
    }

    @Test
    fun `a bind-on-demand plan stays inside the receiver's 10 s limit`() {
        val guard = CapturePolicy.broadcastGuardMs(CapturePlan.Full(CaptureTrigger.SCREEN_OFF, CapturePolicy.BROADCAST_BIND_FULL_DEADLINE_MS))
        assertTrue("guard $guard ms", guard <= 8_000L)
        // The bind deadline leaves room for a read after the source's own bind timeout would let a slow bind finish.
        assertTrue(CapturePolicy.BROADCAST_BIND_FULL_DEADLINE_MS > CapturePolicy.BROADCAST_FULL_DEADLINE_MS)
    }

    @Test
    fun `power broadcast is full only when telemetry service is warm`() {
        assertEquals(
            CapturePlan.Full(CaptureTrigger.POWER_CONNECTED, CapturePolicy.BROADCAST_FULL_DEADLINE_MS),
            CapturePolicy.forBroadcast(Intent.ACTION_POWER_CONNECTED, serviceWarm = true),
        )
        assertEquals(
            CapturePlan.Light(CaptureTrigger.POWER_CONNECTED),
            CapturePolicy.forBroadcast(Intent.ACTION_POWER_CONNECTED, serviceWarm = false),
        )
        // Only the screen broadcasts bind on demand.
        assertEquals(
            CapturePlan.Light(CaptureTrigger.POWER_CONNECTED),
            CapturePolicy.forBroadcast(Intent.ACTION_POWER_CONNECTED, serviceWarm = false, shizukuReady = true, screenOffForMs = Long.MAX_VALUE),
        )
    }

    @Test
    fun `unknown broadcasts are ignored`() {
        assertNull(CapturePolicy.forBroadcast("unknown", serviceWarm = true))
    }

    @Test
    fun `boot arm has no latency while job rearm waits`() {
        assertNull(ChargingCaptureScheduler.minimumLatencyMs(reArmFromJob = false))
        assertEquals(
            ChargingCaptureScheduler.MINIMUM_LATENCY_MS,
            ChargingCaptureScheduler.minimumLatencyMs(reArmFromJob = true),
        )
    }

    @Test
    fun `charge job keys idempotency to plugged history and boot session`() {
        assertTrue(
            CapturePolicy.chargeJobShouldRun(
                lastPluggedHistoryWallMs = 200L,
                lastPowerConnectedWallMs = null,
                lastChargeJobWallMs = 100L,
                lastChargeJobBootCount = 4,
                currentBootCount = 4,
                historyComplete = true,
            ),
        )
        assertFalse(
            CapturePolicy.chargeJobShouldRun(
                lastPluggedHistoryWallMs = 200L,
                lastPowerConnectedWallMs = null,
                lastChargeJobWallMs = 200L,
                lastChargeJobBootCount = 4,
                currentBootCount = 4,
                historyComplete = true,
            ),
        )
        assertTrue(
            CapturePolicy.chargeJobShouldRun(
                lastPluggedHistoryWallMs = null,
                lastPowerConnectedWallMs = null,
                lastChargeJobWallMs = 200L,
                lastChargeJobBootCount = 4,
                currentBootCount = 5,
                historyComplete = true,
            ),
        )
        assertTrue(
            CapturePolicy.chargeJobShouldRun(
                lastPluggedHistoryWallMs = null,
                lastPowerConnectedWallMs = 200L,
                lastChargeJobWallMs = 100L,
                lastChargeJobBootCount = 4,
                currentBootCount = 4,
                historyComplete = false,
            ),
        )
    }
}
