package com.akane.inspiration.toolkit

import androidx.compose.runtime.Immutable
import com.akane.inspiration.ipc.ShizukuBackend
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import com.akane.inspiration.telemetry.toolkit.BgDexoptStatus
import com.akane.inspiration.telemetry.toolkit.ListedOverride
import com.akane.inspiration.telemetry.toolkit.OverrideKind
import com.akane.inspiration.telemetry.toolkit.ToolkitRefusal
import com.akane.inspiration.telemetry.toolkit.ToolkitUnavailable
import com.akane.inspiration.telemetry.toolkit.ToolkitWarning
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** What the whole screen is doing: [BLOCKED] is Shizuku not Ready, [FAILED] is a Ready Shizuku whose service did not answer. */
enum class ToolkitPhase { LOADING, BLOCKED, FAILED, READY }

enum class BlockedReason { NOT_INSTALLED, NOT_RUNNING, PERMISSION_REQUIRED, PERMISSION_DENIED, UNSUPPORTED_SERVER }

/** Shizuku only shows its dialog while the permission is still undecided, so Grant is only offered then. */
val BlockedReason.canGrant: Boolean get() = this == BlockedReason.PERMISSION_REQUIRED

/** What a toolkit call came back with when it was not a success. */
sealed interface Problem {
    data class Refused(
        val reason: ToolkitRefusal,
    ) : Problem

    data class Unavailable(
        val reason: ToolkitUnavailable,
    ) : Problem

    data class Failed(
        val code: Int?,
    ) : Problem

    /** A bg-dexopt cancel went out, but our job's status then read Unknown: it may still be running. */
    data object DexoptCancelUnconfirmed : Problem
}

/** The change a successful action made, for the result line. */
sealed interface Change {
    data class DozeApplied(
        val profile: DozeProfile,
    ) : Change

    data class AppOpSet(
        val op: AppOp,
        val ignored: Boolean,
    ) : Change

    data class BucketSet(
        val bucket: StandbyBucket,
    ) : Change

    data object ForceStopped : Change

    data object Restored : Change

    data object DexoptStarted : Change

    data object DexoptCancelled : Change
}

sealed interface ToolkitNotice {
    data class Applied(
        val change: Change,
    ) : ToolkitNotice

    data class Rejected(
        val problem: Problem,
    ) : ToolkitNotice
}

// ---- doze ------------------------------------------------------------------------------------

/** One effective `device_idle_constants` value. [key] is the AOSP key; the UI words the ones it knows. */
@Immutable
data class DozeValue(
    val key: String,
    val ms: Long,
)

/** [profile] is null when the setting is CUSTOM (not one of ours). [raw] is the stored value, null when unset. */
@Immutable
data class DozeInfo(
    val profile: DozeProfile?,
    val raw: String?,
    val effective: ImmutableList<DozeValue>,
)

sealed interface DozeLoad {
    data object Loading : DozeLoad

    data class Loaded(
        val info: DozeInfo,
    ) : DozeLoad

    data class Unavailable(
        val problem: Problem,
    ) : DozeLoad
}

@Immutable
data class DozeSection(
    val load: DozeLoad = DozeLoad.Loading,
    val notice: ToolkitNotice? = null,
)

// ---- apps ------------------------------------------------------------------------------------

/** One row of the app picker. [packageName] only ever comes from `installedApps()`, never from an Intent (H12). */
@Immutable
data class AppItem(
    val packageName: String,
    val label: String,
    val restricted: Boolean,
)

enum class AppsPhase { LOADING, READY, FAILED }

/** [items] is the list after the search: [total] is how many apps there are before it. */
@Immutable
data class AppsSection(
    val phase: AppsPhase = AppsPhase.LOADING,
    val query: String = "",
    val items: ImmutableList<AppItem> = persistentListOf(),
    val total: Int = 0,
)

/** One app op switch. [ignored] is read back from the system. A null-mode (unknown) op is not [editable]. */
@Immutable
data class OpControl(
    val op: AppOp,
    val ignored: Boolean,
    val editable: Boolean,
)

/** Read-back of the app's current settings: this is what the controls show, never an optimistic guess. */
@Immutable
data class AppControls(
    val ops: ImmutableList<OpControl>,
    val bucket: StandbyBucket?,
    val bucketRaw: Int?,
    val updatedSystemApp: Boolean,
)

sealed interface ControlsState {
    data object Loading : ControlsState

    data class Loaded(
        val controls: AppControls,
    ) : ControlsState

    data class Unavailable(
        val problem: Problem,
    ) : ControlsState
}

@Immutable
data class AppDetail(
    val app: AppItem,
    val controls: ControlsState,
    val notice: ToolkitNotice? = null,
)

// ---- overrides, history, reset ---------------------------------------------------------------

/** What an override changed, without the package: the UI words it. */
sealed interface OverrideWhat {
    data class AppOpIgnored(
        val op: AppOp?,
        val ignored: Boolean? = true,
    ) : OverrideWhat

    data class Bucket(
        val bucket: StandbyBucket?,
    ) : OverrideWhat

    /** [profile] is null for a custom value. */
    data class Doze(
        val profile: DozeProfile?,
    ) : OverrideWhat
}

/**
 * A change of ours that read-back shows is still in effect. [label] is the app's name, null for doze. [unverified]
 * is why it could not be read back now: it may still be in effect, so it stays listed with the value it left. It is
 * `ToolkitRefusal.UNATTRIBUTED` for a change Inspiration cannot confirm it made: Reset all leaves it alone.
 */
@Immutable
data class OverrideItem(
    override val actionId: Long,
    val kind: OverrideKind,
    val label: String?,
    val what: OverrideWhat,
    val sinceMs: Long,
    override val restorable: Boolean,
    override val unverified: ToolkitRefusal? = null,
) : ListedOverride

/** What an audit row did, decoded from its stored strings; an unknown value is [Other]. */
sealed interface HistoryWhat {
    data class AppOpSet(
        val op: AppOp?,
        val ignored: Boolean?,
    ) : HistoryWhat

    data class BucketSet(
        val bucket: StandbyBucket?,
    ) : HistoryWhat

    data class DozeApplied(
        val profile: DozeProfile?,
    ) : HistoryWhat

    data object DozeRestored : HistoryWhat

    data object ForceStop : HistoryWhat

    data object Dexopt : HistoryWhat

    data object Other : HistoryWhat
}

/**
 * One audit row. [unverified] marks a write whose outcome Inspiration could not attribute to it (it was read back too
 * late, `ToolkitAuditLog.unattributed`): it is shown as unverified, not as failed, since it may well have taken effect.
 */
@Immutable
data class HistoryItem(
    val id: Long,
    val what: HistoryWhat,
    val target: String?,
    val status: ToolkitActionStatus,
    val startedAtMs: Long,
    val isRestore: Boolean,
    val unverified: Boolean = false,
)

sealed interface ResetResult {
    data object Restored : ResetResult

    data class NotRestored(
        val problem: Problem,
    ) : ResetResult
}

@Immutable
data class ResetLine(
    val item: OverrideItem,
    val result: ResetResult,
)

/** What "Reset all" did, item by item. Nothing is summarized away: a failed item stays listed with its reason. */
@Immutable
data class ResetReport(
    val lines: ImmutableList<ResetLine>,
) {
    val restored: Int get() = lines.count { it.result == ResetResult.Restored }
    val failed: Int get() = lines.size - restored
}

// ---- bg-dexopt -------------------------------------------------------------------------------

/**
 * [status] is null until the first read. [busy] is a start or cancel call in flight. [cancelAwaiting] is a cancel
 * the service accepted whose outcome the status has not settled yet: only our job's exit confirms it, so its
 * notice waits for a status that is no longer Running.
 */
@Immutable
data class DexoptSection(
    val status: BgDexoptStatus? = null,
    val busy: Boolean = false,
    val notice: ToolkitNotice? = null,
    val cancelAwaiting: Boolean = false,
)

// ---- requests, confirmation ------------------------------------------------------------------

/**
 * Something the user asked for. It carries no package: app requests act on the app of the open detail
 * sheet (which came from the in-app list, H12), restores take an id from the shown overrides.
 */
sealed interface ToolkitRequest {
    data class ApplyDoze(
        val profile: DozeProfile,
    ) : ToolkitRequest

    data class SetAppOp(
        val op: AppOp,
        val ignored: Boolean,
    ) : ToolkitRequest

    data class SetBucket(
        val bucket: StandbyBucket,
    ) : ToolkitRequest

    data object ForceStop : ToolkitRequest

    data class Restore(
        val actionId: Long,
    ) : ToolkitRequest

    data object ResetAll : ToolkitRequest
}

/**
 * A planned action that needs the user's yes first. [warnings] are the typed reasons, worded by the UI.
 * [stale] means the setting changed between the plan and the write, so the user is asked again with the
 * value that is there now. For a reset-all, [count] is the number of listed changes it restores
 * (`ToolkitAdmission.resetRestores`) and [others] the number it tries but cannot be expected to restore: not
 * restorable, or not read back.
 */
@Immutable
data class Confirmation(
    val request: ToolkitRequest,
    val target: String?,
    val warnings: ImmutableList<ToolkitWarning>,
    val stale: Boolean = false,
    val count: Int = 0,
    val others: Int = 0,
)

/** The status-notification switch: what the app stores, and whether the system lets it post at all. */
@Immutable
data class StatusNotificationState(
    val enabled: Boolean = false,
    /** Notifications are blocked for the app (permission or channel), so the switch cannot do anything. */
    val blocked: Boolean = false,
)

@Immutable
data class ToolkitState(
    val status: ToolkitPhase = ToolkitPhase.LOADING,
    val blocked: BlockedReason? = null,
    /** The server whose binder [blocked] was read from, named in the text; null when there is no binder. */
    val blockedBackend: ShizukuBackend? = null,
    val doze: DozeSection = DozeSection(),
    val apps: AppsSection = AppsSection(),
    val overrides: ImmutableList<OverrideItem> = persistentListOf(),
    val overridesNotice: ToolkitNotice? = null,
    val history: ImmutableList<HistoryItem> = persistentListOf(),
    val dexopt: DexoptSection = DexoptSection(),
    /** A doze, app, restore or reset write is running. The controls are disabled meanwhile. */
    val busy: Boolean = false,
    val detail: AppDetail? = null,
    val confirmation: Confirmation? = null,
    val resetReport: ResetReport? = null,
    val statusNotification: StatusNotificationState = StatusNotificationState(),
)

sealed interface ToolkitIntent {
    /** The route started (activity start or tab entered). */
    data object Visible : ToolkitIntent

    /** The route stopped (activity stop or tab left). */
    data object Hidden : ToolkitIntent

    /** Reads everything again (the error state's retry). */
    data object Reload : ToolkitIntent

    /** Asks Shizuku for permission; only the blocked state offers it. */
    data object Grant : ToolkitIntent

    data class SetQuery(
        val query: String,
    ) : ToolkitIntent

    data class OpenApp(
        val packageName: String,
    ) : ToolkitIntent

    data object CloseApp : ToolkitIntent

    data object ReloadApp : ToolkitIntent

    data class Act(
        val request: ToolkitRequest,
    ) : ToolkitIntent

    data object ConfirmPending : ToolkitIntent

    data object DismissConfirmation : ToolkitIntent

    data object DismissResetReport : ToolkitIntent

    data object StartDexopt : ToolkitIntent

    data object CancelDexopt : ToolkitIntent

    /** The status-notification switch. */
    data class SetStatusNotification(
        val enabled: Boolean,
    ) : ToolkitIntent

    /** "Allow in settings": asks the Route to open the app's notification settings. */
    data object OpenNotificationSettings : ToolkitIntent
}

sealed interface ToolkitEffect {
    /** "Reset all" finished: how many changes were restored out of how many were active. */
    data class ResetFinished(
        val restored: Int,
        val total: Int,
    ) : ToolkitEffect

    /** The Route should open the system notification settings of this app. */
    data object OpenNotificationSettings : ToolkitEffect
}

internal fun ShizukuState.blockedReason(): BlockedReason? =
    when (this) {
        is ShizukuState.Ready -> null
        ShizukuState.NotInstalled -> BlockedReason.NOT_INSTALLED
        ShizukuState.NotRunning -> BlockedReason.NOT_RUNNING
        is ShizukuState.PermissionRequired -> BlockedReason.PERMISSION_REQUIRED
        is ShizukuState.PermissionDenied -> BlockedReason.PERMISSION_DENIED
        is ShizukuState.UnsupportedServer -> BlockedReason.UNSUPPORTED_SERVER
    }
