package com.akane.inspiration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BackupRulesTest {
    @Test
    fun everyTransportExcludesEveryStorageDomain() {
        val domains =
            setOf(
                "root",
                "file",
                "database",
                "sharedpref",
                "external",
                "device_root",
                "device_file",
                "device_database",
                "device_sharedpref",
            )
        for ((file, sections) in mapOf(
            "data_extraction_rules.xml" to listOf("cloud-backup", "device-transfer"),
        )) {
            val document = parse(File("src/main/res/xml/$file"))
            for (section in sections) {
                val children = document.getElementsByTagName(section).item(0).childNodes
                val exclusions =
                    (0 until children.length)
                        .map { children.item(it) }
                        .filter { it.nodeName == "exclude" }
                assertEquals(
                    "$file $section",
                    domains,
                    exclusions.map { it.attributes.getNamedItem("domain").nodeValue }.toSet(),
                )
                exclusions.forEach { assertEquals(".", it.attributes.getNamedItem("path").nodeValue) }
            }
        }
    }

    /**
     * With targetSdk 31+, `allowBackup="false"` alone does not stop device-to-device transfer of the database:
     * only the extraction rules above do, and only if the manifest names them.
     */
    @Test
    fun manifestDisablesBackupAndNamesTheExtractionRules() {
        val application = application(parse(File("src/main/AndroidManifest.xml")))

        assertEquals("false", application.getAttributeNS(ANDROID_NS, "allowBackup"))
        assertEquals("@xml/data_extraction_rules", application.getAttributeNS(ANDROID_NS, "dataExtractionRules"))
        assertTrue(File("src/main/res/xml/data_extraction_rules.xml").isFile)
    }

    /**
     * The merged manifest takes these attributes from main: a dependency cannot override the app's values, so only
     * another source set of this module could (the merged release manifest is not built for unit tests).
     */
    @Test
    fun noOtherSourceSetOverridesTheBackupAttributes() {
        val overlays =
            File("src")
                .listFiles()
                .orEmpty()
                .filter { it.name != "main" }
                .map { File(it, "AndroidManifest.xml") }
                .filter { it.isFile }
        assertTrue("the debug overlay manifest is checked", overlays.any { it.parentFile.name == "debug" })
        for (manifest in overlays) {
            val applications = parse(manifest).getElementsByTagName("application")
            for (index in 0 until applications.length) {
                val application = applications.item(index) as Element
                for (attribute in listOf("allowBackup", "dataExtractionRules", "fullBackupContent")) {
                    assertFalse("$manifest sets $attribute", application.hasAttributeNS(ANDROID_NS, attribute))
                }
                for (directive in listOf("replace", "remove", "node")) {
                    assertFalse("$manifest has tools:$directive", application.hasAttributeNS(TOOLS_NS, directive))
                }
            }
        }
    }

    private fun parse(file: File): Document =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)

    private fun application(manifest: Document): Element =
        manifest.getElementsByTagName("application").let {
            assertEquals(1, it.length)
            it.item(0) as Element
        }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val TOOLS_NS = "http://schemas.android.com/tools"
    }
}
