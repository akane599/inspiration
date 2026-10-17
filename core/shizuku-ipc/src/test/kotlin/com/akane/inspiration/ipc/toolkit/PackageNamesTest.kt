package com.akane.inspiration.ipc.toolkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageNamesTest {
    @Test
    fun `ordinary package names are accepted`() {
        listOf("com.android.chrome", "com.example.app_2", "a.b", "org.A1.b2_c").forEach {
            assertTrue(it, PackageNames.isValid(it))
        }
    }

    @Test
    fun `a trailing newline is rejected`() {
        assertFalse(PackageNames.isValid("com.example.app\n"))
        assertFalse(PackageNames.isValid("com.example.app\r\n"))
        assertFalse(PackageNames.isValid("\ncom.example.app"))
    }

    @Test
    fun `a leading dash or non letter first character is rejected`() {
        assertFalse(PackageNames.isValid("-com.example"))
        assertFalse(PackageNames.isValid("--user.0"))
        assertFalse(PackageNames.isValid("com.-example"))
        assertFalse(PackageNames.isValid("1com.example"))
        assertFalse(PackageNames.isValid("com.1example"))
        assertFalse(PackageNames.isValid("_com.example"))
    }

    @Test
    fun `unicode letters and digits are rejected`() {
        assertFalse(PackageNames.isValid("com.exämple.app"))
        assertFalse(PackageNames.isValid("com.example.аpp")) // Cyrillic a
        assertFalse(PackageNames.isValid("com.example.app١")) // Arabic-indic digit one
        assertFalse(PackageNames.isValid("com.example.app​"))
    }

    @Test
    fun `length 255 is accepted and 256 is rejected`() {
        val at255 = "a." + "b".repeat(253)
        val at256 = "a." + "b".repeat(254)

        assertEquals(255, at255.length)
        assertTrue(PackageNames.isValid(at255))
        assertEquals(256, at256.length)
        assertFalse(PackageNames.isValid(at256))
    }

    @Test
    fun `shell metacharacters whitespace and structure errors are rejected`() {
        listOf(
            null, "", ".", "single", "com.", ".com.example", "com..example", "com.example.", "com example",
            "com.example;ls", "com.example&&id", "com.example|x", "com.example\$(id)", "com.example`id`",
            "com.example/Activity", "com.example:x", "com.example app",
        ).forEach { assertFalse("$it", PackageNames.isValid(it)) }
    }

    @Test
    fun `doze raw values are limited to the safe alphabet and 1024 characters`() {
        assertTrue(DozeRaw.isSafe(""))
        assertTrue(DozeRaw.isSafe("light_after_inactive_to=60000,inactive_to=1.5"))
        assertTrue(DozeRaw.isSafe("x".repeat(1024)))
        assertFalse(DozeRaw.isSafe("x".repeat(1025)))
        assertFalse(DozeRaw.isSafe("a b"))
        assertFalse(DozeRaw.isSafe("a;b"))
        assertFalse(DozeRaw.isSafe("a\n"))
    }

    @Test
    fun `profile constants only use safe characters and expose their effective values`() {
        for (profile in DozeProfile.entries) {
            profile.constants?.let { assertTrue(profile.name, DozeRaw.isSafe(it)) }
        }
        assertEquals(60_000L, DozeProfile.BALANCED.effectiveMs["light_after_inactive_to"])
        assertEquals(0L, DozeProfile.AGGRESSIVE_NIGHT.effectiveMs["sensing_to"])
        assertTrue(DozeProfile.STANDARD.effectiveMs.isEmpty())
    }

    @Test
    fun `enum code mapping is explicit and unknown codes map to null`() {
        AppOp.entries.forEach { assertEquals(it, AppOp.fromCode(it.code)) }
        AppOpMode.entries.forEach { assertEquals(it, AppOpMode.fromCode(it.code)) }
        StandbyBucket.entries.forEach { assertEquals(it, StandbyBucket.fromCode(it.code)) }
        DozeProfile.entries.forEach { assertEquals(it, DozeProfile.fromCode(it.code)) }
        assertNull(AppOp.fromCode(0))
        assertNull(AppOp.fromCode(-1))
        assertNull(AppOpMode.fromCode(3))
        assertNull(StandbyBucket.fromCode(0))
        assertNull(StandbyBucket.fromCode(6))
        assertNull(DozeProfile.fromCode(-1))
        assertNull(DozeProfile.fromCode(3))
        assertEquals(3, AppOp.entries.size)
        assertEquals(2, AppOpMode.entries.size)
    }
}
