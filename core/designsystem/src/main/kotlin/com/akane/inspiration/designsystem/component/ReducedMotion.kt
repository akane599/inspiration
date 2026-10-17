package com.akane.inspiration.designsystem.component

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Overrides the reduced-motion decision for a subtree: true = no decorative motion, false = motion allowed,
 * null (default) = follow the system animator duration scale. Provide it from the app or from tests/previews.
 */
val LocalReducedMotion = staticCompositionLocalOf<Boolean?> { null }

/** True when decorative motion should be skipped. Read once per composition, like the system setting itself. */
@Composable
fun rememberReducedMotion(): Boolean {
    val override = LocalReducedMotion.current
    if (override != null) return override
    val context = LocalContext.current
    return remember(context) { animatorDurationScale(context) == 0f }
}

/** The safe default is 1 (animations on) when the setting is unreadable. */
private fun animatorDurationScale(context: Context): Float =
    try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (_: RuntimeException) {
        1f
    }
