package com.akane.inspiration.telemetry.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UidIdentityTest {
    private val a = UidOwnerInstall("test.owner.a", 10_001, 123)
    private val b = UidOwnerInstall("test.owner.b", 10_001, 456)

    @Test
    fun `v1 encoding is canonical and binds full uid serial and every install`() {
        val fingerprint = requireNotNull(UidOwnerFingerprint.create(10_001, 7, listOf(a, b)))
        assertEquals(67, fingerprint.length)
        assertEquals("v1:81b82633779e05282afb339529d87b81e4ce2c47b4a9ac8aec4ad3ef68b6f71b", fingerprint)
        assertTrue(fingerprint.matches(Regex("v1:[0-9a-f]{64}")))
        assertEquals(fingerprint, UidOwnerFingerprint.create(10_001, 7, listOf(b, a)))
        assertNotEquals(fingerprint, UidOwnerFingerprint.create(10_001, 8, listOf(a, b)))
        assertNotEquals(fingerprint, UidOwnerFingerprint.create(110_001, 7, listOf(a.copy(uid = 110_001), b.copy(uid = 110_001))))
        assertNotEquals(fingerprint, UidOwnerFingerprint.create(10_001, 7, listOf(a.copy(firstInstallTimeMs = 124), b)))
        assertNotEquals(fingerprint, UidOwnerFingerprint.create(10_001, 7, listOf(a)))
        assertNotEquals(
            UidOwnerFingerprint.create(10_001, 7, listOf(a.copy(packageName = "a.b"), b.copy(packageName = "c"))),
            UidOwnerFingerprint.create(10_001, 7, listOf(a.copy(packageName = "a"), b.copy(packageName = "b.c"))),
        )
    }

    @Test
    fun `incomplete conflicting and malformed ownership never hashes`() {
        val invalid =
            listOf(
                emptyList(),
                listOf(a, a),
                listOf(a, a.copy(firstInstallTimeMs = 999)),
                listOf(a.copy(uid = 10_002)),
                listOf(a.copy(firstInstallTimeMs = 0)),
                listOf(a.copy(packageName = "")),
                listOf(a.copy(packageName = "test.owner:remote")),
                listOf(a.copy(packageName = "x".repeat(513))),
                (0..16).map { a.copy(packageName = "owner.p$it") },
            )
        invalid.forEach { assertNull(UidOwnerFingerprint.create(10_001, 7, it)) }
        assertNull(UidOwnerFingerprint.create(-1, 7, listOf(a)))
        assertNull(UidOwnerFingerprint.create(10_001, -1, listOf(a)))
    }

    @Test
    fun `only exact supported fingerprints and complete scope are known`() {
        val valid = requireNotNull(UidOwnerFingerprint.create(10_001, 7, listOf(a)))
        assertEquals(valid, UidOwnerFingerprint.known(valid))
        listOf(null, "", valid.uppercase(), valid + " ", valid.replace("v1:", "v2:"), "v1:" + "g".repeat(64)).forEach {
            assertNull(UidOwnerFingerprint.known(it))
        }
        assertTrue(UidIdentityScope(0, 7, "reader", 0).covers(10_001))
        assertTrue(!UidIdentityScope(0, 7, "reader", 0).covers(110_001))
        assertTrue(!UidIdentityScope(0, 7, "", 0).isKnown())
    }
}
