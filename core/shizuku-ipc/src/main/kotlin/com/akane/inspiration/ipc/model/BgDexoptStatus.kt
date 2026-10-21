package com.akane.inspiration.ipc.model

import kotlinx.parcelize.Parcelize

/**
 * Background dexopt job state. [state] is a [BgDexoptState] constant; [exitCode] is meaningful for
 * FINISHED and exited CANCELLED jobs; [reason] ([BgDexoptReason]) describes FINISHED, CANCELLED and UNKNOWN.
 * FINISHED is successful only with exit 0 and reason NONE (complete, recognized successful terminal output).
 * Times are wall-clock ms, 0 when unknown.
 */
@Parcelize
data class BgDexoptStatus(
    val state: Int,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val exitCode: Int,
    val reason: Int,
) : android.os.Parcelable

object BgDexoptState {
    const val IDLE = 0
    const val RUNNING = 1
    const val FINISHED = 2
    const val CANCELLED = 3
    const val UNKNOWN = 4
}

object BgDexoptReason {
    const val NONE = 0
    const val USER = 1
    const val UNPLUGGED = 2
    const val TIMEOUT = 3
    const val CALLER = 4

    /**
     * With UNKNOWN: this Android version (below API 33) has no command that cancels a job started from the shell,
     * so the service never starts one ([ToolkitCode.UNSUPPORTED_API]). A job an older service started may still run.
     */
    const val UNSUPPORTED_API = 5

    /** With FINISHED: the real exit/output did not verify successful completion (including incomplete output). */
    const val RESULT_NOT_SUCCESSFUL = 6
}
