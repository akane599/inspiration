package com.akane.inspiration.ipc.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryModelsTest {
    @Test
    fun `unavailable factories preserve reason and use empty result collections`() {
        val reason = Reasons.REMOTE

        val kernel = KernelBatterySnapshot.unavailable(reason)
        val cpu = CpuTimeSnapshot.unavailable(reason)
        val battery = BatteryUsageSnapshot.unavailable(reason)
        val power = PowerMonitorSnapshot.unavailable(reason)
        val idle = DeviceIdleSnapshot.unavailable(reason)
        val dump = DumpResult.unavailable(reason)

        assertUnavailable(kernel.available, kernel.reason, reason)
        assertUnavailable(cpu.available, cpu.reason, reason)
        assertUnavailable(battery.available, battery.reason, reason)
        assertUnavailable(power.available, power.reason, reason)
        assertUnavailable(idle.available, idle.reason, reason)
        assertUnavailable(dump.available, dump.reason, reason)
        assertTrue(cpu.entries.isEmpty())
        assertTrue(battery.uids.isEmpty())
        assertTrue(power.monitors.isEmpty())
        assertTrue(idle.powerSaveWhitelist.isEmpty())
        assertNull(dump.fd)
    }

    @Test
    fun `dump targets accept only pinned values`() {
        assertTrue(DumpTarget.isValid(DumpTarget.BATTERYSTATS_PROTO))
        assertTrue(DumpTarget.isValid(DumpTarget.ALARM_PROTO))
        // 2 (power proto) and 4 (deviceidle text) were removed and stay rejected.
        for (target in listOf(0, 2, 4, 5)) assertFalse("target $target", DumpTarget.isValid(target))
    }

    private fun assertUnavailable(
        available: Boolean,
        actualReason: String?,
        expectedReason: String,
    ) {
        assertFalse(available)
        assertEquals(expectedReason, actualReason)
    }
}
