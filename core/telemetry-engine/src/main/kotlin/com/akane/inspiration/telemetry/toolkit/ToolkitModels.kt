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
    data class SetAppOp(
        val pkg: String,
        val op: AppOp,
        val mode: AppOpMode,
    ) : ToolkitAction

    data class SetBucket(
        val pkg: String,
        val bucket: StandbyBucket,
    ) : ToolkitAction

    /**
     * Applies [profile]. With [expectedBefore] (the value the user decided against) the action is refused as
     * STALE when the setting now reads otherwise, so a change made while a confirmation was open is asked about
     * again instead of being overwritten. Without it the value read at execute time is the expectation.
     */
    data class ApplyDoze(
        val profile: DozeProfile,
        val expectedBefore: SeenDoze? = null,
    ) : ToolkitAction

    data class ForceStop(
        val pkg: String,
    ) : ToolkitAction

    /**
     * Replays a stored `device_idle_constants` value ([rawOrNull] null deletes it), but only while the
     * current value is [expectedCurrentRaw]; otherwise it needs confirmation.
     */
    data class RestoreDoze(
        val rawOrNull: String?,
        val expectedCurrentRaw: String?,
    ) : ToolkitAction
}

/** A `device_idle_constants` value as the user saw it; [raw] null (or empty) is unset. */
data class SeenDoze(
    val raw: String?,
)

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

    /** Package actions need Inspiration to run in Android user 0 (not a work profile or secondary user). */
    UNSUPPORTED_USER,

    /** The action cannot be made safe on this Android version (bg-dexopt below API 33); nothing was run. */
    UNSUPPORTED_API,

    /**
     * Hard-deny rules: system, persistent, role holders and so on. A protected app (role holder, IME,
     * accessibility, device admin, always-on VPN) still accepts the undo of a change of ours, when that undo only
     * lifts a restriction; it takes no new change, not even one that lifts.
     */
    PROTECTED_APP,

    /**
     * The service could not decide whether the app is protected (the protected-package inspection was
     * incomplete), so it changed nothing. Worth retrying; the undo of a change of ours that only lifts a
     * restriction still goes through.
     */
    PROTECTION_UNKNOWN,
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

    /**
     * The write was issued but whether it took effect is unknown (a timeout, a failed read-back or a doze
     * rollback that did not land). The audit row stays PENDING and is read back before the target is used again.
     */
    UNVERIFIED,
}

sealed interface ToolkitPlan {
    /** Safe to [ToolkitActions.execute] without confirmation. */
    data object Ready : ToolkitPlan

    /** [warning] is the primary reason; [others] are any further reasons. */
    data class NeedsConfirmation(
        val warning: ToolkitWarning,
        val others: List<ToolkitWarning> = emptyList(),
    ) : ToolkitPlan

    data class Refused(
        val reason: ToolkitRefusal,
    ) : ToolkitPlan
}

sealed interface ToolkitOutcome<out T> {
    data class Done<T>(
        val value: T,
    ) : ToolkitOutcome<T>

    data class Refused(
        val reason: ToolkitRefusal,
        val code: Int? = null,
    ) : ToolkitOutcome<Nothing>

    data class Unavailable(
        val reason: ToolkitUnavailable,
        val code: Int? = null,
    ) : ToolkitOutcome<Nothing>

    data class Failed(
        val code: Int?,
    ) : ToolkitOutcome<Nothing>
}

/**
 * Read-back state of one app. A null mode or bucket means the value is not one this surface can set. An app-op
 * mode of [AppOpMode.DEFAULT] is a live `default` reading: background work is restricted, so show it as such.
 */
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
 *
 * [unverified] is set when the target could not be read back now (why, as a refusal): the change may still be
 * in effect, so it stays listed and in Reset all's scope instead of disappearing. [currentRaw] is then the value
 * our change left, not a fresh reading.
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
    val unverified: ToolkitRefusal? = null,
)

/** How toolkit writes are persisted in `toolkit_action`; the store's retention SQL uses the same values. */
object ToolkitAuditLog {
    /** `kind` values: explicit strings, never enum names. */
    const val KIND_APP_OP = "appop"
    const val KIND_BUCKET = "bucket"
    const val KIND_DOZE = "doze"
    const val KIND_FORCE_STOP = "force_stop"
    const val KIND_BG_DEXOPT = "dexopt"

    /** The newest rows always kept as display history; older ones go once no override chain needs them. */
    const val RETAINED_ROWS = 500
}

/** One [resetAll][ToolkitActions.resetAll] item: what was restored and how it went. */
data class ResetItem(
    val override: Override,
    val outcome: ToolkitOutcome<ToolkitActionRecord>,
)

enum class BgDexoptCancelReason { USER, UNPLUGGED, TIMEOUT, OTHER }

sealed interface BgDexoptStatus {
    data object Idle : BgDexoptStatus

    data class Running(
        val startedAtMs: Long,
    ) : BgDexoptStatus

    data class Finished(
        val exitCode: Int,
        val endedAtMs: Long,
    ) : BgDexoptStatus

    data class Cancelled(
        val reason: BgDexoptCancelReason,
        val endedAtMs: Long,
    ) : BgDexoptStatus

    /** A job may still be running that this service instance does not know about; Cancel is idempotent. */
    data object Unknown : BgDexoptStatus

    /**
     * This Android version (below API 33) has no command that cancels a job started from the shell, so the service
     * neither starts nor cancels one and nothing is offered. A PENDING row an older service version left stays PENDING
     * until the job's cap and wind-down have passed; it is then settled as CANCELLED ("unsupported").
     */
    data object Unsupported : BgDexoptStatus
}
