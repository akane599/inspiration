package com.akane.inspiration.capture

class ChargingCaptureRunner {
    suspend fun run(
        shouldCapture: Boolean,
        capture: suspend () -> Unit,
        prune: suspend () -> Unit,
    ) {
        if (!shouldCapture) return
        capture()
        prune()
    }
}
