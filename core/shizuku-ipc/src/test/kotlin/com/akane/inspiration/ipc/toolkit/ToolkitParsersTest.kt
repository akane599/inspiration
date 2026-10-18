package com.akane.inspiration.ipc.toolkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolkitParsersTest {
    @Test
    fun `UID appop override wins over a package mode and is readable alone`() {
        assertEquals(
            "ignore",
            ToolkitParsers.appOpMode(AppOp.RUN_ANY_IN_BACKGROUND, "Uid mode: RUN_ANY_IN_BACKGROUND: ignore\n"),
        )
        assertEquals(
            "ignore",
            ToolkitParsers.appOpMode(
                AppOp.RUN_ANY_IN_BACKGROUND,
                "Uid mode: RUN_ANY_IN_BACKGROUND: ignore\nRUN_ANY_IN_BACKGROUND: default",
            ),
        )
        assertEquals("allow", ToolkitParsers.appOpMode(AppOp.WAKE_LOCK, "WAKE_LOCK: allow (running)"))
    }

    @Test
    fun `appops modes parse from get output`() {
        assertEquals("ignore", ToolkitParsers.appOpMode(AppOp.WAKE_LOCK, "WAKE_LOCK: ignore\n"))
        assertEquals("default", ToolkitParsers.appOpMode(AppOp.WAKE_LOCK, "WAKE_LOCK: default"))
        assertEquals("allow", ToolkitParsers.appOpMode(AppOp.WAKE_LOCK, "WAKE_LOCK: allow; time=+1m ago"))
        assertEquals("default", ToolkitParsers.appOpMode(AppOp.WAKE_LOCK, "No operations.\nDefault mode: allow"))
        assertNull(ToolkitParsers.appOpMode(AppOp.WAKE_LOCK, "RUN_IN_BACKGROUND: ignore"))
        assertNull(ToolkitParsers.appOpMode(AppOp.WAKE_LOCK, "Error: Unknown operation"))
        assertNull(ToolkitParsers.appOpMode(AppOp.WAKE_LOCK, "WAKE_LOCK: ig nore!"))
    }

    @Test
    fun `durations match TimeUtils formatDuration`() {
        assertEquals(0L, ToolkitParsers.durationMs("0"))
        assertEquals(240_000L, ToolkitParsers.durationMs("+4m0s0ms"))
        assertEquals(3_600_000L, ToolkitParsers.durationMs("+1h0m0s0ms"))
        assertEquals(15_000L, ToolkitParsers.durationMs("+15s0ms"))
        assertEquals(30L, ToolkitParsers.durationMs("+30ms"))
        assertEquals(90_061_001L, ToolkitParsers.durationMs("+1d1h1m1s1ms"))
        assertEquals(-5_000L, ToolkitParsers.durationMs("-5s0ms"))
        assertNull(ToolkitParsers.durationMs("20.0m"))
        assertNull(ToolkitParsers.durationMs("true"))
        assertNull(ToolkitParsers.durationMs(""))
    }

    @Test
    fun `settings block stops at the next section and skips non durations`() {
        val text = "  Flags:\n    a=true\n\n  Settings:\n    light_after_inactive_to=+4m0s0ms\n" +
            "    light_idle_factor=2.0\n    location_accuracy=20.0m\n    sensing_to=0\n    wait_for_unlock=true\n" +
            "  Whitelist (except idle) system apps:\n    inactive_to=+9s0ms\n"

        val settings = ToolkitParsers.dozeSettings(text)

        assertEquals(mapOf("light_after_inactive_to" to 240_000L, "sensing_to" to 0L), settings)
        assertNull(ToolkitParsers.dozeSettings(text.replace("Settings:", "Other:")))
    }

    @Test
    fun `settings and component values parse`() {
        assertNull(ToolkitParsers.settingValue("null\n"))
        assertNull(ToolkitParsers.settingValue("\n"))
        assertEquals("a=1", ToolkitParsers.settingValue("a=1\n"))
        assertEquals(setOf("com.ime"), ToolkitParsers.componentPackages("com.ime/.Service"))
        assertEquals(setOf("a.b", "c.d"), ToolkitParsers.componentPackages("a.b/.X:c.d/.Y"))
        assertEquals(emptySet<String>(), ToolkitParsers.componentPackages(null))
        assertEquals(setOf("com.home"), ToolkitParsers.roleHolders("com.home\n\n"))
    }

    @Test
    fun `device admins are read from the enabled admins section`() {
        val dump = "Current Device Policy Manager state:\n  Immutable state:\n    mHasFeature=true\n  \n" +
            "  Enabled Device Admins (User 0, provisioningState: 0):\n    com.corp.mdm/.AdminReceiver:\n" +
            "      uid=10120\n      policies:\n    mPasswordOwner=-1\n  \n  Constants:\n    X: 1\n"

        assertEquals(setOf("com.corp.mdm"), ToolkitParsers.deviceAdmins(dump, truncated = false))
    }

    @Test
    fun `device admins are found after a preamble larger than 4 KB`() {
        val preamble = "Current Device Policy Manager state:\n  Immutable state:\n" +
            "    mSomeLongPreambleLine=true\n".repeat(1_000)
        val dump = preamble + "  Enabled Device Admins (User 0, provisioningState: 0):\n" +
            "    com.corp.mdm/.AdminReceiver:\n      uid=10120\n  \n  Constants:\n"
        assertTrue(dump.length > 4 * RETENTION_CAP_BYTES)

        assertEquals(setOf("com.corp.mdm"), ToolkitParsers.deviceAdmins(dump, truncated = false))
        assertNull("cut inside the preamble still fails closed", ToolkitParsers.deviceAdmins(preamble, truncated = true))
    }

    @Test
    fun `no admins section or a section cut short by truncation fails closed`() {
        assertNull(ToolkitParsers.deviceAdmins("nothing here", truncated = false))
        val cut = "  Enabled Device Admins (User 0):\n    com.corp.mdm/.AdminReceiver:\n      uid=1\n"
        assertNull(ToolkitParsers.deviceAdmins(cut, truncated = true))
        assertEquals(setOf("com.corp.mdm"), ToolkitParsers.deviceAdmins(cut, truncated = false))
        val empty = "  Enabled Device Admins (User 0, provisioningState: 0):\n  \n    mPasswordOwner=-1\n  \n  Constants:\n"
        assertEquals(emptySet<String>(), ToolkitParsers.deviceAdmins(empty, truncated = true))
    }
}
