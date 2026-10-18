package com.akane.inspiration

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupRulesTest {
    @Test
    fun everyTransportExcludesEveryStorageDomain() {
        val domains = setOf("root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref")
        for ((file, sections) in mapOf(
            "data_extraction_rules.xml" to listOf("cloud-backup", "device-transfer"),
        )) {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/xml/$file"))
            for (section in sections) {
                val children = document.getElementsByTagName(section).item(0).childNodes
                val exclusions = (0 until children.length).map { children.item(it) }
                    .filter { it.nodeName == "exclude" }
                assertEquals("$file $section", domains,
                    exclusions.map { it.attributes.getNamedItem("domain").nodeValue }.toSet())
                exclusions.forEach { assertEquals(".", it.attributes.getNamedItem("path").nodeValue) }
            }
        }
    }
}
