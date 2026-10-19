package com.akane.inspiration.ipc.toolkit

import android.os.SystemClock
import com.akane.inspiration.ipc.model.BgDexoptStatus
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult

internal const val CMD = "/system/bin/cmd"
internal const val DUMPSYS = "/system/bin/dumpsys"

/** How long reads reuse the protected-package set; writes never do. */
internal const val PROTECTED_TTL_MS = 30_000L

private const val USER = "0"
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val FIRST_APPLICATION_UID = 10_000

private const val PER_USER_UID_RANGE = 100_000
private const val UNKNOWN_USER = -1

/**
 * The Android user of the app's [appUid] (UID / 100 000). An unresolvable UID is [UNKNOWN_USER], which is not
 * user 0, so the toolkit refuses package actions (fail closed).
 */
internal fun toolkitUserId(appUid: Int?): Int = appUid?.let { it / PER_USER_UID_RANGE } ?: UNKNOWN_USER

// ApplicationInfo flag values (stable; FLAG_PERSISTENT/FLAG_UPDATED_SYSTEM_APP are not all public API).
private const val FLAG_SYSTEM = 0x1
private const val FLAG_PERSISTENT = 0x8
private const val FLAG_UPDATED_SYSTEM_APP = 0x80

/**
 * Typed write surface. Callers have already passed the UID check (the service does that first, on
 * the binder thread). Every entry point then takes one write mutex and validates in this order:
 * the service runs for user 0, package regex, user-0 install check, hard-deny rules; any failure to
 * decide refuses. A protected package (rule d) is refused too, except for a write that only lifts a
 * restriction ([RestrictionLift]), and its reads report the value with the refusal (see [Gate.Allowed]).
 * Global doze actions do not depend on the user and stay available.
 *
 * Every argv is fixed with an absolute executable and pins `--user 0`. `cmd settings` accepts the
 * option for the global namespace too (SettingsService parses `--user` before the namespace; the
 * provider then ignores the user for global keys), so the `device_idle_constants` argv carry it.
 *
 * The protected-package set (eight process spawns) is reused by reads for [PROTECTED_TTL_MS]; every write
 * drops it first, so a write is always gated on a fresh set. [elapsedMs] is the monotonic clock for that.
 */
internal class ToolkitExecutor(
    private val runner: CommandRunner,
    private val inspector: PackageInspector,
    private val dexopt: BgDexoptController,
    private val ownPackage: String,
    /**
     * The Android user the service inspects packages in (the app's UID / 100 000; negative when unknown).
     * Package commands pin `--user 0`, so outside user 0 they would act on another user's copy; see [gate].
     */
    private val userId: Int = 0,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val writeLock = Any()

    /** Doze raw values read so far (guarded by [writeLock]); the only non-profile values restore accepts. */
    private val seenDozeRaws = LinkedHashSet<String>()

    /** The last protected-package set read and when (guarded by [writeLock]); see [protectedPackages]. */
    private var protectedCache: ProtectedPackages? = null
    private var protectedReadAtMs = 0L

    fun setAppOp(
        pkg: String?,
        opCode: Int,
        modeCode: Int,
    ): ToolkitResult =
        synchronized(writeLock) {
            protectedCache = null
            val op = AppOp.fromCode(opCode) ?: return ToolkitResult.refused(ToolkitCode.ARG_VALUE)
            // Only ignore/allow are writable: `default` would leave background ops restricted.
            val mode =
                AppOpMode.fromCode(modeCode)?.takeIf { it.writable }
                    ?: return ToolkitResult.refused(ToolkitCode.ARG_VALUE)
            val target =
                when (val gate = gate(pkg)) {
                    is Gate.Denied -> return gate.result
                    is Gate.Allowed -> gate
                }
            target.protection?.let { if (!RestrictionLift.appOp(mode)) return ToolkitResult.refused(it) }
            val beforeState = readAppOpState(pkg.orEmpty(), op) ?: return ToolkitResult.refused(ToolkitCode.PARSE)
            val before = beforeState.effectiveMode
            // Package writes cannot change a UID override, and changing the UID would affect shared-UID apps.
            if (beforeState.uidMode != null) return ToolkitResult.refused(ToolkitCode.NOT_EFFECTIVE, before = before)
            val write = runner.run(listOf(CMD, "appops", "set", "--user", USER, pkg.orEmpty(), op.argv, mode.argv))
            if (!write.ok) return writeFailed(write, before)
            val after = readAppOp(pkg.orEmpty(), op) ?: return unverified(before)
            if (after != mode.argv) return ToolkitResult.failed(ToolkitCode.NOT_EFFECTIVE, before, after)
            ToolkitResult.done(target.warnCode, before, after)
        }

    fun getAppOps(pkg: String?): ToolkitResult =
        synchronized(writeLock) {
            val target =
                when (val gate = gate(pkg)) {
                    is Gate.Denied -> return gate.result
                    is Gate.Allowed -> gate
                }
            val modes =
                AppOp.entries.map { op ->
                    op to (readAppOp(pkg.orEmpty(), op) ?: return target.unreadable())
                }
            target.answer(modes.joinToString(",") { (op, mode) -> "${op.argv}=$mode" })
        }

    fun setStandbyBucket(
        pkg: String?,
        bucketCode: Int,
    ): ToolkitResult =
        synchronized(writeLock) {
            protectedCache = null
            val bucket = StandbyBucket.fromCode(bucketCode) ?: return ToolkitResult.refused(ToolkitCode.ARG_VALUE)
            val target =
                when (val gate = gate(pkg)) {
                    is Gate.Denied -> return gate.result
                    is Gate.Allowed -> gate
                }
            val before = readBucket(pkg.orEmpty()) ?: return ToolkitResult.refused(ToolkitCode.PARSE)
            target.protection?.let { protection ->
                // An unparsable current bucket cannot show the write only lifts a restriction.
                val lifts = before.toIntOrNull()?.let { RestrictionLift.bucket(bucket, it) } ?: false
                if (!lifts) return ToolkitResult.refused(protection)
            }
            val write =
                runner.run(
                    listOf(CMD, "activity", "set-standby-bucket", "--user", USER, pkg.orEmpty(), bucket.argv),
                )
            if (!write.ok) return writeFailed(write, before)
            val after = readBucket(pkg.orEmpty()) ?: return unverified(before)
            if (after != bucket.rawValue.toString()) return ToolkitResult.failed(ToolkitCode.NOT_EFFECTIVE, before, after)
            ToolkitResult.done(target.warnCode, before, after)
        }

    fun getStandbyBucket(pkg: String?): ToolkitResult =
        synchronized(writeLock) {
            val target =
                when (val gate = gate(pkg)) {
                    is Gate.Denied -> return gate.result
                    is Gate.Allowed -> gate
                }
            val bucket = readBucket(pkg.orEmpty()) ?: return target.unreadable()
            target.answer(bucket)
        }

    fun forceStop(pkg: String?): ToolkitResult =
        synchronized(writeLock) {
            protectedCache = null
            val target =
                when (val gate = gate(pkg)) {
                    is Gate.Denied -> return gate.result
                    is Gate.Allowed -> gate
                }
            // Stopping an app never lifts a restriction.
            target.protection?.let { return ToolkitResult.refused(it) }
            val before = "stopped=${target.facts.stopped}"
            val write = runner.run(listOf(CMD, "activity", "force-stop", "--user", USER, pkg.orEmpty()))
            if (!write.ok) return writeFailed(write, before)
            // The stop already ran; a failed read-back leaves its effect unknown, not failed.
            val after =
                when (val now = inspector.lookup(pkg.orEmpty())) {
                    is PackageLookup.Found -> "stopped=${now.stopped}"
                    else -> return unverified(before)
                }
            ToolkitResult.done(target.warnCode, before, after)
        }

    fun applyDozeProfile(
        profileCode: Int,
        expectedBeforeRaw: String?,
    ): ToolkitResult =
        synchronized(writeLock) {
            protectedCache = null
            val profile = DozeProfile.fromCode(profileCode) ?: return ToolkitResult.refused(ToolkitCode.ARG_VALUE)
            val current = readDozeRaw() ?: return ToolkitResult.refused(ToolkitCode.PARSE)
            if (current.value != expectedBeforeRaw) return ToolkitResult.refused(ToolkitCode.STALE, before = current.value)
            // Undo must survive service recreation, not just an immediate rollback: read-back memory is
            // process-local, so the original must pass a fresh executor's replay validation.
            if (current.value != null && !DozeRaw.isRestorable(current.value)) {
                return ToolkitResult.refused(ToolkitCode.ARG_VALUE, before = current.value)
            }
            remember(current.value)
            val write = writeDozeRaw(profile.constants)
            val after = readDozeRaw()
            val effective =
                write.ok && after != null && after.value == profile.constants &&
                    (profile == DozeProfile.STANDARD || dozeEffective(profile))
            if (effective) return ToolkitResult.done(before = current.value, after = after?.value)
            writeDozeRaw(current.value)
            val rolledBack = readDozeRaw()
            // A rollback that did not read back as the prior value may have left the profile applied.
            if (rolledBack == null || rolledBack.value != current.value) return unverified(current.value, rolledBack?.value)
            ToolkitResult.unavailable(ToolkitCode.NOT_EFFECTIVE, before = current.value, after = rolledBack.value)
        }

    fun getDozeState(): ToolkitResult =
        synchronized(writeLock) {
            val raw = readDozeRaw() ?: return ToolkitResult.failed(ToolkitCode.PARSE)
            remember(raw.value)
            val matched = DozeProfile.entries.firstOrNull { it.constants == raw.value }
            val settings =
                runner
                    .run(listOf(DUMPSYS, "deviceidle"))
                    .takeIf { it.ok }
                    ?.let { ToolkitParsers.dozeSettings(it.text) }
            val message =
                settings?.let { values ->
                    DozeProfile.controlledKeys.mapNotNull { key -> values[key]?.let { "$key=$it" } }.joinToString(",")
                }
            ToolkitResult.done(matched?.code ?: ToolkitCode.DOZE_CUSTOM, after = raw.value, message = message)
        }

    fun restoreDozeRaw(
        rawOrNull: String?,
        expectedCurrentRaw: String?,
    ): ToolkitResult =
        synchronized(writeLock) {
            protectedCache = null
            val target = rawOrNull?.takeIf { it.isNotEmpty() }
            // Only a well-formed `key=value` list (any `[a-z][a-z0-9_]{0,63}` key, OEM keys included; see
            // DozeRaw.isRestorable) or a value this executor itself read may be replayed. Unsupported
            // originals from older releases cannot be restored after recreation; do not bypass validation.
            if (target != null && !(DozeRaw.isRestorable(target) || target in seenDozeRaws)) {
                return ToolkitResult.refused(ToolkitCode.ARG_VALUE)
            }
            val current = readDozeRaw() ?: return ToolkitResult.refused(ToolkitCode.PARSE)
            if (current.value != expectedCurrentRaw?.takeIf { it.isNotEmpty() }) {
                return ToolkitResult.refused(ToolkitCode.STALE, before = current.value)
            }
            val write = writeDozeRaw(target)
            val after = readDozeRaw()
            if (write.timedOut || (write.ok && after == null)) return unverified(current.value, after?.value)
            if (!write.ok || after == null || after.value != target) {
                return ToolkitResult.failed(ToolkitCode.NOT_EFFECTIVE, before = current.value, after = after?.value)
            }
            ToolkitResult.done(before = current.value, after = after.value)
        }

    /**
     * Deliberately outside [writeLock]: the plugged probe can take seconds, and holding the global
     * lock for it would block a cancel. The controller's own compare-and-set serializes starts.
     */
    fun startBgDexopt(): ToolkitResult = dexopt.tryStart()

    fun bgDexoptStatus(): BgDexoptStatus = dexopt.status()

    fun cancelBgDexopt(): ToolkitResult {
        // No command cancels a job here; the platform's nearest argv would start another dexopt pass instead.
        val argv = dexopt.cancelArgv ?: return ToolkitResult.refused(ToolkitCode.UNSUPPORTED_API)
        return synchronized(writeLock) {
            protectedCache = null
            dexopt.beginUserCancel()
            var confirmed = false
            try {
                val out = runner.run(argv)
                confirmed = out.ok
                if (out.ok) ToolkitResult.done() else ToolkitResult.failed(execCode(out))
            } finally {
                dexopt.endUserCancel(confirmed)
            }
        }
    }

    /** Called by the service's destroy() before the process exits; never throws. */
    fun cancelOnShutdown() {
        try {
            dexopt.cancelIfRunning()
        } catch (_: RuntimeException) {
            // Exiting regardless; there is nobody to report to.
        }
    }

    private sealed interface Gate {
        class Denied(
            val result: ToolkitResult,
        ) : Gate

        /**
         * [protection] is null for an ordinary app. [ToolkitCode.DENY_PROTECTED] marks a protected package (rule d),
         * [ToolkitCode.PROTECTION_UNKNOWN] one whose protection could not be decided: a read is still refused with
         * that code but carries the value it read ([answer]), so a change of ours stays visible and checkable, and a
         * write goes ahead only when it lifts a restriction ([RestrictionLift]).
         */
        class Allowed(
            val facts: PackageLookup.Found,
            val warnCode: Int,
            val protection: Int? = null,
        ) : Gate {
            fun answer(value: String): ToolkitResult =
                protection?.let { ToolkitResult.refused(it, after = value) } ?: ToolkitResult.done(warnCode, after = value)

            /** A read that failed: for a protected package its refusal (with no value), else PARSE. */
            fun unreadable(): ToolkitResult = ToolkitResult.refused(protection ?: ToolkitCode.PARSE)
        }
    }

    /**
     * Every package read and write passes here first. Outside user 0 (a work profile or secondary user) the
     * package facts come from that user while every argv and protection check pins `--user 0`, so the
     * command could change the owner's copy of a same-named app and still read back as done: refused.
     * The absolute rules, and a package whose facts could not be read, refuse everything; a protected or
     * undecidable package (rule d) passes with its [Gate.Allowed.protection] set.
     */
    private fun gate(pkg: String?): Gate {
        if (userId != 0) return deny(ToolkitCode.UNSUPPORTED_USER)
        if (!PackageNames.isValid(pkg)) return deny(ToolkitCode.ARG_PACKAGE)
        val name = pkg.orEmpty()
        val facts =
            when (val found = inspector.lookup(name)) {
                PackageLookup.NotInstalled -> return deny(ToolkitCode.NOT_INSTALLED)
                PackageLookup.Unknown -> return deny(ToolkitCode.PROTECTION_UNKNOWN)
                is PackageLookup.Found -> found
            }
        val denial = hardDeny(name, facts)
        if (denial != null) return deny(denial)
        val updatedSystem = facts.flags and FLAG_UPDATED_SYSTEM_APP != 0 && facts.flags and FLAG_SYSTEM != 0
        val protection =
            when (val protected = protectedPackages()) {
                null -> ToolkitCode.PROTECTION_UNKNOWN
                else -> if (protected.contains(name)) ToolkitCode.DENY_PROTECTED else null
            }
        return Gate.Allowed(facts, if (updatedSystem) ToolkitCode.WARN_UPDATED_SYSTEM else ToolkitCode.OK, protection)
    }

    /** The rules that refuse every read and write of a package (rule d, protected packages, is [gate]'s). */
    private fun hardDeny(
        pkg: String,
        facts: PackageLookup.Found,
    ): Int? =
        when {
            pkg == ownPackage || pkg == SHIZUKU_PACKAGE -> ToolkitCode.DENY_OWN
            facts.appId < FIRST_APPLICATION_UID -> ToolkitCode.DENY_APP_ID
            facts.flags and FLAG_PERSISTENT != 0 -> ToolkitCode.DENY_PERSISTENT
            facts.flags and FLAG_SYSTEM != 0 && facts.flags and FLAG_UPDATED_SYSTEM_APP == 0 -> ToolkitCode.DENY_SYSTEM
            else -> null
        }

    /**
     * The protected-package set, read again once [PROTECTED_TTL_MS] has passed or a write dropped it. An override
     * recompute gates one read per target, so this turns 8 spawns per target into 8 per window. A failed read
     * (null, which refuses) is not kept.
     */
    private fun protectedPackages(): ProtectedPackages? {
        val now = elapsedMs()
        protectedCache?.let { if (now - protectedReadAtMs in 0 until PROTECTED_TTL_MS) return it }
        return inspector.protectedPackages()?.also {
            protectedCache = it
            protectedReadAtMs = now
        }
    }

    private fun deny(code: Int): Gate = Gate.Denied(ToolkitResult.refused(code))

    private fun readAppOp(
        pkg: String,
        op: AppOp,
    ): String? = readAppOpState(pkg, op)?.effectiveMode

    private fun readAppOpState(
        pkg: String,
        op: AppOp,
    ): ToolkitParsers.AppOpState? {
        val out = runner.run(listOf(CMD, "appops", "get", "--user", USER, pkg, op.argv))
        return if (out.ok && !out.truncated) ToolkitParsers.appOpState(op, out.text) else null
    }

    private fun readBucket(pkg: String): String? {
        val out = runner.run(listOf(CMD, "activity", "get-standby-bucket", "--user", USER, pkg))
        return if (out.ok) ToolkitParsers.standbyBucket(out.text)?.toString() else null
    }

    /** Wraps the nullable value so that "unset" (a valid read) differs from "read failed" (null). */
    private class DozeRead(
        val value: String?,
    )

    private fun readDozeRaw(): DozeRead? {
        val out = runner.run(listOf(CMD, "settings", "get", "--user", USER, "global", IDLE_KEY))
        return if (out.ok) DozeRead(ToolkitParsers.settingValue(out.text)) else null
    }

    private fun writeDozeRaw(value: String?): CommandOutput =
        runner.run(
            if (value == null) {
                listOf(CMD, "settings", "delete", "--user", USER, "global", IDLE_KEY)
            } else {
                listOf(CMD, "settings", "put", "--user", USER, "global", IDLE_KEY, value)
            },
        )

    /** Remembers a value this executor read (and found safe) so a later restore may replay it. */
    private fun remember(raw: String?) {
        if (raw == null || !DozeRaw.isSafe(raw)) return
        seenDozeRaws.remove(raw)
        seenDozeRaws.add(raw)
        if (seenDozeRaws.size > MAX_SEEN_DOZE_RAWS) seenDozeRaws.remove(seenDozeRaws.first())
    }

    private fun dozeEffective(profile: DozeProfile): Boolean {
        repeat(EFFECTIVE_ATTEMPTS) { attempt ->
            val dump = runner.run(listOf(DUMPSYS, "deviceidle"))
            val settings = if (dump.ok) ToolkitParsers.dozeSettings(dump.text) else null
            if (settings != null && profile.effectiveMs.all { (key, ms) -> settings[key] == ms }) return true
            if (attempt < EFFECTIVE_ATTEMPTS - 1) sleepMs(EFFECTIVE_RETRY_MS)
        }
        return false
    }

    private fun execCode(out: CommandOutput): Int = if (out.timedOut) ToolkitCode.TIMEOUT else ToolkitCode.EXEC

    /** A timed-out write was killed after it may have committed, so its outcome is unknown, not failed. */
    private fun writeFailed(
        write: CommandOutput,
        before: String?,
    ): ToolkitResult = if (write.timedOut) unverified(before) else ToolkitResult.failed(execCode(write), before = before)

    private fun unverified(
        before: String?,
        after: String? = null,
    ): ToolkitResult = ToolkitResult.unavailable(ToolkitCode.UNVERIFIED, before = before, after = after)

    private companion object {
        const val IDLE_KEY = "device_idle_constants"
        const val MAX_SEEN_DOZE_RAWS = 8
        const val EFFECTIVE_ATTEMPTS = 3
        const val EFFECTIVE_RETRY_MS = 300L
    }
}
