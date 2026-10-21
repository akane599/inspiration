package com.akane.inspiration.debug

import org.junit.Assert.assertTrue
import org.junit.Test

/** The probe capture's read deadline must expire before the action's outer timeout, so it reports Missed (SQ-59). */
class ProbeCaptureBudgetTest {
    @Test
    fun captureDeadlineIsShorterThanTheOuterTimeout() {
        assertTrue(
            "capture deadline $PROBE_CAPTURE_DEADLINE_MS must be < outer timeout $PROBE_ACTION_TIMEOUT_MS",
            PROBE_CAPTURE_DEADLINE_MS < PROBE_ACTION_TIMEOUT_MS,
        )
    }

    @Test
    fun captureDeadlineLeavesRoomForTheLockWaitAndResultLogging() {
        assertTrue(PROBE_CAPTURE_DEADLINE_MS > 0)
        assertTrue(PROBE_ACTION_TIMEOUT_MS - PROBE_CAPTURE_DEADLINE_MS >= 1_000L)
    }
}
