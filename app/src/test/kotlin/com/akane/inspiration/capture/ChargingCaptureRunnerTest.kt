package com.akane.inspiration.capture

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingCaptureRunnerTest {
    @Test
    fun `cancellation stops capture and does not prune a charge job snapshot`() = runTest {
        var captureStarted = false
        var pruned = false
        val savedTriggers = mutableListOf<String>()
        val job = launch {
            ChargingCaptureRunner().run(
                shouldCapture = true,
                capture = {
                    captureStarted = true
                    awaitCancellation()
                    savedTriggers += "CHARGE_JOB"
                },
                prune = { pruned = true },
            )
        }

        testScheduler.runCurrent()
        job.cancel()
        testScheduler.runCurrent()

        assertTrue(captureStarted)
        assertEquals(emptyList<String>(), savedTriggers)
        assertEquals(false, pruned)
    }
}
