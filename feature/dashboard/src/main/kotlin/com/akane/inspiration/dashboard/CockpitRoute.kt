package com.akane.inspiration.dashboard

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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Entry point of the Cockpit destination; the signature is pinned (docs/plans/phase-5.md "Entry points").
 *
 * The ViewModel is Activity-scoped and outlives the tab, so visibility comes from composition (H13):
 * [LifecycleStartEffect] sends Visible on start and Hidden from `onStopOrDispose`, which fires both on activity
 * stop and when the tab leaves composition. The ViewModel owns the live reads and the Shizuku binding between
 * the two.
 */
@Composable
fun CockpitRoute(
    deps: CockpitDependencies,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel<CockpitViewModel>(
        key = "cockpit",
        factory = remember(deps) { CockpitViewModel.factory(deps) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.cockpit_refresh_failed)

    LifecycleStartEffect(viewModel) {
        viewModel.onIntent(CockpitIntent.Visible)
        onStopOrDispose { viewModel.onIntent(CockpitIntent.Hidden) }
    }
    LaunchedEffect(viewModel, refreshFailed) {
        viewModel.effects.collect { effect ->
            when (effect) {
                CockpitEffect.RefreshFailed -> snackbarHost.showSnackbar(refreshFailed)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        CockpitScreen(state = state, onIntent = viewModel::onIntent)
        SnackbarHost(hostState = snackbarHost, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
