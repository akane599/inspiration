package com.akane.inspiration.ipc.toolkit

import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.model.ToolkitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FLAG_SYSTEM = 0x1
private const val FLAG_PERSISTENT = 0x8
private const val FLAG_UPDATED_SYSTEM_APP = 0x80
private const val APP = "com.example.app"
private const val OWN = "com.akane.inspiration"

class ToolkitExecutorTest {
    private val runner = FakeRunner()
    private val inspector = FakeInspector(mutableMapOf(APP to userApp()))
    private val appModes = mutableMapOf<String, String>()
    private var bucket = 10

    private fun executor(): ToolkitExecutor =
        ToolkitExecutor(
            runner = runner,
            inspector = inspector,
            dexopt = BgDexoptController(runner, { true }, { 0L }, { }, TEST_SDK_INT),
            ownPackage = OWN,
            sleepMs = { },
        ).also { runner.handler = ::device }

    /** Models appops + standby bucket behaviour as observed on the API 36 emulator. */
    private fun device(argv: List<String>): CommandOutput =
        when {
            argv.take(5) == listOf(CMD, "appops", "get", "--user", "0") -> {
                val op = argv[6]
                appModes[op]?.let { ok("$op: $it") } ?: ok("No operations.\nDefault mode: allow")
            }

            argv.take(5) == listOf(CMD, "appops", "set", "--user", "0") -> {
                appModes[argv[6]] = argv[7]
                ok()
            }

            argv.take(5) == listOf(CMD, "activity", "get-standby-bucket", "--user", "0") -> {
                ok("$bucket")
            }

            argv.take(5) == listOf(CMD, "activity", "set-standby-bucket", "--user", "0") -> {
                bucket = StandbyBucket.entries.first { it.argv == argv[6] }.rawValue
                ok()
            }

            argv.take(5) == listOf(CMD, "activity", "force-stop", "--user", "0") -> {
                inspector.packages[argv[5]] = userApp(stopped = true)
                ok()
            }

            else -> {
                failure("unexpected $argv")
            }
        }

    @Test
    fun `UID overrides are shown but package writes are refused without mutation`() {
        val executor = executor()
        runner.handler = { argv ->
            if (argv[1] == "appops" && argv[2] == "get") {
                val op = argv[6]
                ok("Uid mode: $op: ignore\n$op: default")
            } else {
                error("Unexpected write: $argv")
            }
        }
        val read = executor.getAppOps(APP)
        assertEquals(ToolkitStatus.DONE, read.status)
        assertTrue(read.afterRaw.orEmpty().contains("RUN_ANY_IN_BACKGROUND=ignore"))
        val result = executor.setAppOp(APP, AppOp.RUN_ANY_IN_BACKGROUND.code, AppOpMode.ALLOW.code)
        assertEquals(ToolkitStatus.REFUSED, result.status)
        assertEquals(ToolkitCode.NOT_EFFECTIVE, result.code)
        assertEquals("ignore", result.beforeRaw)
        assertTrue(runner.calls.none { it[2] == "set" })
    }

    @Test
    fun `the permissive write is allow, never default`() {
        appModes["RUN_ANY_IN_BACKGROUND"] = "ignore"

        val result = executor().setAppOp(APP, AppOp.RUN_ANY_IN_BACKGROUND.code, AppOpMode.ALLOW.code)

        assertEquals(ToolkitResult.done(before = "ignore", after = "allow"), result)
        assertTrue(listOf(CMD, "appops", "set", "--user", "0", APP, "RUN_ANY_IN_BACKGROUND", "allow") in runner.calls)
        assertTrue(runner.calls.none { "default" in it })
    }

    @Test
    fun `mode default is refused as a write without running a set`() {
        val result = executor().setAppOp(APP, AppOp.RUN_ANY_IN_BACKGROUND.code, AppOpMode.DEFAULT.code)

        assertEquals(ToolkitResult.refused(ToolkitCode.ARG_VALUE), result)
        assertTrue(runner.calls.none { it.getOrNull(2) == "set" })
    }

    @Test
    fun `an op with no entry reads back as allow`() {
        val read = executor().getAppOps(APP)

        assertEquals(ToolkitStatus.DONE, read.status)
        assertEquals(AppOp.entries.joinToString(",") { "${it.argv}=allow" }, read.afterRaw)
    }

    @Test
    fun `setAppOp issues the exact argv for every op and mode row`() {
        for (op in AppOp.entries) {
            for (mode in AppOpMode.entries.filter { it.writable }) {
                runner.calls.clear()
                val result = executor().setAppOp(APP, op.code, mode.code)

                assertEquals("$op $mode", ToolkitStatus.DONE, result.status)
                assertTrue(
                    "$op $mode argv",
                    listOf(CMD, "appops", "set", "--user", "0", APP, op.argv, mode.argv) in runner.calls,
                )
                assertEquals(mode.argv, result.afterRaw)
            }
        }
    }

    @Test
    fun `setAppOp reports the read back before and after`() {
        appModes["WAKE_LOCK"] = "default"

        val result = executor().setAppOp(APP, AppOp.WAKE_LOCK.code, AppOpMode.IGNORE.code)

        assertEquals(ToolkitResult.done(before = "default", after = "ignore"), result)
    }

    @Test
    fun `unknown op or mode codes are refused without running anything`() {
        val executor = executor()

        assertEquals(ToolkitCode.ARG_VALUE, executor.setAppOp(APP, 0, AppOpMode.IGNORE.code).code)
        assertEquals(ToolkitCode.ARG_VALUE, executor.setAppOp(APP, 4, AppOpMode.IGNORE.code).code)
        assertEquals(ToolkitCode.ARG_VALUE, executor.setAppOp(APP, AppOp.WAKE_LOCK.code, 4).code)
        assertEquals(ToolkitCode.ARG_VALUE, executor.setAppOp(APP, AppOp.WAKE_LOCK.code, 0).code)
        assertEquals(ToolkitCode.ARG_VALUE, executor.setStandbyBucket(APP, 6).code)
        assertEquals(ToolkitCode.ARG_VALUE, executor.applyDozeProfile(3, null).code)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `an unparseable read back refuses the write instead of recording a null before`() {
        val executor = executor()
        runner.handler = { ok("garbage output") }

        val result = executor.setAppOp(APP, AppOp.WAKE_LOCK.code, AppOpMode.IGNORE.code)

        assertEquals(ToolkitStatus.REFUSED, result.status)
        assertEquals(ToolkitCode.PARSE, result.code)
        assertTrue(runner.calls.none { it.contains("set") })
    }

    @Test
    fun `getAppOps reads every op with user 0`() {
        appModes["RUN_ANY_IN_BACKGROUND"] = "ignore"

        val result = executor().getAppOps(APP)

        assertEquals("RUN_ANY_IN_BACKGROUND=ignore,RUN_IN_BACKGROUND=allow,WAKE_LOCK=allow", result.afterRaw)
        for (op in AppOp.entries) {
            assertTrue(listOf(CMD, "appops", "get", "--user", "0", APP, op.argv) in runner.calls)
        }
    }

    @Test
    fun `setStandbyBucket issues the exact argv for every bucket row`() {
        for (target in StandbyBucket.entries) {
            runner.calls.clear()
            val result = executor().setStandbyBucket(APP, target.code)

            assertEquals(target.name, ToolkitStatus.DONE, result.status)
            assertTrue(
                listOf(CMD, "activity", "set-standby-bucket", "--user", "0", APP, target.argv) in runner.calls,
            )
            assertEquals(target.rawValue.toString(), result.afterRaw)
        }
    }

    @Test
    fun `getStandbyBucket reads the bucket with user 0`() {
        bucket = 40

        val result = executor().getStandbyBucket(APP)

        assertEquals("40", result.afterRaw)
        assertEquals(listOf(CMD, "activity", "get-standby-bucket", "--user", "0", APP), runner.calls.last())
    }

    @Test
    fun `forceStop pins user 0 and reads the stopped flag back`() {
        val result = executor().forceStop(APP)

        assertEquals(listOf(CMD, "activity", "force-stop", "--user", "0", APP), runner.calls.single())
        assertEquals("stopped=false", result.beforeRaw)
        assertEquals("stopped=true", result.afterRaw)
        assertEquals(ToolkitStatus.DONE, result.status)
    }

    @Test
    fun `a force-stop that timed out after it may have committed is unverified, not failed`() {
        val executor = executor()
        val base = runner.handler
        runner.handler = { argv ->
            val out = base(argv)
            if ("force-stop" in argv) CommandOutput(exitCode = -1, text = "", timedOut = true) else out
        }

        val result = executor.forceStop(APP)

        assertEquals(ToolkitResult.unavailable(ToolkitCode.UNVERIFIED, before = "stopped=false"), result)
    }

    @Test
    fun `a force-stop whose command succeeded is done even when the lookup after it fails`() {
        val executor = executor()
        val base = runner.handler
        runner.handler = { argv ->
            val out = base(argv)
            // The stop succeeds, then the package can no longer be looked up.
            if ("force-stop" in argv) inspector.packages[APP] = PackageLookup.Unknown
            out
        }

        val result = executor.forceStop(APP)

        // Not unverified: nothing could read a stop back later, so the client would settle it as failed.
        assertEquals(ToolkitResult.done(before = "stopped=false", after = null), result)
    }

    @Test
    fun `a force-stop that failed outright is still FAILED`() {
        val executor = executor()
        val base = runner.handler
        runner.handler = { argv -> if ("force-stop" in argv) failure() else base(argv) }

        val result = executor.forceStop(APP)

        assertEquals(ToolkitResult.failed(ToolkitCode.EXEC, before = "stopped=false"), result)
    }

    @Test
    fun `a failing command is reported as FAILED with the prior read back`() {
        val executor = executor()
        val base = runner.handler
        runner.handler = { argv -> if ("set" in argv) failure() else base(argv) }

        val result = executor.setAppOp(APP, AppOp.WAKE_LOCK.code, AppOpMode.IGNORE.code)

        assertEquals(ToolkitStatus.FAILED, result.status)
        assertEquals(ToolkitCode.EXEC, result.code)
        assertEquals("allow", result.beforeRaw)
    }

    @Test
    fun `a write that timed out after committing is unverified, not failed`() {
        val executor = executor()
        val base = runner.handler
        runner.handler = { argv ->
            val out = base(argv)
            if ("set-standby-bucket" in argv) CommandOutput(exitCode = -1, text = "", timedOut = true) else out
        }

        val result = executor.setStandbyBucket(APP, StandbyBucket.RESTRICTED.code)

        assertEquals(ToolkitResult.unavailable(ToolkitCode.UNVERIFIED, before = "10"), result)
        assertEquals(StandbyBucket.RESTRICTED.rawValue, bucket)
    }

    @Test
    fun `a committed write whose read back fails is unverified, not failed`() {
        val executor = executor()
        val base = runner.handler
        var wrote = false
        runner.handler = { argv ->
            if (wrote && "get" in argv) {
                failure()
            } else {
                base(argv).also { if ("set" in argv) wrote = true }
            }
        }

        val result = executor.setAppOp(APP, AppOp.WAKE_LOCK.code, AppOpMode.IGNORE.code)

        assertEquals(ToolkitResult.unavailable(ToolkitCode.UNVERIFIED, before = "allow"), result)
        assertEquals("ignore", appModes["WAKE_LOCK"])
    }

    @Test
    fun `invalid package names are refused before any lookup or process`() {
        val bad =
            listOf(
                null,
                "",
                "com.example.app\n",
                "-com.example",
                "com.example.-x",
                "singleword",
                "com..x",
                "com.exämple.app",
                "com.example.app; reboot",
                "com example",
                "1com.example",
                ".com.example",
                "a." + "b".repeat(254),
            )
        val executor = executor()
        for (name in bad) {
            assertEquals("$name", ToolkitCode.ARG_PACKAGE, executor.setAppOp(name, 1, 1).code)
            assertEquals("$name", ToolkitCode.ARG_PACKAGE, executor.forceStop(name).code)
        }
        assertTrue(runner.calls.isEmpty())
        assertEquals(0, inspector.protectedQueries)
    }

    @Test
    fun `a package that is not installed for user 0 is refused`() {
        val result = executor().setAppOp("com.example.missing", 1, 1)

        assertEquals(ToolkitResult.refused(ToolkitCode.NOT_INSTALLED), result)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `every hard deny rule refuses on every package action and runs no command`() {
        data class Case(
            val name: String,
            val pkg: String,
            val lookup: PackageLookup,
            val expected: Int,
        )
        val cases =
            listOf(
                Case("own package", OWN, userApp(), ToolkitCode.DENY_OWN),
                Case("shizuku", "moe.shizuku.privileged.api", userApp(), ToolkitCode.DENY_OWN),
                Case("appId below 10000", "com.android.systemui", userApp(appId = 10), ToolkitCode.DENY_APP_ID),
                Case("appId just below app range", "com.example.low", userApp(appId = 9_999), ToolkitCode.DENY_APP_ID),
                Case("persistent", "com.example.persist", userApp(flags = FLAG_PERSISTENT), ToolkitCode.DENY_PERSISTENT),
                Case("pure system app", "com.example.sys", userApp(flags = FLAG_SYSTEM), ToolkitCode.DENY_SYSTEM),
                Case(
                    "persistent beats updated system",
                    "com.example.both",
                    userApp(flags = FLAG_SYSTEM or FLAG_UPDATED_SYSTEM_APP or FLAG_PERSISTENT),
                    ToolkitCode.DENY_PERSISTENT,
                ),
                Case("lookup failed", "com.example.unknown", PackageLookup.Unknown, ToolkitCode.PROTECTION_UNKNOWN),
            )
        // Restricted, so the lifts below would really change something if a gate let them through: unlike rule d,
        // the absolute rules and a failed lookup refuse a write that only lifts a restriction too.
        appModes["RUN_ANY_IN_BACKGROUND"] = "ignore"
        bucket = StandbyBucket.RESTRICTED.rawValue
        for (case in cases) {
            inspector.packages[case.pkg] = case.lookup
            runner.calls.clear()
            val executor = executor()
            for (result in everyPackageAction(executor, case.pkg) + liftWrites(executor, case.pkg)) {
                assertEquals(case.name, ToolkitStatus.REFUSED, result.status)
                assertEquals(case.name, case.expected, result.code)
            }
            assertTrue("${case.name} spawned ${runner.calls}", runner.calls.isEmpty())
        }
        val protectedCases =
            listOf(
                "role holder" to ProtectedPackages(roleHolders = setOf("com.example.guarded")),
                "default ime" to ProtectedPackages(defaultIme = setOf("com.example.guarded")),
                "accessibility" to ProtectedPackages(accessibility = setOf("com.example.guarded")),
                "device admin" to ProtectedPackages(deviceAdmins = setOf("com.example.guarded")),
                "always-on vpn" to ProtectedPackages(alwaysOnVpn = setOf("com.example.guarded")),
            )
        // Rule d refuses every restricting write and the force-stop without writing; its reads are refused too
        // but carry the value they read, so a change made before the app became protected stays visible.
        appModes.clear()
        bucket = StandbyBucket.ACTIVE.rawValue
        for ((name, set) in protectedCases) {
            inspector.packages["com.example.guarded"] = userApp()
            inspector.protectedSet = set
            runner.calls.clear()
            val executor = executor()
            for (result in everyPackageAction(executor, "com.example.guarded")) {
                assertEquals(name, ToolkitStatus.REFUSED, result.status)
                assertEquals(name, ToolkitCode.DENY_PROTECTED, result.code)
            }
            assertEquals(
                name,
                "RUN_ANY_IN_BACKGROUND=allow,RUN_IN_BACKGROUND=allow,WAKE_LOCK=allow",
                executor.getAppOps("com.example.guarded").afterRaw,
            )
            assertEquals(name, "10", executor.getStandbyBucket("com.example.guarded").afterRaw)
            assertTrue("$name wrote ${runner.calls}", runner.calls.none { it.isWrite() })
        }
    }

    @Test
    fun `a failed role query fails closed`() {
        inspector.protectedSet = null
        runner.calls.clear()
        val executor = executor()

        for (result in everyPackageAction(executor, APP)) {
            assertEquals(ToolkitStatus.REFUSED, result.status)
            assertEquals(ToolkitCode.PROTECTION_UNKNOWN, result.code)
        }
        assertEquals("10", executor.getStandbyBucket(APP).afterRaw)
        assertTrue(runner.calls.none { it.isWrite() })
    }

    // --- N-06: a protected app keeps its overrides visible and restorable -------------------------

    @Test
    fun `a protected app takes a write that lifts a restriction and nothing else`() {
        inspector.protectedSet = ProtectedPackages(roleHolders = setOf(APP))
        appModes["RUN_ANY_IN_BACKGROUND"] = "ignore"
        bucket = StandbyBucket.RESTRICTED.rawValue
        val executor = executor()

        assertEquals(
            ToolkitResult.refused(ToolkitCode.DENY_PROTECTED),
            executor.setAppOp(APP, AppOp.WAKE_LOCK.code, AppOpMode.IGNORE.code),
        )
        assertEquals(
            ToolkitResult.done(before = "ignore", after = "allow"),
            executor.setAppOp(APP, AppOp.RUN_ANY_IN_BACKGROUND.code, AppOpMode.ALLOW.code),
        )
        assertEquals(
            ToolkitResult.done(before = "45", after = "10"),
            executor.setStandbyBucket(APP, StandbyBucket.ACTIVE.code),
        )
        // Not more active than what is there now (the same bucket, then a lower one): refused unwritten.
        assertEquals(ToolkitResult.refused(ToolkitCode.DENY_PROTECTED), executor.setStandbyBucket(APP, StandbyBucket.ACTIVE.code))
        assertEquals(ToolkitResult.refused(ToolkitCode.DENY_PROTECTED), executor.setStandbyBucket(APP, StandbyBucket.RARE.code))
        assertEquals(ToolkitResult.refused(ToolkitCode.DENY_PROTECTED), executor.forceStop(APP))
        assertEquals(
            listOf(
                listOf(CMD, "appops", "set", "--user", "0", APP, "RUN_ANY_IN_BACKGROUND", "allow"),
                listOf(CMD, "activity", "set-standby-bucket", "--user", "0", APP, "active"),
            ),
            runner.calls.filter { it.isWrite() },
        )
    }

    @Test
    fun `an undecidable protection still lets a restriction be lifted`() {
        inspector.protectedSet = null
        appModes["WAKE_LOCK"] = "ignore"
        val executor = executor()

        assertEquals(
            ToolkitResult.refused(
                ToolkitCode.PROTECTION_UNKNOWN,
                after = "RUN_ANY_IN_BACKGROUND=allow,RUN_IN_BACKGROUND=allow,WAKE_LOCK=ignore",
            ),
            executor.getAppOps(APP),
        )
        assertEquals(ToolkitStatus.DONE, executor.setAppOp(APP, AppOp.WAKE_LOCK.code, AppOpMode.ALLOW.code).status)
        assertEquals("allow", appModes["WAKE_LOCK"])
    }

    @Test
    fun `a protected updated system app is read with its refusal and its update warning`() {
        inspector.packages[APP] = userApp(flags = FLAG_SYSTEM or FLAG_UPDATED_SYSTEM_APP)
        inspector.protectedSet = ProtectedPackages(roleHolders = setOf(APP))
        val executor = executor()

        val ops = executor.getAppOps(APP)
        assertEquals(
            ToolkitResult.protectedRead(
                ToolkitCode.DENY_PROTECTED,
                "RUN_ANY_IN_BACKGROUND=allow,RUN_IN_BACKGROUND=allow,WAKE_LOCK=allow",
                ToolkitCode.WARN_UPDATED_SYSTEM,
            ),
            ops,
        )
        assertEquals(ToolkitStatus.REFUSED, ops.status)
        assertEquals(ToolkitCode.WARN_UPDATED_SYSTEM, ops.warning)
        assertEquals(ToolkitCode.WARN_UPDATED_SYSTEM, executor.getStandbyBucket(APP).warning)

        // An ordinary protected app, or a read that failed, carries no warning.
        inspector.packages[APP] = userApp()
        assertEquals(ToolkitCode.OK, executor.getAppOps(APP).warning)
        assertEquals(ToolkitCode.OK, ToolkitResult.refused(ToolkitCode.DENY_PROTECTED).warning)
    }

    @Test
    fun `a protected app whose read fails is refused with no value`() {
        inspector.protectedSet = ProtectedPackages(defaultIme = setOf(APP))
        val executor = executor()
        runner.handler = { argv -> if ("get-standby-bucket" in argv) failure() else device(argv) }

        assertEquals(ToolkitResult.refused(ToolkitCode.DENY_PROTECTED), executor.getStandbyBucket(APP))
    }

    // --- protected-package cache (F-07) -----------------------------------------------------

    private fun clockedExecutor(clock: FakeClock): ToolkitExecutor =
        ToolkitExecutor(
            runner = runner,
            inspector = inspector,
            dexopt = BgDexoptController(runner, { true }, { 0L }, { }, TEST_SDK_INT),
            ownPackage = OWN,
            elapsedMs = clock::now,
            sleepMs = { },
        ).also { runner.handler = ::device }

    @Test
    fun `reads reuse the protected-package set within the TTL and read it again after`() {
        val clock = FakeClock()
        val executor = clockedExecutor(clock)

        assertEquals(ToolkitStatus.DONE, executor.getAppOps(APP).status)
        assertEquals(ToolkitStatus.DONE, executor.getStandbyBucket(APP).status)
        clock.nowMs += PROTECTED_TTL_MS - 1
        assertEquals(ToolkitStatus.DONE, executor.getAppOps(APP).status)
        assertEquals(1, inspector.protectedQueries)

        clock.nowMs += 1
        assertEquals(ToolkitStatus.DONE, executor.getStandbyBucket(APP).status)
        assertEquals(2, inspector.protectedQueries)
    }

    @Test
    fun `a write drops the cached set and is gated on a fresh one`() {
        val clock = FakeClock()
        val executor = clockedExecutor(clock)
        executor.getAppOps(APP)
        // APP turns protected (a new default SMS app, say) after the read cached the set.
        inspector.protectedSet = ProtectedPackages(roleHolders = setOf(APP))

        val write = executor.setStandbyBucket(APP, StandbyBucket.RARE.code)

        assertEquals(ToolkitResult.refused(ToolkitCode.DENY_PROTECTED), write)
        assertEquals(2, inspector.protectedQueries)
        assertTrue(runner.calls.none { "set-standby-bucket" in it })
        // Reads stay refused for a protected app (a force-stop is planned from one), carry the value they read,
        // and reuse the write's fresh set.
        assertEquals(
            ToolkitResult.refused(
                ToolkitCode.DENY_PROTECTED,
                after = "RUN_ANY_IN_BACKGROUND=allow,RUN_IN_BACKGROUND=allow,WAKE_LOCK=allow",
            ),
            executor.getAppOps(APP),
        )
        assertEquals(2, inspector.protectedQueries)
    }

    @Test
    fun `an allowed write also reads the set fresh`() {
        val clock = FakeClock()
        val executor = clockedExecutor(clock)
        executor.getStandbyBucket(APP)

        assertEquals(ToolkitStatus.DONE, executor.setStandbyBucket(APP, StandbyBucket.RARE.code).status)
        assertEquals(ToolkitStatus.DONE, executor.getStandbyBucket(APP).status)

        assertEquals(2, inspector.protectedQueries)
    }

    @Test
    fun `a failed protection read is not cached`() {
        val clock = FakeClock()
        val executor = clockedExecutor(clock)
        inspector.protectedSet = null
        assertEquals(ToolkitCode.PROTECTION_UNKNOWN, executor.getAppOps(APP).code)

        inspector.protectedSet = ProtectedPackages()
        assertEquals(ToolkitStatus.DONE, executor.getAppOps(APP).status)
        assertEquals(2, inspector.protectedQueries)
    }

    @Test
    fun `an updated system app is allowed and marked for a warning`() {
        inspector.packages["com.android.chrome"] = userApp(flags = FLAG_SYSTEM or FLAG_UPDATED_SYSTEM_APP, appId = 10_150)

        val result = executor().setAppOp("com.android.chrome", AppOp.RUN_ANY_IN_BACKGROUND.code, AppOpMode.IGNORE.code)

        assertEquals(ToolkitStatus.DONE, result.status)
        assertEquals(ToolkitCode.WARN_UPDATED_SYSTEM, result.code)
    }

    @Test
    fun `an ordinary app is done with a plain OK code`() {
        val result = executor().setAppOp(APP, AppOp.RUN_ANY_IN_BACKGROUND.code, AppOpMode.IGNORE.code)

        assertEquals(ToolkitCode.OK, result.code)
    }

    // --- F-05: only user 0 ---------------------------------------------------------------------

    @Test
    fun `outside user 0 every package read and write is refused without running a command`() {
        appModes["RUN_ANY_IN_BACKGROUND"] = "ignore"
        bucket = StandbyBucket.RESTRICTED.rawValue
        val executor =
            ToolkitExecutor(
                runner = runner,
                inspector = inspector,
                dexopt = BgDexoptController(runner, { true }, { 0L }, { }, TEST_SDK_INT),
                ownPackage = OWN,
                userId = 10,
                sleepMs = { },
            ).also { runner.handler = ::device }
        val refused = ToolkitResult.refused(ToolkitCode.UNSUPPORTED_USER)

        assertEquals(refused, executor.setAppOp(APP, AppOp.RUN_ANY_IN_BACKGROUND.code, AppOpMode.ALLOW.code))
        assertEquals(refused, executor.getAppOps(APP))
        assertEquals(refused, executor.setStandbyBucket(APP, StandbyBucket.RARE.code))
        assertEquals(refused, executor.setStandbyBucket(APP, StandbyBucket.ACTIVE.code))
        assertEquals(refused, executor.getStandbyBucket(APP))
        assertEquals(refused, executor.forceStop(APP))

        assertTrue("no command may run: ${runner.calls}", runner.calls.isEmpty())
        assertEquals("ignore", appModes["RUN_ANY_IN_BACKGROUND"])
        assertEquals(StandbyBucket.RESTRICTED.rawValue, bucket)
        assertEquals(0, inspector.protectedQueries)
    }

    @Test
    fun `outside user 0 the global doze profile still applies`() {
        val device = FakeDozeDevice()
        runner.handler = device::handle
        val executor =
            ToolkitExecutor(runner, inspector, BgDexoptController(runner, { true }, { 0L }, { }, TEST_SDK_INT), OWN, userId = 10) { }

        val result = executor.applyDozeProfile(DozeProfile.BALANCED.code, null)

        assertEquals(ToolkitResult.done(before = null, after = DozeProfile.BALANCED.constants), result)
    }

    @Test
    fun `the toolkit user comes from the app uid and an unknown uid is not user 0`() {
        assertEquals(0, toolkitUserId(10_123))
        assertEquals(10, toolkitUserId(1_010_123))
        assertEquals(-1, toolkitUserId(null))
    }

    // --- Doze -------------------------------------------------------------------------------

    private fun dozeExecutor(device: FakeDozeDevice): ToolkitExecutor {
        runner.handler = device::handle
        return ToolkitExecutor(runner, inspector, BgDexoptController(runner, { true }, { 0L }, { }, TEST_SDK_INT), OWN) { }
    }

    @Test
    fun `applyDozeProfile writes the constant and verifies it in dumpsys`() {
        val device = FakeDozeDevice()
        for (profile in listOf(DozeProfile.BALANCED, DozeProfile.AGGRESSIVE_NIGHT)) {
            device.raw = null
            device.writes.clear()

            val result = dozeExecutor(device).applyDozeProfile(profile.code, null)

            assertEquals(profile.name, ToolkitResult.done(before = null, after = profile.constants), result)
            assertEquals(
                listOf(listOf(CMD, "settings", "put", "--user", "0", "global", "device_idle_constants", profile.constants)),
                device.writes,
            )
        }
    }

    @Test
    fun `STANDARD deletes the setting`() {
        val device = FakeDozeDevice(raw = DozeProfile.BALANCED.constants)

        val result = dozeExecutor(device).applyDozeProfile(DozeProfile.STANDARD.code, DozeProfile.BALANCED.constants)

        assertEquals(ToolkitResult.done(before = DozeProfile.BALANCED.constants, after = null), result)
        assertEquals(listOf(listOf(CMD, "settings", "delete", "--user", "0", "global", "device_idle_constants")), device.writes)
    }

    @Test
    fun `a stale expected value is refused and nothing is written`() {
        val device = FakeDozeDevice(raw = "light_after_inactive_to=5000")

        val result = dozeExecutor(device).applyDozeProfile(DozeProfile.BALANCED.code, null)

        assertEquals(ToolkitStatus.REFUSED, result.status)
        assertEquals(ToolkitCode.STALE, result.code)
        assertEquals("light_after_inactive_to=5000", result.beforeRaw)
        assertTrue(device.writes.isEmpty())
    }

    @Test
    fun `a profile the platform ignores is rolled back to the prior value`() {
        val prior = "light_after_inactive_to=5000"
        val device = FakeDozeDevice(raw = prior, honorsConstants = false)

        val result = dozeExecutor(device).applyDozeProfile(DozeProfile.AGGRESSIVE_NIGHT.code, prior)

        assertEquals(ToolkitStatus.UNAVAILABLE, result.status)
        assertEquals(ToolkitCode.NOT_EFFECTIVE, result.code)
        assertEquals(prior, device.raw)
        assertEquals(prior, result.afterRaw)
        assertEquals(listOf(CMD, "settings", "put", "--user", "0", "global", "device_idle_constants", prior), device.writes.last())
    }

    @Test
    fun `rollback of a previously unset value deletes the setting`() {
        val device = FakeDozeDevice(raw = null, honorsConstants = false)

        val result = dozeExecutor(device).applyDozeProfile(DozeProfile.BALANCED.code, null)

        assertEquals(ToolkitCode.NOT_EFFECTIVE, result.code)
        assertNull(device.raw)
        assertEquals(listOf(CMD, "settings", "delete", "--user", "0", "global", "device_idle_constants"), device.writes.last())
    }

    @Test
    fun `a prior value that could not be replayed safely blocks the apply`() {
        val device = FakeDozeDevice(raw = "weird value;rm")

        val result = dozeExecutor(device).applyDozeProfile(DozeProfile.BALANCED.code, "weird value;rm")

        assertEquals(ToolkitCode.ARG_VALUE, result.code)
        assertTrue(device.writes.isEmpty())
    }

    @Test
    fun `duplicate-key original blocks every profile even when observed before executor recreation`() {
        val prior = "inactive_to=600000,inactive_to=1800000"
        assertTrue(DozeRaw.isSafe(prior))
        assertFalse(DozeRaw.isRestorable(prior))
        val device = FakeDozeDevice(raw = prior)
        val observedExecutor = dozeExecutor(device)
        assertEquals(prior, observedExecutor.getDozeState().afterRaw)
        val freshExecutor = dozeExecutor(device)

        for (executor in listOf(observedExecutor, freshExecutor)) {
            for (profile in DozeProfile.entries) {
                val result = executor.applyDozeProfile(profile.code, prior)

                assertEquals(profile.name, ToolkitResult.refused(ToolkitCode.ARG_VALUE, before = prior), result)
                assertEquals(prior, device.raw)
                assertTrue("neither put nor delete is allowed", device.writes.isEmpty())
            }
        }
    }

    @Test
    fun `safe-character malformed originals block every profile without put or delete`() {
        val originals =
            listOf(
                "oem_key=5.5.5",
                "inactive_to=abc",
                "inactive_to=1,,sensing_to=0",
                "inactive_to",
                "Foo=1",
            )
        for (prior in originals) {
            assertTrue(prior, DozeRaw.isSafe(prior))
            assertFalse(prior, DozeRaw.isRestorable(prior))
            val device = FakeDozeDevice(raw = prior)
            val executor = dozeExecutor(device)

            for (profile in DozeProfile.entries) {
                val result = executor.applyDozeProfile(profile.code, prior)

                assertEquals("$prior ${profile.name}", ToolkitResult.refused(ToolkitCode.ARG_VALUE, before = prior), result)
                assertEquals(prior, device.raw)
                assertTrue("neither put nor delete is allowed", device.writes.isEmpty())
            }
        }
    }

    @Test
    fun `every profile can restore nullable and OEM originals with a fresh executor`() {
        val originals = listOf(null, "inactive_to=1800000", "sec_deep_idle_to=300000,oem_factor=1.5,sec_flag_2=true")
        for (prior in originals) {
            for (profile in DozeProfile.entries) {
                val device = FakeDozeDevice(raw = prior)
                val applied = dozeExecutor(device).applyDozeProfile(profile.code, prior)
                assertEquals("$prior ${profile.name}", ToolkitResult.done(before = prior, after = profile.constants), applied)
                device.writes.clear()

                val restored = dozeExecutor(device).restoreDozeRaw(prior, applied.afterRaw)

                assertEquals("$prior ${profile.name}", ToolkitResult.done(before = profile.constants, after = prior), restored)
                assertEquals(prior, device.raw)
                val expected =
                    if (prior == null) {
                        listOf(CMD, "settings", "delete", "--user", "0", "global", "device_idle_constants")
                    } else {
                        listOf(CMD, "settings", "put", "--user", "0", "global", "device_idle_constants", prior)
                    }
                assertEquals(listOf(expected), device.writes)
            }
        }
    }

    @Test
    fun `fresh executor keeps stale-current checks for accepted OEM originals`() {
        val prior = "sec_deep_idle_to=300000,sec_flag_2=true"
        val device = FakeDozeDevice(raw = prior)
        val applied = dozeExecutor(device).applyDozeProfile(DozeProfile.BALANCED.code, prior)
        assertEquals(ToolkitStatus.DONE, applied.status)
        val external = "inactive_to=999"
        device.raw = external
        device.writes.clear()
        val freshExecutor = dozeExecutor(device)

        val restored = freshExecutor.restoreDozeRaw(prior, applied.afterRaw)
        val reapplied = freshExecutor.applyDozeProfile(DozeProfile.STANDARD.code, applied.afterRaw)

        assertEquals(ToolkitResult.refused(ToolkitCode.STALE, before = external), restored)
        assertEquals(ToolkitResult.refused(ToolkitCode.STALE, before = external), reapplied)
        assertEquals(external, device.raw)
        assertTrue("neither put nor delete is allowed", device.writes.isEmpty())
    }

    @Test
    fun `unsupported originals from older releases remain refused after executor recreation`() {
        // Existing stored originals cannot gain trust just because an older executor accepted them.
        for (prior in listOf("inactive_to=600000,inactive_to=1800000", "oem_key=5.5.5")) {
            val device = FakeDozeDevice(raw = DozeProfile.BALANCED.constants)
            runner.calls.clear()

            val result = dozeExecutor(device).restoreDozeRaw(prior, DozeProfile.BALANCED.constants)

            assertEquals(ToolkitResult.refused(ToolkitCode.ARG_VALUE), result)
            assertEquals(DozeProfile.BALANCED.constants, device.raw)
            assertTrue(device.writes.isEmpty())
            assertTrue("unsupported replay is refused before even a read", runner.calls.isEmpty())
        }
    }

    @Test
    fun `restoreDozeRaw replays the stored prior value when current matches the recorded after`() {
        val device = FakeDozeDevice(raw = DozeProfile.BALANCED.constants)

        val result = dozeExecutor(device).restoreDozeRaw("inactive_to=1000", DozeProfile.BALANCED.constants)

        assertEquals(ToolkitResult.done(before = DozeProfile.BALANCED.constants, after = "inactive_to=1000"), result)
        assertEquals("inactive_to=1000", device.raw)
    }

    @Test
    fun `a rollback that does not land is unverified because the profile may still be applied`() {
        val prior = "light_after_inactive_to=5000"
        val device = FakeDozeDevice(raw = prior, honorsConstants = false)
        val executor = dozeExecutor(device)
        runner.handler = { argv -> if (argv.lastOrNull() == prior) failure() else device.handle(argv) }

        val result = executor.applyDozeProfile(DozeProfile.AGGRESSIVE_NIGHT.code, prior)

        assertEquals(
            ToolkitResult.unavailable(ToolkitCode.UNVERIFIED, before = prior, after = DozeProfile.AGGRESSIVE_NIGHT.constants),
            result,
        )
        assertEquals(DozeProfile.AGGRESSIVE_NIGHT.constants, device.raw)
    }

    @Test
    fun `a restoreDozeRaw write that timed out is unverified`() {
        val device = FakeDozeDevice(raw = DozeProfile.BALANCED.constants)
        val executor = dozeExecutor(device)
        runner.handler = { argv ->
            val out = device.handle(argv)
            if ("delete" in argv) CommandOutput(exitCode = -1, text = "", timedOut = true) else out
        }

        val result = executor.restoreDozeRaw(null, DozeProfile.BALANCED.constants)

        assertEquals(ToolkitStatus.UNAVAILABLE, result.status)
        assertEquals(ToolkitCode.UNVERIFIED, result.code)
        assertEquals(DozeProfile.BALANCED.constants, result.beforeRaw)
    }

    @Test
    fun `restoreDozeRaw deletes when the prior state was unset`() {
        val device = FakeDozeDevice(raw = DozeProfile.BALANCED.constants)

        val result = dozeExecutor(device).restoreDozeRaw(null, DozeProfile.BALANCED.constants)

        assertEquals(ToolkitStatus.DONE, result.status)
        assertNull(device.raw)
        assertEquals(listOf(CMD, "settings", "delete", "--user", "0", "global", "device_idle_constants"), device.writes.single())
    }

    @Test
    fun `restoreDozeRaw refuses when the current value changed since it was recorded`() {
        val device = FakeDozeDevice(raw = "inactive_to=999")

        val result = dozeExecutor(device).restoreDozeRaw(null, DozeProfile.BALANCED.constants)

        assertEquals(ToolkitCode.STALE, result.code)
        assertEquals("inactive_to=999", result.beforeRaw)
        assertTrue(device.writes.isEmpty())
    }

    @Test
    fun `restoreDozeRaw validates the replayed string`() {
        val device = FakeDozeDevice(raw = "a=1")
        val executor = dozeExecutor(device)
        val rejected = listOf("a=1; reboot", "a b", "a=1\n", "-x", "a=\$(id)", "x".repeat(1025), "ä=1", "a=1|b")

        for (value in rejected) {
            assertEquals(value, ToolkitCode.ARG_VALUE, executor.restoreDozeRaw(value, "a=1").code)
        }
        assertTrue(device.writes.isEmpty())
    }

    @Test
    fun `restoreDozeRaw refuses malformed keys non numeric values duplicates and over long strings`() {
        val device = FakeDozeDevice(raw = "a=1")
        val executor = dozeExecutor(device)
        val rejected =
            listOf(
                "Foo=1",
                "1foo=1",
                "foo-bar=1",
                "inactive_to=1000,Foo=1",
                "${"k".repeat(65)}=1",
                "inactive_to=abc",
                "inactive_to=-1",
                "inactive_to=",
                "inactive_to=1.",
                "inactive_to=.5",
                "inactive_to=1.5.2",
                "inactive_to=TRUE",
                "inactive_to=1234567890123456789",
                "inactive_to",
                "x".repeat(1024),
                "inactive_to=1,,sensing_to=0",
                "inactive_to=1,inactive_to=2",
                "inactive_to=1;sensing_to=0",
                "inactive_to=1,sensing_to=\$(id)",
                "inactive_to=1\nsensing_to=0",
                "inactive_to=1,sensing_to=0\n",
                "-inactive_to=1",
                "--user=1",
                overLongButWellFormed(),
            )

        for (value in rejected) {
            assertEquals(value, ToolkitCode.ARG_VALUE, executor.restoreDozeRaw(value, "a=1").code)
        }
        assertTrue("no command at all for a refused value", runner.calls.isEmpty())
    }

    /** Every pair is individually valid and no key repeats, so only the 1024 length cap can refuse it. */
    private fun overLongButWellFormed(): String {
        val pairs = DozeRaw.AOSP_KEYS.map { "$it=123456789012345678" }
        val ok =
            pairs.fold("") { acc, pair ->
                val next = if (acc.isEmpty()) pair else "$acc,$pair"
                if (next.length <= 1024) next else acc
            }
        assertTrue(DozeRaw.isRestorable(ok))
        return pairs.joinToString(",").also { assertTrue(it.length > 1024) }
    }

    @Test
    fun `restoreDozeRaw replays a realistic OEM prior whose keys are not profile keys`() {
        val oemPrior =
            "light_after_inactive_to=30000,light_idle_to=60000,light_idle_factor=2.0," +
                "light_max_idle_to=900000,min_light_maintenance_time=5000,min_deep_maintenance_time=30000," +
                "inactive_to=1800000,sensing_to=240000,locating_to=30000,location_accuracy=20.0," +
                "motion_inactive_to=600000,idle_after_inactive_to=1800000,idle_pending_to=300000," +
                "max_idle_pending_to=600000,idle_pending_factor=2.0,quick_doze_delay_to=60000," +
                "idle_to=3600000,max_idle_to=21600000,idle_factor=2.0,min_time_to_alarm=3600000," +
                "notification_allowlist_duration_ms=30000,pre_idle_factor_long=1.67,pre_idle_factor_short=1.33," +
                "wait_for_unlock=true,use_window_alarms=false"
        assertTrue(DozeRaw.isRestorable(oemPrior))
        val device = FakeDozeDevice(raw = DozeProfile.BALANCED.constants)

        val result = dozeExecutor(device).restoreDozeRaw(oemPrior, DozeProfile.BALANCED.constants)

        assertEquals(ToolkitResult.done(before = DozeProfile.BALANCED.constants, after = oemPrior), result)
        assertEquals(oemPrior, device.raw)
        assertEquals(
            listOf(CMD, "settings", "put", "--user", "0", "global", "device_idle_constants", oemPrior),
            device.writes.single(),
        )
    }

    @Test
    fun `restoreDozeRaw replays a Samsung-style OEM key after a service restart`() {
        // A fresh executor has not read anything, so only the key grammar can admit this value.
        val samsungPrior = "sec_deep_idle_to=300000,sec_motion_wait_to=45000,inactive_to=1800000,sec_flag_2=true"
        assertTrue(DozeRaw.isRestorable(samsungPrior))
        val device = FakeDozeDevice(raw = DozeProfile.BALANCED.constants)

        val restored = dozeExecutor(device).restoreDozeRaw(samsungPrior, DozeProfile.BALANCED.constants)

        assertEquals(ToolkitStatus.DONE, restored.status)
        assertEquals(samsungPrior, device.raw)
        assertEquals(
            listOf(CMD, "settings", "put", "--user", "0", "global", "device_idle_constants", samsungPrior),
            device.writes.single(),
        )
    }

    @Test
    fun `isRestorable accepts identifier keys only and still rejects bad pairs`() {
        assertTrue(DozeRaw.isRestorable("a=1"))
        assertTrue(DozeRaw.isRestorable("${"k".repeat(64)}=1"))
        assertFalse(DozeRaw.isRestorable("${"k".repeat(65)}=1"))
        assertFalse(DozeRaw.isRestorable("1key=1"))
        assertFalse(DozeRaw.isRestorable("_key=1"))
        assertFalse(DozeRaw.isRestorable("Key=1"))
        assertFalse(DozeRaw.isRestorable("key-x=1"))
        assertFalse(DozeRaw.isRestorable("oem_key=1,oem_key=2"))
        assertFalse(DozeRaw.isRestorable("oem_key=-1"))
    }

    @Test
    fun `restoreDozeRaw retains same-executor replay of an observed legacy value only`() {
        // Safe characters but not a key=number pair list, so only the read-back memory admits it.
        val odd = "oem_key=5.5.5"
        val device = FakeDozeDevice(raw = odd, honorsConstants = false)
        val executor = dozeExecutor(device)

        assertEquals(ToolkitCode.ARG_VALUE, executor.restoreDozeRaw(odd, odd).code)
        assertEquals(odd, executor.getDozeState().afterRaw)
        assertEquals(ToolkitCode.ARG_VALUE, executor.applyDozeProfile(DozeProfile.BALANCED.code, odd).code)
        assertTrue(device.writes.isEmpty())

        // Model a legacy override; reading an opaque original never makes a new apply reversible.
        device.raw = DozeProfile.BALANCED.constants
        val restored = executor.restoreDozeRaw(odd, DozeProfile.BALANCED.constants)

        assertEquals(ToolkitStatus.DONE, restored.status)
        assertEquals(odd, device.raw)
        assertEquals(ToolkitStatus.DONE, executor.restoreDozeRaw("sensing_to=0,inactive_to=60000", odd).status)
    }

    @Test
    fun `getDozeState reports the raw value matched profile and effective values`() {
        val device = FakeDozeDevice(raw = DozeProfile.BALANCED.constants)

        val result = dozeExecutor(device).getDozeState()

        assertEquals(DozeProfile.BALANCED.code, result.code)
        assertEquals(DozeProfile.BALANCED.constants, result.afterRaw)
        assertTrue(result.message.orEmpty().contains("light_after_inactive_to=60000"))
        assertTrue(result.message.orEmpty().contains("inactive_to=600000"))

        device.raw = "inactive_to=1"
        assertEquals(ToolkitCode.DOZE_CUSTOM, dozeExecutor(device).getDozeState().code)
        device.raw = null
        assertEquals(DozeProfile.STANDARD.code, dozeExecutor(device).getDozeState().code)
    }

    // --- bg-dexopt argv ---------------------------------------------------------------------

    @Test
    fun `a slow plugged probe in startBgDexopt does not block a cancel on the write lock`() {
        lateinit var executor: ToolkitExecutor
        var cancelResult: ToolkitResult? = null
        val probe =
            PluggedProbe {
                // While the probe is "running", another binder thread cancels; it must not wait for us.
                val other = Thread { cancelResult = executor.cancelBgDexopt() }
                other.start()
                other.join(2_000L)
                true
            }
        runner.handler = { ok() }
        executor = ToolkitExecutor(runner, inspector, BgDexoptController(runner, probe, { 0L }, { }, TEST_SDK_INT), OWN) { }

        val start = executor.startBgDexopt()

        assertEquals(ToolkitCode.BUSY, start.code)
        assertEquals("cancel finished while the probe was still running", ToolkitStatus.DONE, cancelResult?.status)
    }

    @Test
    fun `cancel fences starts and releases admission even when the command throws`() {
        val executor = executor()
        runner.handler = {
            assertEquals(ToolkitCode.BUSY, executor.startBgDexopt().code)
            throw IllegalStateException("cancel failed")
        }
        try {
            executor.cancelBgDexopt()
            org.junit.Assert.fail("expected command failure")
        } catch (_: IllegalStateException) {
            // The finally block must release the in-flight cancellation fence.
        }
        assertEquals(ToolkitStatus.DONE, executor.startBgDexopt().status)
    }

    private fun dexoptExecutor(
        shell: FakeDexoptShell,
        sdkInt: Int,
        probe: PluggedProbe = PluggedProbe { true },
    ): ToolkitExecutor {
        runner.handler = shell::handle
        return ToolkitExecutor(runner, inspector, BgDexoptController(runner, probe, { 0L }, { }, sdkInt), OWN) { }
    }

    @Test
    fun `from API 33 a user cancel stops the system job and runs no extra pass`() {
        for (sdk in 33..36) {
            runner.calls.clear()
            val shell = FakeDexoptShell(sdk)
            val executor = dexoptExecutor(shell, sdk)
            assertEquals(ToolkitStatus.DONE, executor.startBgDexopt().status)
            shell.running = true // the worker has the job under way

            val result = executor.cancelBgDexopt()

            assertEquals("API $sdk", ToolkitStatus.DONE, result.status)
            assertEquals("API $sdk", 1, shell.cancels)
            assertEquals("API $sdk", 0, shell.passes)
            assertFalse("API $sdk", shell.running)
        }
    }

    @Test
    fun `a cancel that times out is unverified rather than failed, and a cancel that errors is failed`() {
        for (sdk in 33..36) {
            val executor = dexoptExecutor(FakeDexoptShell(sdk), sdk)
            // The platform's cancel waits for the running pass to stop; it may still take effect after our bound.
            runner.handler = { CommandOutput(exitCode = -1, text = "", timedOut = true) }

            val timedOut = executor.cancelBgDexopt()

            assertEquals("API $sdk", ToolkitStatus.UNAVAILABLE, timedOut.status)
            assertEquals("API $sdk", ToolkitCode.UNVERIFIED, timedOut.code)

            runner.handler = { failure() }

            val errored = executor.cancelBgDexopt()

            assertEquals("API $sdk", ToolkitStatus.FAILED, errored.status)
            assertEquals("API $sdk", ToolkitCode.EXEC, errored.code)
        }
    }

    @Test
    fun `below API 33 start and cancel are refused and run nothing`() {
        for (sdk in listOf(31, 32)) {
            runner.calls.clear()
            val shell = FakeDexoptShell(sdk)
            var probes = 0
            val executor = dexoptExecutor(shell, sdk, PluggedProbe { true.also { probes += 1 } })
            shell.running = true // a job an older service version started

            val start = executor.startBgDexopt()
            val cancel = executor.cancelBgDexopt()

            for (result in listOf(start, cancel)) {
                assertEquals("API $sdk", ToolkitStatus.REFUSED, result.status)
                assertEquals("API $sdk", ToolkitCode.UNSUPPORTED_API, result.code)
            }
            assertTrue("API $sdk: nothing ran, so no extra dexopt pass", runner.calls.isEmpty())
            assertEquals(0, shell.passes)
            assertEquals(0, probes)
            // A later start is still refused for the same reason, not BUSY.
            assertEquals(ToolkitCode.UNSUPPORTED_API, executor.startBgDexopt().code)
        }
    }

    private fun everyPackageAction(
        executor: ToolkitExecutor,
        pkg: String,
    ): List<ToolkitResult> =
        listOf(
            executor.setAppOp(pkg, AppOp.WAKE_LOCK.code, AppOpMode.IGNORE.code),
            executor.getAppOps(pkg),
            executor.setStandbyBucket(pkg, StandbyBucket.RESTRICTED.code),
            executor.getStandbyBucket(pkg),
            executor.forceStop(pkg),
        )

    /** The writes rule d still lets through on a protected app: each only lifts a restriction. */
    private fun liftWrites(
        executor: ToolkitExecutor,
        pkg: String,
    ): List<ToolkitResult> =
        listOf(
            executor.setAppOp(pkg, AppOp.RUN_ANY_IN_BACKGROUND.code, AppOpMode.ALLOW.code),
            executor.setStandbyBucket(pkg, StandbyBucket.ACTIVE.code),
        )

    private fun List<String>.isWrite(): Boolean =
        take(3) == listOf(CMD, "appops", "set") || "set-standby-bucket" in this || "force-stop" in this
}
