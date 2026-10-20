package com.akane.inspiration.toolkit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.akane.inspiration.designsystem.component.filterTouchesWhenObscured
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.statusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfirmationDialog(confirmation: Confirmation, onIntent: (ToolkitIntent) -> Unit) {
    BasicAlertDialog(onDismissRequest = { onIntent(ToolkitIntent.DismissConfirmation) }) {
        ConfirmationPanel(
            confirmation = confirmation,
            onConfirm = { onIntent(ToolkitIntent.ConfirmPending) },
            onDismiss = { onIntent(ToolkitIntent.DismissConfirmation) },
        )
    }
}

/**
 * The dialog's content: what will happen, the typed warnings in words, and a yes that is not the default
 * focus target. A [Confirmation.stale] one says the setting changed while the user was deciding, so the
 * yes is for the value that is there now.
 */
@Composable
fun ConfirmationPanel(
    confirmation: Confirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val destructive = confirmation.request == ToolkitRequest.ForceStop
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
        Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            Text(
                text = confirmationTitle(confirmation),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (confirmation.stale) InfoText(stringResource(R.string.toolkit_confirm_stale))
            if (confirmation.request == ToolkitRequest.ResetAll) InfoText(resetBody(confirmation))
            confirmation.warnings.forEach { warning ->
                Row(verticalAlignment = Alignment.Top) {
                    LineDot(color = MaterialTheme.statusColors.warning)
                    Text(
                        text = stringResource(warningRes(warning)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = spacing.sm),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Cancel stays outside the guard: an overlay can't do harm with it, and a dimmer mustn't trap the user here.
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.toolkit_confirm_cancel)) }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.filterTouchesWhenObscured(),
                    colors = if (destructive) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Text(stringResource(confirmActionRes(confirmation.request)))
                }
            }
        }
    }
}
