package com.akane.inspiration.di

import android.content.Intent
import android.graphics.Bitmap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PackageChangesTest {
    @Test
    fun `installs, updates, changes and removals each name their package`() {
        assertEquals(
            listOf(
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REPLACED,
                Intent.ACTION_PACKAGE_CHANGED,
                Intent.ACTION_PACKAGE_REMOVED,
            ),
            PACKAGE_CHANGE_ACTIONS,
        )
        for (action in PACKAGE_CHANGE_ACTIONS) {
            assertEquals(action, "com.example.chat", changedPackage(action, "package", "com.example.chat"))
        }
    }

    @Test
    fun `other broadcasts, other schemes and nameless data change nothing`() {
        assertNull(changedPackage(Intent.ACTION_PACKAGE_DATA_CLEARED, "package", "com.example.chat"))
        assertNull(changedPackage(Intent.ACTION_SCREEN_ON, "package", "com.example.chat"))
        assertNull(changedPackage(null, "package", "com.example.chat"))
        assertNull(changedPackage(Intent.ACTION_PACKAGE_ADDED, "file", "/sdcard/x"))
        assertNull(changedPackage(Intent.ACTION_PACKAGE_ADDED, null, "com.example.chat"))
        assertNull(changedPackage(Intent.ACTION_PACKAGE_ADDED, "package", null))
        assertNull(changedPackage(Intent.ACTION_PACKAGE_ADDED, "package", " "))
    }

    @Test
    fun `an update broadcast makes the next lookup read the renamed app's label`() =
        runTest {
            val names = mutableMapOf("com.example.chat" to "Chat")
            val reads = mutableListOf<String>()
            val source =
                object : AppLabelSource {
                    override fun loadLabel(packageName: String): String? = names[packageName].also { reads += packageName }

                    override fun loadIcon(packageName: String): Bitmap? = null

                    override fun loadPackagesForUid(uid: Int): List<String> = emptyList()
                }
            val labels = CachedAppLabels(source, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
            val onPackageChanged: (String) -> Unit = labels::invalidate
            assertEquals("Chat", labels.label("com.example.chat"))
            assertEquals("Chat", labels.label("com.example.chat"))

            names["com.example.chat"] = "Chat Pro"
            changedPackage(Intent.ACTION_PACKAGE_REPLACED, "package", "com.example.chat")?.let(onPackageChanged)

            assertEquals("Chat Pro", labels.label("com.example.chat"))
            assertEquals(listOf("com.example.chat", "com.example.chat"), reads)
        }
}
