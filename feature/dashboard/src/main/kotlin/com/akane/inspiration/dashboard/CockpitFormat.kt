package com.akane.inspiration.dashboard

import androidx.compose.ui.unit.dp

/** Layout constants of the Cockpit that are not spacing tokens. */
internal object CockpitMetrics {
    /** From this content width the story and gauge sit beside the metric grid. */
    val TwoColumnMinWidth = 600.dp

    /** From this font scale the metric tiles stack in one column so their figures keep their lines. */
    const val StackTilesFontScale = 1.3f
}
