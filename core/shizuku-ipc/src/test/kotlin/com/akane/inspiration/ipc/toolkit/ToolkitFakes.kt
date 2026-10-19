package com.akane.inspiration.ipc.toolkit

import java.io.IOException

internal fun ok(text: String = ""): CommandOutput = CommandOutput(exitCode = 0, text = text)

internal fun failure(text: String = "Error"): CommandOutput = CommandOutput(exitCode = 255, text = text)

/** Records every argv; answers through [handler]. No real process is ever created. */
internal class FakeRunner(
    var handler: (List<String>) -> CommandOutput = { ok() },
) : CommandRunner {
    val calls = mutableListOf<List<String>>()

    /** The wait bound each command was given, parallel to [calls]. */
    val timeouts = mutableListOf<Long>()

    override fun start(argv: List<String>): CommandProcess {
        calls += argv
        val out = handler(argv)
        return object : CommandProcess {
            override fun awaitExit(timeoutMs: Long): Boolean {
                timeouts += timeoutMs
                return !out.timedOut
            }

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

internal fun userApp(
    flags: Int = 0,
    appId: Int = 10_123,
    stopped: Boolean = false,
) = PackageLookup.Found(appId = appId, flags = flags, stopped = stopped)

/** Models the settings key and `dumpsys deviceidle` for the doze tests. */
internal class FakeDozeDevice(
    var raw: String? = null,
    /** When false the platform ignores the constants (the dumpsys Settings block never changes). */
    var honorsConstants: Boolean = true,
) {
    val writes = mutableListOf<List<String>>()

    private val defaults =
        linkedMapOf(
            "light_after_inactive_to" to 240_000L,
            "inactive_to" to 15_000L,
            "idle_after_inactive_to" to 15_000L,
            "sensing_to" to 15_000L,
            "locating_to" to 15_000L,
            "motion_inactive_to" to 30_000L,
        )

    fun handle(argv: List<String>): CommandOutput =
        when {
            argv == listOf(CMD, "settings", "get", "--user", "0", "global", "device_idle_constants") -> {
                ok(raw ?: "null")
            }

            argv.take(6) == listOf(CMD, "settings", "put", "--user", "0", "global") -> {
                writes += argv
                raw = argv[7]
                ok()
            }

            argv == listOf(CMD, "settings", "delete", "--user", "0", "global", "device_idle_constants") -> {
                writes += argv
                raw = null
                ok("Deleted 1 rows")
            }

            argv == listOf(DUMPSYS, "deviceidle") -> {
                ok(dump())
            }

            else -> {
                failure("unexpected argv $argv")
            }
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

internal class FakeClock(
    var nowMs: Long = 1_000L,
) {
    fun now(): Long = nowMs
}

/** The redroid test device's API level (Android 15), on which bg-dexopt can be cancelled. */
internal const val TEST_SDK_INT = 35

/**
 * What `cmd package` does with a bg-dexopt argv on [sdkInt], after AOSP `PackageManagerShellCommand`
 * (`android12-release`, `android12L-release`, `android13-release`) and ART's `ArtShellCommand` (`android14-release`
 * to `android16-release`). [running] is our pass in system_server: killing the `cmd` client never stops it.
 */
internal class FakeDexoptShell(
    private val sdkInt: Int,
) {
    enum class Effect {
        /** `bg-dexopt-job` alone: our full dexopt pass. From API 33 it first waits for a [jobAhead] to end. */
        PASS,

        /** Stops the job that runs now: a [jobAhead] first (a start queued behind it then begins), else ours. */
        CANCEL,

        /**
         * `bg-dexopt-job ARGS` on API 31/32: dexopts only the packages ARGS names. `--cancel` names no package, so
         * this does next to nothing, but it cancels nothing either and still prints Success.
         */
        NAMED_PASS,

        /** API 33 `bg-dexopt-job ARGS`: the same, but only once the running job has finished. */
        WAIT_THEN_NAMED_PASS,

        UNKNOWN_COMMAND,
    }

    /** Our pass is running in system_server. */
    var running = false

    /**
     * A job someone else started is running: the system's idle or post-boot job, or one an earlier service
     * instance left. From API 33 our start waits for it to end, and a cancel stops it rather than ours.
     */
    var jobAhead = false

    /** Our start waits behind [jobAhead]: its `cmd` client is alive, but no pass of ours has begun. */
    var queued = false

    /** Full passes of ours that system_server began. */
    var passes = 0

    var cancels = 0

    /** Our start's `cmd` client has not returned yet: it is queued or its pass is running. */
    val clientAlive: Boolean get() = queued || running

    fun effect(argv: List<String>): Effect {
        val args = argv.drop(2).takeIf { argv.take(2) == listOf(CMD, "package") } ?: return Effect.UNKNOWN_COMMAND
        return when {
            args == listOf("bg-dexopt-job") -> Effect.PASS

            // S/S-v2 runDexoptJob: every argument after the command is collected with getNextArg() as a package name.
            args.firstOrNull() == "bg-dexopt-job" && sdkInt <= 32 -> Effect.NAMED_PASS

            // T runDexoptJob does the same, but runBackgroundDexoptJob first waits for the running job.
            args.firstOrNull() == "bg-dexopt-job" && sdkInt == 33 -> Effect.WAIT_THEN_NAMED_PASS

            args == listOf("bg-dexopt-job", "--cancel") -> Effect.CANCEL

            // Added in T; an unknown command on S/S-v2.
            args == listOf("cancel-bg-dexopt-job") && sdkInt >= 33 -> Effect.CANCEL

            else -> Effect.UNKNOWN_COMMAND
        }
    }

    /** Our start: the pass begins now, or (from API 33) queues behind a [jobAhead]. */
    fun start() {
        if (jobAhead && sdkInt >= 33) queued = true else begin()
    }

    /** One cancel: it stops a [jobAhead] first, and a start of ours queued behind it then begins its pass. */
    fun cancel() {
        cancels += 1
        if (jobAhead) {
            jobAhead = false
            if (queued) {
                queued = false
                begin()
            }
        } else {
            running = false
        }
    }

    private fun begin() {
        passes += 1
        running = true
    }

    /** Applies [argv] and answers like the `cmd` client would within the wait (a [FakeRunner] handler). */
    fun handle(argv: List<String>): CommandOutput =
        when (effect(argv)) {
            Effect.PASS -> {
                start()
                if (queued) CommandOutput(exitCode = -1, text = "", timedOut = true) else ok("Success")
            }

            Effect.CANCEL -> {
                cancel()
                ok("Success")
            }

            // Neither named variant begins or stops a pass of ours, so neither touches [running] or [passes].
            Effect.NAMED_PASS -> {
                ok("Success")
            }

            Effect.WAIT_THEN_NAMED_PASS -> {
                if (running || jobAhead) CommandOutput(exitCode = -1, text = "", timedOut = true) else ok("Success")
            }

            Effect.UNKNOWN_COMMAND -> {
                failure("Unknown command: ${argv.drop(2).firstOrNull()}")
            }
        }
}
