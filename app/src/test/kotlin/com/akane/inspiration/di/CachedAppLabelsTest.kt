package com.akane.inspiration.di

import android.graphics.Bitmap
import com.akane.inspiration.telemetry.app.attempt
import com.akane.inspiration.telemetry.app.packageForUid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CachedAppLabelsTest {
    private class FakeSource(
        private val labels: Map<String, String>,
    ) : AppLabelSource {
        val labelLoads = mutableListOf<String>()
        val iconLoads = mutableListOf<String>()

        override fun loadLabel(packageName: String): String? {
            labelLoads += packageName
            return labels[packageName]
        }

        // A Bitmap cannot be built on the JVM; null icons still exercise the cache path.
        override fun loadIcon(packageName: String): Bitmap? {
            iconLoads += packageName
            return null
        }

        val uidPackages = mutableMapOf<Int, List<String>>()
        val uidLoads = mutableListOf<Int>()
        var uidFailure: Exception? = null

        override fun loadPackagesForUid(uid: Int): List<String> {
            uidLoads += uid
            uidFailure?.let { throw it }
            return uidPackages[uid].orEmpty()
        }
    }

    private val source = FakeSource(mapOf("a.app" to "Alpha", "b.app" to "Beta", "c.app" to "Gamma"))

    @Test
    fun `an app uid lists fresh distinct sorted packages on every lookup, an unowned uid included`() =
        runTest {
            source.uidPackages[10_221] = listOf("a.app")
            source.uidPackages[10_300] = listOf("z.shared", "m.shared", "q.shared", "m.shared")
            source.uidPackages[1_010_221] = listOf("b.app")
            val labels = labels()

            assertEquals(listOf("a.app"), labels.packagesForUid(10_221))
            assertEquals(listOf("m.shared", "q.shared", "z.shared"), labels.packagesForUid(10_300))
            assertEquals("a secondary user's full uid resolves too", listOf("b.app"), labels.packagesForUid(1_010_221))
            assertEquals(emptyList<String>(), labels.packagesForUid(10_999))
            assertEquals(listOf("a.app"), labels.packagesForUid(10_221))
            assertEquals(emptyList<String>(), labels.packagesForUid(10_999))
            assertEquals(listOf(10_221, 10_300, 1_010_221, 10_999, 10_221, 10_999), source.uidLoads)
        }

    @Test
    fun `a previously unowned uid resolves a newly installed owner on the same resolver`() =
        runTest {
            val labels = labels()
            assertNull(labels.packageForUid(10_999, reported = "new.app:remote"))

            source.uidPackages[10_999] = listOf("new.app")

            assertEquals("new.app", labels.packageForUid(10_999, reported = "new.app:remote"))
            assertEquals(listOf(10_999, 10_999), source.uidLoads)
        }

    @Test
    fun `removing a shared owner refreshes fallback and preserves a surviving hint on the same resolver`() =
        runTest {
            source.uidPackages[10_300] = listOf("z.shared", "m.shared", "q.shared")
            val labels = labels()
            assertEquals("m.shared", labels.packageForUid(10_300, reported = null))
            assertEquals("z.shared", labels.packageForUid(10_300, reported = "z.shared:push"))

            source.uidPackages[10_300] = listOf("z.shared", "q.shared")

            assertEquals("q.shared", labels.packageForUid(10_300, reported = null))
            assertEquals("q.shared", labels.packageForUid(10_300, reported = "m.shared:push"))
            assertEquals("z.shared", labels.packageForUid(10_300, reported = "z.shared:push"))
            assertEquals(List(5) { 10_300 }, source.uidLoads)
        }

    @Test
    fun `shared platform uids own no package and never ask the source`() =
        runTest {
            source.uidPackages[1_000] = listOf("android", "com.android.settings")
            source.uidPackages[9_999] = listOf("platform.app")
            source.uidPackages[1_001_000] = listOf("android")
            source.uidPackages[1_009_999] = listOf("platform.app")
            val labels = labels()

            assertEquals(emptyList<String>(), labels.packagesForUid(0))
            assertEquals(emptyList<String>(), labels.packagesForUid(1_000))
            assertEquals(emptyList<String>(), labels.packagesForUid(9_999))
            assertEquals(emptyList<String>(), labels.packagesForUid(1_001_000))
            assertEquals(emptyList<String>(), labels.packagesForUid(1_009_999))
            assertNull("a platform uid is named by the uid, not by a reported package", labels.packageForUid(1_000, reported = "android"))
            assertEquals(emptyList<Int>(), source.uidLoads)
        }

    @Test
    fun `a reported process name names its uid only through the uid's installed packages`() =
        runTest {
            source.uidPackages[10_300] = listOf("z.shared", "m.shared", "q.shared")
            val labels = labels()

            assertEquals("q.shared", labels.packageForUid(10_300, reported = "q.shared:push"))
            assertEquals("not installed under this uid", "m.shared", labels.packageForUid(10_300, reported = "q.shared.persistent"))
            assertEquals("m.shared", labels.packageForUid(10_300, reported = null))
            assertNull("an unowned uid keeps no name", labels.packageForUid(10_999, reported = "gone.app:remote"))
            assertEquals("one PackageManager read per lookup", listOf(10_300, 10_300, 10_300, 10_999), source.uidLoads)
        }

    @Test
    fun `every ownership lookup dispatches source reads to the supplied IO dispatcher`() =
        runTest {
            source.uidPackages[10_221] = listOf("a.app")
            val labels = CachedAppLabels(source, ioDispatcher = StandardTestDispatcher(testScheduler))

            repeat(2) { previousLoads ->
                val lookup = async(start = CoroutineStart.UNDISPATCHED) { labels.packagesForUid(10_221) }

                assertFalse("the lookup must wait for the IO dispatcher", lookup.isCompleted)
                assertEquals(List(previousLoads) { 10_221 }, source.uidLoads)
                runCurrent()
                assertEquals(listOf("a.app"), lookup.await())
            }
            assertEquals(listOf(10_221, 10_221), source.uidLoads)
        }

    @Test
    fun `a failed ownership lookup remains containable and the next lookup retries the source`() =
        runTest {
            source.uidFailure = SecurityException("PackageManager unavailable")
            val labels = labels()

            assertNull(attempt { labels.packageForUid(10_221, reported = "a.app") })

            source.uidFailure = null
            source.uidPackages[10_221] = listOf("a.app")
            assertEquals("a.app", labels.packageForUid(10_221, reported = "a.app"))
            assertEquals(listOf(10_221, 10_221), source.uidLoads)
        }

    @Test
    fun `ownership lookup cancellation propagates through the caller containment boundary`() =
        runTest {
            val cancellation = CancellationException("cancelled lookup")
            source.uidFailure = cancellation
            val labels = labels()

            try {
                attempt { labels.packagesForUid(10_221) }
                fail("cancellation must propagate")
            } catch (actual: CancellationException) {
                assertEquals(cancellation.message, actual.message)
            }
            assertEquals(listOf(10_221), source.uidLoads)
        }

    private fun TestScope.labels(
        maxLabels: Int = 8,
        maxIcons: Int = 8,
    ) = CachedAppLabels(source, maxLabels, maxIcons, UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `a label is loaded once and then served from memory`() =
        runTest {
            val labels = labels()

            assertEquals("Alpha", labels.label("a.app"))
            assertEquals("Alpha", labels.label("a.app"))
            assertEquals(listOf("a.app"), source.labelLoads)
        }

    @Test
    fun `an unknown package is labelled with its name and the miss is cached too`() =
        runTest {
            val labels = labels()

            assertEquals("gone.app", labels.label("gone.app"))
            assertEquals("gone.app", labels.label("gone.app"))
            assertEquals(listOf("gone.app"), source.labelLoads)
        }

    @Test
    fun `the least recently used label is evicted first`() =
        runTest {
            val labels = labels(maxLabels = 2)
            labels.label("a.app")
            labels.label("b.app")
            labels.label("a.app") // a is now the most recently used
            labels.label("c.app") // evicts b

            source.labelLoads.clear()
            labels.label("a.app")
            labels.label("c.app")
            assertEquals("a and c are still cached", emptyList<String>(), source.labelLoads)

            labels.label("b.app")
            assertEquals("b was evicted and reloads", listOf("b.app"), source.labelLoads)
        }

    @Test
    fun `icons are cached separately from labels and a missing icon is cached as null`() =
        runTest {
            val labels = labels()

            assertNull(labels.icon("a.app"))
            assertNull(labels.icon("a.app"))
            assertEquals(listOf("a.app"), source.iconLoads)

            labels.label("a.app")
            assertEquals("a label load does not touch the icon cache", listOf("a.app"), source.iconLoads)
        }

    @Test
    fun `the least recently used icon miss is evicted at the separate icon bound`() =
        runTest {
            val labels = labels(maxLabels = 8, maxIcons = 2)
            labels.icon("a.app")
            labels.icon("b.app")
            labels.icon("a.app")
            labels.icon("c.app") // evicts b

            source.iconLoads.clear()
            labels.icon("a.app")
            labels.icon("c.app")
            assertEquals("a and c are still cached", emptyList<String>(), source.iconLoads)

            labels.icon("b.app")
            assertEquals("b was evicted and reloads", listOf("b.app"), source.iconLoads)
        }
}
