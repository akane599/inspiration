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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.akane.inspiration.designsystem.component.VisibilityEffect

/**
 * Entry point of the Cockpit destination; the signature is pinned (docs/plans/phase-5.md "Entry points").
 *
 * The ViewModel is Activity-scoped and outlives the tab, so visibility comes from composition (H13):
 * [VisibilityEffect] sends Visible on start and Hidden on activity stop and when the tab leaves composition, but not
 * across a configuration change, so a rotation keeps the live gauge. The ViewModel owns the live reads and the Shizuku
 * binding between the two.
 */
@Composable
fun CockpitRoute(
    deps: CockpitDependencies,
    modifier: Modifier = Modifier,
) {
    val viewModel =
        viewModel<CockpitViewModel>(
            key = "cockpit",
            factory = remember(deps) { CockpitViewModel.factory(deps) },
        )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.cockpit_refresh_failed)

    VisibilityEffect(
        key = viewModel,
        onVisible = { viewModel.onIntent(CockpitIntent.Visible) },
        onHidden = { viewModel.onIntent(CockpitIntent.Hidden) },
    )
    // Effects belong to the visible visit: they are collected only while STARTED (the ViewModel drains them on Hidden).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle, refreshFailed) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    CockpitEffect.RefreshFailed -> snackbarHost.showSnackbar(refreshFailed)
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        CockpitScreen(state = state, onIntent = viewModel::onIntent)
        SnackbarHost(hostState = snackbarHost, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
