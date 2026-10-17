package com.akane.inspiration.ipc.toolkit

import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wrapped Context has no base, so the sticky-intent step throws and the fallbacks decide. */
class AndroidPluggedProbeTest {
    private val noSticky = ContextWrapper(null)

    private fun probe(root: File = File("/nonexistent"), handler: (List<String>) -> CommandOutput) =
        AndroidPluggedProbe(noSticky, FakeRunner(handler), root)

    @Test
    fun `battery service answer true means plugged and asks with fixed argv`() {
        val runner = FakeRunner { argv -> ok(if (argv.last() == "usb") "true\n" else "false\n") }

        val plugged = AndroidPluggedProbe(noSticky, runner, File("/nonexistent")).isPlugged()

        assertTrue(plugged)
        assertEquals(
            listOf(
                listOf(CMD, "battery", "get", "ac"),
                listOf(CMD, "battery", "get", "usb"),
            ),
            runner.calls,
        )
    }

    @Test
    fun `battery service false for every source means not plugged even if sysfs says online`() {
        val root = Files.createTempDirectory("plugged").toFile()
        try {
            File(root, "sys/class/power_supply/ac").apply { mkdirs() }
            File(root, "sys/class/power_supply/ac/type").writeText("Mains")
            File(root, "sys/class/power_supply/ac/online").writeText("1")

            assertFalse(probe(root) { ok("false") }.isPlugged())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `when the battery service cannot answer sysfs decides`() {
        val root = Files.createTempDirectory("plugged").toFile()
        try {
            val ac = File(root, "sys/class/power_supply/ac").apply { mkdirs() }
            File(root, "sys/class/power_supply/battery").apply { mkdirs() }
            File(root, "sys/class/power_supply/battery/type").writeText("Battery")
            File(root, "sys/class/power_supply/battery/online").writeText("1")
            File(ac, "type").writeText("Mains")
            File(ac, "online").writeText("0\n")
            assertFalse("a Battery supply never counts", probe(root) { failure() }.isPlugged())

            File(ac, "online").writeText("1\n")
            assertTrue(probe(root) { failure() }.isPlugged())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `nothing to read fails closed`() {
        assertFalse(probe { failure() }.isPlugged())
        assertFalse(NoContextPluggedProbe.isPlugged())
    }
}
