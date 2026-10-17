package com.akane.inspiration.toolkit

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.akane.inspiration.designsystem.component.format.TelemetryFormat
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import com.akane.inspiration.telemetry.toolkit.BgDexoptCancelReason
import com.akane.inspiration.telemetry.toolkit.BgDexoptStatus
import com.akane.inspiration.telemetry.toolkit.ToolkitRefusal
import com.akane.inspiration.telemetry.toolkit.ToolkitUnavailable
import com.akane.inspiration.telemetry.toolkit.ToolkitWarning
import java.time.ZoneId
import java.util.Locale

private const val MS_PER_SECOND = 1_000L
private const val MS_PER_MINUTE = 60_000L

@Composable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** Whole minutes through the shared formatter, anything else in seconds (the doze timers go down to 15 s). */
@Composable
internal fun msText(format: TelemetryFormat, ms: Long): String =
    if (ms >= MS_PER_MINUTE && ms % MS_PER_MINUTE == 0L) {
        format.duration(ms)
    } else {
        stringResource(R.string.toolkit_seconds, format.number(ms / MS_PER_SECOND.toDouble(), if (ms % MS_PER_SECOND == 0L) 0 else 1))
    }

@StringRes
internal fun profileLabelRes(profile: DozeProfile): Int = when (profile) {
    DozeProfile.STANDARD -> R.string.toolkit_doze_standard
    DozeProfile.BALANCED -> R.string.toolkit_doze_balanced
    DozeProfile.AGGRESSIVE_NIGHT -> R.string.toolkit_doze_aggressive
}

@StringRes
internal fun profileBodyRes(profile: DozeProfile): Int = when (profile) {
    DozeProfile.STANDARD -> R.string.toolkit_doze_standard_body
    DozeProfile.BALANCED -> R.string.toolkit_doze_balanced_body
    DozeProfile.AGGRESSIVE_NIGHT -> R.string.toolkit_doze_aggressive_body
}

/** The localized name of a doze key; a key this build does not know (an OEM one) is shown as it is. */
@Composable
internal fun dozeKeyLabel(key: String): String = when (key) {
    "inactive_to" -> stringResource(R.string.toolkit_doze_key_inactive)
    "light_after_inactive_to" -> stringResource(R.string.toolkit_doze_key_light)
    "idle_after_inactive_to" -> stringResource(R.string.toolkit_doze_key_idle)
    "sensing_to" -> stringResource(R.string.toolkit_doze_key_sensing)
    "locating_to" -> stringResource(R.string.toolkit_doze_key_locating)
    "motion_inactive_to" -> stringResource(R.string.toolkit_doze_key_motion)
    else -> key
}

@StringRes
internal fun opShortRes(op: AppOp): Int = when (op) {
    AppOp.RUN_ANY_IN_BACKGROUND -> R.string.toolkit_op_short_run_any
    AppOp.RUN_IN_BACKGROUND -> R.string.toolkit_op_short_run_in
    AppOp.WAKE_LOCK -> R.string.toolkit_op_short_wake_lock
}

@StringRes
internal fun opTitleRes(op: AppOp): Int = when (op) {
    AppOp.RUN_ANY_IN_BACKGROUND -> R.string.toolkit_op_run_any_title
    AppOp.RUN_IN_BACKGROUND -> R.string.toolkit_op_run_in_title
    AppOp.WAKE_LOCK -> R.string.toolkit_op_wake_lock_title
}

@StringRes
internal fun opBodyRes(op: AppOp): Int = when (op) {
    AppOp.RUN_ANY_IN_BACKGROUND -> R.string.toolkit_op_run_any_body
    AppOp.RUN_IN_BACKGROUND -> R.string.toolkit_op_run_in_body
    AppOp.WAKE_LOCK -> R.string.toolkit_op_wake_lock_body
}

@StringRes
internal fun bucketLabelRes(bucket: StandbyBucket): Int = when (bucket) {
    StandbyBucket.ACTIVE -> R.string.toolkit_bucket_active
    StandbyBucket.WORKING_SET -> R.string.toolkit_bucket_working_set
    StandbyBucket.FREQUENT -> R.string.toolkit_bucket_frequent
    StandbyBucket.RARE -> R.string.toolkit_bucket_rare
    StandbyBucket.RESTRICTED -> R.string.toolkit_bucket_restricted
}

@StringRes
internal fun blockedBodyRes(reason: BlockedReason): Int = when (reason) {
    BlockedReason.NOT_INSTALLED -> R.string.toolkit_blocked_not_installed
    BlockedReason.NOT_RUNNING -> R.string.toolkit_blocked_not_running
    BlockedReason.PERMISSION_REQUIRED -> R.string.toolkit_blocked_permission_required
    BlockedReason.PERMISSION_DENIED -> R.string.toolkit_blocked_permission_denied
}

@StringRes
internal fun warningRes(warning: ToolkitWarning): Int = when (warning) {
    ToolkitWarning.FORCE_STOP -> R.string.toolkit_warning_force_stop
    ToolkitWarning.WAKE_LOCK_IGNORE -> R.string.toolkit_warning_wake_lock_ignore
    ToolkitWarning.RESTRICTED_BUCKET -> R.string.toolkit_warning_restricted_bucket
    ToolkitWarning.RUN_ANY_IN_BACKGROUND_IGNORE -> R.string.toolkit_warning_run_any_in_background_ignore
    ToolkitWarning.UPDATED_SYSTEM_APP -> R.string.toolkit_warning_updated_system_app
    ToolkitWarning.DOZE_OVER_CUSTOM -> R.string.toolkit_warning_doze_over_custom
    ToolkitWarning.RESTORE_STATE_CHANGED -> R.string.toolkit_warning_restore_state_changed
}

@StringRes
internal fun refusalRes(reason: ToolkitRefusal): Int = when (reason) {
    ToolkitRefusal.INVALID_ARGUMENT -> R.string.toolkit_refused_invalid_argument
    ToolkitRefusal.NOT_INSTALLED -> R.string.toolkit_refused_not_installed
    ToolkitRefusal.PROTECTED_APP -> R.string.toolkit_refused_protected_app
    ToolkitRefusal.CALLER -> R.string.toolkit_refused_caller
    ToolkitRefusal.STALE -> R.string.toolkit_refused_stale
    ToolkitRefusal.BUSY -> R.string.toolkit_refused_busy
    ToolkitRefusal.NOT_PLUGGED -> R.string.toolkit_refused_not_plugged
    ToolkitRefusal.READ_FAILED -> R.string.toolkit_refused_read_failed
    ToolkitRefusal.NOT_CONFIRMED -> R.string.toolkit_refused_not_confirmed
    ToolkitRefusal.SERVICE_UNAVAILABLE -> R.string.toolkit_refused_service_unavailable
    ToolkitRefusal.NOT_RESTORABLE -> R.string.toolkit_refused_not_restorable
    ToolkitRefusal.ALREADY_RESTORED -> R.string.toolkit_refused_already_restored
    ToolkitRefusal.UNKNOWN_ACTION -> R.string.toolkit_refused_unknown_action
    ToolkitRefusal.OTHER -> R.string.toolkit_refused_other
}

@StringRes
internal fun unavailableRes(reason: ToolkitUnavailable): Int = when (reason) {
    ToolkitUnavailable.NO_SERVICE -> R.string.toolkit_unavailable_no_service
    ToolkitUnavailable.STALE_SERVICE -> R.string.toolkit_unavailable_stale_service
    ToolkitUnavailable.NOT_EFFECTIVE -> R.string.toolkit_unavailable_not_effective
}

@Composable
internal fun problemText(problem: Problem): String = when (problem) {
    is Problem.Refused -> stringResource(refusalRes(problem.reason))
    is Problem.Unavailable -> stringResource(unavailableRes(problem.reason))
    is Problem.Failed ->
        problem.code?.let { stringResource(R.string.toolkit_failed_code, it) } ?: stringResource(R.string.toolkit_failed)
}

/** A sentence starts with a capital letter, in the current locale's rules (Turkish `i` becomes `İ`). */
@Composable
private fun String.sentence(): String {
    val locale = currentLocale()
    return replaceFirstChar { it.uppercase(locale) }
}

@Composable
internal fun noticeText(notice: ToolkitNotice): String = when (notice) {
    is ToolkitNotice.Rejected -> problemText(notice.problem)
    is ToolkitNotice.Applied -> when (val change = notice.change) {
        is Change.DozeApplied -> stringResource(R.string.toolkit_notice_doze, stringResource(profileLabelRes(change.profile)))
        is Change.AppOpSet -> {
            val op = stringResource(opShortRes(change.op))
            if (change.ignored) {
                stringResource(R.string.toolkit_notice_op_blocked, op).sentence()
            } else {
                stringResource(R.string.toolkit_notice_op_allowed, op).sentence()
            }
        }
        is Change.BucketSet -> stringResource(R.string.toolkit_notice_bucket, stringResource(bucketLabelRes(change.bucket)))
        Change.ForceStopped -> stringResource(R.string.toolkit_notice_force_stopped)
        Change.Restored -> stringResource(R.string.toolkit_notice_restored)
        Change.DexoptStarted -> stringResource(R.string.toolkit_notice_dexopt_started)
        Change.DexoptCancelled -> stringResource(R.string.toolkit_notice_dexopt_cancelled)
    }
}

@Composable
internal fun overrideText(item: OverrideItem): String {
    val app = item.label.orEmpty()
    return when (val what = item.what) {
        is OverrideWhat.AppOpIgnored ->
            what.op?.let { stringResource(R.string.toolkit_op_blocked_line, stringResource(opShortRes(it)), app) }
                ?: stringResource(R.string.toolkit_override_op_other, app)
        is OverrideWhat.Bucket ->
            what.bucket?.let { stringResource(R.string.toolkit_override_bucket, app, stringResource(bucketLabelRes(it))) }
                ?: stringResource(R.string.toolkit_override_bucket_other, app)
        is OverrideWhat.Doze ->
            what.profile?.let { stringResource(R.string.toolkit_override_doze, stringResource(profileLabelRes(it))) }
                ?: stringResource(R.string.toolkit_override_doze_custom)
    }
}

@Composable
internal fun historyText(item: HistoryItem): String {
    val app = item.target.orEmpty()
    val text = when (val what = item.what) {
        is HistoryWhat.AppOpSet -> when {
            what.op == null || what.ignored == null -> stringResource(R.string.toolkit_history_op_other, app)
            what.ignored -> stringResource(R.string.toolkit_op_blocked_line, stringResource(opShortRes(what.op)), app)
            else -> stringResource(R.string.toolkit_op_allowed_line, stringResource(opShortRes(what.op)), app)
        }
        is HistoryWhat.BucketSet ->
            what.bucket?.let { stringResource(R.string.toolkit_history_bucket, app, stringResource(bucketLabelRes(it))) }
                ?: stringResource(R.string.toolkit_override_bucket_other, app)
        is HistoryWhat.DozeApplied ->
            what.profile?.let { stringResource(R.string.toolkit_history_doze, stringResource(profileLabelRes(it))) }
                ?: stringResource(R.string.toolkit_history_doze_other)
        HistoryWhat.DozeRestored -> stringResource(R.string.toolkit_history_doze_restored)
        HistoryWhat.ForceStop -> stringResource(R.string.toolkit_history_force_stop, app)
        HistoryWhat.Dexopt -> stringResource(R.string.toolkit_history_dexopt)
        HistoryWhat.Other -> stringResource(R.string.toolkit_history_other)
    }
    return if (item.isRestore) stringResource(R.string.toolkit_history_restore, text) else text
}

@StringRes
internal fun statusRes(status: ToolkitActionStatus): Int = when (status) {
    ToolkitActionStatus.PENDING -> R.string.toolkit_status_pending
    ToolkitActionStatus.DONE -> R.string.toolkit_status_done
    ToolkitActionStatus.REFUSED -> R.string.toolkit_status_refused
    ToolkitActionStatus.FAILED -> R.string.toolkit_status_failed
    ToolkitActionStatus.RESTORED -> R.string.toolkit_status_restored
    ToolkitActionStatus.CANCELLED -> R.string.toolkit_status_cancelled
}

@Composable
internal fun dexoptStatusText(status: BgDexoptStatus, format: TelemetryFormat, zone: ZoneId): String = when (status) {
    BgDexoptStatus.Idle -> stringResource(R.string.toolkit_dexopt_idle)
    is BgDexoptStatus.Running ->
        if (status.startedAtMs > 0) {
            stringResource(R.string.toolkit_dexopt_running_since, format.dateTime(status.startedAtMs, zone))
        } else {
            stringResource(R.string.toolkit_dexopt_running)
        }
    is BgDexoptStatus.Finished ->
        if (status.exitCode == 0) {
            stringResource(R.string.toolkit_dexopt_finished)
        } else {
            stringResource(R.string.toolkit_dexopt_finished_code, status.exitCode)
        }
    is BgDexoptStatus.Cancelled -> stringResource(
        when (status.reason) {
            BgDexoptCancelReason.USER -> R.string.toolkit_dexopt_cancelled_user
            BgDexoptCancelReason.UNPLUGGED -> R.string.toolkit_dexopt_cancelled_unplugged
            BgDexoptCancelReason.TIMEOUT -> R.string.toolkit_dexopt_cancelled_timeout
            BgDexoptCancelReason.OTHER -> R.string.toolkit_dexopt_cancelled_other
        },
    )
    BgDexoptStatus.Unknown -> stringResource(R.string.toolkit_dexopt_unknown)
}

@Composable
internal fun confirmationTitle(confirmation: Confirmation): String {
    val target = confirmation.target.orEmpty()
    return when (val request = confirmation.request) {
        is ToolkitRequest.ApplyDoze ->
            stringResource(R.string.toolkit_confirm_title_doze, stringResource(profileLabelRes(request.profile)))
        is ToolkitRequest.SetAppOp -> {
            // The op name is a capitalized label; inside a question it continues the sentence.
            val locale = currentLocale()
            val op = stringResource(opShortRes(request.op)).replaceFirstChar { it.lowercase(locale) }
            if (request.ignored) {
                stringResource(R.string.toolkit_confirm_title_block, op, target)
            } else {
                stringResource(R.string.toolkit_confirm_title_allow, op, target)
            }
        }
        is ToolkitRequest.SetBucket ->
            stringResource(R.string.toolkit_confirm_title_bucket, target, stringResource(bucketLabelRes(request.bucket)))
        ToolkitRequest.ForceStop -> stringResource(R.string.toolkit_confirm_title_force_stop, target)
        is ToolkitRequest.Restore ->
            if (confirmation.target != null) {
                stringResource(R.string.toolkit_confirm_title_restore, target)
            } else {
                stringResource(R.string.toolkit_confirm_title_restore_doze)
            }
        ToolkitRequest.ResetAll ->
            pluralStringResource(R.plurals.toolkit_confirm_title_reset_all, confirmation.count, confirmation.count)
    }
}

@StringRes
internal fun confirmActionRes(request: ToolkitRequest): Int = when (request) {
    is ToolkitRequest.ApplyDoze -> R.string.toolkit_confirm_action_apply
    is ToolkitRequest.SetAppOp ->
        if (request.ignored) R.string.toolkit_confirm_action_block else R.string.toolkit_confirm_action_allow
    is ToolkitRequest.SetBucket -> R.string.toolkit_confirm_action_move
    ToolkitRequest.ForceStop -> R.string.toolkit_confirm_action_force_stop
    is ToolkitRequest.Restore -> R.string.toolkit_confirm_action_restore
    ToolkitRequest.ResetAll -> R.string.toolkit_confirm_action_reset_all
}
