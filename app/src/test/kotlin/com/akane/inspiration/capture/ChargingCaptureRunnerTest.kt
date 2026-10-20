package com.akane.inspiration.capture

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingCaptureRunnerTest {
    @Test
    fun `cancellation during capture skips analysis and pruning`() = runTest {
        var captureStarted = false
        var analyzed = false
        var pruned = false
        val job = launch {
            ChargingCaptureRunner().run(
                shouldCapture = true,
                capture = {
                    captureStarted = true
                    awaitCancellation()
                },
                analyze = { analyzed = true },
                prune = { pruned = true },
            )
        }

        testScheduler.runCurrent()
        job.cancel()
        testScheduler.runCurrent()

        assertTrue(captureStarted)
        assertTrue(job.isCancelled && job.isCompleted)
        // A cancelled capture must propagate: analyzing or pruning after it would act on a half-written pass.
        assertFalse(analyzed)
        assertFalse(pruned)
    }
}
