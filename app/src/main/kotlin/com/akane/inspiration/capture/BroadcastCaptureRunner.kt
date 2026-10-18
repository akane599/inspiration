package com.akane.inspiration.capture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps a failed event capture from taking down the process or skipping independent follow-up work. */
internal class BroadcastCaptureRunner(private val log: (Exception) -> Unit) {
    /** Returns immediately; completion releases the broadcast even if the scope was already cancelled. */
    fun start(
        scope: CoroutineScope,
        deadlineMs: Long,
        capture: suspend () -> Unit,
        finish: () -> Unit,
    ): Job = scope.launch {
        run(capture = { withTimeoutOrNull(deadlineMs) { capture() } })
    }.also { job -> job.invokeOnCompletion { finish() } }

    suspend fun run(
        capture: suspend () -> Unit,
        afterCapture: () -> Unit = {},
        finish: () -> Unit = {},
    ) {
        try {
            guarded { capture() }
            guarded { afterCapture() }
        } finally {
            finish()
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log(error)
        }
    }
}
