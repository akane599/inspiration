package com.akane.inspiration.ipc.toolkit

import com.akane.inspiration.ipc.model.BgDexoptReason
import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BgDexoptControllerTest {
    private val clock = FakeClock(nowMs = 1_000L)
    private val threads = mutableListOf<Runnable>()
    private val calls = mutableListOf<List<String>>()
    private var sdkInt = TEST_SDK_INT
    private var shell = FakeDexoptShell(sdkInt)
    private var plugged = { true }
    private var probes = 0
    private var monotonicMs = 1_000L
    private var jobExitsAtMs: Long? = null
    private var jobExit = 0
    private var cancelEndsJob = true
    private var cancelled = false
    private var cancelOutput = ok()
    private var userCancelNote: (() -> Unit)? = null
    private lateinit var job: FakeJob

    private inner class FakeJob : CommandProcess {
        var destroyed = false

        override fun awaitExit(timeoutMs: Long): Boolean {
            val deadline = clock.nowMs + timeoutMs
            if (cancelled && cancelEndsJob) return true
            val exitsAt = jobExitsAtMs
            if (exitsAt != null && exitsAt <= deadline) {
                clock.nowMs = maxOf(clock.nowMs, exitsAt)
                return true
            }
            clock.nowMs = deadline
            monotonicMs += timeoutMs
            userCancelNote?.invoke()
            return false
        }

        override fun output(timedOut: Boolean) = CommandOutput(exitCode = jobExit, text = "", timedOut = timedOut)

        override fun destroy() {
            destroyed = true
        }
    }

    /** A `cmd` client that never returns within the wait (API 33 `bg-dexopt-job ARGS` behind a running job). */
    private val blocked =
        object : CommandProcess {
            override fun awaitExit(timeoutMs: Long) = false

            override fun output(timedOut: Boolean) = CommandOutput(exitCode = -1, text = "", timedOut = timedOut)

            override fun destroy() = Unit
        }

    /** Answers each argv the way `cmd package` does on [sdkInt] ([FakeDexoptShell]). */
    private val runner =
        CommandRunner { argv ->
            calls += argv
            when (shell.effect(argv)) {
                FakeDexoptShell.Effect.PASS -> {
                    shell.passes += 1
                    shell.running = true
                    FakeJob().also { job = it }
                }

                FakeDexoptShell.Effect.CANCEL -> {
                    shell.cancels += 1
                    cancelled = true
                    if (cancelOutput.ok) shell.running = false
                    object : CommandProcess {
                        override fun awaitExit(timeoutMs: Long) = !cancelOutput.timedOut

                        override fun output(timedOut: Boolean) = cancelOutput

                        override fun destroy() = Unit
                    }
                }

                FakeDexoptShell.Effect.WAIT_THEN_PASS -> {
                    blocked
                }

                FakeDexoptShell.Effect.UNKNOWN_COMMAND -> {
                    object : CommandProcess {
                        override fun awaitExit(timeoutMs: Long) = true

                        override fun output(timedOut: Boolean) = failure("Unknown command")

                        override fun destroy() = Unit
                    }
                }
            }
        }

    private fun controller() =
        BgDexoptController(
            runner = runner,
            plugged = {
                probes += 1
                plugged()
            },
            clockMs = clock::now,
            startThread = { threads += it },
            sdkInt = sdkInt,
        )

    /** A fresh device on [sdk]: no job, nothing issued, both clocks back at 1 s. */
    private fun onApi(sdk: Int) {
        sdkInt = sdk
        shell = FakeDexoptShell(sdk)
        threads.clear()
        calls.clear()
        probes = 0
        cancelled = false
        clock.nowMs = 1_000L
        monotonicMs = 1_000L
    }

    // ---- per API level -----------------------------------------------------------------------

    @Test
    fun `below API 33 nothing can cancel a shell-started job, so none is started and nothing runs`() {
        for (sdk in listOf(31, 32)) {
            onApi(sdk)
            val controller = controller()

            val start = controller.tryStart()
            controller.cancelIfRunning()

            assertEquals(ToolkitStatus.REFUSED, start.status)
            assertEquals(ToolkitCode.UNSUPPORTED_API, start.code)
            assertNull(controller.cancelArgv)
            assertTrue("API $sdk: no worker", threads.isEmpty())
            assertTrue("API $sdk: no command, so no extra dexopt pass", calls.isEmpty())
            assertEquals("API $sdk: refused before the plugged probe", 0, probes)
            assertEquals(0, shell.passes)
            val status = controller.status()
            assertEquals(BgDexoptState.UNKNOWN, status.state)
            assertEquals(BgDexoptReason.UNSUPPORTED_API, status.reason)
        }
    }

    @Test
    fun `from API 33 an unplug cancels the system job instead of starting another pass`() {
        for (sdk in 33..36) {
            onApi(sdk)
            jobExitsAtMs = null
            plugged = { clock.nowMs < 1_000L + 70_000 }
            val controller = controller()

            controller.tryStart()
            threads.single().run()

            assertEquals("API $sdk", BgDexoptState.CANCELLED, controller.status().state)
            assertEquals(BgDexoptReason.UNPLUGGED, controller.status().reason)
            assertEquals("API $sdk: one cancel reached the system", 1, shell.cancels)
            assertEquals("API $sdk: only the job's own pass ran", 1, shell.passes)
            assertFalse("API $sdk: the system job stopped", shell.running)
        }
    }

    @Test
    fun `from API 33 the 60 minute cap cancels the system job`() {
        for (sdk in 33..36) {
            onApi(sdk)
            jobExitsAtMs = null
            val controller = controller()

            controller.tryStart()
            threads.single().run()

            assertEquals("API $sdk", BgDexoptState.CANCELLED, controller.status().state)
            assertEquals(BgDexoptReason.TIMEOUT, controller.status().reason)
            assertEquals("API $sdk", 1, shell.cancels)
            assertEquals("API $sdk", 1, shell.passes)
            assertFalse("API $sdk", shell.running)
        }
    }

    @Test
    fun `from API 33 the destroy hook cancels a running system job with a bounded wait`() {
        for (sdk in 33..36) {
            onApi(sdk)
            val controller = controller()
            controller.tryStart()
            shell.running = true // the worker has the job under way

            controller.cancelIfRunning()

            assertEquals("API $sdk", 1, shell.cancels)
            assertEquals("API $sdk: the cancel itself ran no pass", 0, shell.passes)
            assertFalse("API $sdk", shell.running)
        }
    }

    // ---- supervision -------------------------------------------------------------------------

    @Test
    fun `backward wall correction does not postpone unplug supervision`() = assertWallCorrection(-3_600_000L)

    @Test
    fun `forward wall correction does not prematurely hit the cap`() = assertWallCorrection(3_600_000L)

    private fun assertWallCorrection(delta: Long) {
        var corrected = false
        userCancelNote = {
            if (!corrected) {
                clock.nowMs += delta
                corrected = true
            }
        }
        val controller =
            BgDexoptController(runner, { monotonicMs < 2_000 }, clock::now, { threads += it }, sdkInt, elapsedMs = { monotonicMs })
        controller.tryStart()
        threads.single().run()
        assertEquals(BgDexoptReason.UNPLUGGED, controller.status().reason)
        assertTrue(monotonicMs <= 32_000)
    }

    @Test
    fun `acknowledged cancellation prevents a queued worker from starting`() {
        val controller = controller()
        controller.tryStart()
        cancel(controller, false)
        runner.run(checkNotNull(controller.cancelArgv))
        cancel(controller, true)
        threads.single().run()
        assertEquals("only the cancel ran", 1, calls.size)
        assertEquals(1, shell.cancels)
        assertEquals(0, shell.passes)
        assertEquals(BgDexoptState.CANCELLED, controller.status().state)
        assertEquals(BgDexoptReason.USER, controller.status().reason)
    }

    @Test
    fun `failed automatic cancellation remains unknown and can be retried`() {
        for (out in listOf(failure(), CommandOutput(-1, "", timedOut = true))) {
            cancelOutput = out
            cancelEndsJob = false
            val controller = controller()
            controller.tryStart()
            threads.last().run()
            assertEquals(BgDexoptState.UNKNOWN, controller.status().state)
            assertEquals(ToolkitCode.BUSY, controller.tryStart().code)
            cancel(controller, true)
            assertEquals(BgDexoptState.IDLE, controller.status().state)
            assertEquals(ToolkitStatus.DONE, controller.tryStart().status)
        }
    }

    @Test
    fun `unconfirmed user cancellation stays unknown instead of claiming success`() {
        cancelEndsJob = false
        val controller = controller()
        userCancelNote = { cancel(controller, false) }
        controller.tryStart()
        threads.single().run()
        assertEquals(BgDexoptState.UNKNOWN, controller.status().state)
        assertEquals(BgDexoptReason.USER, controller.status().reason)
        cancel(controller, true)
        assertEquals(BgDexoptState.IDLE, controller.status().state)
    }

    @Test
    fun `successful user cancellation confirms cancellation even before client exit`() {
        cancelEndsJob = false
        val controller = controller()
        userCancelNote = {
            cancel(controller, false)
            cancel(controller, true)
        }
        controller.tryStart()
        threads.single().run()
        assertEquals(BgDexoptState.CANCELLED, controller.status().state)
    }

    @Test
    fun `a second start while the job is running is refused`() {
        val controller = controller()
        jobExitsAtMs = clock.nowMs + 5 * 60_000

        val first = controller.tryStart()
        val second = controller.tryStart()

        assertEquals(ToolkitStatus.DONE, first.status)
        assertEquals(ToolkitStatus.REFUSED, second.status)
        assertEquals(ToolkitCode.BUSY, second.code)
        assertEquals(1, threads.size)
        assertEquals(BgDexoptState.RUNNING, controller.status().state)
        assertEquals(1_000L, controller.status().startedAtMs)
    }

    @Test
    fun `a finished job ran one pass and allows a new start`() {
        val controller = controller()
        jobExitsAtMs = clock.nowMs + 5 * 60_000
        jobExit = 0

        controller.tryStart()
        threads.single().run()

        assertEquals(1, calls.size)
        assertEquals(1, shell.passes)
        assertEquals(0, shell.cancels)
        val status = controller.status()
        assertEquals(BgDexoptState.FINISHED, status.state)
        assertEquals(0, status.exitCode)
        assertEquals(1_000L + 5 * 60_000, status.endedAtMs)
        assertTrue(job.destroyed)

        assertEquals(ToolkitStatus.DONE, controller.tryStart().status)
        assertEquals(2, threads.size)
    }

    @Test
    fun `an unplugged device refuses the start and spawns nothing`() {
        plugged = { false }
        val controller = controller()

        val result = controller.tryStart()

        assertEquals(ToolkitStatus.REFUSED, result.status)
        assertEquals(ToolkitCode.NOT_PLUGGED, result.code)
        assertTrue(threads.isEmpty())
        assertTrue(calls.isEmpty())
        assertEquals(BgDexoptState.UNKNOWN, controller.status().state)
    }

    @Test
    fun `unplugging while the job runs cancels it at the next 30 second check`() {
        plugged = { clock.nowMs < 1_000L + 70_000 }
        jobExitsAtMs = null
        val controller = controller()

        controller.tryStart()
        threads.single().run()

        val status = controller.status()
        assertEquals(BgDexoptState.CANCELLED, status.state)
        assertEquals(BgDexoptReason.UNPLUGGED, status.reason)
        assertEquals("the job, then its cancel", 2, calls.size)
        assertEquals(1, shell.cancels)
        // Checks happen at +30 s, +60 s (plugged) and +90 s (unplugged): never faster than 30 s.
        assertEquals(1_000L + 90_000, status.endedAtMs)
        assertTrue(job.destroyed)
    }

    @Test
    fun `a job that outlives the 60 minute cap is cancelled`() {
        jobExitsAtMs = null
        val controller = controller()

        controller.tryStart()
        threads.single().run()

        val status = controller.status()
        assertEquals(BgDexoptState.CANCELLED, status.state)
        assertEquals(BgDexoptReason.TIMEOUT, status.reason)
        assertEquals(FakeDexoptShell.Effect.CANCEL, shell.effect(calls.last()))
        assertEquals(1_000L + 60 * 60_000, status.endedAtMs)
    }

    @Test
    fun `a user cancel is reported as CANCELLED USER once the job exits`() {
        val controller = controller()
        jobExitsAtMs = null
        cancelEndsJob = false
        userCancelNote = {
            cancel(controller, false)
            jobExitsAtMs = clock.nowMs
        }

        controller.tryStart()
        threads.single().run()

        val status = controller.status()
        assertEquals(BgDexoptState.CANCELLED, status.state)
        assertEquals(BgDexoptReason.USER, status.reason)
    }

    @Test
    fun `a failure to launch the worker leaves the controller usable`() {
        val controller = BgDexoptController(runner, { true }, clock::now, { throw IllegalStateException("no thread") }, sdkInt)

        val result = controller.tryStart()

        assertEquals(ToolkitStatus.FAILED, result.status)
        assertFalse(controller.status().state == BgDexoptState.RUNNING)
    }

    @Test
    fun `a fresh controller reports unknown and never claims idle blindly`() {
        assertEquals(BgDexoptState.UNKNOWN, controller().status().state)
        assertEquals(BgDexoptReason.NONE, controller().status().reason)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a completed cancel proves nothing is running and an unknown state becomes idle`() {
        val controller = controller()

        cancel(controller, true)

        assertEquals(BgDexoptState.IDLE, controller.status().state)
    }

    @Test
    fun `a completed cancel leaves the worker to settle its final state`() {
        val controller = controller()
        jobExitsAtMs = clock.nowMs + 5 * 60_000
        controller.tryStart()

        cancel(controller, true)
        assertEquals(BgDexoptState.RUNNING, controller.status().state)

        threads.single().run()
        cancel(controller, true)
        assertEquals(BgDexoptState.CANCELLED, controller.status().state)
    }

    @Test
    fun `cancelIfRunning cancels only a running job`() {
        val controller = controller()
        controller.cancelIfRunning()
        assertTrue("unknown state: nothing to cancel", calls.isEmpty())

        assertEquals(ToolkitCode.BUSY, controller.tryStart().code)
        assertTrue(calls.isEmpty())
        val running = controller()
        running.tryStart()
        running.cancelIfRunning()
        threads.single().run()
        assertEquals("only the cancel ran; the queued worker never started the job", 1, calls.size)
        assertEquals(1, shell.cancels)
        assertEquals(0, shell.passes)
        assertEquals(BgDexoptState.CANCELLED, running.status().state)
        assertEquals(ToolkitCode.BUSY, running.tryStart().code)
    }

    private fun cancel(
        controller: BgDexoptController,
        success: Boolean,
    ) {
        controller.beginUserCancel()
        controller.endUserCancel(success)
    }

    @Test
    fun `cancel or shutdown during the probe invalidates admission`() {
        for (shutdown in listOf(false, true)) {
            val controller = controller()
            plugged = {
                if (shutdown) controller.cancelIfRunning() else cancel(controller, true)
                true
            }
            assertEquals(ToolkitCode.BUSY, controller.tryStart().code)
            assertTrue(threads.isEmpty())
            assertTrue(calls.isEmpty())
        }
    }

    @Test
    fun `an in flight cancel refuses all starts until completion`() {
        val controller = controller()
        controller.beginUserCancel()
        assertEquals(ToolkitCode.BUSY, controller.tryStart().code)
        controller.endUserCancel(true)
        assertEquals(ToolkitStatus.DONE, controller.tryStart().status)
    }
}
