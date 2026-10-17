package com.akane.inspiration.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.akane.inspiration.designsystem.component.EmptyState
import com.akane.inspiration.designsystem.component.EmptyStateAction
import com.akane.inspiration.designsystem.component.InspirationCard
import com.akane.inspiration.designsystem.theme.spacing
import java.time.ZoneId

/**
 * The Charge & Health screen, stateless: the whole screen is a function of [state] and reports through [onIntent].
 * Under [HealthMetrics.TwoColumnMinWidth] it is one column in the spec's order (curve, health card, wear, analysis);
 * above it the curve and its analysis sit beside the health card and the wear chart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HealthScreen(
    state: HealthState,
    onIntent: (HealthIntent) -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val spacing = MaterialTheme.spacing
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = { onIntent(HealthIntent.Refresh) },
        modifier = modifier,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val wide = maxWidth >= HealthMetrics.TwoColumnMinWidth
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                when (state.phase) {
                    HealthPhase.Loading -> LoadingCard()
                    HealthPhase.Empty -> MessageCard(
                        titleRes = R.string.health_empty_title,
                        bodyRes = R.string.health_empty_body,
                        actionRes = R.string.health_empty_refresh,
                        onAction = { onIntent(HealthIntent.Refresh) },
                    )
                    HealthPhase.Error -> MessageCard(
                        titleRes = R.string.health_error_title,
                        bodyRes = R.string.health_error_body,
                        actionRes = R.string.health_error_retry,
                        onAction = { onIntent(HealthIntent.Refresh) },
                    )
                    HealthPhase.Ready -> if (wide) {
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                                CurveCard(curve = state.curve, charging = state.charging)
                                AnalysisCard(analysis = state.analysis)
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                                state.card?.let { HealthCard(card = it) }
                                WearCard(wear = state.wear, zone = zone)
                            }
                        }
                    } else {
                        CurveCard(curve = state.curve, charging = state.charging)
                        state.card?.let { HealthCard(card = it) }
                        WearCard(wear = state.wear, zone = zone)
                        AnalysisCard(analysis = state.analysis)
                    }
                }
            }
        }
    }
}

/** The designed empty and error states: one card that says what happened and offers the next step. */
@Composable
private fun MessageCard(
    titleRes: Int,
    bodyRes: Int,
    actionRes: Int,
    onAction: () -> Unit,
) {
    InspirationCard(modifier = Modifier.fillMaxWidth()) {
        EmptyState(
            title = stringResource(titleRes),
            body = stringResource(bodyRes),
            action = EmptyStateAction(stringResource(actionRes), onAction),
        )
    }
}
