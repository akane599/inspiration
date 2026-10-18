package com.akane.inspiration.capture

import kotlinx.coroutines.CancellationException

/** Keeps a failed event capture from taking down the process or skipping independent follow-up work. */
internal class BroadcastCaptureRunner(private val log: (Exception) -> Unit) {
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
