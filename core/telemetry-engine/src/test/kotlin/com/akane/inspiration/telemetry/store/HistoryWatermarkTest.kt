package com.akane.inspiration.telemetry.store

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The keep predicate `wall >= cutoff && (ht > H || wall >= W)` and the restart guard, at their boundaries. */
class HistoryWatermarkTest {
    private val watermark = HistoryWatermark(seenThroughHistoryTimeMs = H, prunedBeforeWallMs = W)

    @Test
    fun `keep predicate truth table`() {
        // (history time, wall) -> kept, with the cutoff below W, as after a backward clock step.
        val cases =
            listOf(
                Triple(H + 1, CUTOFF, true), // new position, wall == cutoff: the cutoff alone judges it
                Triple(H + 1, CUTOFF - 1, false), // new position below the cutoff
                Triple(H, W, true), // ht == H is old; wall == W clears W
                Triple(H, W - 1, false), // old position below W: a cutoff already applied removed it
                Triple(H - 1, W - 1, false),
                Triple(H - 1, W + 1, true),
            )
        for ((historyTimeMs, wallMs, kept) in cases) {
            assertEquals("ht=$historyTimeMs wall=$wallMs", kept, watermark.keeps(event(historyTimeMs, wallMs), CUTOFF))
        }
        assertEquals("the cutoff still applies above W", false, watermark.keeps(event(H, W + 5), W + 6))
    }

    @Test
    fun `the neutral watermark keeps exactly what the cutoff keeps`() {
        for (historyTimeMs in listOf(Long.MIN_VALUE + 1, 0L, H)) {
            assertEquals(true, HistoryWatermark.NEUTRAL.keeps(event(historyTimeMs, CUTOFF), CUTOFF))
            assertEquals(false, HistoryWatermark.NEUTRAL.keeps(event(historyTimeMs, CUTOFF - 1), CUTOFF))
        }
        assertEquals(true, HistoryWatermark.NEUTRAL.keeps(event(0L, -5L), -10L))
    }

    @Test
    fun `a page advances H to its last history time and W to the cutoff, never lowering either`() {
        val page = watermark.judgePage(listOf(event(H - 1, W - 1), event(H + 2, CUTOFF)), truncated = true, cutoffWallMs = CUTOFF)

        assertEquals(listOf(event(H + 2, CUTOFF)), page.kept)
        assertEquals(HistoryWatermark(H + 2, W), page.watermark)
        assertEquals(
            "a higher cutoff raises W; an empty page keeps H",
            HistoryWatermark(H, W + 1),
            watermark.judgePage(emptyList(), truncated = false, cutoffWallMs = W + 1).watermark,
        )
    }

    @Test
    fun `restart guard - a complete page ending below H is judged by the cutoff alone and lowers H`() {
        val restarted = listOf(event(1L, W - 1), event(2L, CUTOFF))

        val page = watermark.judgePage(restarted, truncated = false, cutoffWallMs = CUTOFF)

        assertEquals(restarted, page.kept)
        assertEquals(HistoryWatermark(2L, W), page.watermark)
    }

    @Test
    fun `restart guard - a truncated page or one that reaches H does not fire`() {
        val truncated = watermark.judgePage(listOf(event(1L, W - 1), event(2L, CUTOFF)), truncated = true, cutoffWallMs = CUTOFF)
        assertEquals(emptyList<HistoryEvent>(), truncated.kept)
        assertEquals(HistoryWatermark(H, W), truncated.watermark)

        val reachesH = watermark.judgePage(listOf(event(1L, W - 1), event(H, W)), truncated = false, cutoffWallMs = CUTOFF)
        assertEquals(listOf(event(H, W)), reachesH.kept)
        assertEquals(HistoryWatermark(H, W), reachesH.watermark)
    }

    private fun event(
        historyTimeMs: Long,
        wallMs: Long,
    ) = HistoryEvent(historyTimeMs, wallMs, HistoryKind.LEVEL, 50, -1L, 250, 4_000, 0)

    private companion object {
        const val H = 1_000L
        const val W = 50_000L
        const val CUTOFF = 40_000L
    }
}
