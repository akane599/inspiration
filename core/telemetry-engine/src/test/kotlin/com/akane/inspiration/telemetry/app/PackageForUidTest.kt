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
                )

            override suspend fun label(packageName: String) = packageName

            override suspend fun icon(packageName: String): Bitmap? = null

            override suspend fun packagesForUid(uid: Int): List<String> = owners[uid].orEmpty()
        }

    @Test
    fun `a process name of one of the uid's packages names that package`() =
        runTest {
            assertEquals("com.example.messenger", labels.packageForUid(10_100, reported = "com.example.messenger:remote"))
            assertEquals("com.example.messenger", labels.packageForUid(10_100, reported = "com.example.messenger"))
            assertEquals("com.google.android.gms.policy", labels.packageForUid(10_200, reported = "com.google.android.gms.policy:ui"))
        }

    @Test
    fun `a name the uid does not own falls back to its alphabetically first package`() =
        runTest {
            assertEquals("com.google.android.gms", labels.packageForUid(10_200, reported = "com.google.android.gms.persistent"))
            assertEquals("com.google.android.gms", labels.packageForUid(10_200, reported = "com.other.app"))
            assertEquals("com.google.android.gms", labels.packageForUid(10_200, reported = null))
        }

    @Test
    fun `a uid no package runs as has no package, whatever batterystats reported`() =
        runTest {
            assertNull(labels.packageForUid(10_300, reported = "com.example.gone:push"))
            assertNull(labels.packageForUid(1_000, reported = "android"))
            assertNull(labels.packageForUid(10_300, reported = null))
        }
}
