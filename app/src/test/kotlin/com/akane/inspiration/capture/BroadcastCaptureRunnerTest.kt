package com.akane.inspiration.capture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastCaptureRunnerTest {
    @Test
    fun `starting capture returns before persistence and finishes exactly once`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var finished = 0
        val job = BroadcastCaptureRunner { throw it }.start(this, 2_000, { gate.await() }, { finished++ })
        assertEquals(0, finished)
        runCurrent()
        assertEquals(0, finished)
        gate.complete(Unit)
        job.join()
        assertEquals(1, finished)
    }

    @Test
    fun `deadline finishes stalled persistence without blocking the caller`() = runTest {
        var finished = 0
        BroadcastCaptureRunner { throw it }.start(this, 2_000, { CompletableDeferred<Unit>().await() }, { finished++ })
        advanceUntilIdle()
        assertEquals(1, finished)
    }

    @Test
    fun `already cancelled scope still releases pending broadcast`() = runTest {
        val parent = Job().apply { cancel() }
        var finished = 0
        val job = BroadcastCaptureRunner { throw it }.start(
            CoroutineScope(coroutineContext + parent), 2_000, { error("must not capture") }, { finished++ },
        )
        job.join()
        assertEquals(1, finished)
    }

    @Test
    fun `failed capture still schedules boot work and finishes broadcast`() = runTest {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("disk full")
        BroadcastCaptureRunner {
            assertSame(failure, it)
            events += "logged"
        }.run(
            capture = { throw failure },
            afterCapture = { events += "scheduled" },
            finish = { events += "finished" },
        )
        assertEquals(listOf("logged", "scheduled", "finished"), events)
    }

    @Test
    fun `scheduling failure is contained and broadcast finishes`() = runTest {
        val events = mutableListOf<String>()
        BroadcastCaptureRunner { events += "logged" }.run(
            capture = { events += "captured" },
            afterCapture = { error("scheduler unavailable") },
            finish = { events += "finished" },
        )
        assertEquals(listOf("captured", "logged", "finished"), events)
    }

    @Test
    fun `cancellation propagates and finishes without follow-up work`() = runTest {
        val events = mutableListOf<String>()
        val cancellation = CancellationException("stopped")
        try {
            BroadcastCaptureRunner { events += "logged" }.run(
                capture = { throw cancellation },
                afterCapture = { events += "scheduled" },
                finish = { events += "finished" },
            )
            error("expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertEquals(listOf("finished"), events)
    }
}
