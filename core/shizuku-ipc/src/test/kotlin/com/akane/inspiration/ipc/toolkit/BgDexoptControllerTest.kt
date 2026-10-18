package com.akane.inspiration.ipc.toolkit

import com.akane.inspiration.ipc.model.BgDexoptReason
import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BgDexoptControllerTest {
    private val clock = FakeClock(nowMs = 1_000L)
    private val threads = mutableListOf<Runnable>()
    private val calls = mutableListOf<List<String>>()
    private var plugged = { true }
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
            userCancelNote?.invoke()
            return false
        }

        override fun output(timedOut: Boolean) = CommandOutput(exitCode = jobExit, text = "", timedOut = timedOut)

        override fun destroy() {
            destroyed = true
        }
    }

    private val runner = CommandRunner { argv ->
        calls += argv
        when (argv) {
            BG_DEXOPT_ARGV -> FakeJob().also { job = it }
            BG_DEXOPT_CANCEL_ARGV -> {
                cancelled = true
                object : CommandProcess {
                    override fun awaitExit(timeoutMs: Long) = !cancelOutput.timedOut

                    override fun output(timedOut: Boolean) = cancelOutput

                    override fun destroy() = Unit
                }
            }
            else -> error("unexpected argv $argv")
        }
    }

    private fun controller() = BgDexoptController(
        runner = runner,
        plugged = { plugged() },
        clockMs = clock::now,
        startThread = { threads += it },
    )

    @Test
    fun `acknowledged cancellation prevents a queued worker from starting`() {
        val controller = controller()
        controller.tryStart()
        controller.noteUserCancel()
        runner.run(BG_DEXOPT_CANCEL_ARGV)
        controller.noteCancelCompleted()
        threads.single().run()
        assertEquals(listOf(BG_DEXOPT_CANCEL_ARGV), calls)
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
            controller.noteCancelCompleted()
            assertEquals(BgDexoptState.IDLE, controller.status().state)
            assertEquals(ToolkitStatus.DONE, controller.tryStart().status)
        }
    }

    @Test
    fun `unconfirmed user cancellation stays unknown instead of claiming success`() {
        cancelEndsJob = false
        val controller = controller()
        userCancelNote = { controller.noteUserCancel() }
        controller.tryStart()
        threads.single().run()
        assertEquals(BgDexoptState.UNKNOWN, controller.status().state)
        assertEquals(BgDexoptReason.USER, controller.status().reason)
        controller.noteCancelCompleted()
        assertEquals(BgDexoptState.IDLE, controller.status().state)
    }

    @Test
    fun `successful user cancellation confirms cancellation even before client exit`() {
        cancelEndsJob = false
        val controller = controller()
        userCancelNote = {
            controller.noteUserCancel()
            controller.noteCancelCompleted()
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
    fun `argv is exactly bg-dexopt-job and a finished job allows a new start`() {
        val controller = controller()
        jobExitsAtMs = clock.nowMs + 5 * 60_000
        jobExit = 0

        controller.tryStart()
        threads.single().run()

        assertEquals(listOf(listOf(CMD, "package", "bg-dexopt-job")), calls)
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
        assertEquals(listOf(BG_DEXOPT_ARGV, BG_DEXOPT_CANCEL_ARGV), calls)
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
        assertEquals(BG_DEXOPT_CANCEL_ARGV, calls.last())
        assertEquals(1_000L + 60 * 60_000, status.endedAtMs)
    }

    @Test
    fun `a user cancel is reported as CANCELLED USER once the job exits`() {
        val controller = controller()
        jobExitsAtMs = null
        cancelEndsJob = false
        userCancelNote = {
            controller.noteUserCancel()
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
        val controller = BgDexoptController(runner, { true }, clock::now, { throw IllegalStateException("no thread") })

        val result = controller.tryStart()

        assertEquals(ToolkitStatus.FAILED, result.status)
        assertFalse(controller.status().state == BgDexoptState.RUNNING)
    }

    @Test
    fun `a fresh controller reports unknown and never claims idle blindly`() {
        assertEquals(BgDexoptState.UNKNOWN, controller().status().state)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a completed cancel proves nothing is running and an unknown state becomes idle`() {
        val controller = controller()

        controller.noteCancelCompleted()

        assertEquals(BgDexoptState.IDLE, controller.status().state)
    }

    @Test
    fun `a completed cancel does not change a running or finished state`() {
        val controller = controller()
        jobExitsAtMs = clock.nowMs + 5 * 60_000
        controller.tryStart()

        controller.noteCancelCompleted()
        assertEquals(BgDexoptState.RUNNING, controller.status().state)

        threads.single().run()
        controller.noteCancelCompleted()
        assertEquals(BgDexoptState.FINISHED, controller.status().state)
    }

    @Test
    fun `cancelIfRunning cancels only a running job`() {
        val controller = controller()
        controller.cancelIfRunning()
        assertTrue("unknown state: nothing to cancel", calls.isEmpty())

        controller.tryStart()
        controller.cancelIfRunning()

        assertEquals(listOf(BG_DEXOPT_CANCEL_ARGV), calls)
    }
}
