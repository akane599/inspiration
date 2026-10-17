package com.akane.inspiration.di

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppEntriesTest {
    private val labels = mapOf(
        "z.mail" to "Mail",
        "a.maps" to "maps",
        "b.sync" to "Background sync",
        "own.app" to "Inspiration",
        "moe.shizuku.privileged.api" to "Shizuku",
    )

    private suspend fun build(launchable: Set<String>, restricted: Set<String>) = AppEntries.build(
        launchable = launchable,
        restricted = restricted,
        excluded = setOf("own.app", "moe.shizuku.privileged.api"),
        locale = Locale.ENGLISH,
    ) { labels.getValue(it) }

    @Test
    fun `launchable and restricted packages are listed once, sorted by label ignoring case`() = runTest {
        val entries = build(
            launchable = setOf("z.mail", "a.maps", "b.sync"),
            restricted = setOf("b.sync"),
        )

        assertEquals(listOf("b.sync", "z.mail", "a.maps"), entries.map { it.packageName })
        val sync = entries.first { it.packageName == "b.sync" }
        assertEquals(true, sync.launchable)
        assertEquals(true, sync.restricted)
        assertEquals(false, entries.first { it.packageName == "z.mail" }.restricted)
    }

    @Test
    fun `a restricted app without a launcher is still offered so it can be restored`() = runTest {
        val entries = build(launchable = setOf("z.mail"), restricted = setOf("b.sync"))

        val sync = entries.first { it.packageName == "b.sync" }
        assertEquals(false, sync.launchable)
        assertEquals(true, sync.restricted)
    }

    @Test
    fun `our own package and the Shizuku server are never offered`() = runTest {
        val entries = build(
            launchable = setOf("z.mail", "own.app", "moe.shizuku.privileged.api"),
            restricted = setOf("own.app"),
        )

        assertEquals(listOf("z.mail"), entries.map { it.packageName })
    }
}
