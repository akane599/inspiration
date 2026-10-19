package com.akane.inspiration.dashboard

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.component.PulseStatus
import com.akane.inspiration.designsystem.theme.InspirationTheme
import com.akane.inspiration.diagnostics.analysis.SegmentKind
import com.akane.inspiration.diagnostics.analysis.Timeline
import com.akane.inspiration.diagnostics.analysis.TimelinePoint
import com.akane.inspiration.diagnostics.analysis.TimelineSegment
import com.akane.inspiration.diagnostics.story.BatteryStory
import com.akane.inspiration.diagnostics.story.StorySentence
import com.akane.inspiration.diagnostics.story.UsageRating
import kotlinx.collections.immutable.persistentListOf
import java.time.Instant
import java.time.ZoneId
import kotlin.math.sin

/** A synthetic Cockpit in UTC so every machine renders the same labels. Previews never touch Shizuku. */
internal object CockpitPreviewData {
    val zone: ZoneId = ZoneId.of("UTC")

    private const val HOUR_MS = 3_600_000L
    private const val STEP_MS = 10 * 60_000L
    private val startMs = Instant.parse("2026-10-16T00:00:00Z").toEpochMilli()

    private fun atHour(hour: Double) = startMs + (hour * HOUR_MS).toLong()

    private val batteryKeys =
        listOf(
            0.0 to 96f,
            1.0 to 95f,
            6.5 to 90f,
            7.0 to 89f,
            8.7 to 78f,
            12.0 to 70f,
            13.5 to 62f,
            15.0 to 48f,
            17.3 to 30f,
            19.2 to 26f,
            21.5 to 17f,
            24.0 to 62f,
        )
    private val temperatureKeys =
        listOf(
            0.0 to 30f,
            2.0 to 28f,
            6.0 to 28f,
            8.0 to 31f,
            12.0 to 33f,
            13.5 to 35f,
            14.2 to 40.5f,
            14.7 to 43.5f,
            15.2 to 44f,
            15.8 to 41f,
            16.2 to 38f,
            17.5 to 34f,
            19.0 to 32f,
            21.0 to 33f,
            22.0 to 36f,
            24.0 to 35f,
        )

    private fun curve(
        keys: List<Pair<Double, Float>>,
        ripple: Float,
    ): List<TimelinePoint> {
        val out = ArrayList<TimelinePoint>()
        var t = startMs
        while (t <= startMs + 24 * HOUR_MS) {
            val hour = (t - startMs).toDouble() / HOUR_MS
            val after = keys.indexOfFirst { it.first >= hour }.let { if (it < 0) keys.lastIndex else it }
            val a = keys[maxOf(after - 1, 0)]
            val b = keys[after]
            val fraction = if (b.first == a.first) 0.0 else (hour - a.first) / (b.first - a.first)
            out += TimelinePoint(t, a.second + (b.second - a.second) * fraction.toFloat() + ripple * sin(hour * 3.1).toFloat())
            t += STEP_MS
        }
        return out
    }

    private fun segment(
        kind: SegmentKind,
        from: Double,
        to: Double,
    ) = TimelineSegment(kind, atHour(from), atHour(to))

    val timeline =
        Timeline(
            segments =
                listOf(
                    segment(SegmentKind.SCREEN_ON, 7.0, 8.7),
                    segment(SegmentKind.SCREEN_ON, 12.2, 13.0),
                    segment(SegmentKind.SCREEN_ON, 13.5, 17.3),
                    segment(SegmentKind.SCREEN_ON, 18.5, 19.2),
                    segment(SegmentKind.SCREEN_ON, 20.0, 21.4),
                    segment(SegmentKind.DEEP_IDLE, 0.7, 2.2),
                    segment(SegmentKind.DEEP_IDLE, 2.4, 4.6),
                    segment(SegmentKind.DEEP_IDLE, 4.8, 6.4),
                    segment(SegmentKind.AWAKE, 0.0, 0.7),
                    segment(SegmentKind.AWAKE, 2.2, 2.4),
                    segment(SegmentKind.AWAKE, 4.6, 4.8),
                    segment(SegmentKind.AWAKE, 6.4, 7.0),
                    segment(SegmentKind.CHARGING, 21.5, 24.0),
                ),
            levelPoints = curve(batteryKeys, ripple = 0.3f),
            temperaturePoints = curve(temperatureKeys, ripple = 0.25f),
            wakePulsesMs = emptyList(),
            from = startMs,
            to = startMs + 24 * HOUR_MS,
        )

    private val story =
        BatteryStory(
            listOf(
                StorySentence.Summary(hours = 14.0, levelUsedPct = 48, endsNow = false),
                StorySentence.ScreenOnRate(pctPerHour = 8.2, rating = UsageRating.NORMAL),
                StorySentence.ScreenOffRate(pctPerHour = 0.5, rating = UsageRating.EXCELLENT),
                StorySentence.DeepSleep(ratio = 0.946),
                StorySentence.Anomalies(count = 0, topRule = null),
            ),
        )

    private val metrics =
        MetricsUi(
            activePctPerHour = 8.2,
            activeMahPerHour = 410.0,
            idlePctPerHour = 0.5,
            idleMahPerHour = 25.0,
            deepSleepRatio = 0.946,
            deepSleepMs = 11 * HOUR_MS + 4 * 60_000L,
            screenOnMs = 2 * HOUR_MS + 5 * 60_000L,
            screenOffAwakeMs = 40 * 60_000L,
        )

    private val live =
        LiveUi(
            currentMa = -412,
            watts = 1.65,
            voltageMv = 4_012,
            temperatureC = 31.5,
            remainingMs = 5 * HOUR_MS + 12 * 60_000L,
            charging = false,
        )

    private val shizukuReady = ShizukuUi(PulseStatus.ACTIVE, ShizukuReason.Ready, uid = 2000, canGrant = false)

    val ready =
        CockpitState(
            reference = ReferenceUi.SinceUnplugged,
            shizuku = shizukuReady,
            analysis = AnalysisUi.Ready(story, metrics),
            live = live,
            chart = ChartUi.Ready(scrubChartData(timeline)),
            slice = null,
            markerSheet = null,
            refreshing = false,
        )

    /**
     * The story and figures of the 13:30 to 17:30 slice: four hours, not the 14 h of the unplugged period, and in
     * the past, so "over these hours" rather than "in the last hours".
     */
    private val sliceStory =
        BatteryStory(
            listOf(
                StorySentence.Summary(hours = 4.0, levelUsedPct = 32, endsNow = false),
                StorySentence.ScreenOnRate(pctPerHour = 8.4, rating = UsageRating.NORMAL),
                StorySentence.ScreenOffRate(pctPerHour = 0.9, rating = UsageRating.EXCELLENT),
                StorySentence.DeepSleep(ratio = 0.03),
                StorySentence.Anomalies(count = 0, topRule = null),
            ),
        )

    private val sliceMetrics =
        MetricsUi(
            activePctPerHour = 8.4,
            activeMahPerHour = 420.0,
            idlePctPerHour = 0.9,
            idleMahPerHour = 45.0,
            deepSleepRatio = 0.03,
            deepSleepMs = 7 * 60_000L,
            screenOnMs = 3 * HOUR_MS + 48 * 60_000L,
            screenOffAwakeMs = 12 * 60_000L,
        )

    /** The brush has been released over 13:30 to 17:30; the data starts eight minutes late. */
    val slice =
        ready.copy(
            reference = ReferenceUi.TimeSlice,
            analysis = AnalysisUi.Ready(sliceStory, sliceMetrics),
            slice =
                SliceUi(
                    requested = TimeRange.of(atHour(13.5), atHour(17.5)),
                    covered = TimeRange.of(atHour(13.5) + 8 * 60_000L, atHour(17.5)),
                ),
        )

    /** No snapshots, Shizuku waiting for the user: the empty state offers Grant. */
    val empty =
        CockpitState(
            reference = ReferenceUi.SinceUnplugged,
            shizuku = ShizukuUi(PulseStatus.CONNECTING, ShizukuReason.PermissionRequired, uid = null, canGrant = true),
            analysis = AnalysisUi.Empty,
            live = null,
            chart =
                ChartUi.Ready(
                    scrubChartData(Timeline(emptyList(), emptyList(), emptyList(), emptyList(), startMs, startMs + 24 * HOUR_MS)),
                ),
            slice = null,
            markerSheet = null,
            refreshing = false,
        )

    val error =
        ready.copy(
            shizuku = ShizukuUi(PulseStatus.BLOCKED, ShizukuReason.NotRunning, uid = null, canGrant = false),
            analysis = AnalysisUi.Error,
            live = null,
            chart = ChartUi.Failed,
        )

    val loading = CockpitState.Initial.copy(shizuku = shizukuReady)

    val markers =
        MarkerSheetUi(
            loading = false,
            markers =
                persistentListOf(
                    MarkerUi(3, "After dinner", atHour(19.5)),
                    MarkerUi(2, "Before the update", atHour(11.25)),
                    MarkerUi(1, "Marker 08:00", atHour(8.0)),
                ),
            creating = false,
            failed = false,
            selectedId = 2,
        )
}

@Composable
internal fun CockpitPreviewHost(
    darkTheme: Boolean,
    state: CockpitState,
) {
    InspirationTheme(darkTheme = darkTheme) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            CockpitScreen(state = state, onIntent = {}, zone = CockpitPreviewData.zone)
        }
    }
}
