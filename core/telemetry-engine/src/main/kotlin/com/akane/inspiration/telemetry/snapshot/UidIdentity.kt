package com.akane.inspiration.telemetry.snapshot

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Evidence is comparable only within one retained reader, user incarnation and package sequence. */
data class UidIdentityScope(
    val userId: Int,
    val userSerial: Long,
    val readerEpoch: String,
    val packageChangeSequence: Int,
) {
    fun isKnown(): Boolean = userId >= 0 && userSerial >= 0 && readerEpoch.isNotBlank() && packageChangeSequence >= 0

    fun covers(uid: Int): Boolean = uid >= 0 && uid / UID_USER_RANGE == userId
}

/** Full UID is retained even when two users have packages with identical names and install times. */
data class UidOwnerInstall(
    val packageName: String,
    val uid: Int,
    val firstInstallTimeMs: Long,
)

data class UidIdentityProof(
    val scope: UidIdentityScope,
    val fingerprintsByUid: Map<Int, String>,
)

internal const val UID_USER_RANGE = 100_000

internal fun isApplicationUid(uid: Int): Boolean = uid >= 0 && uid % UID_USER_RANGE >= 10_000

/** v1 is binary, length-delimited and locale-independent; unknown versions are never comparable. */
object UidOwnerFingerprint {
    const val MAX_OWNERS = 16
    const val MAX_NAME_BYTES = 512
    private val format = Regex("v1:[0-9a-f]{64}")
    private val packageNameFormat = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")

    fun validPackageName(value: String): Boolean =
        value.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES && packageNameFormat.matches(value)

    fun known(value: String?): String? = value?.takeIf { format.matches(it) }

    fun create(
        uid: Int,
        userSerial: Long,
        owners: List<UidOwnerInstall>,
    ): String? {
        if (uid < 0 || userSerial < 0 || owners.isEmpty() || owners.size > MAX_OWNERS) return null
        if (owners.map { it.packageName }.distinct().size != owners.size) return null
        if (owners.any {
                it.uid != uid || it.firstInstallTimeMs <= 0 || !validPackageName(it.packageName)
            }
        ) {
            return null
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            val domain = "inspiration.uid-owner".toByteArray(Charsets.UTF_8)
            out.writeInt(domain.size)
            out.write(domain)
            out.writeInt(1)
            out.writeInt(uid)
            out.writeLong(userSerial)
            out.writeInt(owners.size)
            owners.sortedBy { it.packageName }.forEach { owner ->
                val name = owner.packageName.toByteArray(Charsets.UTF_8)
                out.writeInt(name.size)
                out.write(name)
                out.writeLong(owner.firstInstallTimeMs)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
        return "v1:" + digest.joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 0xff) }
    }
}
