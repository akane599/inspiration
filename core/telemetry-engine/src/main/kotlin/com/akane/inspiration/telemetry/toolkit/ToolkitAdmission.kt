package com.akane.inspiration.telemetry.toolkit

/**
 * A change of ours as a screen lists it (one of [ToolkitActions.activeOverrides]): the part of it that decides what
 * the screen may ask for. Each screen's own override row implements it, so they all decide by [ToolkitAdmission].
 */
interface ListedOverride {
    /** The audit row to pass to [ToolkitActions.planRestore] and [ToolkitActions.restore]. */
    val actionId: Long

    /** See [Override.restorable]. */
    val restorable: Boolean

    /** See [Override.unverified]. */
    val unverified: ToolkitRefusal?
}

/** A toolkit write a screen is about to ask for, reduced to what decides whether it may ([ToolkitAdmission.admits]). */
sealed interface AdmissionRequest {
    /** A change to the open app's own settings: an app-op, a standby bucket or a force stop. */
    data object AppChange : AdmissionRequest

    /** The undo of the listed change of ours recorded as audit row [actionId]. */
    data class Restore(
        val actionId: Long,
    ) : AdmissionRequest

    /** The undo of every listed change. */
    data object ResetAll : AdmissionRequest
}

/**
 * The one "may this request run" rule of every screen that sends toolkit writes (X-Ray's app sheet and the Toolkit
 * screen), so the screens cannot disagree about it (B-06). It only decides what a screen may ask: [ToolkitActions]
 * plans every write again on a fresh read under its own lock. A screen keeps its own busy, confirmation and access
 * checks; whatever this refuses must not be offered as an enabled control, or a tap would do nothing without a word.
 */
object ToolkitAdmission {
    /**
     * Whether [request] may run on a screen that shows the open app's controls ([controlsShown]: read back and on
     * screen, not loading, failed or refused) and lists [listed].
     */
    fun admits(
        request: AdmissionRequest,
        controlsShown: Boolean,
        listed: List<ListedOverride>,
    ): Boolean =
        when (request) {
            // A change is decided against the values the user sees, never against a read that is not there.
            AdmissionRequest.AppChange -> {
                controlsShown
            }

            // A restore needs no readable controls: the undo of a change of ours that lifts a restriction is the one
            // write a protected app takes, and the restore plan reads its target itself. It must only be one the screen
            // lists as restorable.
            is AdmissionRequest.Restore -> {
                listed.any { it.actionId == request.actionId && it.restorable }
            }

            AdmissionRequest.ResetAll -> {
                listed.isNotEmpty()
            }
        }

    /**
     * Whether Reset all ([ToolkitActions.resetAll]) restores [override], as the screen lists it. Reset all tries every
     * listed change, but one that is not [restorable][ListedOverride.restorable] is refused without a write, and an
     * [unverified][ListedOverride.unverified] one is reported as not restored while its read still fails or its write is
     * still unsettled, and one Inspiration cannot attribute to its own write ([ToolkitRefusal.UNATTRIBUTED], B-01) is
     * left alone. So only a restorable change that was read back counts as one it restores (B-21).
     */
    fun resetRestores(override: ListedOverride): Boolean = override.restorable && override.unverified == null
}
