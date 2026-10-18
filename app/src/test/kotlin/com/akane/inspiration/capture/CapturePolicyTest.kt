package com.akane.inspiration.capture

import android.content.Intent
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePolicyTest {
    @Test
    fun `screen broadcasts always take synchronous light plan`() {
        assertEquals(
            CapturePlan.Light(CaptureTrigger.SCREEN_ON),
            CapturePolicy.forBroadcast(Intent.ACTION_SCREEN_ON, serviceWarm = false),
        )
        assertEquals(
            CapturePlan.Light(CaptureTrigger.SCREEN_OFF),
            CapturePolicy.forBroadcast(Intent.ACTION_SCREEN_OFF, serviceWarm = true),
        )
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
                historyAvailable = true,
            ),
        )
        assertFalse(
            CapturePolicy.chargeJobShouldRun(
                lastPluggedHistoryWallMs = 200L,
                lastPowerConnectedWallMs = null,
                lastChargeJobWallMs = 200L,
                lastChargeJobBootCount = 4,
                currentBootCount = 4,
                historyAvailable = true,
            ),
        )
        assertTrue(
            CapturePolicy.chargeJobShouldRun(
                lastPluggedHistoryWallMs = null,
                lastPowerConnectedWallMs = null,
                lastChargeJobWallMs = 200L,
                lastChargeJobBootCount = 4,
                currentBootCount = 5,
                historyAvailable = true,
            ),
        )
        assertTrue(
            CapturePolicy.chargeJobShouldRun(
                lastPluggedHistoryWallMs = null,
                lastPowerConnectedWallMs = 200L,
                lastChargeJobWallMs = 100L,
                lastChargeJobBootCount = 4,
                currentBootCount = 4,
                historyAvailable = false,
            ),
        )
    }
}
