package com.akane.inspiration.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.akane.inspiration.designsystem.component.ReferencePill
import com.akane.inspiration.designsystem.component.ScreenHeader
import com.akane.inspiration.designsystem.component.ShizukuPulse
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.ipc.ShizukuBackend

/**
 * The top of the screen: its title, then the reference pill with its menu and the Shizuku connection status.
 * [showGrant] is false while the story card already offers the same "Grant access" button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CockpitHeader(
    reference: ReferenceUi,
    shizuku: ShizukuUi,
    showGrant: Boolean,
    onIntent: (CockpitIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        ScreenHeader(title = stringResource(R.string.cockpit_title))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            ReferenceMenu(reference = reference, onIntent = onIntent)
            ShizukuStatus(
                shizuku = shizuku,
                showGrant = showGrant,
                onGrant = { onIntent(CockpitIntent.GrantShizuku) },
            )
        }
    }
}

@Composable
private fun ReferenceMenu(reference: ReferenceUi, onIntent: (CockpitIntent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ReferencePill(label = stringResource(reference.labelRes()), onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ReferenceChoice.entries.forEach { choice ->
                val current = reference == choice.reference()
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(choice.reference().labelRes()),
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        expanded = false
                        onIntent(CockpitIntent.SelectReference(choice))
                    },
                    modifier = Modifier.semantics { selected = current },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(ReferenceUi.CustomMarker.labelRes()),
                        color = if (reference == ReferenceUi.CustomMarker) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                },
                onClick = {
                    expanded = false
                    onIntent(CockpitIntent.OpenMarkers)
                },
                modifier = Modifier.semantics { selected = reference == ReferenceUi.CustomMarker },
            )
        }
    }
}

@Composable
private fun ShizukuStatus(shizuku: ShizukuUi, showGrant: Boolean, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShizukuPulse(status = shizuku.status, label = shizuku.label())
        if (showGrant && shizuku.canGrant) {
            TextButton(onClick = onGrant) {
                Text(text = stringResource(R.string.cockpit_shizuku_grant))
            }
        }
    }
}

@Composable
private fun ShizukuUi.label(): String = when (reason) {
    ShizukuReason.Ready -> stringResource(R.string.cockpit_shizuku_active, uid ?: 0)
    ShizukuReason.PermissionRequired -> stringResource(
        when (backend) {
            null -> R.string.cockpit_shizuku_permission_required
            ShizukuBackend.SHIZUKU -> R.string.cockpit_permission_required_shizuku
            ShizukuBackend.SUI -> R.string.cockpit_permission_required_sui
        },
    )
    ShizukuReason.PermissionDenied -> stringResource(
        when (backend) {
            null -> R.string.cockpit_shizuku_permission_denied
            ShizukuBackend.SHIZUKU -> R.string.cockpit_permission_denied_shizuku
            ShizukuBackend.SUI -> R.string.cockpit_permission_denied_sui
        },
    )
    ShizukuReason.NotRunning -> stringResource(R.string.cockpit_shizuku_not_running)
    ShizukuReason.NotInstalled -> stringResource(R.string.cockpit_shizuku_not_installed)
    ShizukuReason.UnsupportedServer -> stringResource(R.string.cockpit_shizuku_unsupported_server)
}

private fun ReferenceChoice.reference(): ReferenceUi = when (this) {
    ReferenceChoice.SinceUnplugged -> ReferenceUi.SinceUnplugged
    ReferenceChoice.SinceScreenOff -> ReferenceUi.SinceScreenOff
    ReferenceChoice.Last24h -> ReferenceUi.Last24h
}

private fun ReferenceUi.labelRes(): Int = when (this) {
    ReferenceUi.SinceUnplugged -> R.string.cockpit_ref_since_unplugged
    ReferenceUi.SinceScreenOff -> R.string.cockpit_ref_since_screen_off
    ReferenceUi.Last24h -> R.string.cockpit_ref_last_24h
    ReferenceUi.CustomMarker -> R.string.cockpit_ref_custom_marker
    ReferenceUi.TimeSlice -> R.string.cockpit_ref_time_slice
}
