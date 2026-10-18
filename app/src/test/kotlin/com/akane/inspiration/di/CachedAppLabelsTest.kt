package com.akane.inspiration.di

import android.graphics.Bitmap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CachedAppLabelsTest {
    private class FakeSource(private val labels: Map<String, String>) : AppLabelSource {
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

        override fun loadPackagesForUid(uid: Int): List<String> {
            uidLoads += uid
            return uidPackages[uid].orEmpty()
        }
    }

    private val source = FakeSource(mapOf("a.app" to "Alpha", "b.app" to "Beta", "c.app" to "Gamma"))

    @Test
    fun `an app uid resolves to its package, the alphabetically first when shared, and is cached`() = runTest {
        source.uidPackages[10_221] = listOf("a.app")
        source.uidPackages[10_300] = listOf("z.shared", "m.shared", "q.shared")
        source.uidPackages[1_010_221] = listOf("b.app")
        val labels = labels()

        assertEquals("a.app", labels.packageForUid(10_221))
        assertEquals("m.shared", labels.packageForUid(10_300))
        assertEquals("a secondary user's app uid resolves too", "b.app", labels.packageForUid(1_010_221))
        assertNull("no owner is a cached miss", labels.packageForUid(10_999))
        assertEquals("a.app", labels.packageForUid(10_221))
        assertNull(labels.packageForUid(10_999))
        assertEquals(listOf(10_221, 10_300, 1_010_221, 10_999), source.uidLoads)
    }

    @Test
    fun `shared platform uids never resolve to a package and never ask the source`() = runTest {
        source.uidPackages[1_000] = listOf("android", "com.android.settings")
        val labels = labels()

        assertNull(labels.packageForUid(0))
        assertNull(labels.packageForUid(1_000))
        assertNull(labels.packageForUid(1_001_000))
        assertEquals(emptyList<Int>(), source.uidLoads)
    }

    private fun TestScope.labels(maxLabels: Int = 8, maxIcons: Int = 8) =
        CachedAppLabels(source, maxLabels, maxIcons, UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `a label is loaded once and then served from memory`() = runTest {
        val labels = labels()

        assertEquals("Alpha", labels.label("a.app"))
        assertEquals("Alpha", labels.label("a.app"))
        assertEquals(listOf("a.app"), source.labelLoads)
    }

    @Test
    fun `an unknown package is labelled with its name and the miss is cached too`() = runTest {
        val labels = labels()

        assertEquals("gone.app", labels.label("gone.app"))
        assertEquals("gone.app", labels.label("gone.app"))
        assertEquals(listOf("gone.app"), source.labelLoads)
    }

    @Test
    fun `the least recently used label is evicted first`() = runTest {
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
    fun `icons are cached separately from labels and a missing icon is cached as null`() = runTest {
        val labels = labels()

        assertNull(labels.icon("a.app"))
        assertNull(labels.icon("a.app"))
        assertEquals(listOf("a.app"), source.iconLoads)

        labels.label("a.app")
        assertEquals("a label load does not touch the icon cache", listOf("a.app"), source.iconLoads)
    }
}
