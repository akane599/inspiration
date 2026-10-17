package com.akane.inspiration.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.telemetryColors
import kotlinx.collections.immutable.persistentListOf

// Wide windows show the two halves of a sheet side by side, which also checks the components at 840 dp.
private const val WIDE_SHEET_DP = 600

@Composable
private fun SheetFrame(title: String, left: @Composable () -> Unit, right: @Composable () -> Unit) {
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_SHEET_DP
    val gap = MaterialTheme.spacing.md
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) { left() }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) { right() }
                }
            } else {
                left()
                right()
            }
        }
    }
}

/** ReferencePill, SegmentedToggle, ShizukuPulse, StackedBar, TimeSliceBanner and EmptyState. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlsSheet() {
    val telemetry = MaterialTheme.telemetryColors
    val format = rememberTelemetryFormat()
    val spacing = MaterialTheme.spacing
    SheetFrame(
        title = "Controls",
        left = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                ReferencePill(label = "Since last charge", onClick = {})
                ReferencePill(label = "Last 24 h", onClick = {})
            }
            SegmentedToggle(
                options = persistentListOf("6 h", "24 h", "7 d"),
                selected = 1,
                onSelect = {},
                modifier = Modifier.fillMaxWidth(),
            )
            InspirationCard(modifier = Modifier.fillMaxWidth()) {
                Text("Shizuku", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    modifier = Modifier.padding(top = spacing.sm),
                ) {
                    ShizukuPulse(PulseStatus.ACTIVE, "Active")
                    ShizukuPulse(PulseStatus.CONNECTING, "Connecting")
                    ShizukuPulse(PulseStatus.BLOCKED, "Blocked")
                }
            }
            InspirationCard(modifier = Modifier.fillMaxWidth()) {
                Text("Screen time", style = MaterialTheme.typography.labelLarge)
                StackedBar(
                    segments = persistentListOf(
                        BarSegment(0.30f, BarRole.SCREEN_ON, "Screen on", format.duration(135 * 60_000L)),
                        BarSegment(0.20f, BarRole.AWAKE, "Awake", format.duration(90 * 60_000L)),
                        BarSegment(0.35f, BarRole.DEEP_SLEEP, "Deep doze", format.duration(157 * 60_000L)),
                    ),
                    total = 1f,
                    modifier = Modifier.padding(vertical = spacing.sm),
                )
                LegendRow(
                    items = persistentListOf(
                        LegendItem(telemetry.screenOn, "Screen on"),
                        LegendItem(telemetry.awake, "Awake"),
                        LegendItem(telemetry.deepSleep, "Deep doze"),
                    ),
                )
            }
        },
        right = {
            TimeSliceBanner(
                requested = "14:00 – 16:00",
                covered = "14:20 – 16:00",
                gapText = "No data 14:00 – 14:20",
                onClear = {},
                modifier = Modifier.fillMaxWidth(),
            )
            TimeSliceBanner(
                requested = "08:00 – 09:00",
                covered = "08:00 – 09:00",
                gapText = null,
                onClear = {},
                modifier = Modifier.fillMaxWidth(),
            )
            InspirationCard(modifier = Modifier.fillMaxWidth()) {
                EmptyState(
                    title = "No data for this period",
                    body = "Pick a longer range, or leave Inspiration running to collect more.",
                    action = EmptyStateAction("Show last 24 h", onClick = {}),
                )
            }
        },
    )
}

/** MetricTile bento, LiveGauge (discharging, charging, unavailable). */
@Composable
fun TelemetrySheet() {
    val format = rememberTelemetryFormat()
    val spacing = MaterialTheme.spacing
    SheetFrame(
        title = "Telemetry",
        left = {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                MetricTile("Battery", "87", "Since 06:10", 0.87f, Modifier.weight(1f).fillMaxHeight(), primaryUnit = "%")
                MetricTile("Drain", format.percentPerHour(-3.2).value, "Screen off", null, Modifier.weight(1f).fillMaxHeight(), primaryUnit = format.units.percentPerHour)
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                MetricTile("Deep doze", "62", "of screen-off time", 0.62f, Modifier.weight(1f).fillMaxHeight(), primaryUnit = "%", onClick = {})
                MetricTile("Temperature", format.celsius(36.4, decimals = 1).value, "Peak 41 °C", null, Modifier.weight(1f).fillMaxHeight(), primaryUnit = format.units.celsius)
            }
        },
        right = {
            LiveGauge(
                currentMa = -312,
                watts = -1.18,
                voltageMv = 3920,
                temperatureC = 31.5,
                remaining = "About 14 h 20 min left",
                isCharging = false,
                modifier = Modifier.fillMaxWidth(),
            )
            LiveGauge(
                currentMa = 2860,
                watts = 11.4,
                voltageMv = 4105,
                temperatureC = 38.2,
                remaining = null,
                isCharging = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}
