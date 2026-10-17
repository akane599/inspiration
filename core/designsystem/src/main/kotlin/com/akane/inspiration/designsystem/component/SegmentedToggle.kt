package com.akane.inspiration.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akane.inspiration.designsystem.theme.spacing
import kotlinx.collections.immutable.ImmutableList

private val CheckSize = 14.dp
private val CheckStroke = 2.dp

// The check as a polyline in a unit square: short stroke down-right, long stroke up-right.
private val CheckPoints = listOf(0.12f to 0.55f, 0.40f to 0.80f, 0.88f to 0.22f)

/**
 * A single-choice toggle over [options] (labels), with the option at index [selected] checked. Wraps the M3
 * segmented button row, so the targets and the accessibility roles are the platform's. Every segment gets an
 * equal share of the width, a label wraps to a second line and then ends in an ellipsis, and the check mark is
 * small, so long labels ("Son 24 saat") survive 360 dp at 1.5x font scale.
 */
@Composable
fun SegmentedToggle(
    options: ImmutableList<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selected
            SegmentedButton(
                selected = isSelected,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                // The check sits in the label row instead of M3's icon slot: the slot keeps its own width and
                // clips the check when a long label leaves no room, while here the label gives way to it.
                icon = {},
                contentPadding = PaddingValues(horizontal = MaterialTheme.spacing.xs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) {
                        CheckGlyph()
                        Spacer(Modifier.width(MaterialTheme.spacing.xs))
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
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
