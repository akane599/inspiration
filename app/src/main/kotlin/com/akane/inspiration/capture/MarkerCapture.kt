package com.akane.inspiration.capture

import com.akane.inspiration.telemetry.capture.CaptureResult

/**
 * A marker: a FULL capture that ingests the battery history, then the marker row on its snapshot. Null when the
 * capture was not saved. Once it was, [onHistoryStored] fires however writing the row ends: the history and the moved
 * horizon are stored either way, and a screen open meanwhile must re-read them.
 */
internal suspend fun captureMarker(
    capture: suspend () -> CaptureResult,
    addMarker: suspend (snapshotId: Long) -> Long,
    onHistoryStored: () -> Unit,
): Long? {
    val saved = capture() as? CaptureResult.Saved ?: return null
    return try {
        addMarker(saved.id)
    } finally {
        onHistoryStored()
    }
}
