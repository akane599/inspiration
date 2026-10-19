package com.akane.inspiration.ipc.model

import kotlinx.parcelize.Parcelize

/**
 * Outcome of a typed privileged action. Only ints and strings cross the binder (no enums).
 *
 * [beforeRaw]/[afterRaw] are read back inside the service. On a DONE result, null means the
 * underlying value is unset (doze constants); a failed read-back never yields DONE.
 */
@Parcelize
data class ToolkitResult(
    val status: Int,
    val code: Int,
    val beforeRaw: String?,
    val afterRaw: String?,
    val message: String?,
) : android.os.Parcelable {
    val isDone: Boolean get() = status == ToolkitStatus.DONE

    companion object {
        fun done(
            code: Int = ToolkitCode.OK,
            before: String? = null,
            after: String? = null,
            message: String? = null,
        ): ToolkitResult = ToolkitResult(ToolkitStatus.DONE, code, before, after, message)

        fun refused(
            code: Int,
            before: String? = null,
            after: String? = null,
        ): ToolkitResult = ToolkitResult(ToolkitStatus.REFUSED, code, before, after, null)

        fun unavailable(
            code: Int,
            before: String? = null,
            after: String? = null,
        ): ToolkitResult = ToolkitResult(ToolkitStatus.UNAVAILABLE, code, before, after, null)

        fun failed(
            code: Int,
            before: String? = null,
            after: String? = null,
        ): ToolkitResult = ToolkitResult(ToolkitStatus.FAILED, code, before, after, null)
    }
}

object ToolkitStatus {
    const val DONE = 0
    const val REFUSED = 1
    const val UNAVAILABLE = 2
    const val FAILED = 3
}

object ToolkitCode {
    const val OK = 0

    /** DONE, but the target is an updated system app; the UI must warn. */
    const val WARN_UPDATED_SYSTEM = 1

    const val CALLER = 10
    const val ARG_PACKAGE = 11
    const val ARG_VALUE = 12
    const val NOT_INSTALLED = 13

    /** Package actions run only for Android user 0; the service runs for another user (work profile etc.). */
    const val UNSUPPORTED_USER = 14

    /**
     * The action cannot be made safe on this Android version, so nothing was run. bg-dexopt below API 33: no shell
     * command can cancel a job started from the shell there, so the unplug guard and the cap could not stop it.
     */
    const val UNSUPPORTED_API = 15

    const val DENY_APP_ID = 20
    const val DENY_PERSISTENT = 21
    const val DENY_SYSTEM = 22
    const val DENY_PROTECTED = 23
    const val DENY_OWN = 24

    /**
     * [DENY_PROTECTED] (rule d) and this one (the protected set could not be read) refuse every change except one that
     * only lifts a restriction (`RestrictionLift`). A package read refused with either code carries the value it read
     * in `afterRaw` (null when that read failed too), so a change already made stays visible and restorable.
     */
    const val PROTECTION_UNKNOWN = 25

    const val STALE = 30
    const val NOT_EFFECTIVE = 31
    const val PARSE = 32
    const val EXEC = 33
    const val TIMEOUT = 34

    /**
     * UNAVAILABLE: the write was issued but its outcome is unknown (the command timed out after it may have
     * committed, the read-back failed, or a doze rollback did not land). The client keeps the audit row
     * PENDING and reads the target back later instead of recording a failure.
     */
    const val UNVERIFIED = 35

    const val BUSY = 40
    const val NOT_PLUGGED = 41

    /** getDozeState: the raw value matches no profile constant. */
    const val DOZE_CUSTOM = 99
}

/**
 * Service API level. Version 2 added txns 20-31; version 3 added app-op mode code 3 (`allow`) and stopped
 * accepting code 2 (`default`) as a write. Version 4 picks the bg-dexopt cancel command by API level and refuses
 * bg-dexopt below API 33 ([ToolkitCode.UNSUPPORTED_API], status reason [BgDexoptReason.UNSUPPORTED_API]); an older
 * service would still send a cancel that does not cancel there. The client refuses writes below it (STALE_SERVICE).
 */
object ToolkitApi {
    const val VERSION = 4
}
