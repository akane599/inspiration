package com.akane.inspiration.designsystem.chart.hypnogram

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.akane.inspiration.designsystem.chart.model.HypnogramData
import com.akane.inspiration.designsystem.chart.model.HypnogramStage
import com.akane.inspiration.designsystem.chart.model.StageSegment
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.component.InspirationCard
import com.akane.inspiration.designsystem.theme.spacing
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Fixed zone and start so the rendered tick labels never depend on the machine running the previews. */
internal val PreviewZone: ZoneOffset = ZoneOffset.UTC
private val NightStartMs = ZonedDateTime.of(2026, 3, 10, 23, 0, 0, 0, PreviewZone).toInstant().toEpochMilli()
private const val MINUTE_MS = 60_000L

/** Epoch ms of [minutes] after 23:00. */
internal fun nightMs(minutes: Int): Long = NightStartMs + minutes * MINUTE_MS

internal fun nightWindow(): TimeRange = TimeRange.of(nightMs(0), nightMs(480))

private fun seg(from: Int, to: Int, stage: HypnogramStage) = StageSegment(nightMs(from), nightMs(to), stage)

private val Awake = HypnogramStage.AWAKE
private val Maint = HypnogramStage.MAINTENANCE
private val Light = HypnogramStage.LIGHT_IDLE
private val Deep = HypnogramStage.DEEP_IDLE

/** Eight hours, 23:00 to 07:00: four deep/light cycles, maintenance windows, wake pulses and one 30 minute gap. */
internal fun syntheticNight(): HypnogramData = HypnogramData.of(
    window = nightWindow(),
    segments = listOf(
        seg(0, 12, Awake), seg(12, 30, Light), seg(30, 75, Deep), seg(75, 90, Light),
        seg(90, 96, Maint), seg(96, 110, Light), seg(110, 150, Deep), seg(150, 165, Light),
        seg(165, 170, Maint), seg(170, 190, Light), seg(190, 215, Deep), seg(215, 240, Light),
        seg(240, 243, Awake), seg(243, 270, Light),
        // 270..300: nothing recorded.
        seg(300, 330, Light), seg(330, 345, Deep), seg(345, 390, Light), seg(390, 396, Maint),
        seg(396, 430, Light), seg(430, 440, Awake), seg(440, 470, Light), seg(470, 480, Awake),
    ),
    wakePulsesMs = listOf(126, 241, 352, 391, 468).map(::nightMs),
)

/** Partial data: nothing for the first hour, a long hole mid-night, and an early stop. */
internal fun partialNight(): HypnogramData = HypnogramData.of(
    window = nightWindow(),
    segments = listOf(
        seg(60, 80, Light), seg(80, 130, Deep), seg(130, 145, Light), seg(145, 150, Maint),
        // 150..250: nothing recorded.
        seg(250, 270, Light), seg(270, 310, Deep), seg(310, 340, Light),
        // 340..360 and 400..480: nothing recorded.
        seg(360, 372, Awake), seg(372, 400, Light),
    ),
    wakePulsesMs = listOf(266, 371).map(::nightMs),
)

internal fun emptyNight(): HypnogramData = HypnogramData.of(nightWindow(), emptyList(), emptyList())

/** The hypnogram on a card over the app background, the way a screen shows it. */
@Composable
internal fun HypnogramSheet(
    data: HypnogramData,
    selection: TimeRange? = null,
    cursorMs: Long? = null,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.md)) {
            InspirationCard(modifier = Modifier.fillMaxWidth()) {
                SleepHypnogram(
                    data = data,
                    selection = selection,
                    cursorMs = { cursorMs },
                    zone = PreviewZone,
                )
            }
        }
    }
}
