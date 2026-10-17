package com.akane.inspiration.toolkit

import android.view.MotionEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Compose's counterpart of `View.setFilterTouchesWhenObscured(true)`, which Compose has no modifier for.
 * While another window covers this one, the system marks the touch with FLAG_WINDOW_IS_OBSCURED; the
 * events are consumed on the way down, before any child (button, switch, chip) sees them, so a
 * tapjacking overlay can't press the privileged controls. Put it on the container of destructive controls.
 */
internal fun Modifier.filterTouchesWhenObscured(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.isObscured()) event.changes.forEach { it.consume() }
        }
    }
}

private fun PointerEvent.isObscured(): Boolean = (motionEvent?.flags ?: 0) and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0
