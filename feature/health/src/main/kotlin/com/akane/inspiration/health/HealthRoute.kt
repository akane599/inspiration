package com.akane.inspiration.health

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.akane.inspiration.designsystem.component.VisibilityEffect

/**
 * Entry point of the Charge & Health destination; the signature is pinned (docs/plans/phase-5.md "Entry points").
 *
 * The ViewModel is Activity-scoped and outlives the tab, so visibility comes from composition (H13):
 * [VisibilityEffect] sends Visible on start and Hidden on activity stop and when the tab leaves composition, but not
 * across a configuration change, so a rotation keeps the observed plug edges and the live buffer. The plug state comes
 * from the system's sticky battery broadcast, a push that costs no read, and is listened to only while started. The ViewModel owns the live readings and the
 * Shizuku binding, and holds them only while the phone is plugged in.
 */
@Composable
fun HealthRoute(
    deps: HealthDependencies,
    modifier: Modifier = Modifier,
) {
    val viewModel =
        viewModel<HealthViewModel>(
            key = "health",
            factory = remember(deps) { HealthViewModel.factory(deps) },
        )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.health_refresh_failed)
    val context = LocalContext.current

    // Composed first, so Visible arrives before the first sticky plug state and Hidden after the receiver is gone.
    VisibilityEffect(
        key = viewModel,
        onVisible = { viewModel.onIntent(HealthIntent.Visible) },
        onHidden = { viewModel.onIntent(HealthIntent.Hidden) },
    )
    // The receiver belongs to this activity's context, so it follows every start and stop, a recreation included. The
    // recreated one's sticky state repeats the known plug state, which keeps an observed plug-in edge.
    LifecycleStartEffect(viewModel, context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    viewModel.onIntent(HealthIntent.PlugChanged(isPlugged(intent)))
                }
            }
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) viewModel.onIntent(HealthIntent.PlugChanged(isPlugged(sticky)))
        onStopOrDispose { context.unregisterReceiver(receiver) }
    }
    // Effects belong to the visible visit: they are collected only while STARTED (the ViewModel drains them on Hidden).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle, refreshFailed) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    HealthEffect.RefreshFailed -> snackbarHost.showSnackbar(refreshFailed)
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        HealthScreen(state = state, onIntent = viewModel::onIntent)
        SnackbarHost(hostState = snackbarHost, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** True when any power source is connected (USB, AC, wireless, dock). Matches `LiveReading.plugged`. */
private fun isPlugged(battery: Intent): Boolean = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
