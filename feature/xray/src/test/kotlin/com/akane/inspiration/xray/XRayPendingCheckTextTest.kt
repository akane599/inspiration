package com.akane.inspiration.xray

import com.akane.inspiration.telemetry.toolkit.ToolkitRefusal
import com.akane.inspiration.telemetry.toolkit.ToolkitUnavailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * SQ-52: a request refused because an earlier change to the same setting is still PENDING sent nothing, so its message
 * says so instead of "the change was sent, but it couldn't be confirmed".
 */
class XRayPendingCheckTextTest {
    private fun strings(directory: String): Map<String, String> {
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(File("src/main/res/$directory/strings.xml"))
                .getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    @Test
    fun `a pending-check refusal has its own message, not the sent-but-unconfirmed one`() {
        assertEquals(R.string.xray_refused_pending_check, refusalRes(ToolkitRefusal.PENDING_CHECK))
        assertNotEquals(unavailableRes(ToolkitUnavailable.UNVERIFIED), refusalRes(ToolkitRefusal.PENDING_CHECK))
    }

    @Test
    fun `the pending-check message says nothing was sent, in English and Turkish`() {
        for ((directory, turkish) in listOf("values" to false, "values-tr" to true)) {
            val text = strings(directory).getValue("xray_refused_pending_check").lowercase(Locale.ROOT)
            assertTrue("$directory names the earlier change", text.contains(if (turkish) "önceki bir değişiklik" else "an earlier change"))
            assertTrue("$directory says nothing was sent", text.contains(if (turkish) "hiçbir şey gönderilmedi" else "nothing was sent"))
            assertFalse("$directory must not claim it was sent", text.contains(if (turkish) "değişiklik gönderildi" else "change was sent"))
        }
    }
}
