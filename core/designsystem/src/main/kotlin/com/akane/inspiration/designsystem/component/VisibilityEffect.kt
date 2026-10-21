package com.akane.inspiration.designsystem.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect

/**
 * Tells a screen when the user can see it: [onVisible] when it starts and [onHidden] when it stops or leaves the
 * composition (a tab switch). Restarts when [key] changes.
 *
 * A configuration change (rotation, a dark-mode switch, a fold) recreates the activity but is not leaving: the
 * Activity-scoped ViewModel behind the screen survives it, and resetting it would drop what the user was doing (an open
 * confirmation, an observed plug-in). So the old activity's stop skips [onHidden] while it
 * [isChangingConfigurations][Activity.isChangingConfigurations], and the recreated activity skips the matching
 * [onVisible]. The skip crosses the recreation in the saved instance state, which is written after onStop.
 * Work bound to the activity instance itself, such as a receiver registered on its context, belongs in its own
 * `LifecycleStartEffect`, which still runs on every start and stop.
 */
@Composable
fun VisibilityEffect(
    key: Any?,
    onVisible: () -> Unit,
    onHidden: () -> Unit,
) {
    val activity = LocalContext.current.findActivity()
    // True from a configuration-change stop until the recreated screen starts: the screen stayed visible throughout.
    val stillVisible = rememberSaveable { mutableStateOf(false) }
    val currentOnVisible by rememberUpdatedState(onVisible)
    val currentOnHidden by rememberUpdatedState(onHidden)
    LifecycleStartEffect(key) {
        if (stillVisible.value) stillVisible.value = false else currentOnVisible()
        onStopOrDispose {
            if (activity?.isChangingConfigurations == true) stillVisible.value = true else currentOnHidden()
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
