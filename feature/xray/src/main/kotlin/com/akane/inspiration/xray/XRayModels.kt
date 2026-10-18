package com.akane.inspiration.xray

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.toolkit.OverrideKind
import com.akane.inspiration.telemetry.toolkit.ToolkitRefusal
import com.akane.inspiration.telemetry.toolkit.ToolkitUnavailable
import com.akane.inspiration.telemetry.toolkit.ToolkitWarning
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** The two views of the screen: where energy goes by hardware, and by app. */
enum class XRayTab { COMPONENTS, APPS }

enum class XRayStatus { LOADING, EMPTY, FAILED, READY }

/**
 * One hardware component. [mah] and [sharePct] are null when the data does not provide the figure (the UI
 * shows "—"); nothing is ever estimated. [key] is the battery-stats component name, or [XRayMapper.OTHER_KEY].
 */
@Immutable
data class ComponentRow(val key: String, val mah: Double?, val sharePct: Double?)

/** Components of the per-app bar. Few on purpose: the bar has only a handful of telemetry hues. */
enum class ComponentGroup { SCREEN, CPU, RADIOS, OTHER }

@Immutable
data class GroupSlice(val group: ComponentGroup, val mah: Double)

@Immutable
data class WakelockRow(val name: String, val durationMs: Long, val count: Long)

@Immutable
data class AlarmRow(val tag: String, val count: Long)

/** Energy by process state. A null field means the interval has no figure for it. */
@Immutable
data class ProcessStateMah(
    val foreground: Double?,
    val background: Double?,
    val foregroundService: Double?,
    val cached: Double?,
) {
    val isEmpty: Boolean get() = foreground == null && background == null && foregroundService == null && cached == null
}

/**
 * One ranked app. [label] and [icon] come from `AppLabels` and are null until resolved (and for entries
 * without a package). [packageName] is what the quick actions act on; it only ever comes from this list.
 */
@Immutable
data class AppRow(
    val uid: Int,
    val packageName: String?,
    val label: String?,
    val icon: ImageBitmap?,
    val totalMah: Double,
    val sharePct: Double,
    val slices: ImmutableList<GroupSlice>,
    val components: ImmutableList<ComponentRow>,
    val processStates: ProcessStateMah,
    val cpuMs: Long,
    val foregroundMs: Long,
    val wakelocks: ImmutableList<WakelockRow>,
    val alarms: ImmutableList<AlarmRow>,
    val dumpCountersAvailable: Boolean = true,
    val alarmsAvailable: Boolean = true,
)

/**
 * The time slice the figures describe (H14). [coveredFromMs]/[coveredToMs] are null while the data does
 * not cover it at all; [gapMs] is only set when the uncovered part is over five minutes.
 */
@Immutable
data class SliceInfo(
    val requestedFromMs: Long,
    val requestedToMs: Long,
    val coveredFromMs: Long?,
    val coveredToMs: Long?,
    val gapMs: Long?,
)

enum class BlockedReason { NOT_INSTALLED, NOT_RUNNING, PERMISSION_REQUIRED, PERMISSION_DENIED }

/** What a toolkit call came back with when it was not a success. */
sealed interface Problem {
    data class Refused(val reason: ToolkitRefusal) : Problem

    data class Unavailable(val reason: ToolkitUnavailable) : Problem

    data class Failed(val code: Int?) : Problem
}

/** Read-back of the app's current settings: this is what the controls show, never an optimistic guess. */
@Immutable
data class AppControls(
    val restricted: Boolean,
    val restrictionEditable: Boolean,
    val bucket: StandbyBucket?,
    val bucketRaw: Int?,
)

sealed interface ActionsState {
    /** Shizuku is not Ready. Granting happens in Cockpit or Toolkit, never here. */
    data class Blocked(val reason: BlockedReason) : ActionsState

    /** The entry has no single package to act on. */
    data object NoPackage : ActionsState

    data object Loading : ActionsState

    data class Loaded(val controls: AppControls) : ActionsState

    data class Unavailable(val problem: Problem) : ActionsState
}

/** A change of ours to this app that read-back shows is still in effect. */
@Immutable
data class OverrideRow(
    val actionId: Long,
    val kind: OverrideKind,
    val appOp: AppOp?,
    val bucket: StandbyBucket?,
    val sinceMs: Long,
    val restorable: Boolean,
)

/** The change an action made, for the result line. */
sealed interface Change {
    data object Restricted : Change

    data object Unrestricted : Change

    data class BucketSet(val bucket: StandbyBucket) : Change

    data object ForceStopped : Change

    data object Restored : Change
}

sealed interface ActionNotice {
    data class Applied(val change: Change) : ActionNotice

    data class Rejected(val problem: Problem) : ActionNotice
}

/**
 * A quick action the user picked. It carries no package: the ViewModel acts on the app of the open detail
 * sheet, which came from the in-app list (H12), never from an Intent.
 */
sealed interface ActionRequest {
    data class SetRestricted(val restricted: Boolean) : ActionRequest

    data class SetBucket(val bucket: StandbyBucket) : ActionRequest

    data object ForceStop : ActionRequest

    /** [actionId] is an audit row id taken from this app's [OverrideRow]s. */
    data class Restore(val actionId: Long) : ActionRequest
}

@Immutable
data class AppDetail(
    val app: AppRow,
    val actions: ActionsState,
    val overrides: ImmutableList<OverrideRow> = persistentListOf(),
    val busy: Boolean = false,
    val notice: ActionNotice? = null,
)

/** A planned action that needs the user's yes first. [warnings] are the typed reasons, worded by the UI. */
@Immutable
data class Confirmation(
    val request: ActionRequest,
    val target: String,
    val warnings: ImmutableList<ToolkitWarning>,
)

@Immutable
data class XRayState(
    val status: XRayStatus = XRayStatus.LOADING,
    val tab: XRayTab = XRayTab.COMPONENTS,
    val components: ImmutableList<ComponentRow> = persistentListOf(),
    val perUidAvailable: Boolean = true,
    val breakdownAvailable: Boolean = true,
    val apps: ImmutableList<AppRow> = persistentListOf(),
    val slice: SliceInfo? = null,
    val refreshing: Boolean = false,
    val detail: AppDetail? = null,
    val confirmation: Confirmation? = null,
)

sealed interface XRayIntent {
    /** The route started (activity start or tab entered). */
    data object Visible : XRayIntent

    /** The route stopped (activity stop or tab left). */
    data object Hidden : XRayIntent

    data class SelectTab(val tab: XRayTab) : XRayIntent

    /** Asks for a fresh capture (empty and error states). */
    data object Capture : XRayIntent

    data object ClearSlice : XRayIntent

    data class OpenApp(val uid: Int) : XRayIntent

    data object CloseApp : XRayIntent

    data object ReloadActions : XRayIntent

    data class Act(val request: ActionRequest) : XRayIntent

    data object ConfirmPending : XRayIntent

    data object DismissConfirmation : XRayIntent
}

sealed interface XRayEffect {
    /** A capture asked for by the screen did not complete; the last one stays on screen. */
    data object RefreshFailed : XRayEffect
}

internal fun ShizukuState.blockedReason(): BlockedReason? = when (this) {
    is ShizukuState.Ready -> null
    ShizukuState.NotInstalled -> BlockedReason.NOT_INSTALLED
    ShizukuState.NotRunning -> BlockedReason.NOT_RUNNING
    ShizukuState.PermissionRequired -> BlockedReason.PERMISSION_REQUIRED
    ShizukuState.PermissionDenied -> BlockedReason.PERMISSION_DENIED
}
