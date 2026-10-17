package com.akane.inspiration.ipc.service

import com.akane.inspiration.ipc.model.ClusterFreqTime
import com.akane.inspiration.ipc.model.DumpTarget
import com.akane.inspiration.ipc.model.Reasons
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryUserServiceTest {
    @Test
    fun `single battery supply reads supported nodes and preserves raw values`() = withFixture { root ->
        battery(root, "battery") {
            node("current_now", "-234000")
            node("voltage_now", "4012000")
            node("charge_counter", "3100000")
            node("charge_full", "5000000")
            node("charge_full_design", "5200000")
            node("cycle_count", "117")
            node("temp", "295")
            node("status", "Charging")
            node("capacity", "62")
        }

        val snapshot = service(root).readKernelBattery()

        assertTrue(snapshot.available)
        assertNull(snapshot.reason)
        assertEquals(-234000L, snapshot.currentNowRaw)
        assertEquals(4012000L, snapshot.voltageNowRaw)
        assertEquals(3100000L, snapshot.chargeCounterRaw)
        assertEquals(5000000L, snapshot.chargeFullRaw)
        assertEquals(5200000L, snapshot.chargeFullDesignRaw)
        assertEquals(117, snapshot.cycleCount)
        assertEquals(295, snapshot.temperatureDeciC)
        assertEquals("Charging", snapshot.status)
        assertEquals(62, snapshot.capacityPercent)
        assertEquals("${root.path}/sys/class/power_supply/battery", snapshot.sourcePath)
        assertEquals(42L, snapshot.readAtElapsedMs)
    }

    @Test
    fun `missing battery nodes remain null rather than becoming zero`() = withFixture { root ->
        battery(root, "battery") {
            node("status", "Discharging")
        }

        val snapshot = service(root).readKernelBattery()

        assertTrue(snapshot.available)
        assertEquals("Discharging", snapshot.status)
        assertNull(snapshot.currentNowRaw)
        assertNull(snapshot.chargeFullRaw)
        assertNull(snapshot.capacityPercent)
    }

    @Test
    fun `named battery supply wins over other Battery entries`() = withFixture { root ->
        battery(root, "BAT0") { node("capacity", "1") }
        battery(root, "battery") { node("capacity", "88") }

        val snapshot = service(root).readKernelBattery()

        assertTrue(snapshot.available)
        assertEquals(88, snapshot.capacityPercent)
        assertEquals("${root.path}/sys/class/power_supply/battery", snapshot.sourcePath)
    }

    @Test
    fun `time in state converts ten millisecond ticks for every policy`() = withFixture { root ->
        timeInState(root, "policy0", "300000 4\n600000 9\n")
        timeInState(root, "policy4", "1000000 2\n")

        val snapshot = service(root).readCpuTimeInState()

        assertTrue(snapshot.available)
        assertEquals(
            listOf(
                ClusterFreqTime(policy = 0, freqKhz = 300000L, timeMs = 40L),
                ClusterFreqTime(policy = 0, freqKhz = 600000L, timeMs = 90L),
                ClusterFreqTime(policy = 4, freqKhz = 1000000L, timeMs = 20L),
            ),
            snapshot.entries,
        )
    }

    @Test
    fun `wrong caller is rejected before any telemetry or dump work`() = withFixture { root ->
        battery(root, "battery") { node("capacity", "88") }
        val service = service(root, callingUid = { APP_UID + 1 })

        val battery = service.readKernelBattery()
        val cpu = service.readCpuTimeInState()
        val dump = service.openDump(DumpTarget.BATTERYSTATS_PROTO)

        assertFalse(battery.available)
        assertEquals(Reasons.CALLER, battery.reason)
        assertFalse(cpu.available)
        assertEquals(Reasons.CALLER, cpu.reason)
        assertFalse(dump.available)
        assertEquals(Reasons.CALLER, dump.reason)
    }

    @Test
    fun `unknown dump target is rejected for an authorized caller`() = withFixture { root ->
        val dump = service(root).openDump(99)

        assertFalse(dump.available)
        assertEquals(Reasons.TARGET, dump.reason)
        assertNull(dump.fd)
    }

    private fun service(root: File, callingUid: () -> Int = { APP_UID }): TelemetryUserService =
        TelemetryUserService(
            appUid = APP_UID,
            fileRoot = root,
            callingUid = callingUid,
            elapsedRealtimeMs = { 42L },
        )

    private fun battery(root: File, name: String, configure: FixtureDirectory.() -> Unit) {
        FixtureDirectory(File(root, "sys/class/power_supply/$name")).apply {
            node("type", "Battery")
            configure()
        }
    }

    private fun timeInState(root: File, policy: String, content: String) {
        FixtureDirectory(File(root, "sys/devices/system/cpu/cpufreq/$policy/stats")).node("time_in_state", content)
    }

    private fun withFixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("telemetry-user-service").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private class FixtureDirectory(private val directory: File) {
        fun node(name: String, content: String) {
            directory.mkdirs()
            File(directory, name).writeText(content)
        }
    }

    private companion object {
        const val APP_UID = 10_001
    }
}
