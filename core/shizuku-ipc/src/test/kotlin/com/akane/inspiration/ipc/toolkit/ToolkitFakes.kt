package com.akane.inspiration.ipc.toolkit

import java.io.IOException

internal fun ok(text: String = ""): CommandOutput = CommandOutput(exitCode = 0, text = text)

internal fun failure(text: String = "Error"): CommandOutput = CommandOutput(exitCode = 255, text = text)

/** Records every argv; answers through [handler]. No real process is ever created. */
internal class FakeRunner(var handler: (List<String>) -> CommandOutput = { ok() }) : CommandRunner {
    val calls = mutableListOf<List<String>>()

    override fun start(argv: List<String>): CommandProcess {
        calls += argv
        val out = handler(argv)
        return object : CommandProcess {
            override fun awaitExit(timeoutMs: Long): Boolean = !out.timedOut

            override fun output(timedOut: Boolean): CommandOutput = out

            override fun destroy() = Unit
        }
    }
}

internal class FakeInspector(
    val packages: MutableMap<String, PackageLookup> = mutableMapOf(),
    var protectedSet: ProtectedPackages? = ProtectedPackages(),
) : PackageInspector {
    var protectedQueries = 0

    override fun lookup(pkg: String): PackageLookup = packages[pkg] ?: PackageLookup.NotInstalled

    override fun protectedPackages(): ProtectedPackages? {
        protectedQueries += 1
        return protectedSet
    }
}

internal fun userApp(flags: Int = 0, appId: Int = 10_123, stopped: Boolean = false) =
    PackageLookup.Found(appId = appId, flags = flags, stopped = stopped)

/** Models the settings key and `dumpsys deviceidle` for the doze tests. */
internal class FakeDozeDevice(
    var raw: String? = null,
    /** When false the platform ignores the constants (the dumpsys Settings block never changes). */
    var honorsConstants: Boolean = true,
) {
    val writes = mutableListOf<List<String>>()

    private val defaults = linkedMapOf(
        "light_after_inactive_to" to 240_000L,
        "inactive_to" to 15_000L,
        "idle_after_inactive_to" to 15_000L,
        "sensing_to" to 15_000L,
        "locating_to" to 15_000L,
        "motion_inactive_to" to 30_000L,
    )

    fun handle(argv: List<String>): CommandOutput = when {
        argv == listOf(CMD, "settings", "get", "global", "device_idle_constants") -> ok(raw ?: "null")
        argv.take(4) == listOf(CMD, "settings", "put", "global") -> {
            writes += argv
            raw = argv[5]
            ok()
        }
        argv == listOf(CMD, "settings", "delete", "global", "device_idle_constants") -> {
            writes += argv
            raw = null
            ok("Deleted 1 rows")
        }
        argv == listOf(DUMPSYS, "deviceidle") -> ok(dump())
        else -> failure("unexpected argv $argv")
    }

    private fun dump(): String {
        val effective = LinkedHashMap(defaults)
        if (honorsConstants) {
            raw?.split(',')?.forEach { pair -> effective[pair.substringBefore('=')] = pair.substringAfter('=').toLong() }
        }
        return buildString {
            append("  Flags:\n    x=true\n\n  Settings:\n")
            effective.forEach { (key, ms) -> append("    $key=${format(ms)}\n") }
            append("    location_accuracy=20.0m\n    use_mode_manager=false\n  Whitelist (except idle) system apps:\n")
        }
    }

    private fun format(ms: Long): String {
        if (ms == 0L) return "0"
        val minutes = ms / 60_000 % 60
        val hours = ms / 3_600_000
        return buildString {
            append('+')
            if (hours > 0) append("${hours}h")
            if (hours > 0 || minutes > 0) append("${minutes}m")
            append("${ms / 1_000 % 60}s${ms % 1_000}ms")
        }
    }
}

internal class ThrowingRunner : CommandRunner {
    var starts = 0

    override fun start(argv: List<String>): CommandProcess {
        starts += 1
        throw IOException("must not spawn: $argv")
    }
}

internal class FakeClock(var nowMs: Long = 1_000L) {
    fun now(): Long = nowMs
}
