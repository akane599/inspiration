package com.akane.inspiration.toolkit

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Entry point of the Toolkit destination; the signature is pinned (docs/plans/phase-5.md "Entry points").
 *
 * Work follows visibility: while the route is started the ViewModel reads the settings and follows the
 * Shizuku state, the overrides and the bg-dexopt status; when it stops, all of it is cancelled (§6, no
 * polling). The screen never takes a binding hold of its own: the toolkit actions hold the service while
 * a change needs it. The package a change applies to only ever comes from a tap on the in-app list, never
 * from an Intent or a URI.
 */
@Composable
fun ToolkitRoute(
    deps: ToolkitDependencies,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel<ToolkitViewModel>(key = "toolkit", factory = ToolkitViewModel.factory(deps))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loadIcon = remember(deps) {
        val loader: AppIconLoader = { packageName -> deps.appLabels.icon(packageName)?.asImageBitmap() }
        loader
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LifecycleStartEffect(viewModel) {
        viewModel.onIntent(ToolkitIntent.Visible)
        onStopOrDispose { viewModel.onIntent(ToolkitIntent.Hidden) }
    }
    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ToolkitEffect.ResetFinished ->
                    snackbarHostState.showSnackbar(context.getString(R.string.toolkit_reset_finished, effect.restored, effect.total))
            }
        }
    }

    ToolkitScreen(
        state = state,
        onIntent = viewModel::onIntent,
        loadIcon = loadIcon,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}
