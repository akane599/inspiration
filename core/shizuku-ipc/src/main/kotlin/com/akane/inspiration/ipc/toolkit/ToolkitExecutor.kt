package com.akane.inspiration.ipc.toolkit

import com.akane.inspiration.ipc.model.BgDexoptStatus
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult

internal const val CMD = "/system/bin/cmd"
internal const val DUMPSYS = "/system/bin/dumpsys"

private const val USER = "0"
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val FIRST_APPLICATION_UID = 10_000

// ApplicationInfo flag values (stable; FLAG_PERSISTENT/FLAG_UPDATED_SYSTEM_APP are not all public API).
private const val FLAG_SYSTEM = 0x1
private const val FLAG_PERSISTENT = 0x8
private const val FLAG_UPDATED_SYSTEM_APP = 0x80

/**
 * Typed write surface. Callers have already passed the UID check (the service does that first, on
 * the binder thread). Every entry point then takes one write mutex and validates in this order:
 * package regex, user-0 install check, hard-deny rules; any failure to decide refuses.
 *
 * Every argv is fixed with an absolute executable and pins `--user 0`. `cmd settings` accepts the
 * option for the global namespace too (SettingsService parses `--user` before the namespace; the
 * provider then ignores the user for global keys), so the `device_idle_constants` argv carry it.
 */
internal class ToolkitExecutor(
    private val runner: CommandRunner,
    private val inspector: PackageInspector,
    private val dexopt: BgDexoptController,
    private val ownPackage: String,
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val writeLock = Any()

    /** Doze raw values read so far (guarded by [writeLock]); the only non-profile values restore accepts. */
    private val seenDozeRaws = LinkedHashSet<String>()

    fun setAppOp(pkg: String?, opCode: Int, modeCode: Int): ToolkitResult = synchronized(writeLock) {
        val op = AppOp.fromCode(opCode) ?: return ToolkitResult.refused(ToolkitCode.ARG_VALUE)
        val mode = AppOpMode.fromCode(modeCode) ?: return ToolkitResult.refused(ToolkitCode.ARG_VALUE)
        val target = when (val gate = gate(pkg)) {
            is Gate.Denied -> return gate.result
            is Gate.Allowed -> gate
        }
        val before = readAppOp(pkg.orEmpty(), op) ?: return ToolkitResult.refused(ToolkitCode.PARSE)
        val write = runner.run(listOf(CMD, "appops", "set", "--user", USER, pkg.orEmpty(), op.argv, mode.argv))
        if (!write.ok) return ToolkitResult.failed(execCode(write), before = before)
        val after = readAppOp(pkg.orEmpty(), op) ?: return ToolkitResult.failed(ToolkitCode.PARSE, before = before)
        if (after != mode.argv) return ToolkitResult.failed(ToolkitCode.NOT_EFFECTIVE, before, after)
        ToolkitResult.done(target.warnCode, before, after)
    }

    fun getAppOps(pkg: String?): ToolkitResult = synchronized(writeLock) {
        val target = when (val gate = gate(pkg)) {
            is Gate.Denied -> return gate.result
            is Gate.Allowed -> gate
        }
        val modes = AppOp.entries.map { op ->
            op to (readAppOp(pkg.orEmpty(), op) ?: return ToolkitResult.refused(ToolkitCode.PARSE))
        }
        ToolkitResult.done(target.warnCode, after = modes.joinToString(",") { (op, mode) -> "${op.argv}=$mode" })
    }

    fun setStandbyBucket(pkg: String?, bucketCode: Int): ToolkitResult = synchronized(writeLock) {
        val bucket = StandbyBucket.fromCode(bucketCode) ?: return ToolkitResult.refused(ToolkitCode.ARG_VALUE)
        val target = when (val gate = gate(pkg)) {
            is Gate.Denied -> return gate.result
            is Gate.Allowed -> gate
        }
        val before = readBucket(pkg.orEmpty()) ?: return ToolkitResult.refused(ToolkitCode.PARSE)
        val write = runner.run(
            listOf(CMD, "activity", "set-standby-bucket", "--user", USER, pkg.orEmpty(), bucket.argv),
        )
        if (!write.ok) return ToolkitResult.failed(execCode(write), before = before)
        val after = readBucket(pkg.orEmpty()) ?: return ToolkitResult.failed(ToolkitCode.PARSE, before = before)
        if (after != bucket.rawValue.toString()) return ToolkitResult.failed(ToolkitCode.NOT_EFFECTIVE, before, after)
        ToolkitResult.done(target.warnCode, before, after)
    }

    fun getStandbyBucket(pkg: String?): ToolkitResult = synchronized(writeLock) {
        val target = when (val gate = gate(pkg)) {
            is Gate.Denied -> return gate.result
            is Gate.Allowed -> gate
        }
        val bucket = readBucket(pkg.orEmpty()) ?: return ToolkitResult.refused(ToolkitCode.PARSE)
        ToolkitResult.done(target.warnCode, after = bucket)
    }

    fun forceStop(pkg: String?): ToolkitResult = synchronized(writeLock) {
        val target = when (val gate = gate(pkg)) {
            is Gate.Denied -> return gate.result
            is Gate.Allowed -> gate
        }
        val before = "stopped=${target.facts.stopped}"
        val write = runner.run(listOf(CMD, "activity", "force-stop", "--user", USER, pkg.orEmpty()))
        if (!write.ok) return ToolkitResult.failed(execCode(write), before = before)
        val after = when (val now = inspector.lookup(pkg.orEmpty())) {
            is PackageLookup.Found -> "stopped=${now.stopped}"
            else -> return ToolkitResult.failed(ToolkitCode.PARSE, before = before)
        }
        ToolkitResult.done(target.warnCode, before, after)
    }

    fun applyDozeProfile(profileCode: Int, expectedBeforeRaw: String?): ToolkitResult = synchronized(writeLock) {
        val profile = DozeProfile.fromCode(profileCode) ?: return ToolkitResult.refused(ToolkitCode.ARG_VALUE)
        val current = readDozeRaw() ?: return ToolkitResult.refused(ToolkitCode.PARSE)
        if (current.value != expectedBeforeRaw) return ToolkitResult.refused(ToolkitCode.STALE, before = current.value)
        // The prior value is replayed on rollback, so it must be one we would accept to write.
        if (current.value != null && !DozeRaw.isSafe(current.value)) {
            return ToolkitResult.refused(ToolkitCode.ARG_VALUE, before = current.value)
        }
        remember(current.value)
        val write = writeDozeRaw(profile.constants)
        val after = readDozeRaw()
        val effective = write.ok && after != null && after.value == profile.constants &&
            (profile == DozeProfile.STANDARD || dozeEffective(profile))
        if (effective) return ToolkitResult.done(before = current.value, after = after?.value)
        writeDozeRaw(current.value)
        val rolledBack = readDozeRaw()
        ToolkitResult.unavailable(ToolkitCode.NOT_EFFECTIVE, before = current.value, after = rolledBack?.value)
    }

    fun getDozeState(): ToolkitResult = synchronized(writeLock) {
        val raw = readDozeRaw() ?: return ToolkitResult.failed(ToolkitCode.PARSE)
        remember(raw.value)
        val matched = DozeProfile.entries.firstOrNull { it.constants == raw.value }
        val settings = runner.run(listOf(DUMPSYS, "deviceidle")).takeIf { it.ok }
            ?.let { ToolkitParsers.dozeSettings(it.text) }
        val message = settings?.let { values ->
            DozeProfile.controlledKeys.mapNotNull { key -> values[key]?.let { "$key=$it" } }.joinToString(",")
        }
        ToolkitResult.done(matched?.code ?: ToolkitCode.DOZE_CUSTOM, after = raw.value, message = message)
    }

    fun restoreDozeRaw(rawOrNull: String?, expectedCurrentRaw: String?): ToolkitResult = synchronized(writeLock) {
        val target = rawOrNull?.takeIf { it.isNotEmpty() }
        // Only a well-formed AOSP-key string or a value this executor itself read may be replayed.
        if (target != null && !(DozeRaw.isRestorable(target) || target in seenDozeRaws)) {
            return ToolkitResult.refused(ToolkitCode.ARG_VALUE)
        }
        val current = readDozeRaw() ?: return ToolkitResult.refused(ToolkitCode.PARSE)
        if (current.value != expectedCurrentRaw?.takeIf { it.isNotEmpty() }) {
            return ToolkitResult.refused(ToolkitCode.STALE, before = current.value)
        }
        val write = writeDozeRaw(target)
        val after = readDozeRaw()
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

    fun cancelBgDexopt(): ToolkitResult = synchronized(writeLock) {
        dexopt.noteUserCancel()
        val out = runner.run(BG_DEXOPT_CANCEL_ARGV)
        if (out.ok) dexopt.noteCancelCompleted()
        if (out.ok) ToolkitResult.done() else ToolkitResult.failed(execCode(out))
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
        class Denied(val result: ToolkitResult) : Gate

        class Allowed(val facts: PackageLookup.Found, val warnCode: Int) : Gate
    }

    private fun gate(pkg: String?): Gate {
        if (!PackageNames.isValid(pkg)) return deny(ToolkitCode.ARG_PACKAGE)
        val name = pkg.orEmpty()
        val facts = when (val found = inspector.lookup(name)) {
            PackageLookup.NotInstalled -> return deny(ToolkitCode.NOT_INSTALLED)
            PackageLookup.Unknown -> return deny(ToolkitCode.PROTECTION_UNKNOWN)
            is PackageLookup.Found -> found
        }
        val denial = hardDeny(name, facts)
        if (denial != null) return deny(denial)
        val updatedSystem = facts.flags and FLAG_UPDATED_SYSTEM_APP != 0 && facts.flags and FLAG_SYSTEM != 0
        return Gate.Allowed(facts, if (updatedSystem) ToolkitCode.WARN_UPDATED_SYSTEM else ToolkitCode.OK)
    }

    private fun hardDeny(pkg: String, facts: PackageLookup.Found): Int? = when {
        pkg == ownPackage || pkg == SHIZUKU_PACKAGE -> ToolkitCode.DENY_OWN
        facts.appId < FIRST_APPLICATION_UID -> ToolkitCode.DENY_APP_ID
        facts.flags and FLAG_PERSISTENT != 0 -> ToolkitCode.DENY_PERSISTENT
        facts.flags and FLAG_SYSTEM != 0 && facts.flags and FLAG_UPDATED_SYSTEM_APP == 0 -> ToolkitCode.DENY_SYSTEM
        else -> when (val protected = inspector.protectedPackages()) {
            null -> ToolkitCode.PROTECTION_UNKNOWN
            else -> if (protected.contains(pkg)) ToolkitCode.DENY_PROTECTED else null
        }
    }

    private fun deny(code: Int): Gate = Gate.Denied(ToolkitResult.refused(code))

    private fun readAppOp(pkg: String, op: AppOp): String? {
        val out = runner.run(listOf(CMD, "appops", "get", "--user", USER, pkg, op.argv))
        return if (out.ok) ToolkitParsers.appOpMode(op, out.text) else null
    }

    private fun readBucket(pkg: String): String? {
        val out = runner.run(listOf(CMD, "activity", "get-standby-bucket", "--user", USER, pkg))
        return if (out.ok) ToolkitParsers.standbyBucket(out.text)?.toString() else null
    }

    /** Wraps the nullable value so that "unset" (a valid read) differs from "read failed" (null). */
    private class DozeRead(val value: String?)

    private fun readDozeRaw(): DozeRead? {
        val out = runner.run(listOf(CMD, "settings", "get", "--user", USER, "global", IDLE_KEY))
        return if (out.ok) DozeRead(ToolkitParsers.settingValue(out.text)) else null
    }

    private fun writeDozeRaw(value: String?): CommandOutput = runner.run(
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

    private companion object {
        const val IDLE_KEY = "device_idle_constants"
        const val MAX_SEEN_DOZE_RAWS = 8
        const val EFFECTIVE_ATTEMPTS = 3
        const val EFFECTIVE_RETRY_MS = 300L
    }
}
