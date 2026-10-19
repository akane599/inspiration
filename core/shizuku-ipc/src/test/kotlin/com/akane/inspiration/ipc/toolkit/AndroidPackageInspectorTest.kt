package com.akane.inspiration.ipc.toolkit

import android.content.ContextWrapper
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `protectedPackages()` through a fake runner: the real argv list and fail-closed behaviour. */
class AndroidPackageInspectorTest {
    private val roles = listOf("HOME", "DIALER", "SMS", "ASSISTANT").map { "android.app.role.$it" }

    private val adminDump = "Current Device Policy Manager state:\n" +
        "  Enabled Device Admins (User 0, provisioningState: 0):\n    com.corp.mdm/.AdminReceiver:\n" +
        "      uid=10120\n  \n  Constants:\n"

    private fun healthy(argv: List<String>): CommandOutput = when {
        argv.getOrNull(1) == "role" -> ok("com.holder.${argv.last().substringAfterLast('.').lowercase()}\n")
        argv == DEVICE_POLICY_ARGV -> ok(adminDump)
        argv.getOrNull(1) == "settings" -> when (argv.last()) {
            "default_input_method" -> ok("com.ime/.Service\n")
            "enabled_accessibility_services" -> ok("com.a11y/.Svc:com.a11y2/.Svc2\n")
            else -> ok("com.vpn\n")
        }
        else -> failure("unexpected $argv")
    }

    private fun inspector(runner: CommandRunner) = AndroidPackageInspector(ContextWrapper(null), runner)

    @Test
    fun `all four role queries are issued with user 0 and the result unions every source`() {
        val runner = FakeRunner(::healthy)

        val result = inspector(runner).protectedPackages()

        assertNotNull(result)
        for (role in roles) {
            assertTrue(role, listOf(CMD, "role", "get-role-holders", "--user", "0", role) in runner.calls)
        }
        assertEquals(
            setOf("com.holder.home", "com.holder.dialer", "com.holder.sms", "com.holder.assistant"),
            result?.roleHolders,
        )
        assertEquals(setOf("com.ime"), result?.defaultIme)
        assertEquals(setOf("com.a11y", "com.a11y2"), result?.accessibility)
        assertEquals(setOf("com.corp.mdm"), result?.deviceAdmins)
        assertEquals(setOf("com.vpn"), result?.alwaysOnVpn)
    }

    @Test
    fun `every settings and role argv pins user 0 with an absolute executable`() {
        val runner = FakeRunner(::healthy)

        inspector(runner).protectedPackages()

        val pinned = runner.calls.filter { it != DEVICE_POLICY_ARGV }
        assertEquals(roles.size + 3, pinned.size)
        for (argv in pinned) {
            assertEquals(CMD, argv.first())
            val index = argv.indexOf("--user")
            assertTrue("$argv", index >= 0 && argv[index + 1] == "0")
        }
        assertTrue(DEVICE_POLICY_ARGV in runner.calls)
    }

    @Test
    fun `any single failing query makes the whole answer null`() {
        val total = FakeRunner(::healthy).also { inspector(it).protectedPackages() }.calls.size
        assertEquals(roles.size + 4, total)

        for (failing in 0 until total) {
            var index = 0
            val runner = FakeRunner { argv -> if (index++ == failing) failure() else healthy(argv) }

            assertNull("query #$failing failing", inspector(runner).protectedPackages())
        }
    }

    @Test
    fun `a device policy dump with a preamble much larger than 4 KB still yields the admins`() {
        val preamble = buildString {
            append("Current Device Policy Manager state:\n  Immutable state:\n")
            repeat(2_000) { append("    mSomeLongPreambleLine$it=true\n") }
        }
        assertTrue(preamble.length > 4 * RETENTION_CAP_BYTES)
        val runner = FakeRunner { argv ->
            if (argv == DEVICE_POLICY_ARGV) ok(preamble + adminDump.substringAfter("state:\n")) else healthy(argv)
        }

        val result = inspector(runner).protectedPackages()

        assertEquals(setOf("com.corp.mdm"), result?.deviceAdmins)
    }

    @Test
    fun `a truncated device policy dump that ends before the admins section fails closed`() {
        val runner = FakeRunner { argv ->
            if (argv == DEVICE_POLICY_ARGV) {
                CommandOutput(0, "Current Device Policy Manager state:\n  Immutable state:\n", truncated = true)
            } else {
                healthy(argv)
            }
        }

        assertNull(inspector(runner).protectedPackages())
    }

    @Test
    fun `a truncated role or settings read fails closed`() {
        val queries = FakeRunner(::healthy).also { inspector(it).protectedPackages() }.calls.filter { it != DEVICE_POLICY_ARGV }
        assertEquals(roles.size + 3, queries.size)

        for (cut in queries) {
            val runner = FakeRunner { argv -> healthy(argv).let { if (argv == cut) it.copy(truncated = true) else it } }

            assertNull("$cut cut at the retention cap", inspector(runner).protectedPackages())
        }
    }

    @Test
    fun `an accessibility list far past 4 KB is read whole through the real runner`() {
        val value = accessibilityServices(count = 120)
        assertTrue("precondition: over twice the generic cap", value.length > 2 * RETENTION_CAP_BYTES)

        val result = inspector(cannedRunner(value)).protectedPackages()

        assertEquals(121, result?.accessibility?.size)
        assertTrue(result?.contains(LATE_SERVICE) == true)
    }

    @Test
    fun `an accessibility list longer than its own cap fails closed through the real runner`() {
        val value = accessibilityServices(count = ACCESSIBILITY_RETENTION_CAP_BYTES / 64)
        assertTrue("precondition: over the accessibility cap", value.length > ACCESSIBILITY_RETENTION_CAP_BYTES)

        assertNull(inspector(cannedRunner(value)).protectedPackages())
    }

    @Test
    fun `force-stop of an accessibility service listed past 4 KB is hard-denied`() {
        val started = mutableListOf<List<String>>()
        val runner = cannedRunner(accessibilityServices(count = 120), started)
        val real = inspector(runner)
        val installed = object : PackageInspector {
            override fun lookup(pkg: String): PackageLookup = userApp()

            override fun protectedPackages(): ProtectedPackages? = real.protectedPackages()
        }
        val executor = ToolkitExecutor(runner, installed, BgDexoptController(runner, { true }, { 0L }, { }, TEST_SDK_INT), OWN) { }

        val result = executor.forceStop(LATE_SERVICE)

        assertEquals(ToolkitStatus.REFUSED, result.status)
        assertEquals(ToolkitCode.DENY_PROTECTED, result.code)
        assertTrue("no force-stop was issued", started.none { "force-stop" in it })
    }

    /** [count] vendor services, then [LATE_SERVICE], as `settings get` prints the value. */
    private fun accessibilityServices(count: Int): String = buildString {
        for (i in 1..count) append("com.vendor.helper$i/com.vendor.helper$i.service.AutomationAccessibilityService:")
        append("$LATE_SERVICE/$LATE_SERVICE.ReaderService")
    }

    /** The real [ProcessCommandRunner], with its retention caps, over processes that exit 0 at once. */
    private fun cannedRunner(accessibility: String, started: MutableList<List<String>> = mutableListOf()) =
        ProcessCommandRunner { argv ->
            started += argv
            CannedProcess(
                when {
                    argv == ACCESSIBILITY_SERVICES_ARGV -> "$accessibility\n"
                    argv.getOrNull(1) == "activity" -> ""
                    else -> healthy(argv).text
                },
            )
        }

    private class CannedProcess(text: String) : Process() {
        private val stdout = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = stdout

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = 0

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

        override fun exitValue(): Int = 0

        override fun destroy() = Unit
    }

    private companion object {
        const val OWN = "com.akane.inspiration"

        /** Enabled last, so its entry starts well past the generic 4 KB retention cap. */
        const val LATE_SERVICE = "com.example.screenreader"
    }
}
