package com.akane.inspiration.xray

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.akane.inspiration.designsystem.component.VisibilityEffect
import com.akane.inspiration.designsystem.component.format.currentLocale

/**
 * Entry point of the X-Ray destination; the signature is pinned (docs/plans/phase-5.md "Entry points").
 *
 * Work follows visibility: while the route is started the ViewModel watches the analysis and the Shizuku
 * state and asks for a fresh capture once; when it stops, everything is cancelled (§6, no polling). The
 * inspected package only ever comes from a tap on the in-app list, never from an Intent or a URI.
 */
@Composable
fun XRayRoute(
    deps: XRayDependencies,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel<XRayViewModel>(key = "xray", factory = XRayViewModel.factory(deps))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = currentLocale()
    val explain = remember(deps, locale) {
        val dictionary = deps.dictionary()
        val explainer: Explainer = { kind, name -> dictionary.explain(kind, name, locale) }
        explainer
    }
    val loadIcon = remember(deps) {
        val loader: AppIconLoader = { packageName -> deps.appLabels.icon(packageName)?.asImageBitmap() }
        loader
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.xray_refresh_failed)

    // A rotation is not leaving: an open confirmation survives it (VisibilityEffect).
    VisibilityEffect(
        key = viewModel,
        onVisible = { viewModel.onIntent(XRayIntent.Visible) },
        onHidden = { viewModel.onIntent(XRayIntent.Hidden) },
    )
    // One-shot results are shown only while the route is started; the ViewModel queues none while it is not.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle, refreshFailed) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    XRayEffect.RefreshFailed -> snackbarHostState.showSnackbar(refreshFailed)
                }
            }
        }
    }

    XRayScreen(
        state = state,
        onIntent = viewModel::onIntent,
        explain = explain,
        modifier = modifier,
        loadIcon = loadIcon,
        snackbarHostState = snackbarHostState,
    )
}
