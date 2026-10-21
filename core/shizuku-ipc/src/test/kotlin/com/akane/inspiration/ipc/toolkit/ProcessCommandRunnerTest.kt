package com.akane.inspiration.ipc.toolkit

import com.akane.inspiration.ipc.model.BgDexoptReason
import com.akane.inspiration.ipc.model.BgDexoptState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ProcessCommandRunnerTest {
    private class TrackedStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    /**
     * Hands out [head], then blocks like a pipe whose write end is still open (a grandchild holding it,
     * or a descheduled reader) until [close], which ends the read with an IOException as on a real pipe.
     */
    private class StalledStream(private val head: ByteArray) : InputStream() {
        private val closed = CountDownLatch(1)
        private var headSent = false

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!headSent) {
                headSent = true
                head.copyInto(b, off, 0, minOf(len, head.size))
                return minOf(len, head.size)
            }
            closed.await()
            throw IOException("closed")
        }

        override fun close() = closed.countDown()

        val isClosed: Boolean get() = closed.count == 0L
    }

    private class FakeProcess(
        output: ByteArray,
        private val finishes: Boolean,
        private val exit: Int = 0,
        private val stdoutOverride: InputStream? = null,
    ) : Process() {
        val stdout = TrackedStream(output)
        val stderr = TrackedStream(ByteArray(0))
        var forcedKill = false
        var requestedTimeoutMs = -1L
        var stdinClosed = false
        private val stdin = object : ByteArrayOutputStream() {
            override fun close() {
                stdinClosed = true
            }
        }

        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream(): InputStream = stdoutOverride ?: stdout

        override fun getErrorStream(): InputStream = stderr

        override fun waitFor(): Int = exit

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            requestedTimeoutMs = unit.toMillis(timeout)
            return finishes
        }

        override fun exitValue(): Int = if (finishes) exit else throw IllegalThreadStateException()

        override fun destroy() {
            forcedKill = true
        }

        override fun destroyForcibly(): Process {
            forcedKill = true
            return this
        }
    }

    @Test
    fun `output is retained up to 4 KB while the stream is drained to the end`() {
        val big = ByteArray(100_000) { 'x'.code.toByte() }
        val process = FakeProcess(big, finishes = true, exit = 0)
        val runner = ProcessCommandRunner { process }

        val out = runner.run(listOf("/system/bin/cmd", "x"))

        assertTrue(out.ok)
        assertEquals(RETENTION_CAP_BYTES, out.text.length)
        assertTrue(out.truncated)
        assertFalse("a cut output is never complete", out.complete)
        assertEquals("whole stream consumed so the child never blocks", 0, process.stdout.available())
        assertTrue(process.stdout.closed)
        assertTrue(process.stderr.closed)
        assertTrue(process.stdinClosed)
    }

    @Test
    fun `dexopt output at the cap verifies success but extra output is fully drained and fails closed`() {
        val terminal = "Job finished. See logs for details\n"
        val retainedSuccess = " ".repeat(RETENTION_CAP_BYTES - terminal.length) + terminal
        for (suffix in listOf("", "Job encountered a fatal error\n".repeat(4_000))) {
            val process = FakeProcess((retainedSuccess + suffix).toByteArray(), finishes = true)
            val controller = BgDexoptController(
                ProcessCommandRunner { process }, { true }, { 1_000L }, { it.run() }, sdkInt = 35,
            )

            controller.tryStart()

            val status = controller.status()
            assertEquals(BgDexoptState.FINISHED, status.state)
            assertEquals(0, status.exitCode)
            assertEquals(
                if (suffix.isEmpty()) BgDexoptReason.NONE else BgDexoptReason.RESULT_NOT_SUCCESSFUL,
                status.reason,
            )
            assertEquals("even overflow past a retained success is drained", 0, process.stdout.available())
            assertTrue(process.stdout.closed)
        }
    }

    @Test
    fun `dexopt does not trust a success line before EOF and closes the reader after a bounded join`() {
        val stdout = StalledStream("Job finished. See logs for details\n".toByteArray())
        val process = FakeProcess(ByteArray(0), finishes = true, stdoutOverride = stdout)
        val controller = BgDexoptController(
            ProcessCommandRunner(readerJoinMs = 20L) { process }, { true }, { 1_000L }, { it.run() }, sdkInt = 35,
        )

        controller.tryStart()

        assertEquals(BgDexoptState.FINISHED, controller.status().state)
        assertEquals(0, controller.status().exitCode)
        assertEquals(BgDexoptReason.RESULT_NOT_SUCCESSFUL, controller.status().reason)
        assertTrue(stdout.isClosed)
    }

    @Test
    fun `the child's stdin is closed right after start, before any wait or destroy`() {
        val process = FakeProcess("x".toByteArray(), finishes = true)

        val started = ProcessCommandRunner { process }.start(listOf("x"))

        assertTrue("stdin closed at start", process.stdinClosed)
        started.destroy()
    }

    @Test
    fun `device_policy keeps far more than 4 KB and is only truncated past its own cap`() {
        val preamble = ByteArray(100_000) { 'x'.code.toByte() }
        val whole = ProcessCommandRunner { FakeProcess(preamble, finishes = true) }.run(DEVICE_POLICY_ARGV)
        assertEquals(100_000, whole.text.length)
        assertFalse(whole.truncated)

        val huge = ByteArray(DEVICE_POLICY_RETENTION_CAP_BYTES + 5_000) { 'x'.code.toByte() }
        val capped = ProcessCommandRunner { FakeProcess(huge, finishes = true) }.run(DEVICE_POLICY_ARGV)
        assertEquals(DEVICE_POLICY_RETENTION_CAP_BYTES, capped.text.length)
        assertTrue(capped.truncated)

        val other = ProcessCommandRunner { FakeProcess(preamble, finishes = true) }.run(listOf(DUMPSYS, "deviceidle"))
        assertEquals("other commands keep the 4 KB cap", RETENTION_CAP_BYTES, other.text.length)
        assertTrue(other.truncated)
    }

    @Test
    fun `the accessibility services read keeps far more than 4 KB and is only truncated past its own cap`() {
        val list = ByteArray(20_000) { 'x'.code.toByte() }
        val whole = ProcessCommandRunner { FakeProcess(list, finishes = true) }.run(ACCESSIBILITY_SERVICES_ARGV)
        assertEquals(20_000, whole.text.length)
        assertTrue(whole.complete)

        val huge = ByteArray(ACCESSIBILITY_RETENTION_CAP_BYTES + 5_000) { 'x'.code.toByte() }
        val capped = ProcessCommandRunner { FakeProcess(huge, finishes = true) }.run(ACCESSIBILITY_SERVICES_ARGV)
        assertEquals(ACCESSIBILITY_RETENTION_CAP_BYTES, capped.text.length)
        assertTrue(capped.ok)
        assertFalse(capped.complete)

        val ime = listOf(CMD, "settings", "get", "--user", "0", "secure", "default_input_method")
        val other = ProcessCommandRunner { FakeProcess(list, finishes = true) }.run(ime)
        assertEquals("other settings keep the 4 KB cap", RETENTION_CAP_BYTES, other.text.length)
        assertFalse(other.complete)
    }

    @Test
    fun `small output is returned whole and not flagged truncated`() {
        val out = ProcessCommandRunner { FakeProcess("hello\n".toByteArray(), finishes = true) }.run(listOf("x"))

        assertEquals("hello\n", out.text)
        assertFalse(out.truncated)
        assertEquals(0, out.exitCode)
    }

    @Test
    fun `a process that does not exit within the timeout is destroyed forcibly`() {
        val process = FakeProcess("partial".toByteArray(), finishes = false)

        val out = ProcessCommandRunner { process }.run(listOf("x"), timeoutMs = 10_000L)

        assertTrue(out.timedOut)
        assertFalse(out.ok)
        assertEquals(10_000L, process.requestedTimeoutMs)
        assertTrue(process.forcedKill)
        assertTrue(process.stdout.closed)
    }

    @Test
    fun `output whose reader has not reached EOF when the join ends is never complete`() {
        val stdout = StalledStream("com.example.launcher\n".toByteArray())
        val process = FakeProcess(ByteArray(0), finishes = true, stdoutOverride = stdout)

        val out = ProcessCommandRunner(readerJoinMs = 20L) { process }.run(listOf("x"))

        assertTrue("the child exited 0", out.ok)
        assertTrue("an undrained stream is a cut", out.truncated)
        assertFalse("a list read before EOF is never trusted whole", out.complete)
        assertTrue("destroy releases the stalled reader", stdout.isClosed)
    }

    @Test
    fun `an interrupted reader join reports the output cut and keeps the interrupt`() {
        val stdout = StalledStream("com.example.ime\n".toByteArray())
        val process = FakeProcess(ByteArray(0), finishes = true, stdoutOverride = stdout)

        Thread.currentThread().interrupt()
        val out = try {
            ProcessCommandRunner(readerJoinMs = 10_000L) { process }.run(listOf("x"))
        } finally {
            assertTrue("the interrupt is restored for the caller", Thread.interrupted())
        }

        assertFalse(out.complete)
        assertTrue(out.truncated)
    }

    @Test
    fun `a zero reader join is rejected because it would wait forever`() {
        assertThrows(IllegalArgumentException::class.java) { ProcessCommandRunner(readerJoinMs = 0L) }
    }

    @Test
    fun `a nonzero exit code is reported and not ok`() {
        val out = ProcessCommandRunner { FakeProcess("Error".toByteArray(), finishes = true, exit = 255) }.run(listOf("x"))

        assertEquals(255, out.exitCode)
        assertFalse(out.ok)
    }

    @Test
    fun `a launch failure is reported without throwing`() {
        val out = ProcessCommandRunner { throw java.io.IOException("no such file") }.run(listOf("/nope"))

        assertTrue(out.failedToStart)
        assertFalse(out.ok)
    }

    @Test
    fun `the default runner clears the inherited environment`() {
        assumeTrue(File("/usr/bin/env").canExecute())

        val out = ProcessCommandRunner().run(listOf("/usr/bin/env"))

        val lines = out.text.lines().filter { it.isNotBlank() }.toSet()
        assertTrue(out.text, out.ok)
        assertEquals(FIXED_ENVIRONMENT.map { (k, v) -> "$k=$v" }.toSet(), lines)
    }

    @Test
    fun `the default runner drains more output than the pipe buffer holds`() {
        assumeTrue(File("/usr/bin/head").canExecute())

        val out = ProcessCommandRunner().run(listOf("/usr/bin/head", "-c", "300000", "/dev/zero"), timeoutMs = 5_000L)

        assertTrue("timedOut=${out.timedOut} exit=${out.exitCode}", out.ok)
        assertTrue(out.truncated)
        assertTrue(out.text.length <= RETENTION_CAP_BYTES)
    }
}
