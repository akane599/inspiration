package com.akane.inspiration.designsystem.chart.scrub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.akane.inspiration.designsystem.R
import com.akane.inspiration.designsystem.chart.math.normalizeSelection
import com.akane.inspiration.designsystem.chart.math.overlaps
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.theme.spacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

private const val MINUTE_MS = 60_000L

/** A boundary button moves an edge by the window divided by this: 30 min on the Cockpit's 24 h chart. */
private const val ADJUSTMENTS_PER_WINDOW = 48

/**
 * How far one boundary button moves an edge: 1/[ADJUSTMENTS_PER_WINDOW] of [window] in whole minutes, never under a
 * minute. A fixed minute made a 3 h slice on a 24 h chart take about 175 activations, each restarting the analysis.
 */
internal fun adjustmentStepMs(window: TimeRange): Long =
    maxOf(MINUTE_MS, window.durationMs / ADJUSTMENTS_PER_WINDOW / MINUTE_MS * MINUTE_MS)

/** A button's immutable target; null clears the selection through the same callback as a plot tap. */
internal data class SliceControl(
    val labelRes: Int,
    val selection: TimeRange?,
    val enabled: Boolean = true,
)

/** Production targets shared by the buttons and boundary tests. No local selection shadows the caller's. */
internal fun sliceControls(
    window: TimeRange,
    selection: TimeRange?,
    minSelectionMs: Long,
    hasData: Boolean,
): ImmutableList<SliceControl> {
    val minimum = minSelectionMs.coerceAtLeast(1L)
    if (!hasData || window.durationMs < minimum) return persistentListOf()
    val create = SliceControl(R.string.chart_select_interval, TimeRange.of(window.endMs - minimum, window.endMs))
    if (selection == null) return persistentListOf(create)
    // A selection the window has slid past has no edge on the chart to move: adjusting it would edit an invented range
    // at the window's start. It can only be replaced by a new interval or cleared.
    if (!selection.overlaps(window)) {
        return persistentListOf(create, SliceControl(R.string.chart_clear_selection, selection = null))
    }
    val active = normalizeSelection(selection.startMs, selection.endMs, window, minimum) ?: return persistentListOf(create)
    val step = adjustmentStepMs(window)
    val shrinkBy = minOf(step, active.durationMs - minimum)

    fun adjustment(
        labelRes: Int,
        next: TimeRange,
    ) = SliceControl(labelRes, next, enabled = next != active)
    return persistentListOf(
        adjustment(
            R.string.chart_start_earlier,
            TimeRange.of(active.startMs - minOf(step, active.startMs - window.startMs), active.endMs),
        ),
        adjustment(R.string.chart_start_later, TimeRange.of(active.startMs + shrinkBy, active.endMs)),
        adjustment(R.string.chart_end_earlier, TimeRange.of(active.startMs, active.endMs - shrinkBy)),
        adjustment(
            R.string.chart_end_later,
            TimeRange.of(active.startMs, active.endMs + minOf(step, window.endMs - active.endMs)),
        ),
        SliceControl(R.string.chart_clear_selection, selection = null),
    )
}

/** Standard buttons provide discoverable semantic clicks and keyboard focus/activation, unlike plot gestures. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SliceControls(
    window: TimeRange,
    selection: TimeRange?,
    onSelectionChange: (TimeRange?) -> Unit,
    minSelectionMs: Long,
    hasData: Boolean,
    modifier: Modifier = Modifier,
) {
    val controls =
        remember(window, selection, minSelectionMs, hasData) {
            sliceControls(window, selection, minSelectionMs, hasData)
        }
    if (controls.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        controls.forEach { control ->
            key(control.labelRes) {
                TextButton(
                    onClick = { onSelectionChange(control.selection) },
                    enabled = control.enabled,
                ) {
                    Text(stringResource(control.labelRes))
                }
            }
        }
    }
}
