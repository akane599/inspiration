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
    fun `UID overrides are shown but package writes are refused without mutation`() {
        val executor = executor()
        runner.handler = { argv ->
            if (argv[1] == "appops" && argv[2] == "get") {
                val op = argv[6]
                ok("Uid mode: $op: ignore\n$op: default")
            } else error("Unexpected write: $argv")
        }
        val read = executor.getAppOps(APP)
        assertEquals(ToolkitStatus.DONE, read.status)
        assertTrue(read.afterRaw.orEmpty().contains("RUN_ANY_IN_BACKGROUND=ignore"))
        val result = executor.setAppOp(APP, AppOp.RUN_ANY_IN_BACKGROUND.code, AppOpMode.DEFAULT.code)
        assertEquals(ToolkitStatus.REFUSED, result.status)
        assertEquals(ToolkitCode.NOT_EFFECTIVE, result.code)
        assertEquals("ignore", result.beforeRaw)
        assertTrue(runner.calls.none { it[2] == "set" })
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
        val rejected = listOf(
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
        val ok = pairs.fold("") { acc, pair ->
            val next = if (acc.isEmpty()) pair else "$acc,$pair"
            if (next.length <= 1024) next else acc
        }
        assertTrue(DozeRaw.isRestorable(ok))
        return pairs.joinToString(",").also { assertTrue(it.length > 1024) }
    }

    @Test
    fun `restoreDozeRaw replays a realistic OEM prior whose keys are not profile keys`() {
        val oemPrior = "light_after_inactive_to=30000,light_idle_to=60000,light_idle_factor=2.0," +
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
    fun `restoreDozeRaw accepts a value the executor itself read even when it is not well formed`() {
        // Safe characters but not a key=number pair list, so only the read-back memory admits it.
        val odd = "oem_key=5.5.5"
        val device = FakeDozeDevice(raw = odd)
        val executor = dozeExecutor(device)

        assertEquals(ToolkitCode.ARG_VALUE, executor.restoreDozeRaw(odd, odd).code)

        // Applying a profile reads the prior raw value, which makes it replayable.
        executor.applyDozeProfile(DozeProfile.BALANCED.code, odd)
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
        val probe = PluggedProbe {
            // While the probe is "running", another binder thread cancels; it must not wait for us.
            val other = Thread { cancelResult = executor.cancelBgDexopt() }
            other.start()
            other.join(2_000L)
            true
        }
        runner.handler = { ok() }
        executor = ToolkitExecutor(runner, inspector, BgDexoptController(runner, probe, { 0L }, { }), OWN) { }

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
