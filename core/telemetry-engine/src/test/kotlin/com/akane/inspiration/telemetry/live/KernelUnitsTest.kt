package com.akane.inspiration.telemetry.live

import com.akane.inspiration.ipc.model.KernelBatterySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KernelUnitsTest {
    @Test
    fun `microamp hours and microamps convert to milli units`() {
        assertEquals(4_850.5, KernelUnits.uahToMah(4_850_500L)!!, 1e-9)
        assertEquals(-312.25, KernelUnits.uaToMa(-312_250L)!!, 1e-9)
    }

    @Test
    fun `null and zero pass through unconverted`() {
        assertNull(KernelUnits.uahToMah(null))
        assertNull(KernelUnits.uaToMa(null))
        assertEquals(0.0, KernelUnits.uahToMah(0L)!!, 0.0)
        assertEquals(0.0, KernelUnits.uaToMa(0L)!!, 0.0)
        assertNull(KernelUnits.normalizeCurrentMa(null, true))
        assertEquals(0, KernelUnits.normalizeCurrentMa(0, true))
        assertEquals(0, KernelUnits.normalizeCurrentMa(0, false))
    }

    @Test
    fun `kernel that reports discharge as negative is left unchanged`() {
        assertEquals(-420, KernelUnits.normalizeCurrentMa(-420, charging = false))
        assertEquals(1_800, KernelUnits.normalizeCurrentMa(1_800, charging = true))
    }

    @Test
    fun `kernel that reports discharge as positive is flipped so discharge is negative`() {
        assertEquals(-420, KernelUnits.normalizeCurrentMa(420, charging = false))
        assertEquals(1_800, KernelUnits.normalizeCurrentMa(-1_800, charging = true))
    }

    @Test
    fun `unknown charging state leaves the sign alone`() {
        assertEquals(420, KernelUnits.normalizeCurrentMa(420, charging = null))
        assertEquals(-420, KernelUnits.normalizeCurrentMa(-420, charging = null))
    }

    @Test
    fun `kernel capacity converts sysfs micro amp hours and drops non positive nodes`() {
        val capacity = KernelUnits.kernelCapacity(
            kernel(chargeFullRaw = 4_700_000L, chargeFullDesignRaw = 5_000_000L, cycleCount = 212),
        )

        assertEquals(4_700.0, capacity.chargeFullMah!!, 1e-9)
        assertEquals(5_000.0, capacity.designMah!!, 1e-9)
        assertEquals(212, capacity.cycleCount)

        val missing = KernelUnits.kernelCapacity(kernel(chargeFullRaw = 0L, chargeFullDesignRaw = null, cycleCount = null))
        assertNull(missing.chargeFullMah)
        assertNull(missing.designMah)
        assertNull(missing.cycleCount)
    }

    private fun kernel(chargeFullRaw: Long?, chargeFullDesignRaw: Long?, cycleCount: Int?) = KernelBatterySnapshot(
        available = true,
        reason = null,
        currentNowRaw = null,
        voltageNowRaw = null,
        chargeCounterRaw = null,
        chargeFullRaw = chargeFullRaw,
        chargeFullDesignRaw = chargeFullDesignRaw,
        cycleCount = cycleCount,
        temperatureDeciC = null,
        status = null,
        capacityPercent = null,
        sourcePath = null,
        readAtElapsedMs = 0L,
    )
}
