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
    }

    private val source = FakeSource(mapOf("a.app" to "Alpha", "b.app" to "Beta", "c.app" to "Gamma"))

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
