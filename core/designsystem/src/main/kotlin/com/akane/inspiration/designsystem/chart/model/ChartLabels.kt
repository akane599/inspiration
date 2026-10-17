package com.akane.inspiration.designsystem.chart.model

import androidx.annotation.StringRes
import com.akane.inspiration.designsystem.R

/** Legend and accessibility label for a lane. */
val LaneKind.labelRes: Int
    @StringRes get() = when (this) {
        LaneKind.SCREEN_ON -> R.string.chart_lane_screen_on
        LaneKind.DEEP_SLEEP -> R.string.chart_lane_deep_sleep
        LaneKind.AWAKE -> R.string.chart_lane_awake
        LaneKind.CHARGING -> R.string.chart_lane_charging
    }

/** Row label for a hypnogram stage. */
val HypnogramStage.labelRes: Int
    @StringRes get() = when (this) {
        HypnogramStage.AWAKE -> R.string.chart_stage_awake
        HypnogramStage.MAINTENANCE -> R.string.chart_stage_maintenance
        HypnogramStage.LIGHT_IDLE -> R.string.chart_stage_light_idle
        HypnogramStage.DEEP_IDLE -> R.string.chart_stage_deep_idle
    }
