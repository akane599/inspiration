package com.akane.inspiration.ipc.toolkit

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal const val RETENTION_CAP_BYTES = 4096

/**
 * `dumpsys device_policy` prints a long preamble before "Enabled Device Admins" on managed
 * devices, so this one command keeps more; truncation still fails closed in the parser, which
 * rejects a cut before the admins section ends. Every other protected-package read requires
 * [CommandOutput.complete], so a cut there fails closed too.
 */
internal const val DEVICE_POLICY_RETENTION_CAP_BYTES = 256 * 1024
internal val DEVICE_POLICY_ARGV = listOf(DUMPSYS, "device_policy")

/**
 * `enabled_accessibility_services` is one colon-joined line of about 80 bytes per service, so the
 * 4 KB cap holds only about 50 of them; this one read keeps more. A cut here still fails closed.
 */
internal const val ACCESSIBILITY_RETENTION_CAP_BYTES = 64 * 1024
internal val ACCESSIBILITY_SERVICES_ARGV =
    listOf(CMD, "settings", "get", "--user", "0", "secure", "enabled_accessibility_services")
internal const val DEFAULT_COMMAND_TIMEOUT_MS = 10_000L

internal data class CommandOutput(
    val exitCode: Int,
    val text: String,
    val timedOut: Boolean = false,
    val failedToStart: Boolean = false,
    /** Not the whole output: more was produced than the retention cap kept, or the stream was not read to EOF. */
    val truncated: Boolean = false,
) {
    /** Ran and exited 0. Ignores [truncated]: a reader that needs only the head of a long dump accepts a cut. */
    val ok: Boolean get() = !timedOut && !failedToStart && exitCode == 0

    /** [ok] and nothing was cut: required before the output is trusted as a whole list (fail closed). */
    val complete: Boolean get() = ok && !truncated
}

internal interface CommandProcess {
    /** True when the process exited within [timeoutMs]. */
    fun awaitExit(timeoutMs: Long): Boolean

    fun output(timedOut: Boolean): CommandOutput

    /** Idempotent: kills the process if still alive and closes every stream. */
    fun destroy()
}

/** Starts a fixed argv. Implementations never interpret the argv through a shell. */
internal fun interface CommandRunner {
    fun start(argv: List<String>): CommandProcess
}

internal fun CommandRunner.run(argv: List<String>, timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS): CommandOutput {
    val process = try {
        start(argv)
    } catch (_: IOException) {
        return CommandOutput(exitCode = -1, text = "", failedToStart = true)
    } catch (_: SecurityException) {
        return CommandOutput(exitCode = -1, text = "", failedToStart = true)
    }
    try {
        val exited = process.awaitExit(timeoutMs)
        return process.output(timedOut = !exited)
    } finally {
        process.destroy()
    }
}

/** Cleared, fixed environment: nothing from the service process leaks into the child. */
internal val FIXED_ENVIRONMENT: Map<String, String> = mapOf(
    "PATH" to "/system/bin",
    "ANDROID_ROOT" to "/system",
    "ANDROID_DATA" to "/data",
)

/** How long the reader may take to reach EOF after the child exits; a longer wait reports the output cut. */
internal const val READER_JOIN_MS = 1_000L

internal class ProcessCommandRunner(
    private val readerJoinMs: Long = READER_JOIN_MS,
    private val processFactory: (List<String>) -> Process = ::startProcess,
) : CommandRunner {
    init {
        require(readerJoinMs > 0) { "Thread.join(0) waits forever" }
    }

    override fun start(argv: List<String>): CommandProcess =
        RealCommandProcess(processFactory(argv), retentionCapFor(argv), readerJoinMs)

    private companion object {
        fun retentionCapFor(argv: List<String>): Int = when (argv) {
            DEVICE_POLICY_ARGV -> DEVICE_POLICY_RETENTION_CAP_BYTES
            ACCESSIBILITY_SERVICES_ARGV -> ACCESSIBILITY_RETENTION_CAP_BYTES
            else -> RETENTION_CAP_BYTES
        }

        fun startProcess(argv: List<String>): Process {
            val builder = ProcessBuilder(argv).redirectErrorStream(true)
            builder.environment().apply {
                clear()
                putAll(FIXED_ENVIRONMENT)
            }
            return builder.start()
        }
    }
}

/**
 * Drains merged stdout+stderr on a reader thread for the whole process lifetime, retaining only
 * the first [retentionCap] bytes; draining continues past the cap so the child never blocks.
 * The child's stdin is closed at once: no command here reads it, and one that tried would block
 * until the timeout. Output is reported cut unless the reader reached EOF: a reader still blocked
 * (descheduled, or a grandchild holding the pipe) or interrupted after the bounded join, a read
 * error and a timeout all leave unread bytes behind, so a list read from it must fail closed.
 */
private class RealCommandProcess(
    private val process: Process,
    private val retentionCap: Int,
    private val readerJoinMs: Long,
) : CommandProcess {
    private val retained = ByteArrayOutputStream()
    private var overflow = false

    /** Set by the reader at EOF; guarded by [retained] so [output] reads it with the bytes it describes. */
    private var reachedEof = false

    init {
        closeQuietly(process.outputStream)
    }
    private val reader = Thread { drain(process.inputStream) }.apply {
        isDaemon = true
        start()
    }

    override fun awaitExit(timeoutMs: Long): Boolean {
        val exited = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (exited) {
            try {
                reader.join(readerJoinMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return exited
    }

    override fun output(timedOut: Boolean): CommandOutput {
        val (text, truncated) =
            synchronized(retained) { retained.toString(Charsets.UTF_8.name()) to (overflow || !reachedEof) }
        val exitCode = if (timedOut) -1 else exitValueOrMinusOne()
        return CommandOutput(exitCode = exitCode, text = text, timedOut = timedOut, truncated = truncated)
    }

    override fun destroy() {
        process.destroyForcibly()
        closeQuietly(process.inputStream)
        closeQuietly(process.errorStream)
        closeQuietly(process.outputStream)
    }

    private fun exitValueOrMinusOne(): Int = try {
        process.exitValue()
    } catch (_: IllegalThreadStateException) {
        -1
    }

    private fun drain(stream: InputStream) {
        val buffer = ByteArray(1024)
        try {
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) {
                    synchronized(retained) { reachedEof = true }
                    return
                }
                synchronized(retained) {
                    val room = retentionCap - retained.size()
                    if (room > 0) retained.write(buffer, 0, minOf(room, count))
                    if (count > room) overflow = true
                }
            }
        } catch (_: IOException) {
            // Closed by destroy() or a read error; whatever was retained stays, reported as cut.
        }
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: IOException) {
            // Nothing useful to do.
        }
    }
}
