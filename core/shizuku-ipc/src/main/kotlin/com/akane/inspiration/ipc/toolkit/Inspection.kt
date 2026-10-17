package com.akane.inspiration.ipc.toolkit

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import java.io.File
import java.io.IOException

internal sealed interface PackageLookup {
    data object NotInstalled : PackageLookup

    /** The query itself failed; callers fail closed. */
    data object Unknown : PackageLookup

    /** [flags] are `ApplicationInfo.flags` for user 0. */
    data class Found(val appId: Int, val flags: Int, val stopped: Boolean) : PackageLookup
}

/** Packages whose removal from the background would break the device; hard-denied (rule d). */
internal data class ProtectedPackages(
    val roleHolders: Set<String> = emptySet(),
    val defaultIme: Set<String> = emptySet(),
    val accessibility: Set<String> = emptySet(),
    val deviceAdmins: Set<String> = emptySet(),
    val alwaysOnVpn: Set<String> = emptySet(),
) {
    fun contains(pkg: String): Boolean =
        pkg in roleHolders || pkg in defaultIme || pkg in accessibility || pkg in deviceAdmins || pkg in alwaysOnVpn
}

internal interface PackageInspector {
    fun lookup(pkg: String): PackageLookup

    /** Null when any query fails (fail closed). */
    fun protectedPackages(): ProtectedPackages?
}

internal fun interface PluggedProbe {
    /** True only when positively plugged in; unknown is false (fail closed). */
    fun isPlugged(): Boolean
}

/** What the shell-UID service uses when it has no Context: every question is a refusal. */
internal object NoContextInspector : PackageInspector {
    override fun lookup(pkg: String): PackageLookup = PackageLookup.Unknown

    override fun protectedPackages(): ProtectedPackages? = null
}

internal val NoContextPluggedProbe = PluggedProbe { false }

internal class AndroidPackageInspector(
    private val context: Context,
    private val runner: CommandRunner,
) : PackageInspector {
    @Suppress("DEPRECATION")
    override fun lookup(pkg: String): PackageLookup = try {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        PackageLookup.Found(
            appId = info.uid % PER_USER_RANGE,
            flags = info.flags,
            stopped = info.flags and FLAG_STOPPED != 0,
        )
    } catch (_: PackageManager.NameNotFoundException) {
        PackageLookup.NotInstalled
    } catch (_: RuntimeException) {
        PackageLookup.Unknown
    }

    override fun protectedPackages(): ProtectedPackages? {
        val roles = HashSet<String>()
        for (role in ROLES) {
            val out = runner.run(listOf(CMD, "role", "get-role-holders", "--user", "0", role))
            if (!out.ok) return null
            roles += ToolkitParsers.roleHolders(out.text)
        }
        val ime = secureSetting("default_input_method") ?: return null
        val accessibility = secureSetting("enabled_accessibility_services") ?: return null
        val vpn = secureSetting("always_on_vpn_app") ?: return null
        val policy = runner.run(DEVICE_POLICY_ARGV)
        if (!policy.ok) return null
        val admins = ToolkitParsers.deviceAdmins(policy.text, policy.truncated) ?: return null
        return ProtectedPackages(
            roleHolders = roles,
            defaultIme = ToolkitParsers.componentPackages(ime.value),
            accessibility = ToolkitParsers.componentPackages(accessibility.value),
            deviceAdmins = admins,
            alwaysOnVpn = setOfNotNull(vpn.value),
        )
    }

    private class Setting(val value: String?)

    private fun secureSetting(key: String): Setting? {
        val out = runner.run(listOf(CMD, "settings", "get", "--user", "0", "secure", key))
        return if (out.ok) Setting(ToolkitParsers.settingValue(out.text)) else null
    }

    private companion object {
        const val PER_USER_RANGE = 100_000

        // ApplicationInfo.FLAG_STOPPED is hidden API; the value is stable.
        const val FLAG_STOPPED = 0x00200000
        val ROLES = listOf(
            "android.app.role.HOME",
            "android.app.role.DIALER",
            "android.app.role.SMS",
            "android.app.role.ASSISTANT",
        )
    }
}

/** Cap per `cmd battery get`: the probe runs on a binder thread and on every dexopt re-check. */
private const val PROBE_TIMEOUT_MS = 2_000L

/**
 * Plugged-in state, first answer wins: the sticky ACTION_BATTERY_CHANGED, then BatteryService's own
 * view via `cmd battery get`, then sysfs `power_supply/<non-battery>/online`. The middle step exists
 * because a shell-UID service Context cannot reliably register for the sticky intent, and sysfs
 * alone ignores BatteryService state overrides (`dumpsys battery unplug`), which the emulator
 * proof needs. Anything unknown is "not plugged" (fail closed).
 */
internal class AndroidPluggedProbe(
    private val context: Context,
    private val runner: CommandRunner,
    private val fileRoot: File = File("/"),
) : PluggedProbe {
    override fun isPlugged(): Boolean {
        val sticky = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: RuntimeException) {
            null
        }
        if (sticky != null) {
            return sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        }
        return batteryServicePlugged() ?: sysfsPlugged()
    }

    private fun batteryServicePlugged(): Boolean? {
        var answered = false
        for (source in listOf("ac", "usb", "wireless", "dock")) {
            val out = runner.run(listOf(CMD, "battery", "get", source), PROBE_TIMEOUT_MS)
            when (if (out.ok) out.text.trim() else null) {
                "true" -> return true
                "false" -> answered = true
            }
        }
        return if (answered) false else null
    }

    private fun sysfsPlugged(): Boolean {
        val supplies = File(fileRoot, "sys/class/power_supply").listFiles() ?: return false
        return supplies.any { dir ->
            dir.isDirectory && readTrimmed(File(dir, "type")) != "Battery" && readTrimmed(File(dir, "online")) == "1"
        }
    }

    private fun readTrimmed(file: File): String? = try {
        file.takeIf(File::isFile)?.readText()?.trim()
    } catch (_: IOException) {
        null
    }
}
