package com.akane.inspiration.telemetry.status

import org.junit.Assert.assertEquals
import org.junit.Test

class ChargeStateTest {
    @Test
    fun `unplugged is discharging whatever the status says`() {
        assertEquals(ChargeState.Discharging, chargeState(plugged = false, status = "charging", currentMa = 500))
    }

    @Test
    fun `a charging status on the plug is charging`() {
        assertEquals(ChargeState.Charging, chargeState(plugged = true, status = "charging", currentMa = 800))
        assertEquals("unknown current", ChargeState.Charging, chargeState(plugged = true, status = "charging", currentMa = null))
    }

    @Test
    fun `a charging status losing to the load past the deadband is plugged not charging`() {
        assertEquals(ChargeState.PluggedNotCharging, chargeState(plugged = true, status = "charging", currentMa = -50))
        assertEquals("inside the deadband", ChargeState.Charging, chargeState(plugged = true, status = "charging", currentMa = -49))
    }

    @Test
    fun `a full held or draining battery on the plug is plugged not charging`() {
        for (status in listOf("full", "not_charging", "discharging")) {
            assertEquals(status, ChargeState.PluggedNotCharging, chargeState(plugged = true, status = status, currentMa = 0))
        }
    }

    @Test
    fun `a discharging status with an unknown plug is discharging`() {
        assertEquals(ChargeState.Discharging, chargeState(plugged = null, status = "discharging", currentMa = -300))
    }

    @Test
    fun `a charging status with an unknown plug is charging`() {
        assertEquals(ChargeState.Charging, chargeState(plugged = null, status = "charging", currentMa = 300))
    }

    @Test
    fun `without a usable status the plug decides and a measured drain still overrides it`() {
        assertEquals(ChargeState.Charging, chargeState(plugged = true, status = "unknown", currentMa = 10))
        assertEquals(ChargeState.PluggedNotCharging, chargeState(plugged = true, status = null, currentMa = -60))
    }

    @Test
    fun `with neither plug nor status only a clear charge current is charging`() {
        assertEquals(ChargeState.Charging, chargeState(plugged = null, status = null, currentMa = 50))
        assertEquals(ChargeState.Discharging, chargeState(plugged = null, status = null, currentMa = 20))
        assertEquals(ChargeState.Discharging, chargeState(plugged = null, status = null, currentMa = null))
    }

    @Test
    fun `a label the current decided holds until the current is back inside the deadband by the hysteresis`() {
        val pnc = ChargeState.PluggedNotCharging
        assertEquals(pnc, chargeState(plugged = true, status = "charging", currentMa = -45, previous = pnc))
        assertEquals(ChargeState.Charging, chargeState(plugged = true, status = "charging", currentMa = -39, previous = pnc))
        assertEquals(
            ChargeState.Charging,
            chargeState(plugged = null, status = null, currentMa = 45, previous = ChargeState.Charging),
        )
    }
}
