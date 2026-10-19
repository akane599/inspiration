package com.akane.inspiration.telemetry.source

import android.content.pm.ApplicationInfo
import android.content.pm.ChangedPackages
import android.content.pm.PackageInfo
import android.os.Process
import com.akane.inspiration.telemetry.snapshot.UidOwnerInstall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [AndroidUidIdentityBackendTest.SequenceShadow::class])
class AndroidUidIdentityBackendTest {
    @Test
    fun `context user namespace comes from current process full uid using public APIs`() {
        val originalUid = Process.myUid()
        try {
            ShadowProcess.setUid(10_001)
            assertEquals(0, AndroidUidIdentityBackend(RuntimeEnvironment.getApplication()).userId)
            ShadowProcess.setUid(110_001)
            assertEquals(1, AndroidUidIdentityBackend(RuntimeEnvironment.getApplication()).userId)
        } finally {
            ShadowProcess.setUid(originalUid)
        }
    }

    @Test
    fun `API null succeeds as zero but a nonnull empty change list retains its sequence`() {
        val backend = AndroidUidIdentityBackend(RuntimeEnvironment.getApplication())
        SequenceShadow.changes = null
        assertEquals(0, backend.packageChangeSequence())
        SequenceShadow.changes = ChangedPackages(9, emptyList())
        assertEquals(9, backend.packageChangeSequence())
    }

    @Test
    fun `actual API null passes blocking bridge but API exception is unknown not zero`() =
        runBlocking {
            val android = AndroidUidIdentityBackend(RuntimeEnvironment.getApplication())
            val backend =
                object : UidIdentityBackend by android {
                    override fun userSerial(): Long = 7
                }
            val reader = UidIdentityReader(backend, openingBudgetMs = 1_000, closingBudgetMs = 1_000)
            SequenceShadow.changes = null
            SequenceShadow.fail = false
            val known = requireNotNull(reader.close(reader.open(), emptySet()))
            assertEquals(0, known.scope.packageChangeSequence)
            try {
                SequenceShadow.fail = true
                assertNull(reader.open())
            } finally {
                SequenceShadow.fail = false
            }
            val fresh = requireNotNull(reader.close(reader.open(), emptySet()))
            assertNotEquals(known.scope.readerEpoch, fresh.scope.readerEpoch)
        }

    @Test
    fun `ownership reads are fresh full uid and first install proof rather than cached labels`() {
        val context = RuntimeEnvironment.getApplication()
        val backend = AndroidUidIdentityBackend(context)

        fun install(time: Long) {
            shadowOf(context.packageManager).installPackage(
                PackageInfo().apply {
                    packageName = "test.owner"
                    firstInstallTime = time
                    applicationInfo =
                        ApplicationInfo().apply {
                            packageName = "test.owner"
                            uid = 12345
                        }
                },
            )
        }
        install(123)
        assertEquals(listOf("test.owner"), backend.packagesForUid(12345))
        assertEquals(UidOwnerInstall("test.owner", 12345, 123), backend.packageInfo("test.owner"))
        install(456)
        assertEquals(456L, backend.packageInfo("test.owner")!!.firstInstallTimeMs)
        assertNull(backend.packageInfo("missing.owner"))
    }

    @Test
    fun `bulk installed owners carry each package full uid and first install time`() {
        val context = RuntimeEnvironment.getApplication()
        for ((name, time) in listOf("shared.a" to 123L, "shared.b" to 456L, "single.c" to 789L)) {
            shadowOf(context.packageManager).installPackage(
                PackageInfo().apply {
                    packageName = name
                    firstInstallTime = time
                    applicationInfo =
                        ApplicationInfo().apply {
                            packageName = name
                            uid = if (name.startsWith("shared")) 10_123 else 10_124
                        }
                },
            )
        }
        val owners = requireNotNull(AndroidUidIdentityBackend(context).installedOwners())
        assertTrue(
            owners.containsAll(
                listOf(
                    UidOwnerInstall("shared.a", 10_123, 123),
                    UidOwnerInstall("shared.b", 10_123, 456),
                    UidOwnerInstall("single.c", 10_124, 789),
                ),
            ),
        )
    }

    @Implements(className = "android.app.ApplicationPackageManager")
    class SequenceShadow : ShadowApplicationPackageManager() {
        @Implementation
        override fun getChangedPackages(sequenceNumber: Int): Any? {
            if (fail) throw SecurityException("test sequence service failure")
            return changes
        }

        companion object {
            var changes: ChangedPackages? = null
            var fail = false
        }
    }
}
