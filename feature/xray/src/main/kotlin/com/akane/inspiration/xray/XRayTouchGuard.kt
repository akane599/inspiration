package com.akane.inspiration.xray

import android.view.MotionEvent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputEventHandler
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Compose's counterpart of `View.setFilterTouchesWhenObscured(true)`, which Compose has no modifier for.
 * While another window covers this one, the system marks the touch with FLAG_WINDOW_IS_OBSCURED; the
 * events are consumed on the way down, before any child (button, switch, chip) sees them, so a
 * tapjacking overlay can't press the privileged quick actions. Put it on the container of destructive controls,
 * never on a Cancel or dismiss control (an overlay pressing those can do no harm, and a screen dimmer
 * would otherwise trap the user). The first blocked press of each gesture calls [LocalObscuredTouchNotice].
 */
internal fun Modifier.filterTouchesWhenObscured(): Modifier = this then ObscuredTouchGuardElement

/** Called on a blocked press, to say why nothing happened. The screen provides it; previews keep the no-op. */
internal val LocalObscuredTouchNotice = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * The notice for [LocalObscuredTouchNotice]: a snackbar on [hostState], at most one queued or showing at a
 * time, so a burst of blocked taps doesn't line up a row of identical snackbars. A no-op without a host.
 */
@Composable
internal fun rememberObscuredTouchNotice(hostState: SnackbarHostState?): () -> Unit {
    val message = stringResource(R.string.xray_touch_obscured)
    val scope = rememberCoroutineScope()
    return remember(hostState, scope, message) {
        var shown: Job? = null
        val notice: () -> Unit = {
            if (hostState != null && shown?.isActive != true) shown = scope.launch { hostState.showSnackbar(message) }
        }
        notice
    }
}

/**
 * The guard's decision for one pointer event, free of Compose so a JVM test covers it. An obscured event is
 * always blocked; the first obscured press of a gesture also asks for the notice, and the gesture ends when
 * no pointer is down any more.
 */
internal class ObscuredTouchGate {
    enum class Verdict { PASS, BLOCK, BLOCK_AND_NOTIFY }

    private var noticed = false

    /** [obscured]: another window covered this event; [pressed]: a pointer went down in it; [anyDown]: one is still down after it. */
    fun onEvent(obscured: Boolean, pressed: Boolean, anyDown: Boolean): Verdict {
        val notify = obscured && pressed && !noticed
        if (notify) noticed = true
        if (!anyDown) noticed = false
        return when {
            notify -> Verdict.BLOCK_AND_NOTIFY
            obscured -> Verdict.BLOCK
            else -> Verdict.PASS
        }
    }
}

private data object ObscuredTouchGuardElement : ModifierNodeElement<ObscuredTouchGuardNode>() {
    override fun create() = ObscuredTouchGuardNode()

    override fun update(node: ObscuredTouchGuardNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "filterTouchesWhenObscured"
    }
}

private class ObscuredTouchGuardNode : DelegatingNode(), PointerInputModifierNode, CompositionLocalConsumerModifierNode {
    private val gate = ObscuredTouchGate()

    private val pointerInput = delegate(
        SuspendingPointerInputModifierNode(
            PointerInputEventHandler {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val verdict = gate.onEvent(
                            obscured = event.isObscured(),
                            pressed = event.changes.any { it.pressed && !it.previousPressed },
                            anyDown = event.changes.any { it.pressed },
                        )
                        if (verdict != ObscuredTouchGate.Verdict.PASS) event.changes.forEach { it.consume() }
                        if (verdict == ObscuredTouchGate.Verdict.BLOCK_AND_NOTIFY) currentValueOf(LocalObscuredTouchNotice)()
                    }
                }
            },
        ),
    )

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        pointerInput.onPointerEvent(pointerEvent, pass, bounds)
    }

    override fun onCancelPointerInput() {
        pointerInput.onCancelPointerInput()
    }
}

private fun PointerEvent.isObscured(): Boolean = (motionEvent?.flags ?: 0) and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0
