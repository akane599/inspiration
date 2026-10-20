package com.akane.inspiration.xray

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.akane.inspiration.designsystem.component.format.NBSP
import com.akane.inspiration.designsystem.component.format.NO_VALUE
import com.akane.inspiration.designsystem.component.format.TelemetryFormat
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.ipc.ShizukuBackend
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.toolkit.ToolkitRefusal
import com.akane.inspiration.telemetry.toolkit.ToolkitUnavailable
import com.akane.inspiration.telemetry.toolkit.ToolkitWarning
import java.util.Locale

private const val MS_PER_MINUTE = 60_000L

/** "12.5 mAh"; "—" when the data has no figure. */
@Composable
internal fun mahText(mah: Double?): String {
    if (mah == null) return NO_VALUE
    return stringResource(R.string.xray_mah_value, rememberTelemetryFormat().number(mah, 1))
}

/** "42%" / "%42" by locale, "<1%" under one percent, "—" without a figure. */
@Composable
internal fun shareText(pct: Double?): String =
    when {
        pct == null -> NO_VALUE
        pct > 0.0 && pct < 1.0 -> stringResource(R.string.xray_share_under_one)
        else -> stringResource(R.string.xray_share_percent, rememberTelemetryFormat().number(pct, 0))
    }

/** A duration for CPU and wakelock times: whole minutes, "<1 min" for anything shorter. */
internal fun durationText(
    format: TelemetryFormat,
    durationMs: Long,
): String = if (durationMs in 1 until MS_PER_MINUTE) "<1$NBSP${format.units.minute}" else format.duration(durationMs)

@Composable
internal fun currentLocale(): Locale = LocalLocale.current.platformLocale

@Composable
internal fun timesText(count: Long): String {
    val clamped = count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    return pluralStringResource(R.plurals.xray_count_times, clamped, clamped)
}

@Composable
internal fun displayName(app: AppRow): String =
    app.label
        ?: app.packageName
        ?: app.sharedBy.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.xray_uid_shared, it, it, app.uid) }
        ?: systemUidLabelRes(app.uid)?.let { stringResource(it) }
        ?: stringResource(R.string.xray_uid_label, app.uid)

private const val PER_USER_UID_RANGE = 100_000

/** A readable name for a well-known shared platform UID (in any user), or null for everything else. */
@StringRes
internal fun systemUidLabelRes(uid: Int): Int? =
    when (uid % PER_USER_UID_RANGE) {
        0 -> R.string.xray_uid_root
        1_000 -> R.string.xray_uid_system
        1_001 -> R.string.xray_uid_phone
        1_002 -> R.string.xray_uid_bluetooth
        1_010 -> R.string.xray_uid_wifi
        1_013 -> R.string.xray_uid_media
        1_017 -> R.string.xray_uid_keystore
        1_021 -> R.string.xray_uid_gps
        1_027 -> R.string.xray_uid_nfc
        1_041 -> R.string.xray_uid_audio
        1_047 -> R.string.xray_uid_camera
        2_000 -> R.string.xray_uid_shell
        else -> null
    }

@StringRes
internal fun componentLabelRes(key: String): Int? =
    when (key) {
        "SCREEN" -> R.string.xray_component_screen
        "CPU" -> R.string.xray_component_cpu
        "MOBILE_RADIO" -> R.string.xray_component_mobile_radio
        "WIFI" -> R.string.xray_component_wifi
        "IDLE" -> R.string.xray_component_idle
        "BLUETOOTH" -> R.string.xray_component_bluetooth
        "GNSS" -> R.string.xray_component_gps
        "SENSORS" -> R.string.xray_component_sensors
        "CAMERA" -> R.string.xray_component_camera
        "AUDIO" -> R.string.xray_component_audio
        "VIDEO" -> R.string.xray_component_video
        "FLASHLIGHT" -> R.string.xray_component_flashlight
        "SYSTEM_SERVICES" -> R.string.xray_component_system_services
        "MEMORY" -> R.string.xray_component_memory
        "PHONE" -> R.string.xray_component_phone
        "AMBIENT_DISPLAY" -> R.string.xray_component_ambient_display
        "WAKELOCK" -> R.string.xray_component_wakelock
        XRayMapper.OTHER_KEY -> R.string.xray_component_other
        else -> null
    }

/** The localized name of a component; an unknown battery-stats name is shown as it is, never guessed at. */
@Composable
internal fun componentLabel(key: String): String = componentLabelRes(key)?.let { stringResource(it) } ?: key

@StringRes
internal fun groupLabelRes(group: ComponentGroup): Int =
    when (group) {
        ComponentGroup.SCREEN -> R.string.xray_group_screen
        ComponentGroup.CPU -> R.string.xray_group_cpu
        ComponentGroup.RADIOS -> R.string.xray_group_radios
        ComponentGroup.OTHER -> R.string.xray_group_other
    }

@StringRes
internal fun bucketLabelRes(bucket: StandbyBucket): Int =
    when (bucket) {
        StandbyBucket.ACTIVE -> R.string.xray_bucket_active
        StandbyBucket.WORKING_SET -> R.string.xray_bucket_working_set
        StandbyBucket.FREQUENT -> R.string.xray_bucket_frequent
        StandbyBucket.RARE -> R.string.xray_bucket_rare
        StandbyBucket.RESTRICTED -> R.string.xray_bucket_restricted
    }

@StringRes
internal fun blockedTitleRes(backend: ShizukuBackend?): Int =
    when (backend) {
        null -> R.string.xray_blocked_title
        ShizukuBackend.SHIZUKU -> R.string.xray_blocked_title_shizuku
        ShizukuBackend.SUI -> R.string.xray_blocked_title_sui
    }

/** Names the [backend] whose binder is there; with none, the text names both Shizuku and Sui. */
@StringRes
internal fun blockedBodyRes(
    reason: BlockedReason,
    backend: ShizukuBackend?,
): Int =
    when (reason) {
        BlockedReason.NOT_INSTALLED -> {
            R.string.xray_blocked_not_installed
        }

        BlockedReason.NOT_RUNNING -> {
            R.string.xray_blocked_not_running
        }

        BlockedReason.PERMISSION_REQUIRED -> {
            when (backend) {
                null -> R.string.xray_blocked_permission_required
                ShizukuBackend.SHIZUKU -> R.string.xray_blocked_permission_required_shizuku
                ShizukuBackend.SUI -> R.string.xray_blocked_permission_required_sui
            }
        }

        BlockedReason.PERMISSION_DENIED -> {
            when (backend) {
                null -> R.string.xray_blocked_permission_denied
                ShizukuBackend.SHIZUKU -> R.string.xray_blocked_permission_denied_shizuku
                ShizukuBackend.SUI -> R.string.xray_blocked_permission_denied_sui
            }
        }

        BlockedReason.UNSUPPORTED_SERVER -> {
            R.string.xray_blocked_unsupported_server
        }
    }

@StringRes
internal fun warningRes(warning: ToolkitWarning): Int =
    when (warning) {
        ToolkitWarning.FORCE_STOP -> R.string.xray_warning_force_stop
        ToolkitWarning.WAKE_LOCK_IGNORE -> R.string.xray_warning_wake_lock_ignore
        ToolkitWarning.RESTRICTED_BUCKET -> R.string.xray_warning_restricted_bucket
        ToolkitWarning.RUN_ANY_IN_BACKGROUND_IGNORE -> R.string.xray_warning_run_any_in_background_ignore
        ToolkitWarning.UPDATED_SYSTEM_APP -> R.string.xray_warning_updated_system_app
        ToolkitWarning.DOZE_OVER_CUSTOM -> R.string.xray_warning_doze_over_custom
        ToolkitWarning.RESTORE_STATE_CHANGED -> R.string.xray_warning_restore_state_changed
        ToolkitWarning.UNATTRIBUTED_CHANGE -> R.string.xray_warning_unattributed_change
    }

@StringRes
internal fun refusalRes(reason: ToolkitRefusal): Int =
    when (reason) {
        ToolkitRefusal.INVALID_ARGUMENT -> R.string.xray_refused_invalid_argument
        ToolkitRefusal.NOT_INSTALLED -> R.string.xray_refused_not_installed
        ToolkitRefusal.UNSUPPORTED_USER -> R.string.xray_refused_unsupported_user
        ToolkitRefusal.UNSUPPORTED_API -> R.string.xray_refused_unsupported_api
        ToolkitRefusal.PROTECTED_APP -> R.string.xray_refused_protected_app
        ToolkitRefusal.PROTECTION_UNKNOWN -> R.string.xray_refused_protection_unknown
        ToolkitRefusal.CALLER -> R.string.xray_refused_caller
        ToolkitRefusal.STALE -> R.string.xray_refused_stale
        ToolkitRefusal.BUSY -> R.string.xray_refused_busy
        ToolkitRefusal.NOT_PLUGGED -> R.string.xray_refused_not_plugged
        ToolkitRefusal.READ_FAILED -> R.string.xray_refused_read_failed
        ToolkitRefusal.NOT_CONFIRMED -> R.string.xray_refused_not_confirmed
        ToolkitRefusal.SERVICE_UNAVAILABLE -> R.string.xray_refused_service_unavailable
        ToolkitRefusal.NOT_RESTORABLE -> R.string.xray_refused_not_restorable
        ToolkitRefusal.ALREADY_RESTORED -> R.string.xray_refused_already_restored
        ToolkitRefusal.UNKNOWN_ACTION -> R.string.xray_refused_unknown_action
        ToolkitRefusal.UNATTRIBUTED -> R.string.xray_refused_unattributed
        ToolkitRefusal.OTHER -> R.string.xray_refused_other
    }

/**
 * The line under a listed change that is unverified: why it could not be read back now, or that Inspiration cannot
 * confirm it made the change at all, which is why Reset all leaves that one alone.
 */
@Composable
internal fun overrideUnverifiedText(reason: ToolkitRefusal): String =
    if (reason == ToolkitRefusal.UNATTRIBUTED) {
        stringResource(R.string.xray_override_unattributed)
    } else {
        stringResource(R.string.xray_override_unverified, stringResource(refusalRes(reason)))
    }

@StringRes
internal fun unavailableRes(reason: ToolkitUnavailable): Int =
    when (reason) {
        ToolkitUnavailable.NO_SERVICE -> R.string.xray_unavailable_no_service
        ToolkitUnavailable.STALE_SERVICE -> R.string.xray_unavailable_stale_service
        ToolkitUnavailable.NOT_EFFECTIVE -> R.string.xray_unavailable_not_effective
        ToolkitUnavailable.UNVERIFIED -> R.string.xray_unavailable_unverified
    }

@Composable
internal fun problemText(problem: Problem): String =
    when (problem) {
        is Problem.Refused -> {
            stringResource(refusalRes(problem.reason))
        }

        is Problem.Unavailable -> {
            stringResource(unavailableRes(problem.reason))
        }

        is Problem.Failed -> {
            problem.code?.let { stringResource(R.string.xray_failed_code, it) } ?: stringResource(R.string.xray_failed)
        }
    }

@Composable
internal fun noticeText(notice: ActionNotice): String =
    when (notice) {
        is ActionNotice.Rejected -> {
            problemText(notice.problem)
        }

        is ActionNotice.Applied -> {
            when (val change = notice.change) {
                Change.Restricted -> stringResource(R.string.xray_notice_restricted)
                Change.Unrestricted -> stringResource(R.string.xray_notice_unrestricted)
                is Change.BucketSet -> stringResource(R.string.xray_notice_bucket, stringResource(bucketLabelRes(change.bucket)))
                Change.ForceStopped -> stringResource(R.string.xray_notice_force_stopped)
                Change.Restored -> stringResource(R.string.xray_notice_restored)
            }
        }
    }

@Composable
internal fun overrideText(row: OverrideRow): String =
    when {
        row.bucket != null -> stringResource(R.string.xray_override_bucket, stringResource(bucketLabelRes(row.bucket)))
        row.appOp == AppOp.RUN_ANY_IN_BACKGROUND -> stringResource(R.string.xray_override_run_any_in_background)
        row.appOp == AppOp.RUN_IN_BACKGROUND -> stringResource(R.string.xray_override_run_in_background)
        row.appOp == AppOp.WAKE_LOCK -> stringResource(R.string.xray_override_wake_lock)
        else -> stringResource(R.string.xray_override_bucket_other)
    }

@Composable
internal fun confirmationTitle(confirmation: Confirmation): String =
    when (val request = confirmation.request) {
        is ActionRequest.SetRestricted -> {
            if (request.restricted) {
                stringResource(R.string.xray_confirm_title_restrict, confirmation.target)
            } else {
                stringResource(R.string.xray_confirm_title_unrestrict, confirmation.target)
            }
        }

        is ActionRequest.SetBucket -> {
            stringResource(R.string.xray_confirm_title_bucket, confirmation.target, stringResource(bucketLabelRes(request.bucket)))
        }

        ActionRequest.ForceStop -> {
            stringResource(R.string.xray_confirm_title_force_stop, confirmation.target)
        }

        is ActionRequest.Restore -> {
            stringResource(R.string.xray_confirm_title_restore, confirmation.target)
        }
    }

@StringRes
internal fun confirmActionRes(request: ActionRequest): Int =
    when (request) {
        is ActionRequest.SetRestricted -> {
            if (request.restricted) R.string.xray_confirm_action_restrict else R.string.xray_confirm_action_unrestrict
        }

        is ActionRequest.SetBucket -> {
            R.string.xray_confirm_action_bucket
        }

        ActionRequest.ForceStop -> {
            R.string.xray_confirm_action_force_stop
        }

        is ActionRequest.Restore -> {
            R.string.xray_confirm_action_restore
        }
    }
