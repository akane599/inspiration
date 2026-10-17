package com.akane.inspiration.ipc.client

import com.akane.inspiration.ipc.model.BatteryUsageSnapshot
import com.akane.inspiration.ipc.model.UidUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UsageParcelBudgetTest {
    private fun uid(uid: Int, totalMah: Double) = UidUsage(uid, null, totalMah, emptyMap(), null)

    private fun snapshot(vararg uids: UidUsage) = BatteryUsageSnapshot(
        available = true,
        reason = null,
        statsStartMs = 0L,
        statsEndMs = 1L,
        dischargedPowerMah = 0.0,
        batteryTimeRemainingMs = 0L,
        chargeTimeRemainingMs = 0L,
        uids = uids.toList(),
    )

    /** 100 bytes per UID plus a 50-byte header. */
    private val sizeOf: (BatteryUsageSnapshot) -> Int = { 50 + it.uids.size * 100 }

    @Test
    fun `snapshot within budget is returned unchanged`() {
        val input = snapshot(uid(1, 1.0), uid(2, 2.0))

        val result = UsageParcelBudget.fit(input, sizeOf, maxBytes = 250)

        assertSame(input, result)
        assertEquals(0, result.truncatedUids)
    }

    @Test
    fun `over budget drops the lowest totalMah uids until it fits and keeps order`() {
        val input = snapshot(uid(1, 5.0), uid(2, 0.5), uid(3, 9.0), uid(4, 0.1), uid(5, 3.0), uid(6, 1.0))

        val result = UsageParcelBudget.fit(input, sizeOf, maxBytes = 350)

        assertEquals(listOf(1, 3, 5), result.uids.map { it.uid })
        assertEquals(3, result.truncatedUids)
    }

    @Test
    fun `equal totals drop in their original order`() {
        val input = snapshot(uid(1, 1.0), uid(2, 1.0), uid(3, 1.0))

        val result = UsageParcelBudget.fit(input, sizeOf, maxBytes = 250)

        assertEquals(listOf(2, 3), result.uids.map { it.uid })
        assertEquals(1, result.truncatedUids)
    }

    @Test
    fun `the default budget is 512 KiB`() {
        assertEquals(512 * 1024, UsageParcelBudget.MAX_BYTES)
        val input = snapshot(uid(1, 2.0), uid(2, 1.0))
        val result = UsageParcelBudget.fit(input, { if (it.uids.size > 1) 512 * 1024 + 1 else 512 * 1024 })
        assertEquals(listOf(1), result.uids.map { it.uid })
    }

    @Test
    fun `readUsage applies the budget to the mapped snapshot`() {
        val usage = FakeBatteryUsage(
            uidConsumers = listOf(
                FakeUidConsumer(uid = 10, packageName = "a", totalMah = 4.0),
                FakeUidConsumer(uid = 11, packageName = "b", totalMah = 0.2),
                FakeUidConsumer(uid = 12, packageName = "c", totalMah = 7.0),
            ),
        )
        val parcelBytes: (BatteryUsageSnapshot) -> Int = { if (it.uids.size > 2) Int.MAX_VALUE else 0 }

        val result = BatteryStatsClient({ fakeBinder }, 35, parcelBytes) { FakeBatteryStatsCalls(usage = usage) }
            .readUsage(0L)

        assertEquals(listOf(10, 12), result.uids.map { it.uid })
        assertEquals(1, result.truncatedUids)
    }

    @Test
    fun `stats duration falls back to end minus start and device components keep positive values`() {
        val withoutDuration = BatteryUsageMapper.map(
            stats = FakeBatteryUsage(statsStartMs = 1_000L, statsEndMs = 4_500L),
            sdkInt = 35,
            batteryTimeRemainingMs = 0L,
            chargeTimeRemainingMs = 0L,
        )
        assertEquals(3_500L, withoutDuration.statsDurationMs)
        assertEquals(emptyMap<String, Double>(), withoutDuration.deviceComponents)

        val device = mapOf(0 to 12.5, 1 to 30.0, 11 to 0.0)
        val withDuration = BatteryUsageMapper.map(
            stats = FakeBatteryUsage(
                statsDurationMs = 9_999L,
                deviceConsumer = DeviceConsumerAdapter { device[it] ?: 0.0 },
            ),
            sdkInt = 35,
            batteryTimeRemainingMs = 0L,
            chargeTimeRemainingMs = 0L,
        )
        assertEquals(9_999L, withDuration.statsDurationMs)
        assertEquals(mapOf("SCREEN" to 12.5, "CPU" to 30.0), withDuration.deviceComponents)
    }
}
