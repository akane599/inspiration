package com.akane.inspiration.capture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * One run of the persisted charge job and how it ends. Every way out re-arms the job once, from `finally`,
 * so a stop that lands after [work]'s last suspension point (no CancellationException, coroutine no
 * longer active) cannot drop the job. The re-arm is skipped when an immediate arm happened since this run
 * started ([immediateArmSinceStart]): that same-ID schedule is what stopped the run, and replacing it would
 * delay a post-boot capture by the re-arm latency. A re-arm that throws is logged, never propagated.
 */
internal suspend fun runChargeJob(
    work: suspend () -> Unit,
    finish: () -> Unit,
    immediateArmSinceStart: () -> Boolean,
    reArm: () -> Unit,
    log: (String, Throwable) -> Unit,
) {
    try {
        work()
        // A same-ID schedule cancels active work, so finish before re-arming the next attempt.
        if (currentCoroutineContext().isActive) finish()
    } catch (_: CancellationException) {
        // onStopJob has already stopped this job, so it must not be finished; it is re-armed below.
    } catch (error: Exception) {
        // Finish and re-arm so the persisted job survives a bad run.
        log("charge job capture failed", error)
        finish()
    } finally {
        if (!immediateArmSinceStart()) {
            try {
                reArm()
            } catch (error: RuntimeException) {
                // JobScheduler can refuse (job limit, IllegalStateException); the next app open or boot re-arms.
                log("charge job re-arm failed", error)
            }
        }
    }
}
