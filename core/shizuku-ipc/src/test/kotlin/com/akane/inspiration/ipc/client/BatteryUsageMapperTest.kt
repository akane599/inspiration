package com.akane.inspiration.ipc.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryUsageMapperTest {
    private val consumer = FakeUidConsumer(
        uid = 10_123,
        packageName = "com.example.drain",
        totalMah = 12.0,
        components = mapOf(0 to 5.0, 1 to 4.0, 11 to 3.0, 16 to 0.0, 17 to 0.5),
        processStates = mapOf(1 to 7.0, 2 to 3.0, 3 to 1.5, 4 to 0.5),
    )

    @Test
    fun `api 31 and 32 leave byProcessState null and never ask for it`() {
        for (sdk in listOf(31, 32)) {
            val fresh = FakeUidConsumer(1, null, 1.0, processStates = mapOf(1 to 1.0))
            val uid = BatteryUsageMapper.map(FakeBatteryUsage(uidConsumers = listOf(fresh)), sdk, 0L, 0L).uids.single()
            assertNull(uid.byProcessState)
            assertEquals(0, fresh.processStateCalls)
        }
    }

    @Test
    fun `api 33 maps process states by stable name`() {
        val uid = BatteryUsageMapper.map(FakeBatteryUsage(uidConsumers = listOf(consumer)), 33, 0L, 0L).uids.single()
        assertEquals(
            mapOf("FOREGROUND" to 7.0, "BACKGROUND" to 3.0, "FOREGROUND_SERVICE" to 1.5, "CACHED" to 0.5),
            uid.byProcessState,
        )
    }

    @Test
    fun `components use stable names and drop zero entries`() {
        val uid = BatteryUsageMapper.map(FakeBatteryUsage(uidConsumers = listOf(consumer)), 37, 0L, 0L).uids.single()
        assertEquals(
            mapOf("SCREEN" to 5.0, "CPU" to 4.0, "WIFI" to 3.0, "REATTRIBUTED_TO_OTHER_CONSUMERS" to 0.5),
            uid.components,
        )
        assertEquals(10_123, uid.uid)
        assertEquals("com.example.drain", uid.packageName)
        assertEquals(12.0, uid.totalMah, 0.0)
    }

    @Test
    fun `missing package name stays null`() {
        val noPackage = FakeUidConsumer(uid = 1_000, packageName = null, totalMah = 1.0)
        val uid = BatteryUsageMapper.map(FakeBatteryUsage(uidConsumers = listOf(noPackage)), 35, 0L, 0L).uids.single()
        assertNull(uid.packageName)
    }

    @Test
    fun `component ids match the framework constants`() {
        assertEquals(18, BatteryUsageMapper.COMPONENTS.size)
        assertEquals((0..17).toList(), BatteryUsageMapper.COMPONENTS.map { it.first })
        assertEquals(listOf(1, 2, 3, 4), BatteryUsageMapper.PROCESS_STATES.map { it.first })
    }
}
