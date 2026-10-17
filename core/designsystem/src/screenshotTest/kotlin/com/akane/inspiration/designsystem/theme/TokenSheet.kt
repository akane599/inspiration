package com.akane.inspiration.designsystem.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private class Swatch(val name: String, val color: Color)

/** Color roles, semantic telemetry colors. */
@Composable
internal fun TokenSheetColors() {
    val scheme = MaterialTheme.colorScheme
    val telemetry = MaterialTheme.telemetryColors
    val roles = listOf(
        Swatch("background", scheme.background),
        Swatch("onBackground", scheme.onBackground),
        Swatch("surfaceContainerLowest", scheme.surfaceContainerLowest),
        Swatch("surfaceContainerLow", scheme.surfaceContainerLow),
        Swatch("surfaceContainer", scheme.surfaceContainer),
        Swatch("surfaceContainerHigh", scheme.surfaceContainerHigh),
        Swatch("surfaceContainerHighest", scheme.surfaceContainerHighest),
        Swatch("onSurface", scheme.onSurface),
        Swatch("onSurfaceVariant", scheme.onSurfaceVariant),
        Swatch("outline", scheme.outline),
        Swatch("outlineVariant", scheme.outlineVariant),
        Swatch("primary", scheme.primary),
        Swatch("onPrimary", scheme.onPrimary),
        Swatch("primaryContainer", scheme.primaryContainer),
        Swatch("secondary", scheme.secondary),
        Swatch("secondaryContainer", scheme.secondaryContainer),
        Swatch("tertiary", scheme.tertiary),
        Swatch("error", scheme.error),
        Swatch("errorContainer", scheme.errorContainer),
        Swatch("onErrorContainer", scheme.onErrorContainer),
    )
    val semantic = listOf(
        Swatch("deepSleep", telemetry.deepSleep),
        Swatch("lightSleep", telemetry.lightSleep),
        Swatch("screenOn", telemetry.screenOn),
        Swatch("awake", telemetry.awake),
        Swatch("critical", telemetry.critical),
        Swatch("charging", telemetry.charging),
        Swatch("batteryLine", telemetry.batteryLine),
        Swatch("temperatureLine", telemetry.temperatureLine),
        Swatch("chartGrid", telemetry.chartGrid),
        Swatch("chartAxisLabel", telemetry.chartAxisLabel),
        Swatch("deepSleepContainer", telemetry.deepSleepContainer),
        Swatch("screenOnContainer", telemetry.screenOnContainer),
        Swatch("awakeContainer", telemetry.awakeContainer),
        Swatch("criticalContainer", telemetry.criticalContainer),
        Swatch("chargingContainer", telemetry.chargingContainer),
    )
    SheetSurface {
        SectionTitle("Color roles")
        SwatchGrid(roles)
        SectionTitle("Telemetry colors")
        SwatchGrid(semantic)
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
            // Legend sample: hues used as the data marks they stand for.
            listOf(telemetry.deepSleep, telemetry.lightSleep, telemetry.screenOn, telemetry.awake, telemetry.critical, telemetry.charging)
                .forEach { Box(Modifier.weight(1f).height(MaterialTheme.spacing.md).background(it, MaterialTheme.shapes.extraSmall)) }
        }
    }
}

/** Type scale, numeric typography, shapes and spacing. */
@Composable
internal fun TokenSheetTypeAndShape() {
    val type = MaterialTheme.typography
    val numeric = MaterialTheme.numericTypography
    val styles = listOf(
        "displayLarge" to type.displayLarge, "displayMedium" to type.displayMedium, "displaySmall" to type.displaySmall,
        "headlineLarge" to type.headlineLarge, "headlineMedium" to type.headlineMedium, "headlineSmall" to type.headlineSmall,
        "titleLarge" to type.titleLarge, "titleMedium" to type.titleMedium, "titleSmall" to type.titleSmall,
        "bodyLarge" to type.bodyLarge, "bodyMedium" to type.bodyMedium, "bodySmall" to type.bodySmall,
        "labelLarge" to type.labelLarge, "labelMedium" to type.labelMedium, "labelSmall" to type.labelSmall,
    )
    val numericStyles = listOf(
        "display" to numeric.display, "headline" to numeric.headline, "title" to numeric.title,
        "body" to numeric.body, "label" to numeric.label,
    )
    val shapes = MaterialTheme.shapes
    SheetSurface {
        SectionTitle("Type scale (Inter)")
        styles.forEach { (name, style) -> SampleLine(name, "Deep sleep 87% 0123456789", style) }
        SectionTitle("Numeric typography (tnum)")
        numericStyles.forEach { (name, style) -> SampleLine(name, "11:11 1111.1 °C", style) }
        SectionTitle("Shapes")
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
            ShapeSample("xs 6", shapes.extraSmall)
            ShapeSample("s 10", shapes.small)
            ShapeSample("m 16", shapes.medium)
            ShapeSample("l 24", shapes.large)
            ShapeSample("xl 32", shapes.extraLarge)
        }
        SectionTitle("Spacing")
        val spacing = MaterialTheme.spacing
        listOf(
            "xs" to spacing.xs, "sm" to spacing.sm, "ms" to spacing.ms, "md" to spacing.md,
            "lg" to spacing.lg, "xl" to spacing.xl, "xxl" to spacing.xxl,
        ).forEach { (name, size) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                Text("$name ${size.value.toInt()}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(72.dp))
                Box(Modifier.width(size).height(MaterialTheme.spacing.sm).background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

@Composable
private fun SheetSurface(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) { content() }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = MaterialTheme.spacing.ms),
    )
}

@Composable
private fun SwatchGrid(swatches: List<Swatch>) {
    swatches.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm), modifier = Modifier.fillMaxWidth()) {
            pair.forEach { SwatchCell(it, Modifier.weight(1f)) }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun SwatchCell(swatch: Swatch, modifier: Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(32.dp)
                .background(swatch.color, MaterialTheme.shapes.extraSmall)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), MaterialTheme.shapes.extraSmall),
        )
        Column(Modifier.padding(start = MaterialTheme.spacing.sm)) {
            Text(swatch.name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = "#%08X".format(swatch.color.toArgb().toLong() and 0xFFFFFFFFL),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SampleLine(name: String, sample: String, style: TextStyle) {
    Column {
        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(sample, style = style, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

@Composable
private fun ShapeSample(label: String, shape: Shape) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
