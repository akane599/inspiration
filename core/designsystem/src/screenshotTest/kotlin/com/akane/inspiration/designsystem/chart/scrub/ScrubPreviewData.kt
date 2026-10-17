package com.akane.inspiration.designsystem.chart.scrub

import com.akane.inspiration.designsystem.chart.model.Lane
import com.akane.inspiration.designsystem.chart.model.LaneKind
import com.akane.inspiration.designsystem.chart.model.LaneSegment
import com.akane.inspiration.designsystem.chart.model.LineSeries
import com.akane.inspiration.designsystem.chart.model.ScrubChartData
import com.akane.inspiration.designsystem.chart.model.SeriesKind
import com.akane.inspiration.designsystem.chart.model.TimePoint
import com.akane.inspiration.designsystem.chart.model.TimeRange
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.time.Instant
import java.time.ZoneId
import kotlin.math.sin

/** Synthetic 24 h of battery history in UTC, so tick labels are identical on every machine. */
internal object ScrubPreviewData {
    val zone: ZoneId = ZoneId.of("UTC")

    private const val HOUR_MS = 3_600_000L
    private const val STEP_MS = 10 * 60_000L
    private val startMs = Instant.parse("2026-10-16T00:00:00Z").toEpochMilli()

    val window = TimeRange.of(startMs, startMs + 24 * HOUR_MS)

    /** 13:30 to 17:30, the heavy-use stretch that also holds the temperature excursion. */
    val selection = TimeRange.of(atHour(13.5), atHour(17.5))

    /** 14:50, inside the excursion. */
    val cursorMs = atHour(14.83)

    fun atHour(hour: Double): Long = startMs + (hour * HOUR_MS).toLong()

    // Discharge by day, near-flat overnight, charging from 21:30.
    private val batteryKeys = listOf(
        0.0 to 96f, 1.0 to 95f, 6.5 to 90f, 7.0 to 89f, 8.7 to 78f, 12.0 to 70f, 13.5 to 62f,
        15.0 to 48f, 17.3 to 30f, 19.2 to 26f, 21.5 to 17f, 24.0 to 62f,
    )

    // One excursion above 40 C between about 14:10 and 15:55.
    private val temperatureKeys = listOf(
        0.0 to 30f, 2.0 to 28f, 6.0 to 28f, 8.0 to 31f, 12.0 to 33f, 13.5 to 35f, 14.2 to 40.5f,
        14.7 to 43.5f, 15.2 to 44f, 15.8 to 41f, 16.2 to 38f, 17.5 to 34f, 19.0 to 32f,
        21.0 to 33f, 22.0 to 36f, 24.0 to 35f,
    )

    private fun curve(keys: List<Pair<Double, Float>>, ripple: Float): List<TimePoint> {
        val out = ArrayList<TimePoint>()
        var t = startMs
        while (t <= startMs + 24 * HOUR_MS) {
            val hour = (t - startMs).toDouble() / HOUR_MS
            val after = keys.indexOfFirst { it.first >= hour }.let { if (it < 0) keys.lastIndex else it }
            val a = keys[maxOf(after - 1, 0)]
            val b = keys[after]
            val fraction = if (b.first == a.first) 0.0 else (hour - a.first) / (b.first - a.first)
            val base = a.second + (b.second - a.second) * fraction.toFloat()
            out += TimePoint(t, base + ripple * sin(hour * 3.1).toFloat())
            t += STEP_MS
        }
        return out
    }

    private fun lane(kind: LaneKind, vararg spans: Pair<Double, Double>) =
        Lane.of(kind, spans.map { LaneSegment(atHour(it.first), atHour(it.second)) })

    val full = ScrubChartData(
        window = window,
        series = persistentListOf(
            LineSeries.of(SeriesKind.BATTERY_PERCENT, curve(batteryKeys, ripple = 0.3f)),
            LineSeries.of(SeriesKind.TEMPERATURE_C, curve(temperatureKeys, ripple = 0.25f)),
        ),
        lanes = listOf(
            lane(LaneKind.SCREEN_ON, 7.0 to 8.7, 12.2 to 13.0, 13.5 to 17.3, 18.5 to 19.2, 20.0 to 21.4),
            lane(LaneKind.DEEP_SLEEP, 0.7 to 2.2, 2.4 to 4.6, 4.8 to 6.4),
            lane(LaneKind.AWAKE, 2.2 to 2.4, 4.6 to 4.8, 6.4 to 7.0, 0.0 to 0.7),
            lane(LaneKind.CHARGING, 21.5 to 24.0),
        ).toImmutableList(),
    )

    val empty = ScrubChartData(window, persistentListOf(), persistentListOf())
}
