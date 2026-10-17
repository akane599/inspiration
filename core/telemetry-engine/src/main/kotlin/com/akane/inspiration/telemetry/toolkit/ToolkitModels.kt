package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionRecord

/**
 * A typed privileged action. There is deliberately no Intent, URI or free-form command input: a caller
 * can only pick a package name plus a value from the closed enums, and every value is re-validated by
 * `ToolkitActions` and again by the service.
 */
sealed interface ToolkitAction {
    data class SetAppOp(val pkg: String, val op: AppOp, val mode: AppOpMode) : ToolkitAction

    data class SetBucket(val pkg: String, val bucket: StandbyBucket) : ToolkitAction

    data class ApplyDoze(val profile: DozeProfile) : ToolkitAction

    data class ForceStop(val pkg: String) : ToolkitAction

    /**
     * Replays a stored `device_idle_constants` value ([rawOrNull] null deletes it), but only while the
     * current value is [expectedCurrentRaw]; otherwise it needs confirmation.
     */
    data class RestoreDoze(val rawOrNull: String?, val expectedCurrentRaw: String?) : ToolkitAction
}

/** Why a plan or an attempt must be confirmed by the user first. The UI owns the (localized) wording. */
enum class ToolkitWarning {
    /** Force-stop kills the app now and drops its alarms and pending work. */
    FORCE_STOP,

    /** Ignoring WAKE_LOCK can break alarms and messaging. */
    WAKE_LOCK_IGNORE,

    /** The RESTRICTED bucket heavily limits jobs, alarms and network. */
    RESTRICTED_BUCKET,

    /** Ignoring RUN_ANY_IN_BACKGROUND stops all background work of the app. */
    RUN_ANY_IN_BACKGROUND_IGNORE,

    /** The target is an updated system app. */
    UPDATED_SYSTEM_APP,

    /** STANDARD would delete a custom (not one of ours) `device_idle_constants` value. */
    DOZE_OVER_CUSTOM,

    /** A restore: the current value is no longer the one the original action wrote. */
    RESTORE_STATE_CHANGED,
}

/** Why an action was refused. [ToolkitOutcome.Refused] carries the service code when there is one. */
enum class ToolkitRefusal {
    INVALID_ARGUMENT,
    NOT_INSTALLED,

    /** Hard-deny rules: system, persistent, role holders and so on (or the service could not decide). */
    PROTECTED_APP,
    CALLER,
    STALE,
    BUSY,
    NOT_PLUGGED,
    READ_FAILED,
    NOT_CONFIRMED,

    /** A plan could not be made because the service is unreachable or outdated. */
    SERVICE_UNAVAILABLE,
    NOT_RESTORABLE,
    ALREADY_RESTORED,
    UNKNOWN_ACTION,
    OTHER,
}

enum class ToolkitUnavailable {
    /** Shizuku is not ready, the service did not bind, or the binder call failed. */
    NO_SERVICE,

    /** API version below 2, or a null parcelable. Nothing was written. */
    STALE_SERVICE,

    /** The service applied a doze profile, saw that it was not effective and rolled it back. */
    NOT_EFFECTIVE,
}

sealed interface ToolkitPlan {
    /** Safe to [ToolkitActions.execute] without confirmation. */
    data object Ready : ToolkitPlan

    /** [warning] is the primary reason; [others] are any further reasons. */
    data class NeedsConfirmation(
        val warning: ToolkitWarning,
        val others: List<ToolkitWarning> = emptyList(),
    ) : ToolkitPlan

    data class Refused(val reason: ToolkitRefusal) : ToolkitPlan
}

sealed interface ToolkitOutcome<out T> {
    data class Done<T>(val value: T) : ToolkitOutcome<T>

    data class Refused(val reason: ToolkitRefusal, val code: Int? = null) : ToolkitOutcome<Nothing>

    data class Unavailable(val reason: ToolkitUnavailable, val code: Int? = null) : ToolkitOutcome<Nothing>

    data class Failed(val code: Int?) : ToolkitOutcome<Nothing>
}

/** Read-back state of one app. A null mode or bucket means the value is not one this surface can set. */
data class AppToolkitState(
    val pkg: String,
    val appOps: Map<AppOp, AppOpMode?>,
    val bucket: StandbyBucket?,
    val bucketRaw: Int?,
    val updatedSystemApp: Boolean,
)

/** [raw] is `device_idle_constants` (null = unset). [profile] is null when the value is CUSTOM. */
data class DozeState(
    val raw: String?,
    val profile: DozeProfile?,
    val effectiveMs: Map<String, Long>,
) {
    val isCustom: Boolean get() = profile == null
}

enum class OverrideKind { APP_OP, BUCKET, DOZE }

/**
 * A change of ours that read-back shows is still in effect. [actionId] is the audit row to pass to
 * `ToolkitActions.restore`. [pkg] and [appOp] are null for doze. [restorable] is false when the
 * recorded previous value cannot be replayed (the service did not report it, or it is not a value this
 * surface can set): the change is in effect but `restore` refuses it as NOT_RESTORABLE.
 */
data class Override(
    val actionId: Long,
    val kind: OverrideKind,
    val pkg: String?,
    val appOp: AppOp?,
    val currentRaw: String?,
    val previousRaw: String?,
    val sinceMs: Long,
    val restorable: Boolean = true,
)

/** One [resetAll][ToolkitActions.resetAll] item: what was restored and how it went. */
data class ResetItem(val override: Override, val outcome: ToolkitOutcome<ToolkitActionRecord>)

enum class BgDexoptCancelReason { USER, UNPLUGGED, TIMEOUT, OTHER }

sealed interface BgDexoptStatus {
    data object Idle : BgDexoptStatus

    data class Running(val startedAtMs: Long) : BgDexoptStatus

    data class Finished(val exitCode: Int, val endedAtMs: Long) : BgDexoptStatus

    data class Cancelled(val reason: BgDexoptCancelReason, val endedAtMs: Long) : BgDexoptStatus

    /** A job may still be running that this service instance does not know about; Cancel is idempotent. */
    data object Unknown : BgDexoptStatus
}
