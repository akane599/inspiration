package com.akane.inspiration.ipc.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WideningIntFieldTest {
    /** API 34 framework shape: `char batteryVoltage`. */
    class CharVoltageItem {
        @JvmField var batteryVoltage: Char = 4_123.toChar()
    }

    /** API 35+ framework shape: `short batteryVoltage`. */
    class ShortVoltageItem {
        @JvmField var batteryVoltage: Short = 3_987
    }

    class NoVoltageItem {
        @JvmField var batteryLevel: Byte = 50
    }

    class LongVoltageItem {
        @JvmField var batteryVoltage: Long = 4_000L
    }

    @Test
    fun `reads a char field as its unsigned value`() {
        val field = WideningIntField.of(CharVoltageItem::class.java, "batteryVoltage")
        assertEquals(4_123, field.read(CharVoltageItem()))
    }

    @Test
    fun `reads a short field with the same accessor`() {
        val field = WideningIntField.of(ShortVoltageItem::class.java, "batteryVoltage")
        assertEquals(3_987, field.read(ShortVoltageItem()))
    }

    @Test
    fun `a missing field reads as zero instead of throwing`() {
        val field = WideningIntField.of(NoVoltageItem::class.java, "batteryVoltage")
        assertEquals(0, field.read(NoVoltageItem()))
    }

    @Test
    fun `a field that cannot widen to int reads as zero`() {
        val field = WideningIntField.of(LongVoltageItem::class.java, "batteryVoltage")
        assertEquals(0, field.read(LongVoltageItem()))
    }

    @Test
    fun `the hidden-api stub does not declare batteryVoltage`() {
        // A stub field would let compiled code link `batteryVoltage` by a fixed type again, which
        // throws NoSuchFieldError on every release whose framework type differs (API 34: char).
        // Unit tests run with the module directory as the working directory.
        val stub = File("../hidden-api-stub/src/main/java/android/os/BatteryStats.java")
        assertTrue("stub source not found at ${stub.absolutePath}", stub.isFile)
        val declaration = Regex("""\b(byte|char|short|int|long)\s+batteryVoltage\s*;""")
        assertFalse(declaration.containsMatchIn(stub.readText()))
    }
}
