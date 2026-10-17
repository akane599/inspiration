package com.akane.inspiration.debug

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.akane.inspiration.debug.gallery.GALLERY_LOG_TAG
import com.akane.inspiration.debug.gallery.GalleryData
import com.akane.inspiration.debug.gallery.GalleryDataGenerator
import com.akane.inspiration.debug.gallery.RecomposeCounter
import com.akane.inspiration.debug.gallery.RecompositionCounter
import com.akane.inspiration.debug.gallery.gestureEndLine
import com.akane.inspiration.designsystem.chart.hypnogram.SleepHypnogram
import com.akane.inspiration.designsystem.chart.model.ScrubChartData
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.chart.scrub.SynchronizedScrubbingChart
import com.akane.inspiration.designsystem.theme.InspirationTheme
import com.akane.inspiration.designsystem.theme.spacing

/**
 * Debug-only gallery of the design-system charts over dense synthetic data (frame-stats target).
 * Launch: `adb shell am start -n com.akane.inspiration/.debug.DesignGalleryActivity`. It reads no intent extras.
 */
class DesignGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            InspirationTheme {
                DesignGalleryScreen()
            }
        }
    }
}

private const val NO_CURSOR = Long.MIN_VALUE

private class GalleryCounters {
    val parent = RecompositionCounter()
    val chart = RecompositionCounter()

    /** Logs the recompositions since the previous gesture end, then starts the next interval. */
    fun logGestureEnd() {
        Log.i(GALLERY_LOG_TAG, gestureEndLine(parent.drain(), chart.drain()))
    }

    fun reset() {
        parent.drain()
        chart.drain()
    }
}

@Composable
private fun DesignGalleryScreen() {
    val data = remember { GalleryDataGenerator.generate() }
    val counters = remember { GalleryCounters() }
    var selection by remember { mutableStateOf<TimeRange?>(null) }
    // Scrub cursor: written by the chart's gesture, read only in the hypnogram's draw phase.
    val cursor = remember { mutableLongStateOf(NO_CURSOR) }
    val cursorMs = remember { { cursor.longValue.takeIf { it != NO_CURSOR } } }
    val onScrub = remember {
        { tMs: Long? ->
            cursor.longValue = tMs ?: NO_CURSOR
            if (tMs == null) counters.logGestureEnd()
        }
    }
    val onSelectionChange = remember {
        { range: TimeRange? ->
            // Log before the state write so the commit's own recomposition belongs to the next interval.
            counters.logGestureEnd()
            selection = range
        }
    }
    // The first composition's side effects are setup, not gesture cost.
    LaunchedEffect(Unit) { counters.reset() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(MaterialTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg),
    ) {
        RecomposeCounter(counters.parent)
        GalleryScrubChart(
            data = data.chart,
            selection = selection,
            onSelectionChange = onSelectionChange,
            onScrub = onScrub,
            counter = counters.chart,
        )
        GalleryHypnogram(data = data, selection = selection, cursorMs = cursorMs)
    }
}

/** The chart's call site: its own recompose scope, so it is counted apart from the parent Column. */
@Composable
private fun GalleryScrubChart(
    data: ScrubChartData,
    selection: TimeRange?,
    onSelectionChange: (TimeRange?) -> Unit,
    onScrub: (Long?) -> Unit,
    counter: RecompositionCounter,
) {
    RecomposeCounter(counter)
    SynchronizedScrubbingChart(
        data = data,
        selection = selection,
        onSelectionChange = onSelectionChange,
        onScrub = onScrub,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GalleryHypnogram(
    data: GalleryData,
    selection: TimeRange?,
    cursorMs: () -> Long?,
) {
    SleepHypnogram(
        data = data.hypnogram,
        modifier = Modifier.fillMaxWidth(),
        selection = selection,
        cursorMs = cursorMs,
    )
}
