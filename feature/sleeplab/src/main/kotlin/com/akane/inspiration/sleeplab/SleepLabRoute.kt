package com.akane.inspiration.sleeplab

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.akane.inspiration.designsystem.component.EmptyState

/**
 * Entry point of the Sleep Lab destination. Placeholder until the screen ticket replaces the body; the
 * signature is pinned (docs/plans/phase-5.md "Entry points").
 */
@Composable
fun SleepLabRoute(
    deps: SleepLabDependencies,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            title = stringResource(R.string.sleeplab_stub_title),
            body = stringResource(R.string.sleeplab_stub_body),
            action = null,
        )
    }
}
