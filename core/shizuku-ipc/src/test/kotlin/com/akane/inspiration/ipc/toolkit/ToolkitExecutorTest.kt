package com.akane.inspiration.ipc.toolkit

import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.model.ToolkitStatus
import org.junit.Assert.assertEquals
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

    private fun executor(): ToolkitExecutor = ToolkitExecutor(
        runner = runner,
        inspector = inspector,
        dexopt = BgDexoptController(runner, { true }, { 0L }, { }),
        ownPackage = OWN,
        sleepMs = { },
    ).also { runner.handler = ::device }

    /** Models appops + standby bucket behaviour as observed on the API 36 emulator. */
    private fun device(argv: List<String>): CommandOutput = when {
        argv.take(5) == listOf(CMD, "appops", "get", "--user", "0") -> {
            val op = argv[6]
            appModes[op]?.let { ok("$op: $it") } ?: ok("No operations.\nDefault mode: allow")
        }
        argv.take(5) == listOf(CMD, "appops", "set", "--user", "0") -> {
            appModes[argv[6]] = argv[7]
            ok()
        }
        argv.take(5) == listOf(CMD, "activity", "get-standby-bucket", "--user", "0") -> ok("$bucket")
        argv.take(5) == listOf(CMD, "activity", "set-standby-bucket", "--user", "0") -> {
            bucket = StandbyBucket.entries.first { it.argv == argv[6] }.rawValue
            ok()
        }
        argv.take(5) == listOf(CMD, "activity", "force-stop", "--user", "0") -> {
            inspector.packages[argv[5]] = userApp(stopped = true)
            ok()
        }
        else -> failure("unexpected $argv")
    }

    @Test
    fun `setAppOp issues the exact argv for every op and mode row`() {
        for (op in AppOp.entries) {
            for (mode in AppOpMode.entries) {
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
        assertEquals(ToolkitCode.ARG_VALUE, executor.setAppOp(APP, AppOp.WAKE_LOCK.code, 3).code)
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

        assertEquals("RUN_ANY_IN_BACKGROUND=ignore,RUN_IN_BACKGROUND=default,WAKE_LOCK=default", result.afterRaw)
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
    fun `a failing command is reported as FAILED with the prior read back`() {
        val executor = executor()
        val base = runner.handler
        runner.handler = { argv -> if ("set" in argv) failure() else base(argv) }

        val result = executor.setAppOp(APP, AppOp.WAKE_LOCK.code, AppOpMode.IGNORE.code)

        assertEquals(ToolkitStatus.FAILED, result.status)
        assertEquals(ToolkitCode.EXEC, result.code)
        assertEquals("default", result.beforeRaw)
    }

    @Test
    fun `invalid package names are refused before any lookup or process`() {
        val bad = listOf(
            null, "", "com.example.app\n", "-com.example", "com.example.-x", "singleword", "com..x",
            "com.exämple.app", "com.example.app; reboot", "com example", "1com.example", ".com.example",
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
        data class Case(val name: String, val pkg: String, val lookup: PackageLookup, val expected: Int)
        val cases = listOf(
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
        for (case in cases) {
            inspector.packages[case.pkg] = case.lookup
            runner.calls.clear()
            val executor = executor()
            for (result in everyPackageAction(executor, case.pkg)) {
                assertEquals(case.name, ToolkitStatus.REFUSED, result.status)
                assertEquals(case.name, case.expected, result.code)
            }
            assertTrue("${case.name} spawned ${runner.calls}", runner.calls.isEmpty())
        }
        val protectedCases = listOf(
            "role holder" to ProtectedPackages(roleHolders = setOf("com.example.guarded")),
            "default ime" to ProtectedPackages(defaultIme = setOf("com.example.guarded")),
            "accessibility" to ProtectedPackages(accessibility = setOf("com.example.guarded")),
            "device admin" to ProtectedPackages(deviceAdmins = setOf("com.example.guarded")),
            "always-on vpn" to ProtectedPackages(alwaysOnVpn = setOf("com.example.guarded")),
        )
        for ((name, set) in protectedCases) {
            inspector.packages["com.example.guarded"] = userApp()
            inspector.protectedSet = set
            runner.calls.clear()
            val executor = executor()
            for (result in everyPackageAction(executor, "com.example.guarded")) {
                assertEquals(name, ToolkitCode.DENY_PROTECTED, result.code)
            }
            assertTrue(name, runner.calls.isEmpty())
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
        assertTrue(runner.calls.isEmpty())
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

    // --- Doze -------------------------------------------------------------------------------

    private fun dozeExecutor(device: FakeDozeDevice): ToolkitExecutor {
        runner.handler = device::handle
        return ToolkitExecutor(runner, inspector, BgDexoptController(runner, { true }, { 0L }, { }), OWN) { }
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
                listOf(listOf(CMD, "settings", "put", "global", "device_idle_constants", profile.constants)),
                device.writes,
            )
        }
    }

    @Test
    fun `STANDARD deletes the setting`() {
        val device = FakeDozeDevice(raw = DozeProfile.BALANCED.constants)

        val result = dozeExecutor(device).applyDozeProfile(DozeProfile.STANDARD.code, DozeProfile.BALANCED.constants)

        assertEquals(ToolkitResult.done(before = DozeProfile.BALANCED.constants, after = null), result)
        assertEquals(listOf(listOf(CMD, "settings", "delete", "global", "device_idle_constants")), device.writes)
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
        assertEquals(listOf(CMD, "settings", "put", "global", "device_idle_constants", prior), device.writes.last())
    }

    @Test
    fun `rollback of a previously unset value deletes the setting`() {
        val device = FakeDozeDevice(raw = null, honorsConstants = false)

        val result = dozeExecutor(device).applyDozeProfile(DozeProfile.BALANCED.code, null)

        assertEquals(ToolkitCode.NOT_EFFECTIVE, result.code)
        assertNull(device.raw)
        assertEquals(listOf(CMD, "settings", "delete", "global", "device_idle_constants"), device.writes.last())
    }

    @Test
    fun `a prior value that could not be replayed safely blocks the apply`() {
        val device = FakeDozeDevice(raw = "weird value;rm")

        val result = dozeExecutor(device).applyDozeProfile(DozeProfile.BALANCED.code, "weird value;rm")

        assertEquals(ToolkitCode.ARG_VALUE, result.code)
        assertTrue(device.writes.isEmpty())
    }

    @Test
    fun `restoreDozeRaw replays the stored prior value when current matches the recorded after`() {
        val device = FakeDozeDevice(raw = DozeProfile.BALANCED.constants)

        val result = dozeExecutor(device).restoreDozeRaw("inactive_to=1000", DozeProfile.BALANCED.constants)

        assertEquals(ToolkitResult.done(before = DozeProfile.BALANCED.constants, after = "inactive_to=1000"), result)
        assertEquals("inactive_to=1000", device.raw)
    }

    @Test
    fun `restoreDozeRaw deletes when the prior state was unset`() {
        val device = FakeDozeDevice(raw = DozeProfile.BALANCED.constants)

        val result = dozeExecutor(device).restoreDozeRaw(null, DozeProfile.BALANCED.constants)

        assertEquals(ToolkitStatus.DONE, result.status)
        assertNull(device.raw)
        assertEquals(listOf(CMD, "settings", "delete", "global", "device_idle_constants"), device.writes.single())
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
        assertEquals(ToolkitStatus.DONE, executor.restoreDozeRaw("x".repeat(1024), "a=1").status)
        assertTrue(device.writes.size == 1)
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
    fun `cancelBgDexopt issues exactly bg-dexopt-job --cancel`() {
        val result = executor().also { runner.handler = { ok() } }.cancelBgDexopt()

        assertEquals(ToolkitStatus.DONE, result.status)
        assertEquals(listOf(CMD, "package", "bg-dexopt-job", "--cancel"), runner.calls.single())
    }

    private fun everyPackageAction(executor: ToolkitExecutor, pkg: String): List<ToolkitResult> = listOf(
        executor.setAppOp(pkg, AppOp.WAKE_LOCK.code, AppOpMode.IGNORE.code),
        executor.getAppOps(pkg),
        executor.setStandbyBucket(pkg, StandbyBucket.RESTRICTED.code),
        executor.getStandbyBucket(pkg),
        executor.forceStop(pkg),
    )
}
