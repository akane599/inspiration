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
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.statusColors
import com.akane.inspiration.designsystem.theme.telemetryColors
import kotlinx.collections.immutable.persistentListOf
import java.time.Instant
import java.time.ZoneId

// Wide windows show the two halves of a sheet side by side, which also checks the components at 840 dp.
private const val WIDE_SHEET_DP = 600

@Composable
private fun SheetFrame(
    title: String,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
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

// A fixed zone, so the banner previews render the same on every machine.
private val PreviewZone: ZoneId = ZoneId.of("UTC")

private fun previewRange(
    startIso: String,
    endIso: String,
) = TimeRange.of(Instant.parse(startIso).toEpochMilli(), Instant.parse(endIso).toEpochMilli())

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
                CardTitle("Shizuku")
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
                CardTitle("Screen time")
                StackedBar(
                    segments =
                        persistentListOf(
                            BarSegment(0.30f, BarRole.SCREEN_ON, "Screen on", format.duration(135 * 60_000L)),
                            BarSegment(0.20f, BarRole.AWAKE, "Awake", format.duration(90 * 60_000L)),
                            BarSegment(0.35f, BarRole.DEEP_SLEEP, "Deep doze", format.duration(157 * 60_000L)),
                            BarSegment(0.10f, BarRole.RADIOS, "Radios", format.duration(45 * 60_000L)),
                        ),
                    total = 1f,
                    modifier = Modifier.padding(vertical = spacing.sm),
                )
                LegendRow(
                    items =
                        persistentListOf(
                            LegendItem(telemetry.screenOn, "Screen on"),
                            LegendItem(telemetry.awake, "Awake"),
                            LegendItem(telemetry.deepSleep, "Deep doze"),
                            LegendItem(BarRole.RADIOS.color(), "Radios"),
                        ),
                )
            }
        },
        right = {
            TimeSliceBanner(
                requested = previewRange("2026-03-02T14:00:00Z", "2026-03-02T16:00:00Z"),
                covered = previewRange("2026-03-02T14:25:00Z", "2026-03-02T16:00:00Z"),
                onClear = {},
                modifier = Modifier.fillMaxWidth(),
                zone = PreviewZone,
            )
            TimeSliceBanner(
                requested = previewRange("2026-03-02T08:00:00Z", "2026-03-02T09:00:00Z"),
                covered = previewRange("2026-03-02T08:00:00Z", "2026-03-02T09:00:00Z"),
                onClear = {},
                modifier = Modifier.fillMaxWidth(),
                zone = PreviewZone,
            )
            TimeSliceBanner(
                requested = previewRange("2026-03-02T22:00:00Z", "2026-03-03T06:00:00Z"),
                covered = previewRange("2026-03-02T23:10:00Z", "2026-03-03T05:30:00Z"),
                onClear = {},
                modifier = Modifier.fillMaxWidth(),
                zone = PreviewZone,
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

/**
 * Duration headlines in MetricTile, NBSP-joined so they cannot wrap at a space. At 360 dp and 1.5x font the value
 * shrinks to fit instead of breaking mid-word ("2 h 05 mi" / "n"). English and Turkish units, short and long.
 */
@Composable
fun DurationTilesSheet() {
    val spacing = MaterialTheme.spacing
    val nbsp = " "
    SheetFrame(
        title = "Duration tiles",
        left = {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                MetricTile(
                    "Screen time",
                    "2${nbsp}h${nbsp}05${nbsp}min",
                    "Awake off-screen 14${nbsp}min",
                    null,
                    Modifier.weight(1f).fillMaxHeight(),
                )
                MetricTile(
                    "Ekran süresi",
                    "2${nbsp}sa${nbsp}05${nbsp}dk",
                    "Ekran kapalıyken 14${nbsp}dk",
                    null,
                    Modifier.weight(1f).fillMaxHeight(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                MetricTile("Screen time", "12${nbsp}h${nbsp}45${nbsp}min", null, null, Modifier.weight(1f).fillMaxHeight())
                MetricTile("Drain", "3.2", "Screen off", null, Modifier.weight(1f).fillMaxHeight(), primaryUnit = "%/h")
            }
        },
        right = {},
    )
}

/**
 * LiveGauge with a charger connected but the battery not charging: held at a charge limit (no time left), and losing
 * to the load (time left still shown). The status names the plug, never "Charging".
 */
@Composable
fun PluggedGaugeSheet() {
    SheetFrame(
        title = "Plugged, not charging",
        left = {
            LiveGauge(
                currentMa = 3,
                watts = 0.01,
                voltageMv = 4105,
                temperatureC = 30.2,
                remaining = "Time left: —",
                chargeState = GaugeChargeState.PluggedNotCharging,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        right = {
            LiveGauge(
                currentMa = -280,
                watts = -1.09,
                voltageMv = 3890,
                temperatureC = 39.6,
                remaining = "About 9 h 40 min left",
                chargeState = GaugeChargeState.PluggedNotCharging,
                modifier = Modifier.fillMaxWidth(),
            )
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
                MetricTile(
                    "Drain",
                    format.percentPerHour(-3.2).value,
                    "Screen off",
                    null,
                    Modifier.weight(1f).fillMaxHeight(),
                    primaryUnit = format.units.percentPerHour,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                MetricTile(
                    "Deep doze",
                    "62",
                    "of screen-off time",
                    0.62f,
                    Modifier.weight(1f).fillMaxHeight(),
                    primaryUnit = "%",
                    onClick = {},
                )
                MetricTile(
                    "Temperature",
                    format.celsius(36.4, decimals = 1).value,
                    "Peak 41 °C",
                    null,
                    Modifier.weight(1f).fillMaxHeight(),
                    primaryUnit = format.units.celsius,
                )
            }
        },
        right = {
            LiveGauge(
                currentMa = -312,
                watts = -1.18,
                voltageMv = 3920,
                temperatureC = 31.5,
                remaining = "About 14 h 20 min left",
                chargeState = GaugeChargeState.Discharging,
                modifier = Modifier.fillMaxWidth(),
            )
            LiveGauge(
                currentMa = 2860,
                watts = 11.4,
                voltageMv = 4105,
                temperatureC = 38.2,
                remaining = null,
                chargeState = GaugeChargeState.Charging,
                modifier = Modifier.fillMaxWidth(),
            )
            // Nothing readable: dashes at body size, no units, and a status that never says "Discharging".
            LiveGauge(
                currentMa = null,
                watts = null,
                voltageMv = null,
                temperatureC = null,
                remaining = null,
                chargeState = null,
                modifier = Modifier.fillMaxWidth(),
            )
            LiveGauge(
                currentMa = -312,
                watts = null,
                voltageMv = 3920,
                temperatureC = null,
                remaining = null,
                chargeState = GaugeChargeState.Discharging,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/** ScreenHeader, CardTitle, MetricBar and the status colours: the shared chrome of a screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChromeSheet() {
    val status = MaterialTheme.statusColors
    val spacing = MaterialTheme.spacing
    SheetFrame(
        title = "Chrome",
        left = {
            ScreenHeader(
                title = "Sleep Lab",
                subtitle = "What kept the phone awake, last night",
                actions = { ReferencePill(label = "Last night", onClick = {}) },
            )
            ScreenHeader(title = "Cockpit")
            InspirationCard(modifier = Modifier.fillMaxWidth()) {
                CardTitle("Screen-off drain, wake-ups, X-Ray")
                Text(
                    text = "Card titles share one style.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        right = {
            InspirationCard(modifier = Modifier.fillMaxWidth()) {
                CardTitle("MetricBar")
                Column(
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    modifier = Modifier.padding(top = spacing.sm),
                ) {
                    MetricBar(progress = 0f)
                    MetricBar(progress = 0.35f)
                    MetricBar(progress = 0.89f, color = MaterialTheme.telemetryColors.deepSleep)
                    MetricBar(progress = 1f)
                }
            }
            InspirationCard(modifier = Modifier.fillMaxWidth()) {
                CardTitle("Status colours")
                LegendRow(
                    items =
                        persistentListOf(
                            LegendItem(status.ok, "Active"),
                            LegendItem(status.warning, "Warning"),
                            LegendItem(status.neutral, "Idle"),
                        ),
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
        },
    )
}

/** SegmentedToggle with the longest labels, for the narrow large-font Turkish preview. */
@Composable
fun ToggleSheet() {
    SheetFrame(
        title = "Toggle",
        left = {
            // The exact Sleep Lab tab labels (sleeplab_tab_apps/kernel/alarms in Turkish), one per selected state:
            // at 360 dp and 1.5x none of them may break inside a word, and all segments keep the same width.
            for (selectedTab in 0..2) {
                SegmentedToggle(
                    options = persistentListOf("Uygulama", "Çekirdek", "Alarm"),
                    selected = selectedTab,
                    onSelect = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SegmentedToggle(
                options = persistentListOf("Son 6 saat", "Son 24 saat", "Son 7 gün"),
                selected = 1,
                onSelect = {},
                modifier = Modifier.fillMaxWidth(),
            )
            SegmentedToggle(
                options = persistentListOf("Uygulamalar", "Alarmlar", "Çekirdek uyandırmaları"),
                selected = 0,
                onSelect = {},
                modifier = Modifier.fillMaxWidth(),
            )
        },
        right = {
            SegmentedToggle(
                options = persistentListOf("6 h", "24 h", "7 d"),
                selected = 2,
                onSelect = {},
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}
