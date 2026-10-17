package com.akane.inspiration.ipc.toolkit

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private class FakeProcess(output: ByteArray, private val finishes: Boolean, private val exit: Int = 0) : Process() {
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

        override fun getInputStream(): InputStream = stdout

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
        assertEquals("whole stream consumed so the child never blocks", 0, process.stdout.available())
        assertTrue(process.stdout.closed)
        assertTrue(process.stderr.closed)
        assertTrue(process.stdinClosed)
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
