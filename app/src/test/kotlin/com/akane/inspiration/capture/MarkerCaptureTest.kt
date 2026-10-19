package com.akane.inspiration.capture

import com.akane.inspiration.telemetry.capture.CaptureResult
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkerCaptureTest {
    private val saved: suspend () -> CaptureResult = { CaptureResult.Saved(id = 3, kind = SnapshotKind.FULL) }

    @Test
    fun `a saved marker capture ticks once, even when writing the marker row throws`() =
        runTest {
            var ticks = 0
            val markedSnapshots = mutableListOf<Long>()

            val id = captureMarker(saved, addMarker = { snapshotId -> 40L.also { markedSnapshots += snapshotId } }) { ticks++ }
            assertEquals(40L, id)
            assertEquals(listOf(3L), markedSnapshots)
            assertEquals(1, ticks)

            val thrown = runCatching { captureMarker(saved, addMarker = { error("disk full") }) { ticks++ } }.exceptionOrNull()
            assertEquals("disk full", thrown?.message)
            assertEquals("the capture's history is stored without the row", 2, ticks)
        }

    @Test
    fun `a capture that was not saved writes no marker row`() =
        runTest {
            var rows = 0

            val id =
                captureMarker({ CaptureResult.Missed("deadline_or_service_unavailable") }, addMarker = {
                    rows++
                    1L
                }) {}

            assertNull(id)
            assertEquals(0, rows)
        }
}
