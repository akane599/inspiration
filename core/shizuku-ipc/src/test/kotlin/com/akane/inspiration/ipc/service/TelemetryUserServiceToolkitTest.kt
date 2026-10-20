package com.akane.inspiration.ipc.service

import android.os.Process
import com.akane.inspiration.ipc.model.BgDexoptReason
import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.model.ToolkitApi
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.model.ToolkitStatus
import com.akane.inspiration.ipc.toolkit.BG_DEXOPT_ARGV
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
import java.io.IOException

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
     * like `cmd package` on [sdkInt] and logs what each command did there ("cancel", "pass", ...); a cancel that
     * cannot be spawned (`cancelSpawns` false) logs "cancel-not-spawned". The bg-dexopt worker runs only when a
     * test calls [runWorker].
     */
    private class Harness(
        sdkInt: Int,
    ) {
        val events = mutableListOf<String>()
        val shell = FakeDexoptShell(sdkInt)
        var cancelSpawns = true
        val runner =
            FakeRunner { argv ->
                val effect = shell.effect(argv)
                if (effect == FakeDexoptShell.Effect.CANCEL && !cancelSpawns) {
                    events += "cancel-not-spawned"
                    throw IOException("cannot spawn $argv")
                }
                events += effect.name.lowercase()
                shell.handle(argv)
            }

        /** The controller's clock: it moves only while a wait on our live client times out. */
        var elapsedMs = 0L
        var worker: Runnable? = null

        /** The bound of each wait on a cancel command. */
        val cancelWaits = mutableListOf<Long>()
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
                        BgDexoptController(runner, { true }, { elapsedMs }, { worker = it }, sdkInt),
                        "com.akane.inspiration",
                    ) { },
                exitProcess = { events += "exit" },
            )

        /**
         * Runs the admitted worker: our `cmd` client stays alive while our pass runs, and [whileOurPassRuns] is
         * called once while the worker waits on it. The worker's cap still ends a pass nothing cancelled.
         */
        fun runWorker(whileOurPassRuns: () -> Unit) {
            var called = false
            runner.onAwaitExit = { argv, timeoutMs ->
                if (shell.effect(argv) == FakeDexoptShell.Effect.CANCEL) cancelWaits += timeoutMs
                if (argv != BG_DEXOPT_ARGV) {
                    true
                } else {
                    if (!called && shell.clientAlive) {
                        called = true
                        whileOurPassRuns()
                    }
                    shell.clientAlive.also { alive -> if (alive) elapsedMs += timeoutMs }.not()
                }
            }
            checkNotNull(worker) { "no bg-dexopt job was admitted" }.run()
        }
    }

    private fun harness(sdkInt: Int = TEST_SDK_INT) = Harness(sdkInt)

    @Test
    fun `destroy cancels our running bg-dexopt pass with a bounded wait before exiting`() {
        for (sdk in 33..36) {
            val h = harness(sdk)
            h.service.startBgDexopt()
            assertEquals(BgDexoptState.RUNNING, h.service.getBgDexoptStatus().state)

            h.runWorker { h.service.destroy() }

            assertEquals("API $sdk: cancel runs before the exit hook", listOf("pass", "cancel", "exit"), h.events)
            assertEquals("API $sdk: our pass was cancelled", 1, h.shell.cancels)
            assertFalse("API $sdk", h.shell.running)
            // From API 33 a cancel's own client may wait for the stopped job to wind down; only ours confirms.
            assertTrue("API $sdk: no wait on the cancel's own client: ${h.cancelWaits}", h.cancelWaits.all { it == 0L })
            assertTrue("API $sdk: the hook's wait stayed within its budget", h.elapsedMs <= BgDexoptController.SHUTDOWN_CANCEL_TIMEOUT_MS)
            assertEquals("API $sdk", BgDexoptState.CANCELLED, h.service.getBgDexoptStatus().state)
        }
    }

    @Test
    fun `destroy before the worker started our client sends no cancel, so the system's own job keeps running`() {
        for (sdk in 33..36) {
            val h = harness(sdk)
            h.shell.jobAhead = true // the system's idle or post-boot job
            assertEquals(ToolkitStatus.DONE, h.service.startBgDexopt().status)

            h.service.destroy()
            checkNotNull(h.worker).run() // the queued worker runs only after the hook

            assertEquals("API $sdk: no cancel, no start", listOf("exit"), h.events)
            assertTrue("API $sdk", h.runner.calls.isEmpty())
            assertTrue("API $sdk: the system's own job was not stopped", h.shell.jobAhead)
            assertEquals("API $sdk", 0, h.shell.passes)
            assertEquals("API $sdk", BgDexoptState.CANCELLED, h.service.getBgDexoptStatus().state)
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
        val h = harness()
        h.service.startBgDexopt()
        h.cancelSpawns = false

        h.runWorker { h.service.destroy() }

        val exitAt = h.events.indexOf("exit")
        assertTrue("it exited: ${h.events}", exitAt > 0)
        assertEquals("pass", h.events.first())
        assertTrue("the cancel was attempted before the exit: ${h.events}", "cancel-not-spawned" in h.events.take(exitAt))
        assertEquals(1, h.events.count { it == "exit" })
    }

    @Test
    fun `destroy accepts the app uid and its own uid and refuses every other caller`() {
        val h = harness()
        h.service.startBgDexopt()

        h.runWorker {
            h.caller = APP_UID + 1
            h.service.destroy()
            h.caller = Process.myUid() + 1
            h.service.destroy()
            h.caller = -1
            h.service.destroy()
            assertEquals("refused callers neither cancel nor exit", listOf("pass"), h.events)

            h.caller = APP_UID
            h.service.destroy()
        }
        assertEquals(listOf("pass", "cancel", "exit"), h.events)

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
