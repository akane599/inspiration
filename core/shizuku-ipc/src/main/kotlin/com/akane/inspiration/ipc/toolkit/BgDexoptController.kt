package com.akane.inspiration.ipc.toolkit

import android.os.Build
import com.akane.inspiration.ipc.model.BgDexoptReason
import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.model.BgDexoptStatus
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import java.io.IOException

/** Runs the system's background dexopt job now and waits for it, on every API level that can cancel it. */
internal val BG_DEXOPT_ARGV = listOf(CMD, "package", "bg-dexopt-job")

/**
 * The command that cancels a running background dexopt job on [sdkInt], or null where no command can.
 * From AOSP `PackageManagerShellCommand` (and ART's `ArtShellCommand` from API 34):
 * - API 31/32: `bg-dexopt-job` optimises in a fresh `BackgroundDexOptService` per shell call, which nothing can
 *   cancel, and reads every further argument as a package name: `bg-dexopt-job --cancel` dexopts a package named
 *   `--cancel` (next to nothing), cancels nothing and still prints Success.
 * - API 33: `cancel-bg-dexopt-job` cancels a shell-started or scheduled run. `bg-dexopt-job --cancel` still reads
 *   `--cancel` as a package name, after waiting for the running job to finish.
 * - API 34+: `bg-dexopt-job --cancel` (legacy dexopt and ART Service alike); `cancel-bg-dexopt-job` is only a
 *   deprecated alias there.
 *
 * From API 33 a `bg-dexopt-job` start first waits for a job that is already running (the system's idle or
 * post-boot job, or one an earlier service instance left), and a cancel stops whichever job runs now. A cancel
 * that succeeds while our start waits stops that other job, and ours then runs a full pass. So only the exit of
 * our own `cmd` client proves our pass stopped.
 */
internal fun bgDexoptCancelArgv(sdkInt: Int): List<String>? =
    when {
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(CMD, "package", "bg-dexopt-job", "--cancel")
        sdkInt == Build.VERSION_CODES.TIRAMISU -> listOf(CMD, "package", "cancel-bg-dexopt-job")
        else -> null
    }

/**
 * One user-initiated bg-dexopt job at a time. While it runs, a worker re-checks the plugged state
 * every [recheckMs] (bounded, user-initiated; not resident polling), cancels on unplug, and cancels
 * at [capMs]. [elapsedMs] drives supervision; [clockMs] supplies display timestamps.
 *
 * Every cancel uses [cancelArgv] for [sdkInt]. Where there is none (below API 33) the guard could not stop
 * the job, so the controller never starts one: a start or cancel is refused as UNSUPPORTED_API, and the
 * status stays UNKNOWN with reason UNSUPPORTED_API.
 *
 * A cancel is confirmed only by our client's exit. While the client outlives a cancel and its wind-down, the
 * cancel is sent again, up to [CANCEL_ATTEMPTS] in all ([cancelIfRunning]: as many as fit its budget); a client
 * still alive after that is reported UNKNOWN.
 *
 * The unplug/cap guard lives only in this process, so two things keep it honest across service
 * restarts. [cancelIfRunning] is the destroy hook: a job of ours still running is cancelled (bounded)
 * before the process exits. And a freshly created controller starts UNKNOWN, not IDLE: an earlier service
 * instance may have died without cancelling, and there is no status query for the system job. It
 * stays UNKNOWN until a start (this controller then owns the guard) or a completed cancel
 * ([endUserCancel]) proves nothing is running. The cancel is deliberately not issued blindly at
 * creation, because it would also cancel the system's own scheduled dexopt job on every launch.
 * An unconfirmed cancellation also reports UNKNOWN; this instance refuses another start until
 * a successful cancel resolves it, and the client retains its pending row and cancellation action.
 */
internal class BgDexoptController(
    private val runner: CommandRunner,
    private val plugged: PluggedProbe,
    private val clockMs: () -> Long,
    private val startThread: (Runnable) -> Unit,
    /** The platform API level (`Build.VERSION.SDK_INT`); it selects [cancelArgv]. */
    sdkInt: Int,
    private val recheckMs: Long = RECHECK_MS,
    private val capMs: Long = CAP_MS,
    private val elapsedMs: () -> Long = clockMs,
) {
    /** The cancel command for this platform; null where a running job could not be cancelled at all. */
    val cancelArgv: List<String>? = bgDexoptCancelArgv(sdkInt)

    private val lock = Any()
    private var state = BgDexoptState.UNKNOWN
    private var startedAtMs = 0L
    private var startedElapsedMs = 0L
    private var endedAtMs = 0L
    private var exitCode = 0
    private var reason = if (cancelArgv == null) BgDexoptReason.UNSUPPORTED_API else BgDexoptReason.NONE
    private var userCancelRequested = false
    private var cancelGeneration = 0L
    private var cancellationsInFlight = 0
    private var shuttingDown = false

    /** Our running `bg-dexopt-job` client, from its start until the worker is done with it. */
    private var client: CommandProcess? = null

    fun status(): BgDexoptStatus =
        synchronized(lock) {
            BgDexoptStatus(state, startedAtMs, endedAtMs, exitCode, reason)
        }

    fun tryStart(): ToolkitResult {
        // Neither the unplug guard nor the cap could stop the job, and a later cancel would start another pass.
        if (cancelArgv == null) return ToolkitResult.refused(ToolkitCode.UNSUPPORTED_API)
        val generation =
            synchronized(lock) {
                if (shuttingDown || cancellationsInFlight != 0 ||
                    state == BgDexoptState.RUNNING ||
                    (state == BgDexoptState.UNKNOWN && reason != BgDexoptReason.NONE)
                ) {
                    return ToolkitResult.refused(ToolkitCode.BUSY)
                }
                cancelGeneration
            }
        if (!plugged.isPlugged()) return ToolkitResult.refused(ToolkitCode.NOT_PLUGGED)
        val before =
            synchronized(lock) {
                // Atomic compare-and-set: a second start that raced past the check above loses here.
                if (shuttingDown || cancellationsInFlight != 0 || generation != cancelGeneration ||
                    state == BgDexoptState.RUNNING || (state == BgDexoptState.UNKNOWN && reason != BgDexoptReason.NONE)
                ) {
                    return ToolkitResult.refused(ToolkitCode.BUSY)
                }
                val previous = state
                state = BgDexoptState.RUNNING
                startedAtMs = clockMs()
                startedElapsedMs = elapsedMs()
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

    /** Fence probing starts and reject fresh admission until the cancellation command completes. */
    fun beginUserCancel() {
        synchronized(lock) {
            cancelGeneration += 1
            cancellationsInFlight += 1
            if (state == BgDexoptState.RUNNING) userCancelRequested = true
        }
    }

    /**
     * A successful cancel while no job of this instance runs proves nothing is running (UNKNOWN becomes IDLE).
     * While ours runs it proves nothing: it may have stopped a job ours waits behind, so the worker settles
     * the state from our client's exit.
     */
    fun endUserCancel(success: Boolean) {
        synchronized(lock) {
            cancellationsInFlight -= 1
            if (success && state == BgDexoptState.UNKNOWN) state = BgDexoptState.IDLE
        }
    }

    /**
     * The destroy hook. Closes admission even if the worker is queued or the plugged probe has not returned yet,
     * then cancels a job of ours within [SHUTDOWN_CANCEL_TIMEOUT_MS] in all, sending the cancel again while
     * our client outlives it. Before the worker has started our client nothing of ours runs, so no cancel is
     * sent: it could only stop the system's own job.
     */
    fun cancelIfRunning() {
        val running =
            synchronized(lock) {
                shuttingDown = true
                cancelGeneration += 1
                userCancelRequested = true
                if (state != BgDexoptState.RUNNING) return
                // runJob starts the client under this lock only while shuttingDown is false, so none will start.
                client ?: return
            }
        // Without a cancel command nothing was ever started here, so the state is never RUNNING.
        val argv = cancelArgv ?: return
        val deadline = elapsedMs() + SHUTDOWN_CANCEL_TIMEOUT_MS
        repeat(CANCEL_ATTEMPTS) {
            val left = deadline - elapsedMs()
            if (left <= 0) return
            runner.run(argv, left)
            val wait = minOf(deadline - elapsedMs(), WAIT_SLICE_MS)
            if (wait <= 0 || running.awaitExit(wait)) return
        }
    }

    private fun runJob() {
        val process =
            try {
                synchronized(lock) {
                    if (shuttingDown || userCancelRequested) {
                        finish(BgDexoptState.CANCELLED, exit = 0, why = BgDexoptReason.USER)
                        return
                    }
                    runner.start(BG_DEXOPT_ARGV).also { client = it }
                }
            } catch (_: IOException) {
                finish(BgDexoptState.FINISHED, exit = -1, why = BgDexoptReason.NONE)
                return
            }
        try {
            supervise(process)
        } catch (_: RuntimeException) {
            finish(BgDexoptState.FINISHED, exit = -1, why = BgDexoptReason.NONE)
        } finally {
            synchronized(lock) { client = null }
            process.destroy()
        }
    }

    private fun supervise(process: CommandProcess) {
        val deadline = synchronized(lock) { startedElapsedMs } + capMs
        var lastCheckMs = elapsedMs()
        var cancelReason = BgDexoptReason.NONE
        while (cancelReason == BgDexoptReason.NONE) {
            // Short waits so a user cancel is noticed promptly; the plugged re-check stays at recheckMs.
            if (process.awaitExit(minOf(WAIT_SLICE_MS, maxOf(deadline - elapsedMs(), 1L)))) {
                finishFromExit(process)
                return
            }
            val now = elapsedMs()
            when {
                synchronized(lock) { userCancelRequested } -> {
                    // The cancel was already issued; give the job a bounded time to wind down, and send it again
                    // if our client outlives that (the cancel stopped a job ours was waiting behind).
                    val exited =
                        process.awaitExit(DEFAULT_COMMAND_TIMEOUT_MS) || cancelUntilExit(process, CANCEL_ATTEMPTS - 1)
                    finish(
                        if (exited) BgDexoptState.CANCELLED else BgDexoptState.UNKNOWN,
                        exit = if (exited) process.output(timedOut = false).exitCode else -1,
                        why = BgDexoptReason.USER,
                    )
                    return
                }

                now >= deadline -> {
                    cancelReason = BgDexoptReason.TIMEOUT
                }

                now - lastCheckMs >= recheckMs -> {
                    lastCheckMs = now
                    if (!plugged.isPlugged()) cancelReason = BgDexoptReason.UNPLUGGED
                }
            }
        }
        val exited = cancelUntilExit(process, CANCEL_ATTEMPTS)
        // Neither a successful cancel nor killing the cmd client proves system_server stopped our pass. UNKNOWN
        // preserves the app's pending audit row and Cancel action when our client never exited.
        finish(
            if (exited) BgDexoptState.CANCELLED else BgDexoptState.UNKNOWN,
            exit = if (exited) process.output(timedOut = false).exitCode else -1,
            why = cancelReason,
        )
    }

    /**
     * Sends the cancel up to [attempts] times, each followed by a bounded wind-down, and returns true once our
     * client has exited. A cancel stops whichever job runs now: while ours waits behind another job, the first
     * one stops that job and ours begins a full pass, so the cancel must go out again.
     */
    private fun cancelUntilExit(
        process: CommandProcess,
        attempts: Int,
    ): Boolean {
        // tryStart admits a job only when cancelArgv exists; without one nothing is confirmed.
        val argv = cancelArgv ?: return false
        repeat(attempts) {
            runner.run(argv)
            if (process.awaitExit(DEFAULT_COMMAND_TIMEOUT_MS)) return true
        }
        return false
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

    private fun finish(
        newState: Int,
        exit: Int,
        why: Int,
    ) {
        synchronized(lock) {
            state = newState
            exitCode = exit
            reason = why
            endedAtMs = clockMs()
        }
    }

    companion object {
        const val SHUTDOWN_CANCEL_TIMEOUT_MS = 3_000L

        /** Cancels sent for one job (a user's own included) before an outliving client is reported UNKNOWN. */
        const val CANCEL_ATTEMPTS = 3
        const val WAIT_SLICE_MS = 1_000L
        const val RECHECK_MS = 30_000L
        const val CAP_MS = 60L * 60_000L
    }
}
