package com.akane.inspiration.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.akane.inspiration.designsystem.theme.numericTypography
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.telemetryColors
import kotlinx.collections.immutable.persistentListOf

/** Every base component in one sheet, for the screenshot previews. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComponentSheet() {
    val telemetry = MaterialTheme.telemetryColors
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            Text("Components", style = MaterialTheme.typography.titleLarge)

            InspirationCard(modifier = Modifier.fillMaxWidth()) {
                CardTitle("Battery level")
                MetricValue(value = "87", unit = "%", style = MaterialTheme.numericTypography.display)
            }

            InspirationCard(modifier = Modifier.fillMaxWidth(), onClick = {}) {
                CardTitle("Tappable card")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                    itemVerticalAlignment = Alignment.Bottom,
                ) {
                    MetricValue(value = "36.4", unit = "°C")
                    MetricValue(value = "1h 24m", unit = "deep sleep")
                    MetricValue(value = "-312", unit = "mA", style = MaterialTheme.numericTypography.body)
                }
            }

            InspirationCard(modifier = Modifier.fillMaxWidth()) {
                CardTitle("Semantic dots")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = MaterialTheme.spacing.sm),
                ) {
                    SemanticDot(telemetry.deepSleep, contentDescription = "Deep sleep")
                    SemanticDot(telemetry.screenOn, contentDescription = "Screen on")
                    SemanticDot(telemetry.awake, contentDescription = "Awake")
                    SemanticDot(telemetry.critical, contentDescription = "Critical")
                    SemanticDot(telemetry.charging, contentDescription = "Charging")
                }
            }

            InspirationCard(modifier = Modifier.fillMaxWidth()) {
                CardTitle("Legend")
                LegendRow(
                    items = persistentListOf(
                        LegendItem(telemetry.screenOn, "Screen on"),
                        LegendItem(telemetry.deepSleep, "Deep sleep"),
                        LegendItem(telemetry.awake, "Awake"),
                        LegendItem(telemetry.charging, "Charging"),
                    ),
                    modifier = Modifier.padding(top = MaterialTheme.spacing.sm),
                )
            }
        }
    }
}
