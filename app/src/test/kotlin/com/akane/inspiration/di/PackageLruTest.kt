package com.akane.inspiration.di

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class PackageLruTest {
    private val loads = mutableListOf<String>()

    private suspend fun PackageLru<String>.read(
        key: String,
        value: String? = "v:$key",
    ): String? =
        getOrLoad(key) {
            loads += key
            value
        }

    @Test
    fun `a found value is loaded once and then served from memory`() =
        runTest {
            val lru = PackageLru<String>(4)

            assertEquals("v:a", lru.read("a"))
            assertEquals("v:a", lru.read("a", value = "other"))
            assertEquals(listOf("a"), loads)
        }

    @Test
    fun `a load that finds nothing is not cached`() =
        runTest {
            val lru = PackageLru<String>(4)

            assertNull(lru.read("gone", value = null))
            assertEquals("v:gone", lru.read("gone"))
            assertEquals("v:gone", lru.read("gone"))
            assertEquals(listOf("gone", "gone"), loads)
        }

    @Test
    fun `the least recently used value is evicted at the bound`() =
        runTest {
            val lru = PackageLru<String>(2)
            lru.read("a")
            lru.read("b")
            lru.read("a") // a is now the most recently used
            lru.read("c") // evicts b

            loads.clear()
            lru.read("a")
            lru.read("c")
            assertEquals("a and c are still cached", emptyList<String>(), loads)

            lru.read("b")
            assertEquals("b was evicted and reloads", listOf("b"), loads)
        }

    @Test
    fun `invalidate drops only that package`() =
        runTest {
            val lru = PackageLru<String>(4)
            lru.read("a")
            lru.read("b")

            lru.invalidate("a")
            lru.invalidate("never.cached")

            assertEquals("new:a", lru.read("a", value = "new:a"))
            assertEquals("v:b", lru.read("b"))
            assertEquals(listOf("a", "b", "a"), loads)
        }

    @Test
    fun `a load that overlaps any invalidation is returned but not cached`() =
        runTest {
            val lru = PackageLru<String>(4)

            val read =
                lru.getOrLoad("a") {
                    // Which package changed is not known to the load in flight, so any change voids it.
                    lru.invalidate("b")
                    "old:a"
                }

            assertEquals("old:a", read)
            assertEquals("the next lookup reads again", "new:a", lru.read("a", value = "new:a"))
            assertEquals("and that read is kept", "new:a", lru.read("a", value = "newer:a"))
            assertEquals(listOf("a"), loads)
        }

    @Test
    fun `a failing load caches nothing and the next lookup reads again`() =
        runTest {
            val lru = PackageLru<String>(4)

            try {
                lru.getOrLoad("a") { throw IllegalStateException("package manager died") }
                fail("the failure must reach the caller")
            } catch (expected: IllegalStateException) {
                assertEquals("package manager died", expected.message)
            }

            assertEquals("v:a", lru.read("a"))
            assertEquals(listOf("a"), loads)
        }
}
