package com.akane.inspiration.capture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingCaptureRunnerTest {
    @Test
    fun `a skipped capture prunes without capturing or analyzing`() = runTest {
        val calls = mutableListOf<String>()

        ChargingCaptureRunner().run(
            shouldCapture = false,
            capture = { calls += "capture" },
            analyze = { calls += "analyze" },
            prune = { calls += "prune" },
        )

        assertEquals(listOf("prune"), calls)
    }

    @Test
    fun `a due capture finishes capture and analysis before pruning`() = runTest {
        val calls = mutableListOf<String>()

        ChargingCaptureRunner().run(
            shouldCapture = true,
            capture = { calls += "capture" },
            analyze = { calls += "analyze" },
            prune = { calls += "prune" },
        )

        assertEquals(listOf("capture", "analyze", "prune"), calls)
    }

    @Test
    fun `failure or cancellation propagates without running later stages`() = runTest {
        val stages = listOf("capture", "analyze", "prune")
        for (failedStage in stages) {
            for (failure in listOf(IllegalStateException(failedStage), CancellationException(failedStage))) {
                val calls = mutableListOf<String>()
                suspend fun stage(name: String) {
                    calls += name
                    if (name == failedStage) throw failure
                }

                val thrown = runCatching {
                    ChargingCaptureRunner().run(
                        shouldCapture = true,
                        capture = { stage("capture") },
                        analyze = { stage("analyze") },
                        prune = { stage("prune") },
                    )
                }.exceptionOrNull()

                assertSame(failure, thrown)
                assertEquals(stages.take(stages.indexOf(failedStage) + 1), calls)
            }
        }
    }

    @Test
    fun `a skipped pass propagates prune failure and cancellation`() = runTest {
        for (failure in listOf(IllegalStateException("prune"), CancellationException("prune"))) {
            val calls = mutableListOf<String>()
            val thrown = runCatching {
                ChargingCaptureRunner().run(
                    shouldCapture = false,
                    capture = { calls += "capture" },
                    analyze = { calls += "analyze" },
                    prune = {
                        calls += "prune"
                        throw failure
                    },
                )
            }.exceptionOrNull()

            assertSame(failure, thrown)
            assertEquals(listOf("prune"), calls)
        }
    }

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

    @Test
    fun `cancellation during analysis skips pruning`() = runTest {
        val calls = mutableListOf<String>()
        val job = launch {
            ChargingCaptureRunner().run(
                shouldCapture = true,
                capture = { calls += "capture" },
                analyze = {
                    calls += "analyze"
                    awaitCancellation()
                },
                prune = { calls += "prune" },
            )
        }

        testScheduler.runCurrent()
        job.cancel()
        testScheduler.runCurrent()

        assertTrue(job.isCancelled && job.isCompleted)
        assertEquals(listOf("capture", "analyze"), calls)
    }
}
