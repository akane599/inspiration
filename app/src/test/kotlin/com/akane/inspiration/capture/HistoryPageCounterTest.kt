package com.akane.inspiration.capture

import com.akane.inspiration.capture.ChargeJobCaptureTest.Companion.DEADLINE_MS
import com.akane.inspiration.capture.ChargeJobCaptureTest.Companion.event
import com.akane.inspiration.ipc.model.BatteryHistorySnapshot
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.capture.CaptureResult
import com.akane.inspiration.telemetry.capture.SnapshotCapturer
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.source.FullRead
import com.akane.inspiration.telemetry.source.LightRead
import com.akane.inspiration.telemetry.source.TelemetrySource
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A FULL capture that missed its deadline after storing history pages still ticks the store, once. */
class HistoryPageCounterTest {
    private val store = ChargeJobCaptureTest.FakeStore()
    private val pages = HistoryPageCounter(store)
    private var ticks = 0

    private fun capturer(source: TelemetrySource) = SnapshotCapturer(source, pages, ChargeJobCaptureTest.FixedClocks)

    private suspend fun SnapshotCapturer.appOpen(): CaptureResult =
        pages.withMissTick({ ticks++ }) { captureFull(CaptureTrigger.APP_OPEN, DEADLINE_MS, ingestHistory = true) }

    @Test
    fun `an app-open capture that misses its deadline after storing a page ticks once`() =
        runTest {
            val result = capturer(PageThenHang(page(available = true))).appOpen()

            assertEquals(CaptureResult.Missed("deadline_or_service_unavailable"), result)
            assertEquals("the page arrived before the deadline", 1, store.history.size)
            assertEquals(1, ticks)
        }

    @Test
    fun `a missed capture that stored nothing does not tick`() =
        runTest {
            assertEquals(CaptureResult.Missed("deadline_or_service_unavailable"), capturer(PageThenHang(null)).appOpen())
            assertEquals(
                "an unavailable page stores nothing",
                CaptureResult.Missed("deadline_or_service_unavailable"),
                capturer(PageThenHang(page(available = false))).appOpen(),
            )

            assertEquals(0, store.history.size)
            assertEquals(0, ticks)
        }

    @Test
    fun `a saved capture leaves its tick to its own path`() =
        runTest {
            val result = capturer(ChargeJobCaptureTest.CountingSource(history = listOf(event(5_000, HistoryKind.PLUGGED)))).appOpen()

            assertEquals(CaptureResult.Saved(1, SnapshotKind.FULL), result)
            assertEquals(1, store.history.size)
            assertEquals("the refresh coordinator ticks a saved capture", 0, ticks)
        }

    @Test
    fun `a capture that throws after storing a page ticks and rethrows`() =
        runTest {
            val thrown =
                runCatching {
                    pages.withMissTick({ ticks++ }) {
                        pages.persistHistoryPage(10L, listOf(event(5_000, HistoryKind.PLUGGED)), truncated = false, cutoffWallMs = 0L)
                        error("disk full")
                    }
                }.exceptionOrNull()

            assertEquals("disk full", thrown?.message)
            assertEquals(1, ticks)
        }

    @Test
    fun `a missed capture whose page kept no event does not tick`() =
        runTest {
            pages.withMissTick({ ticks++ }) {
                val kept = pages.persistHistoryPage(10L, listOf(event(5_000, HistoryKind.PLUGGED)), false, Long.MAX_VALUE)
                assertEquals("every event is past the retention", 0, kept)
                CaptureResult.Missed("deadline_or_service_unavailable")
            }

            assertEquals(0, store.history.size)
            assertEquals(0, ticks)
        }

    @Test
    fun `a missed marker capture ticks for its stored pages and writes no marker row`() =
        runTest {
            val hanging = capturer(PageThenHang(page(available = true)))
            var rows = 0

            val id =
                captureMarker(
                    capture = {
                        pages.withMissTick({ ticks++ }) {
                            hanging.captureFull(CaptureTrigger.MARKER, DEADLINE_MS, ingestHistory = true)
                        }
                    },
                    addMarker = { rows++.toLong() },
                    onHistoryStored = { ticks++ },
                )

            assertNull(id)
            assertEquals(0, rows)
            assertEquals(1, ticks)
        }

    @Test
    fun `a saved marker capture ticks once, not twice`() =
        runTest {
            val saving = capturer(ChargeJobCaptureTest.CountingSource(history = listOf(event(5_000, HistoryKind.PLUGGED))))

            val id =
                captureMarker(
                    capture = {
                        pages.withMissTick({ ticks++ }) {
                            saving.captureFull(CaptureTrigger.MARKER, DEADLINE_MS, ingestHistory = true)
                        }
                    },
                    addMarker = { 9L },
                    onHistoryStored = { ticks++ },
                )

            assertEquals(9L, id)
            assertEquals(1, ticks)
        }

    private fun page(available: Boolean) =
        BatteryHistorySnapshot(available, null, 10L, false, listOf(event(5_000, HistoryKind.PLUGGED)), false)

    /** Hands over [page] (when there is one), then never finishes the read, as a hung service does. */
    private class PageThenHang(
        private val page: BatteryHistorySnapshot?,
    ) : TelemetrySource {
        override suspend fun full(
            trigger: CaptureTrigger,
            historyCursor: Pair<Long, Long>?,
        ): FullRead? = awaitCancellation()

        override suspend fun full(
            trigger: CaptureTrigger,
            historyCursor: Pair<Long, Long>?,
            onHistoryPage: suspend (BatteryHistorySnapshot) -> Unit,
        ): FullRead? {
            page?.let { onHistoryPage(it) }
            awaitCancellation()
        }

        override fun light(): LightRead = error("a FULL capture that misses reads no light snapshot")
    }
}
