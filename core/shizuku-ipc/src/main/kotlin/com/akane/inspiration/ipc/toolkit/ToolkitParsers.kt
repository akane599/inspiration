package com.akane.inspiration.ipc.toolkit

/** Pure parsers for command output. A null result means "could not parse": callers fail closed. */
internal object ToolkitParsers {
    private val OP_MODE = Regex("[a-z_]+")
    private val DURATION = Regex("([+-])?(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?(?:(\\d+)ms)?")
    private val SETTING_LINE = Regex("    ([a-z_]+)=(\\S+)")
    private val ADMIN_LINE = Regex("    ([A-Za-z][A-Za-z0-9_.]*)/\\S+:")

    /** `cmd appops get`: "OP: mode[; ...]" or "No operations." (the op has no override). */
    fun appOpMode(op: AppOp, output: String): String? {
        val lines = output.lines().map { it.trim() }
        val entry = lines.firstOrNull { it.startsWith(op.argv + ":") }
        if (entry != null) {
            val mode = entry.substringAfter(':').substringBefore(';').trim()
            return mode.takeIf { OP_MODE.matches(it) }
        }
        return if (lines.any { it == "No operations." }) AppOpMode.DEFAULT.argv else null
    }

    /** `cmd activity get-standby-bucket`: a bare integer. */
    fun standbyBucket(output: String): Int? = output.trim().toIntOrNull()

    /** "0", "+4m0s0ms", "+1h0m0s0ms" as printed by TimeUtils.formatDuration. */
    fun durationMs(text: String): Long? {
        if (text.trim() == "0") return 0L // TimeUtils prints a zero duration as a bare "0"
        val match = DURATION.matchEntire(text.trim())?.takeIf { text.isNotBlank() } ?: return null
        val g = match.groupValues
        fun part(index: Int): Long = g[index].toLongOrNull() ?: 0L
        val total = part(2) * 86_400_000L + part(3) * 3_600_000L + part(4) * 60_000L + part(5) * 1_000L + part(6)
        return if (g[1] == "-") -total else total
    }

    /** `dumpsys deviceidle` "  Settings:" block as key to ms. Non-duration values are skipped. */
    fun dozeSettings(dump: String): Map<String, Long>? {
        val lines = dump.lines()
        val start = lines.indexOfFirst { it == "  Settings:" }
        if (start < 0) return null
        val result = LinkedHashMap<String, Long>()
        for (line in lines.drop(start + 1)) {
            val match = SETTING_LINE.matchEntire(line) ?: break
            durationMs(match.groupValues[2])?.let { result[match.groupValues[1]] = it }
        }
        return result
    }

    /** `cmd role get-role-holders`: one package per line. */
    fun roleHolders(output: String): Set<String> =
        output.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** `cmd settings get`: literal "null" means unset. */
    fun settingValue(output: String): String? = output.trim().takeIf { it != "null" && it.isNotEmpty() }

    /** "pkg/Class[:pkg2/Class2]" to package names. */
    fun componentPackages(value: String?): Set<String> =
        value.orEmpty().split(':').map { it.substringBefore('/').trim() }.filter { it.isNotEmpty() }.toSet()

    /**
     * `dumpsys device_policy`: components listed under "Enabled Device Admins". Null when the
     * section is missing or the (capped) output ends before the section does.
     */
    fun deviceAdmins(dump: String, truncated: Boolean): Set<String>? {
        val lines = dump.lines()
        val start = lines.indexOfFirst { it.startsWith("  Enabled Device Admins") }
        if (start < 0) return null
        val admins = LinkedHashSet<String>()
        var sectionEnded = false
        for (line in lines.drop(start + 1)) {
            if (line.length > 2 && line.startsWith("  ") && !line.startsWith("   ")) {
                sectionEnded = true
                break
            }
            ADMIN_LINE.matchEntire(line.trimEnd())?.let { admins += it.groupValues[1] }
        }
        return if (sectionEnded || !truncated) admins else null
    }
}
