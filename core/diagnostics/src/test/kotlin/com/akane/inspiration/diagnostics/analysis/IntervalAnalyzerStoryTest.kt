package com.akane.inspiration.diagnostics.analysis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.akane.inspiration.diagnostics.story.AndroidStoryWriter
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.diff.ReferenceKind
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The rendered story of an analysed interval frames its time span by whether the interval runs up to now. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IntervalAnalyzerStoryTest {
    private val hour = 3_600_000L
    private val writer = AndroidStoryWriter(ApplicationProvider.getApplicationContext<Context>())
    private val tr = Locale.forLanguageTag("tr")

    /** Unplugged with the screen off at 01:00, 90% then; 70% at 05:00, when the screen came back on. */
    private fun store() = FakeSnapshotStore(
        snapshots = listOf(
            lightSnapshot(wallMs = 1 * hour, level = 90),
            lightSnapshot(wallMs = 5 * hour, level = 70),
        ),
        history = listOf(
            historyEvent(1 * hour, HistoryKind.UNPLUGGED),
            historyEvent(1 * hour, HistoryKind.SCREEN_OFF),
            historyEvent(1 * hour, HistoryKind.LEVEL, level = 90),
            historyEvent(5 * hour, HistoryKind.LEVEL, level = 70),
            historyEvent(5 * hour, HistoryKind.SCREEN_ON),
        ),
    )

    private suspend fun render(kind: ReferenceKind, nowWallMs: Long, locale: Locale): String {
        val analysis = IntervalAnalyzer(store(), ruleEngine()).analyze(kind, nowWallMs)
        return writer.render(checkNotNull(analysis) { "$kind resolves no interval" }.story, locale)
    }

    private fun assertStartsWith(expected: String, actual: String) =
        assertTrue("expected \"$actual\" to start with \"$expected\"", actual.startsWith(expected))

    @Test
    fun `a past time slice is not described as the last hours`() = runTest {
        // The slice 01:00-05:00 is analysed at 24:00, 19 hours after it ended.
        val slice = ReferenceKind.TimeSlice(fromWallMs = 1 * hour, toWallMs = 5 * hour)

        assertStartsWith("Over these 4 hours you used 20% of your battery.", render(slice, 24 * hour, Locale.ENGLISH))
        assertStartsWith("Bu 4 saatte pilin %20 kadarını kullandın.", render(slice, 24 * hour, tr))
    }

    @Test
    fun `a completed screen-off is not described as the last hours`() = runTest {
        // The screen-off 01:00-05:00 is the last completed one; it is analysed an hour after it ended.
        assertStartsWith(
            "Over these 4 hours you used 20% of your battery.",
            render(ReferenceKind.SinceScreenOff, 6 * hour, Locale.ENGLISH),
        )
    }

    @Test
    fun `an interval that runs up to now keeps the last hours wording`() = runTest {
        assertStartsWith(
            "In the last 4 hours you used 20% of your battery.",
            render(ReferenceKind.SinceUnplugged, 5 * hour, Locale.ENGLISH),
        )
        assertStartsWith("Son 4 saatte pilin %20 kadarını kullandın.", render(ReferenceKind.SinceUnplugged, 5 * hour, tr))
    }
}
