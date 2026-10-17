package com.akane.inspiration.ipc.toolkit

import com.akane.inspiration.ipc.model.BgDexoptReason
import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.model.BgDexoptStatus
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import java.io.IOException

internal val BG_DEXOPT_ARGV = listOf(CMD, "package", "bg-dexopt-job")
internal val BG_DEXOPT_CANCEL_ARGV = listOf(CMD, "package", "bg-dexopt-job", "--cancel")

/**
 * One user-initiated bg-dexopt job at a time. While it runs, a worker re-checks the plugged state
 * every [recheckMs] (bounded, user-initiated; not resident polling), cancels on unplug, and cancels
 * at [capMs]. [clockMs] drives both the cap and the reported timestamps.
 */
internal class BgDexoptController(
    private val runner: CommandRunner,
    private val plugged: PluggedProbe,
    private val clockMs: () -> Long,
    private val startThread: (Runnable) -> Unit,
    private val recheckMs: Long = RECHECK_MS,
    private val capMs: Long = CAP_MS,
) {
    private val lock = Any()
    private var state = BgDexoptState.IDLE
    private var startedAtMs = 0L
    private var endedAtMs = 0L
    private var exitCode = 0
    private var reason = BgDexoptReason.NONE
    private var userCancelRequested = false

    fun status(): BgDexoptStatus = synchronized(lock) {
        BgDexoptStatus(state, startedAtMs, endedAtMs, exitCode, reason)
    }

    fun tryStart(): ToolkitResult {
        if (isRunning()) return ToolkitResult.refused(ToolkitCode.BUSY)
        if (!plugged.isPlugged()) return ToolkitResult.refused(ToolkitCode.NOT_PLUGGED)
        val before = synchronized(lock) {
            // Atomic compare-and-set: a second start that raced past the check above loses here.
            if (state == BgDexoptState.RUNNING) return ToolkitResult.refused(ToolkitCode.BUSY)
            val previous = state
            state = BgDexoptState.RUNNING
            startedAtMs = clockMs()
            endedAtMs = 0L
            exitCode = 0
            reason = BgDexoptReason.NONE
            userCancelRequested = false
            previous
        }
        try {
            startThread(Runnable { runJob() })
        } catch (error: Throwable) {
            finish(BgDexoptState.FINISHED, exit = -1, why = BgDexoptReason.NONE)
            return ToolkitResult.failed(ToolkitCode.EXEC, before = before.toString())
        }
        return ToolkitResult.done(before = before.toString(), after = BgDexoptState.RUNNING.toString())
    }

    /** A user cancel was issued; the worker reports CANCELLED(USER) when the job then exits. */
    fun noteUserCancel() {
        synchronized(lock) {
            if (state == BgDexoptState.RUNNING) userCancelRequested = true
        }
    }

    private fun isRunning(): Boolean = synchronized(lock) { state == BgDexoptState.RUNNING }

    private fun runJob() {
        val process = try {
            runner.start(BG_DEXOPT_ARGV)
        } catch (_: IOException) {
            finish(BgDexoptState.FINISHED, exit = -1, why = BgDexoptReason.NONE)
            return
        }
        try {
            supervise(process)
        } catch (_: RuntimeException) {
            finish(BgDexoptState.FINISHED, exit = -1, why = BgDexoptReason.NONE)
        } finally {
            process.destroy()
        }
    }

    private fun supervise(process: CommandProcess) {
        val deadline = synchronized(lock) { startedAtMs } + capMs
        var lastCheckMs = clockMs()
        var cancelReason = BgDexoptReason.NONE
        while (cancelReason == BgDexoptReason.NONE) {
            // Short waits so a user cancel is noticed promptly; the plugged re-check stays at recheckMs.
            if (process.awaitExit(minOf(WAIT_SLICE_MS, maxOf(deadline - clockMs(), 1L)))) {
                finishFromExit(process)
                return
            }
            val now = clockMs()
            when {
                synchronized(lock) { userCancelRequested } -> {
                    // `--cancel` was already issued; give the job a bounded time to wind down.
                    val exited = process.awaitExit(DEFAULT_COMMAND_TIMEOUT_MS)
                    val exit = if (exited) process.output(timedOut = false).exitCode else 0
                    finish(BgDexoptState.CANCELLED, exit = exit, why = BgDexoptReason.USER)
                    return
                }
                now >= deadline -> cancelReason = BgDexoptReason.TIMEOUT
                now - lastCheckMs >= recheckMs -> {
                    lastCheckMs = now
                    if (!plugged.isPlugged()) cancelReason = BgDexoptReason.UNPLUGGED
                }
            }
        }
        runner.run(BG_DEXOPT_CANCEL_ARGV)
        process.awaitExit(DEFAULT_COMMAND_TIMEOUT_MS)
        finish(BgDexoptState.CANCELLED, exit = 0, why = cancelReason)
    }

    private fun finishFromExit(process: CommandProcess) {
        val exit = process.output(timedOut = false).exitCode
        val cancelledByUser = synchronized(lock) { userCancelRequested }
        if (cancelledByUser) {
            finish(BgDexoptState.CANCELLED, exit = exit, why = BgDexoptReason.USER)
        } else {
            finish(BgDexoptState.FINISHED, exit = exit, why = BgDexoptReason.NONE)
        }
    }

    private fun finish(newState: Int, exit: Int, why: Int) {
        synchronized(lock) {
            state = newState
            exitCode = exit
            reason = why
            endedAtMs = clockMs()
        }
    }

    companion object {
        const val WAIT_SLICE_MS = 1_000L
        const val RECHECK_MS = 30_000L
        const val CAP_MS = 60L * 60_000L
    }
}
