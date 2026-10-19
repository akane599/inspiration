package com.akane.inspiration.ipc.service

import android.os.Process
import com.akane.inspiration.ipc.model.BgDexoptReason
import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.model.ToolkitApi
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.model.ToolkitStatus
import com.akane.inspiration.ipc.toolkit.BgDexoptController
import com.akane.inspiration.ipc.toolkit.CMD
import com.akane.inspiration.ipc.toolkit.CommandRunner
import com.akane.inspiration.ipc.toolkit.FakeDexoptShell
import com.akane.inspiration.ipc.toolkit.FakeInspector
import com.akane.inspiration.ipc.toolkit.FakeRunner
import com.akane.inspiration.ipc.toolkit.TEST_SDK_INT
import com.akane.inspiration.ipc.toolkit.ThrowingRunner
import com.akane.inspiration.ipc.toolkit.ToolkitExecutor
import com.akane.inspiration.ipc.toolkit.ok
import com.akane.inspiration.ipc.toolkit.userApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TelemetryUserServiceToolkitTest {
    private val spawn = ThrowingRunner()

    private fun toolkit(
        runner: CommandRunner = spawn,
        inspector: FakeInspector = FakeInspector(mutableMapOf("com.example.app" to userApp())),
    ) = ToolkitExecutor(runner, inspector, BgDexoptController(runner, { true }, { 0L }, { }, TEST_SDK_INT), "com.akane.inspiration") { }

    private fun service(
        appUid: Int?,
        callingUid: () -> Int,
        toolkit: ToolkitExecutor,
    ) = TelemetryUserService(appUid = appUid, fileRoot = File("/"), callingUid = callingUid, toolkit = toolkit)

    private fun everyWrite(service: TelemetryUserService): List<ToolkitResult> =
        listOf(
            service.setAppOp("com.example.app", 3, 1),
            service.getAppOps("com.example.app"),
            service.setStandbyBucket("com.example.app", 5),
            service.getStandbyBucket("com.example.app"),
            service.applyDozeProfile(1, null),
            service.getDozeState(),
            service.forceStop("com.example.app"),
            service.startBgDexopt(),
            service.cancelBgDexopt(),
            service.restoreDozeRaw(null, null),
        )

    @Test
    fun `a wrong UID is refused on every typed transaction with no process spawned`() {
        val service = service(appUid = APP_UID, callingUid = { APP_UID + 1 }, toolkit = toolkit())

        everyWrite(service).forEach {
            assertEquals(ToolkitResult.refused(ToolkitCode.CALLER), it)
        }
        val status = service.getBgDexoptStatus()
        assertEquals(BgDexoptState.UNKNOWN, status.state)
        assertEquals(BgDexoptReason.CALLER, status.reason)
        assertEquals(-1, service.getApiVersion())
        assertEquals(0, spawn.starts)
    }

    @Test
    fun `an unresolved application UID refuses every typed transaction with no process spawned`() {
        val service = service(appUid = null, callingUid = { APP_UID }, toolkit = toolkit())

        everyWrite(service).forEach {
            assertEquals(ToolkitStatus.REFUSED, it.status)
            assertEquals(ToolkitCode.CALLER, it.code)
        }
        assertEquals(-1, service.getApiVersion())
        assertEquals(0, spawn.starts)
    }

    @Test
    fun `a throwing caller lookup is treated as denied`() {
        val service = service(appUid = APP_UID, callingUid = { throw IllegalStateException() }, toolkit = toolkit())

        everyWrite(service).forEach { assertEquals(ToolkitCode.CALLER, it.code) }
        assertEquals(0, spawn.starts)
    }

    @Test
    fun `the default service has no Context and fails closed`() {
        val service = TelemetryUserService(appUid = APP_UID, fileRoot = File("/"), callingUid = { APP_UID })

        val result = service.setAppOp("com.example.app", 3, 1)

        assertEquals(ToolkitStatus.REFUSED, result.status)
        assertEquals(ToolkitCode.PROTECTION_UNKNOWN, result.code)
    }

    @Test
    fun `the allowed caller reaches the executor and the api version is 4`() {
        val runner =
            FakeRunner { argv ->
                if (argv.contains("get")) ok("WAKE_LOCK: default") else ok()
            }
        val service = service(APP_UID, { APP_UID }, toolkit(runner))

        // 4: a stale running v3 service would still send a bg-dexopt cancel that does not cancel on API 31-33.
        assertEquals(4, ToolkitApi.VERSION)
        assertEquals(4, service.getApiVersion())
        service.setAppOp("com.example.app", 3, 1)

        assertTrue(listOf(CMD, "appops", "set", "--user", "0", "com.example.app", "WAKE_LOCK", "ignore") in runner.calls)
        assertEquals(BgDexoptState.UNKNOWN, service.getBgDexoptStatus().state)
    }

    // --- destroy(): caller gate, dexopt cancel, exit hook ---------------------------------------

    /**
     * One ordered log shared by the fake runner and the exit hook, so ordering is observable. The runner answers
     * like `cmd package` on [sdkInt] and logs what each command did there ("cancel", "pass", ...).
     */
    private class Harness(
        sdkInt: Int,
    ) {
        val events = mutableListOf<String>()
        val shell = FakeDexoptShell(sdkInt)
        val runner =
            FakeRunner { argv ->
                events += shell.effect(argv).name.lowercase()
                shell.handle(argv)
            }
        var caller = APP_UID
        val service =
            TelemetryUserService(
                appUid = APP_UID,
                fileRoot = File("/"),
                callingUid = { caller },
                toolkit =
                    ToolkitExecutor(
                        runner,
                        FakeInspector(),
                        BgDexoptController(runner, { true }, { 0L }, { }, sdkInt),
                        "com.akane.inspiration",
                    ) { },
                exitProcess = { events += "exit" },
            )
    }

    private fun harness(sdkInt: Int = TEST_SDK_INT) = Harness(sdkInt)

    @Test
    fun `destroy cancels a running bg-dexopt job with a bounded wait before exiting`() {
        for (sdk in 33..36) {
            val h = harness(sdk)
            h.service.startBgDexopt()
            assertEquals(BgDexoptState.RUNNING, h.service.getBgDexoptStatus().state)
            h.shell.running = true // the worker has the job under way

            h.service.destroy()

            assertEquals("API $sdk", 1, h.runner.calls.size)
            assertEquals("API $sdk: the system job was cancelled", 1, h.shell.cancels)
            assertFalse("API $sdk", h.shell.running)
            assertTrue("cancel wait is bounded to about 3 s", h.runner.timeouts.single() in 1L..3_000L)
            assertEquals("cancel runs before the exit hook", listOf("cancel", "exit"), h.events)
        }
    }

    @Test
    fun `below API 33 the service starts no bg-dexopt job, so destroy has nothing to cancel`() {
        for (sdk in listOf(31, 32)) {
            val h = harness(sdk)

            val start = h.service.startBgDexopt()
            val cancel = h.service.cancelBgDexopt()
            val status = h.service.getBgDexoptStatus()
            h.service.destroy()

            assertEquals(ToolkitResult.refused(ToolkitCode.UNSUPPORTED_API), start)
            assertEquals(ToolkitResult.refused(ToolkitCode.UNSUPPORTED_API), cancel)
            assertEquals(BgDexoptState.UNKNOWN, status.state)
            assertEquals(BgDexoptReason.UNSUPPORTED_API, status.reason)
            assertTrue("API $sdk: no command ran", h.runner.calls.isEmpty())
            assertEquals(listOf("exit"), h.events)
        }
    }

    @Test
    fun `destroy issues no command when no job is running and still exits`() {
        val h = harness()

        h.service.destroy()

        assertTrue(h.runner.calls.isEmpty())
        assertEquals(listOf("exit"), h.events)
    }

    @Test
    fun `destroy still exits when the cancel cannot be spawned`() {
        val spawnFails = ThrowingRunner()
        var exited = false
        val service =
            TelemetryUserService(
                appUid = APP_UID,
                fileRoot = File("/"),
                callingUid = { APP_UID },
                toolkit = toolkit(spawnFails),
                exitProcess = { exited = true },
            )
        service.startBgDexopt()

        service.destroy()

        assertEquals("the cancel was attempted", 1, spawnFails.starts)
        assertTrue(exited)
    }

    @Test
    fun `destroy accepts the app uid and its own uid and refuses every other caller`() {
        val h = harness()
        h.service.startBgDexopt()

        h.caller = APP_UID + 1
        h.service.destroy()
        h.caller = Process.myUid() + 1
        h.service.destroy()
        h.caller = -1
        h.service.destroy()
        assertTrue("refused callers neither cancel nor exit", h.events.isEmpty())
        assertTrue(h.runner.calls.isEmpty())

        h.caller = APP_UID
        h.service.destroy()
        assertEquals(listOf("cancel", "exit"), h.events)

        h.events.clear()
        h.caller = Process.myUid()
        h.service.destroy()
        assertEquals("exit", h.events.last())
    }

    @Test
    fun `a throwing caller lookup refuses destroy`() {
        var exited = false
        val service =
            TelemetryUserService(
                appUid = APP_UID,
                fileRoot = File("/"),
                callingUid = { throw IllegalStateException() },
                toolkit = toolkit(),
                exitProcess = { exited = true },
            )

        service.destroy()

        assertFalse(exited)
    }

    @Test
    fun `a cancel through the service moves a fresh unknown dexopt status to idle`() {
        val runner = FakeRunner { ok() }
        val service = service(APP_UID, { APP_UID }, toolkit(runner))
        assertEquals(BgDexoptState.UNKNOWN, service.getBgDexoptStatus().state)

        service.cancelBgDexopt()

        assertEquals(BgDexoptState.IDLE, service.getBgDexoptStatus().state)
    }

    private companion object {
        const val APP_UID = 10_001
    }
}
