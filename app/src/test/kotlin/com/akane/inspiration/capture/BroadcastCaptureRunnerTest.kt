package com.akane.inspiration.capture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BroadcastCaptureRunnerTest {
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
