package com.akane.inspiration.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.akane.inspiration.designsystem.theme.spacing
import kotlinx.collections.immutable.ImmutableList

private val CheckSize = 14.dp
private val CheckStroke = 2.dp
private val BorderWidth = 1.dp
private val MinHeight = 40.dp

// The check as a polyline in a unit square: short stroke down-right, long stroke up-right.
private val CheckPoints = listOf(0.12f to 0.55f, 0.40f to 0.80f, 0.88f to 0.22f)

/**
 * A single-choice toggle over [options] (labels), with the option at index [selected] checked. Every segment gets
 * exactly the same share of the width. A label wraps to a second line only at spaces, never inside a word: when its
 * longest word does not fit the segment it stays on one line and ends in an ellipsis instead ("Uygulam…" beats
 * "Uygula/ma"). The check mark is small, and is left out of a segment where it would squeeze the label out of its
 * line; the filled background still marks the selected segment, so long labels survive 360 dp at 1.5x font scale.
 *
 * Built on plain selectable boxes (radio-button role, 40 dp tall, the M3 segmented-button shapes and colours)
 * because the M3 segmented-button row left the segments at unequal widths with long Turkish labels.
 */
@Composable
fun SegmentedToggle(
    options: ImmutableList<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val labelStyle = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val count = options.size
        // Neighbouring segments overlap by one border width so the shared edge is a single line.
        val segmentWidth = (maxWidth + BorderWidth * (count - 1)) / count
        val textWidthPx = with(density) { (segmentWidth - spacing.xs * 2).toPx() }
        val checkPx = with(density) { (CheckSize + spacing.xs).toPx() }
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(-BorderWidth),
        ) {
            options.forEachIndexed { index, label ->
                val longestWordPx = label.split(' ').maxOf {
                    measurer.measure(it, labelStyle, softWrap = false, maxLines = 1).size.width
                }
                val wraps = longestWordPx <= textWidthPx
                Segment(
                    label = label,
                    isSelected = index == selected,
                    showCheck = index == selected && longestWordPx + checkPx <= textWidthPx,
                    wraps = wraps,
                    labelStyle = labelStyle,
                    index = index,
                    count = count,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun Segment(
    label: String,
    isSelected: Boolean,
    showCheck: Boolean,
    wraps: Boolean,
    labelStyle: TextStyle,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = SegmentedButtonDefaults.itemShape(index = index, count = count)
    val container = if (isSelected) colors.secondaryContainer else Color.Transparent
    val content = if (isSelected) colors.onSecondaryContainer else colors.onSurface
    val border = BorderStroke(BorderWidth, colors.outline)
    Box(
        modifier = modifier
            // The selected segment sits above its neighbours so its whole outline shows.
            .zIndex(if (isSelected) 1f else 0f)
            .defaultMinSize(minHeight = MinHeight)
            .clip(shape)
            .background(container)
            .border(border, shape)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = MaterialTheme.spacing.xs, vertical = MaterialTheme.spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides content) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showCheck) {
                    CheckGlyph()
                    Spacer(Modifier.width(MaterialTheme.spacing.xs))
                }
                Text(
                    text = label,
                    style = labelStyle,
                    color = content,
                    textAlign = TextAlign.Center,
                    maxLines = if (wraps) 2 else 1,
                    softWrap = wraps,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

/** The selected-segment check, drawn small (the M3 default is 18 dp) in the current content colour. */
@Composable
private fun CheckGlyph() {
    val color = LocalContentColor.current
    Canvas(modifier = Modifier.size(CheckSize)) {
        val path = Path().apply {
            CheckPoints.forEachIndexed { i, (x, y) ->
                val point = Offset(x * size.width, y * size.height)
                if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = CheckStroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
