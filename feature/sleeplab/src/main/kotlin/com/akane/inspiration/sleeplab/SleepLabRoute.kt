package com.akane.inspiration.sleeplab

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
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.akane.inspiration.designsystem.component.VisibilityEffect
import com.akane.inspiration.diagnostics.dictionary.EntryKind
import com.akane.inspiration.diagnostics.dictionary.Explanation
import java.util.Locale

/**
 * Entry point of the Sleep Lab destination; the signature is pinned (docs/plans/phase-5.md "Entry points").
 *
 * The ViewModel is Activity-scoped and outlives the tab, so visibility comes from composition (H13):
 * [VisibilityEffect] sends Visible on start (which refreshes, debounced by the container) and Hidden on activity stop
 * and when the tab leaves composition, but neither across a configuration change. Nothing polls.
 */
@Composable
fun SleepLabRoute(
    deps: SleepLabDependencies,
    modifier: Modifier = Modifier,
) {
    val viewModel =
        viewModel<SleepLabViewModel>(
            key = "sleeplab",
            factory = remember(deps) { SleepLabViewModel.factory(deps) },
        )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale: Locale = LocalLocale.current.platformLocale
    // The dictionary words its explanations in the current locale, so a locale change rebuilds the lookup.
    val explain: (EntryKind, String) -> Explanation? =
        remember(deps, locale) {
            { kind, name -> deps.dictionary().explain(kind, name, locale) }
        }
    val snackbarHost = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.sleeplab_refresh_failed)

    VisibilityEffect(
        key = viewModel,
        onVisible = { viewModel.onIntent(SleepLabIntent.Visible) },
        onHidden = { viewModel.onIntent(SleepLabIntent.Hidden) },
    )
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle, refreshFailed) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    SleepLabEffect.RefreshFailed -> snackbarHost.showSnackbar(refreshFailed)
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        SleepLabScreen(state = state, onIntent = viewModel::onIntent, explain = explain)
        SnackbarHost(hostState = snackbarHost, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
