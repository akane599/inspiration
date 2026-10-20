package com.akane.inspiration.telemetry.app

import android.graphics.Bitmap
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one rule that turns batterystats' reported process name into the package that identifies a UID. */
class PackageForUidTest {
    private val labels =
        object : AppLabels {
            val owners =
                mapOf(
                    10_100 to listOf("com.example.messenger"),
                    10_200 to listOf("com.google.android.gsf", "com.google.android.gms", "com.google.android.gms.policy"),
                    // The media UID as the redroid device reports it (android:sharedUserId="android.media").
                    10_011 to
                        listOf(
                            "com.android.providers.media",
                            "com.android.providers.downloads",
                            "com.android.mtp",
                            "com.android.providers.downloads.ui",
                            "com.android.soundpicker",
                        ),
                    // A source that lists one package twice.
                    10_400 to listOf("com.example.a", "com.example.b", "com.example.a"),
                )

            override suspend fun label(packageName: String) = packageName

            override suspend fun icon(packageName: String): Bitmap? = null

            override suspend fun packagesForUid(uid: Int): List<String> = owners[uid].orEmpty()
        }

    @Test
    fun `a process name of one of the uid's packages names that package`() =
        runTest {
            assertEquals("com.example.messenger", labels.uidIdentity(10_100, reported = "com.example.messenger:remote").packageName)
            assertEquals("com.example.messenger", labels.uidIdentity(10_100, reported = "com.example.messenger").packageName)
            assertEquals(
                "com.google.android.gms.policy",
                labels.uidIdentity(10_200, reported = "com.google.android.gms.policy:ui").packageName,
            )
        }

    @Test
    fun `a process an owner names after itself is named by that owner, the longest one first`() =
        runTest {
            assertEquals("com.google.android.gms", labels.uidIdentity(10_200, reported = "com.google.android.gms.persistent").packageName)
            assertEquals(
                "com.google.android.gms.policy",
                labels.uidIdentity(10_200, reported = "com.google.android.gms.policy.sync").packageName,
            )
            assertEquals("com.google.android.gms", labels.uidIdentity(10_200, reported = "com.google.android.gms.unstable:ui").packageName)
        }

    @Test
    fun `a uid one package runs as is named by it, whatever batterystats reported`() =
        runTest {
            assertEquals("com.example.messenger", labels.uidIdentity(10_100, reported = "com.other.app").packageName)
            assertEquals("com.example.messenger", labels.uidIdentity(10_100, reported = null).packageName)
        }

    @Test
    fun `a shared uid whose reported process names none of its packages has no package to name or act on`() =
        runTest {
            // android.process.media is the media UID's process: never "MTP Host", the alphabetically first owner.
            assertNull(labels.uidIdentity(10_011, reported = "android.process.media").packageName)
            assertNull(labels.uidIdentity(10_200, reported = "com.google.process.gapps").packageName)
            assertNull(labels.uidIdentity(10_200, reported = "com.other.app").packageName)
            assertNull(labels.uidIdentity(10_200, reported = null).packageName)
            // A dotted prefix of an owner is not one of its processes.
            assertNull(labels.uidIdentity(10_200, reported = "com.google.android").packageName)
        }

    @Test
    fun `the rule over a uid's owners is the one uidIdentity applies`() {
        val owners = listOf("com.android.mtp", "com.android.providers.media")
        assertEquals("com.android.providers.media", identifyingPackage(owners, "com.android.providers.media:scan"))
        assertNull(identifyingPackage(owners, "android.process.media"))
        assertEquals("com.android.mtp", identifyingPackage(listOf("com.android.mtp"), "android.process.media"))
        assertNull(identifyingPackage(emptyList(), "com.example.gone"))
    }

    @Test
    fun `a shared uid no owner names says how many apps share it, so every screen words it the same`() =
        runTest {
            assertEquals(UidIdentity(null, sharedBy = 5), labels.uidIdentity(10_011, reported = "android.process.media"))
            assertEquals(UidIdentity(null, sharedBy = 3), labels.uidIdentity(10_200, reported = "com.google.process.gapps"))
            assertEquals("a package listed twice is one app", UidIdentity(null, sharedBy = 2), labels.uidIdentity(10_400, reported = null))
        }

    @Test
    fun `a named uid, a single-owner uid and an unowned uid count no sharers`() =
        runTest {
            assertEquals(UidIdentity("com.google.android.gms"), labels.uidIdentity(10_200, reported = "com.google.android.gms.persistent"))
            assertEquals(UidIdentity("com.example.messenger"), labels.uidIdentity(10_100, reported = "com.other.app"))
            assertEquals(UidIdentity(null, sharedBy = 0), labels.uidIdentity(10_300, reported = "com.example.gone:push"))
        }

    @Test
    fun `a uid no package runs as has no package, whatever batterystats reported`() =
        runTest {
            assertNull(labels.uidIdentity(10_300, reported = "com.example.gone:push").packageName)
            assertNull(labels.uidIdentity(1_000, reported = "android").packageName)
            assertNull(labels.uidIdentity(10_300, reported = null).packageName)
        }
}
