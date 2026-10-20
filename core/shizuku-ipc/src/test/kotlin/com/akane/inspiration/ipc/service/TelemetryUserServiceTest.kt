package com.akane.inspiration.ipc.service

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import com.akane.inspiration.ipc.model.ClusterFreqTime
import com.akane.inspiration.ipc.model.DumpTarget
import com.akane.inspiration.ipc.model.Reasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileDescriptor
import java.nio.file.Files

class TelemetryUserServiceTest {
    @Test
    fun `single battery supply reads supported nodes and preserves raw values`() =
        withFixture { root ->
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
    fun `missing battery nodes remain null rather than becoming zero`() =
        withFixture { root ->
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
    fun `named battery supply wins over other Battery entries`() =
        withFixture { root ->
            battery(root, "BAT0") { node("capacity", "1") }
            battery(root, "battery") { node("capacity", "88") }

            val snapshot = service(root).readKernelBattery()

            assertTrue(snapshot.available)
            assertEquals(88, snapshot.capacityPercent)
            assertEquals("${root.path}/sys/class/power_supply/battery", snapshot.sourcePath)
        }

    @Test
    fun `fallback skips scope Device peripheral batteries`() =
        withFixture { root ->
            battery(root, "hidpp_battery_0") {
                node("scope", "Device")
                node("capacity", "5")
            }
            battery(root, "main_bat") {
                node("scope", "System")
                node("capacity", "70")
            }

            val snapshot = service(root).readKernelBattery()

            assertTrue(snapshot.available)
            assertEquals(70, snapshot.capacityPercent)
            assertEquals("${root.path}/sys/class/power_supply/main_bat", snapshot.sourcePath)
        }

    @Test
    fun `only scope Device batteries yields unavailable`() =
        withFixture { root ->
            battery(root, "hidpp_battery_0") {
                node("scope", "Device")
                node("capacity", "5")
            }

            assertFalse(service(root).readKernelBattery().available)
        }

    @Test
    fun `time in state converts ten millisecond ticks for every policy`() =
        withFixture { root ->
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
    fun `wrong caller is rejected before any telemetry or dump work`() =
        withFixture { root ->
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
    fun `wrong caller is rejected before battery history touches any binder`() =
        withFixture { root ->
            val requested = mutableListOf<String>()
            val service =
                service(root, callingUid = { APP_UID + 1 }, serviceBinder = { name ->
                    requested += name
                    null
                })

            val history = service.readBatteryHistory(1_000L, 0L, 4096)

            assertFalse(history.available)
            assertEquals(Reasons.CALLER, history.reason)
            assertEquals(emptyList<String>(), requested)
        }

    @Test
    fun `authorized battery history reaches the batterystats binder`() =
        withFixture { root ->
            val requested = mutableListOf<String>()
            val service =
                service(root, serviceBinder = { name ->
                    requested += name
                    null
                })

            val history = service.readBatteryHistory(1_000L, 0L, 4096)

            assertEquals(listOf("batterystats"), requested)
            assertFalse(history.available)
            assertEquals(Reasons.REMOTE, history.reason)
        }

    @Test
    fun `unknown dump target is rejected for an authorized caller`() =
        withFixture { root ->
            val dump = service(root).openDump(99)

            assertFalse(dump.available)
            assertEquals(Reasons.TARGET, dump.reason)
            assertNull(dump.fd)
        }

    @Test
    fun `removed power and deviceidle dump targets are rejected before any binder lookup`() =
        withFixture { root ->
            val requested = mutableListOf<String>()
            val service =
                service(root, serviceBinder = { name ->
                    requested += name
                    null
                })

            for (target in listOf(2, 4)) {
                val dump = service.openDump(target)
                assertFalse(dump.available)
                assertEquals(Reasons.TARGET, dump.reason)
            }
            assertEquals(emptyList<String>(), requested)
        }

    @Test
    fun `thread start failure closes both pipe ends and reports IO`() =
        withFixture { root ->
            val pipe = FakeDumpPipe()

            val result =
                service(
                    root = root,
                    serviceBinder = { ThrowingDumpBinder },
                    pipeFactory = { pipe },
                    startThread = { throw OutOfMemoryError("pthread_create failed") },
                ).openDump(DumpTarget.BATTERYSTATS_PROTO)

            assertFalse(result.available)
            assertEquals(Reasons.IO, result.reason)
            assertNull(result.fd)
            assertTrue(pipe.readClosed)
            assertTrue(pipe.writeClosed)
        }

    @Test
    fun `dump exception is contained and closes the write pipe end`() =
        withFixture { root ->
            val pipe = FakeDumpPipe()

            val result =
                service(
                    root = root,
                    serviceBinder = { ThrowingDumpBinder },
                    pipeFactory = { pipe },
                    startThread = Runnable::run,
                ).openDump(DumpTarget.BATTERYSTATS_PROTO)

            assertTrue(result.available)
            assertTrue(pipe.writeClosed)
        }

    private fun service(
        root: File,
        callingUid: () -> Int = { APP_UID },
        serviceBinder: (String) -> IBinder? = { null },
        pipeFactory: (() -> DumpPipe)? = null,
        startThread: ((Runnable) -> Unit)? = null,
    ): TelemetryUserService =
        TelemetryUserService(
            appUid = APP_UID,
            fileRoot = root,
            callingUid = callingUid,
            elapsedRealtimeMs = { 42L },
            serviceBinder = serviceBinder,
            pipeFactory = pipeFactory ?: { FakeDumpPipe() },
            startThread = startThread ?: Runnable::run,
        )

    private class FakeDumpPipe : DumpPipe {
        override val readEnd = null
        override val writeDescriptor = FileDescriptor()
        var readClosed = false
        var writeClosed = false

        override fun closeRead() {
            readClosed = true
        }

        override fun closeWrite() {
            writeClosed = true
        }
    }

    private object ThrowingDumpBinder : IBinder {
        override fun dump(
            fd: FileDescriptor,
            args: Array<out String>?,
        ): Unit = throw RemoteException()

        override fun dumpAsync(
            fd: FileDescriptor,
            args: Array<out String>?,
        ) = Unit

        override fun getInterfaceDescriptor(): String = ""

        override fun isBinderAlive(): Boolean = true

        override fun linkToDeath(
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ) = Unit

        override fun pingBinder(): Boolean = true

        override fun queryLocalInterface(descriptor: String): IInterface? = null

        override fun transact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean = false

        override fun unlinkToDeath(
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ): Boolean = true
    }

    private fun battery(
        root: File,
        name: String,
        configure: FixtureDirectory.() -> Unit,
    ) {
        FixtureDirectory(File(root, "sys/class/power_supply/$name")).apply {
            node("type", "Battery")
            configure()
        }
    }

    private fun timeInState(
        root: File,
        policy: String,
        content: String,
    ) {
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

    private class FixtureDirectory(
        private val directory: File,
    ) {
        fun node(
            name: String,
            content: String,
        ) {
            directory.mkdirs()
            File(directory, name).writeText(content)
        }
    }

    private companion object {
        const val APP_UID = 10_001
    }
}
