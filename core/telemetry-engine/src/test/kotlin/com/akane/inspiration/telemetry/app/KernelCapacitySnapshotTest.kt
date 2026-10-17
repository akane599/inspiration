package com.akane.inspiration.telemetry.app

import com.akane.inspiration.ipc.model.KernelBatterySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KernelCapacitySnapshotTest {
    @Test
    fun `raw micro amp hours become mAh and the other nodes pass through`() {
        val snapshot = KernelCapacitySnapshot.from(
            kernel(
                chargeCounterRaw = 2_350_000L,
                chargeFullRaw = 4_700_000L,
                chargeFullDesignRaw = 5_000_000L,
                cycleCount = 212,
            ),
        )!!

        assertEquals(2_350.0, snapshot.chargeCounterMah!!, 1e-9)
        assertEquals(4_700.0, snapshot.chargeFullMah!!, 1e-9)
        assertEquals(5_000.0, snapshot.designMah!!, 1e-9)
        assertEquals(212, snapshot.cycleCount)
        assertEquals(48, snapshot.levelPct)
        assertEquals(312, snapshot.temperatureDeciC)
    }

    @Test
    fun `an unavailable or missing kernel read gives no snapshot`() {
        assertNull(KernelCapacitySnapshot.from(null))
        assertNull(KernelCapacitySnapshot.from(KernelBatterySnapshot.unavailable("no sysfs")))
    }

    @Test
    fun `non positive capacity nodes are dropped rather than shown as zero`() {
        val snapshot = KernelCapacitySnapshot.from(
            kernel(chargeCounterRaw = null, chargeFullRaw = 0L, chargeFullDesignRaw = null, cycleCount = null),
        )!!

        assertNull(snapshot.chargeCounterMah)
        assertNull(snapshot.chargeFullMah)
        assertNull(snapshot.designMah)
        assertNull(snapshot.cycleCount)
    }

    private fun kernel(
        chargeCounterRaw: Long?,
        chargeFullRaw: Long?,
        chargeFullDesignRaw: Long?,
        cycleCount: Int?,
    ) = KernelBatterySnapshot(
        available = true,
        reason = null,
        currentNowRaw = null,
        voltageNowRaw = null,
        chargeCounterRaw = chargeCounterRaw,
        chargeFullRaw = chargeFullRaw,
        chargeFullDesignRaw = chargeFullDesignRaw,
        cycleCount = cycleCount,
        temperatureDeciC = 312,
        status = null,
        capacityPercent = 48,
        sourcePath = null,
        readAtElapsedMs = 0L,
    )
}
