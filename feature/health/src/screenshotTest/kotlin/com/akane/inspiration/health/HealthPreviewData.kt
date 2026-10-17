package com.akane.inspiration.health

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.akane.inspiration.designsystem.theme.InspirationTheme
import com.akane.inspiration.telemetry.store.HealthSample
import java.time.Instant
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

/** A synthetic Health screen in UTC so every machine renders the same labels. Previews never touch Shizuku. */
internal object HealthPreviewData {
    val zone: ZoneId = ZoneId.of("UTC")

    private const val DAY_MS = 24L * 3_600_000
    private val startMs = Instant.parse("2026-10-17T01:10:00Z").toEpochMilli()

    /**
     * A fast charge from 20 % to 92 %: power peaks early and tapers, voltage climbs toward the end of the
     * constant-current phase, and the phone runs warm in the middle, where the system throttles.
     */
    private fun chargePoints(history: Boolean): List<CurvePoint> = (20..92).map { level ->
        val t = (level - 20) / 72.0
        val power = if (level < 78) 9.0 + 6.0 * sin(Math.PI * t.coerceAtMost(0.8)) else 15.0 * (1 - (level - 78) / 16.0)
        val voltage = 3_700.0 + (level - 20) * 7.2 + if (level > 80) (level - 80) * 14.0 else 0.0
        val temperature = 29.0 + 14.0 * sin(Math.PI * t * 0.95) + 0.6 * cos(level * 1.7)
        CurvePoint(
            wallMs = startMs + (level - 20) * 60_000L,
            levelPct = level,
            watts = if (history) null else power,
            currentMa = if (history) null else (power / voltage * 1_000_000).toInt(),
            voltageMv = voltage.toInt(),
            temperatureC = temperature,
            thermalStatus = if (history) null else if (level in 58..67) 2 else 0,
            fromHistory = history,
        )
    }

    /** Seven monthly records, the newest today: capacity slipping from 98 % to 89 % of design. */
    private fun wearSamples() = listOf(98.4, 97.6, 95.1, 93.9, 92.0, 90.3, 88.9).mapIndexed { i, pct ->
        HealthSample(
            wallMs = startMs - (6 - i) * 30 * DAY_MS,
            fccMah = 5_000.0 * pct / 100.0,
            designMah = 5_000.0,
            cycleCount = 40 + i * 35,
        )
    }

    private val card = HealthCardUi(
        design = CapacityUi(5_000.0, pctOfDesign = null, missing = null),
        reported = CapacityUi(4_445.0, pctOfDesign = 88.9, missing = null),
        measured = CapacityUi(4_380.0, pctOfDesign = 87.6, missing = null),
        cycles = 250,
        cyclesMissing = null,
    )

    private val liveAnalysis = WearAnalysisUi(
        source = CurveSource.Live,
        chargeMs = 72 * 60_000L,
        hotMs = 21 * 60_000L,
        hotObservedMs = 70 * 60_000L,
        highVoltageMs = 9 * 60_000L,
        voltageObservedMs = 70 * 60_000L,
        maxTemperatureC = 43.2,
        maxVoltageMv = 4_410,
        sparse = false,
    )

    val charging = HealthState(
        phase = HealthPhase.Ready,
        charging = true,
        curve = curveUi(chargePoints(history = false), liveActive = true),
        card = card,
        wear = wearUi(wearSamples()),
        analysis = liveAnalysis,
        refreshing = false,
    )

    /** The same charge seen only through the battery history: no power, no current, no throttle flags. */
    val historyOnly = charging.copy(
        charging = false,
        curve = curveUi(chargePoints(history = true), liveActive = false),
        analysis = liveAnalysis.copy(
            source = CurveSource.History,
            hotMs = 0,
            hotObservedMs = 64 * 60_000L,
            highVoltageMs = 0,
            voltageObservedMs = 64 * 60_000L,
            maxTemperatureC = 39.4,
            maxVoltageMv = 4_320,
            sparse = true,
        ),
    )

    /** Kernel readable, no charge recorded, too few health records, and a measured figure missing for a reason. */
    val quiet = HealthState(
        phase = HealthPhase.Ready,
        charging = false,
        curve = CurveUi.None,
        card = card.copy(
            measured = CapacityUi(null, pctOfDesign = null, missing = MissingReason.NotMeasuredYet),
            cycles = null,
            cyclesMissing = MissingReason.NotReported,
        ),
        wear = WearUi.NotEnough(1),
        analysis = null,
        refreshing = false,
    )

    val empty = HealthState.Initial.copy(phase = HealthPhase.Empty)
    val error = HealthState.Initial.copy(phase = HealthPhase.Error)
    val loading = HealthState.Initial
}

@Composable
internal fun HealthPreviewHost(darkTheme: Boolean, state: HealthState) {
    InspirationTheme(darkTheme = darkTheme) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            HealthScreen(state = state, onIntent = {}, zone = HealthPreviewData.zone)
        }
    }
}
