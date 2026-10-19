package com.akane.inspiration.di

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Restricted apps without a launcher are added by the Toolkit screen itself; ToolkitViewModelTest covers that. */
class AppEntriesTest {
    private val labels =
        mapOf(
            "z.mail" to "Mail",
            "a.maps" to "maps",
            "b.sync" to "Background sync",
            "own.app" to "Inspiration",
            "moe.shizuku.privileged.api" to "Shizuku",
        )

    private suspend fun build(launchable: Set<String>) =
        AppEntries.build(
            launchable = launchable,
            excluded = setOf("own.app", "moe.shizuku.privileged.api"),
            locale = Locale.ENGLISH,
        ) { labels.getValue(it) }

    @Test
    fun `launchable packages are listed once, sorted by label ignoring case`() =
        runTest {
            val entries = build(launchable = setOf("z.mail", "a.maps", "b.sync"))

            assertEquals(listOf("b.sync", "z.mail", "a.maps"), entries.map { it.packageName })
            assertEquals("Background sync", entries.first().label)
        }

    @Test
    fun `our own package and the Shizuku server are never offered`() =
        runTest {
            val entries = build(launchable = setOf("z.mail", "own.app", "moe.shizuku.privileged.api"))

            assertEquals(listOf("z.mail"), entries.map { it.packageName })
        }
}
