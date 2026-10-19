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
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.theme.spacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

private const val ADJUSTMENT_STEP_MS = 60_000L

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
    val active =
        selection?.let { normalizeSelection(it.startMs, it.endMs, window, minimum) }
            ?: return persistentListOf(
                SliceControl(R.string.chart_select_interval, TimeRange.of(window.endMs - minimum, window.endMs)),
            )
    val shrinkBy = minOf(ADJUSTMENT_STEP_MS, active.durationMs - minimum)

    fun adjustment(
        labelRes: Int,
        next: TimeRange,
    ) = SliceControl(labelRes, next, enabled = next != active)
    return persistentListOf(
        adjustment(
            R.string.chart_start_earlier,
            TimeRange.of(active.startMs - minOf(ADJUSTMENT_STEP_MS, active.startMs - window.startMs), active.endMs),
        ),
        adjustment(R.string.chart_start_later, TimeRange.of(active.startMs + shrinkBy, active.endMs)),
        adjustment(R.string.chart_end_earlier, TimeRange.of(active.startMs, active.endMs - shrinkBy)),
        adjustment(
            R.string.chart_end_later,
            TimeRange.of(active.startMs, active.endMs + minOf(ADJUSTMENT_STEP_MS, window.endMs - active.endMs)),
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
