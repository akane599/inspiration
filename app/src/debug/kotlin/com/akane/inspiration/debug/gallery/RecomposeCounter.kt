package com.akane.inspiration.debug.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect

/** Plain, single-thread (main) recomposition tally. Debug only. */
internal class RecompositionCounter {
    private var count = 0

    fun increment() {
        count++
    }

    /** Returns the count since the last drain and starts again from zero. */
    fun drain(): Int {
        val drained = count
        count = 0
        return drained
    }
}

/**
 * Counts every composition of the scope that calls it. It is inline on purpose: a restartable
 * composable with an unchanged argument would be skipped and miss the very recompositions it measures.
 */
@Composable
internal inline fun RecomposeCounter(counter: RecompositionCounter) {
    SideEffect { counter.increment() }
}

/** The one-line JSON the orchestrator reads from logcat tag [GALLERY_LOG_TAG] on every gesture end. */
internal fun gestureEndLine(parentRecompositions: Int, chartRecompositions: Int): String =
    """{"event":"gesture_end","parentRecompositions":$parentRecompositions,"chartRecompositions":$chartRecompositions}"""

internal const val GALLERY_LOG_TAG = "InspirationGallery"
