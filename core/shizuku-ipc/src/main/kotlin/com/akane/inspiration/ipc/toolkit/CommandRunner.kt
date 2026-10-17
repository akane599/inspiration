package com.akane.inspiration.ipc.toolkit

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal const val RETENTION_CAP_BYTES = 4096
internal const val DEFAULT_COMMAND_TIMEOUT_MS = 10_000L

internal data class CommandOutput(
    val exitCode: Int,
    val text: String,
    val timedOut: Boolean = false,
    val failedToStart: Boolean = false,
    /** More output was produced than the retention cap kept. */
    val truncated: Boolean = false,
) {
    val ok: Boolean get() = !timedOut && !failedToStart && exitCode == 0
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

internal class ProcessCommandRunner(
    private val processFactory: (List<String>) -> Process = ::startProcess,
) : CommandRunner {
    override fun start(argv: List<String>): CommandProcess = RealCommandProcess(processFactory(argv))

    private companion object {
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
 * the first [RETENTION_CAP_BYTES]; draining continues past the cap so the child never blocks.
 */
private class RealCommandProcess(private val process: Process) : CommandProcess {
    private val retained = ByteArrayOutputStream()
    private var overflow = false
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
                reader.join(READER_JOIN_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return exited
    }

    override fun output(timedOut: Boolean): CommandOutput {
        val (text, truncated) = synchronized(retained) { retained.toString(Charsets.UTF_8.name()) to overflow }
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
                if (count < 0) return
                synchronized(retained) {
                    val room = RETENTION_CAP_BYTES - retained.size()
                    if (room > 0) retained.write(buffer, 0, minOf(room, count))
                    if (count > room) overflow = true
                }
            }
        } catch (_: IOException) {
            // The stream was closed by destroy(); whatever was retained stays.
        }
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: IOException) {
            // Nothing useful to do.
        }
    }

    private companion object {
        const val READER_JOIN_MS = 1_000L
    }
}
