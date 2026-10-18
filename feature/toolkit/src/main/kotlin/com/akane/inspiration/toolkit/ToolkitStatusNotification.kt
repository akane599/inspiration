package com.akane.inspiration.toolkit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.akane.inspiration.designsystem.component.InspirationCard
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.statusColors

/**
 * The switch for the ongoing status notification. It lives outside the Shizuku gate: the notification reads
 * the local snapshot store, so it works whatever the Toolkit's own state is. When the system blocks the app's
 * notifications the switch is off the table and the card says how to get out of that.
 */
@Composable
internal fun StatusNotificationCard(
    state: StatusNotificationState,
    onIntent: (ToolkitIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val editable = !state.blocked
    InspirationCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = spacing.xxl)
                    .toggleable(
                        value = state.enabled,
                        enabled = editable,
                        role = Role.Switch,
                        onValueChange = { onIntent(ToolkitIntent.SetStatusNotification(it)) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text(
                        text = stringResource(R.string.toolkit_status_notification_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    InfoText(stringResource(R.string.toolkit_status_notification_body), small = true)
                }
                // Drawn by the row's toggleable: the switch itself takes no input.
                Switch(checked = state.enabled, onCheckedChange = null, enabled = editable)
            }
            if (state.blocked) {
                // Stacked, not side by side: at a large font scale the message and the button would fight for width.
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    LineDot(color = MaterialTheme.statusColors.warning, style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = stringResource(R.string.toolkit_status_notification_blocked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = spacing.sm),
                    )
                }
                TextButton(
                    onClick = { onIntent(ToolkitIntent.OpenNotificationSettings) },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.toolkit_status_notification_allow))
                }
            }
        }
    }
}
