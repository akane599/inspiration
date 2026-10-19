package com.akane.inspiration.di

import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import com.akane.inspiration.telemetry.app.AppLabels
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where labels and icons come from; the production one reads `PackageManager`, tests use a fake. */
interface AppLabelSource {
    /** The launcher label, or null when the package is not installed (or not visible). */
    fun loadLabel(packageName: String): String?

    /** The launcher icon, or null when the package is not installed (or not visible). */
    fun loadIcon(packageName: String): Bitmap?

    /** The visible packages running as [uid], in any order; empty when none is. */
    fun loadPackagesForUid(uid: Int): List<String>
}

class PackageManagerAppLabelSource(
    private val packageManager: PackageManager,
) : AppLabelSource {
    @Suppress("DEPRECATION") // The flags overload needs API 33; this call is identical for flags 0 on API 31+.
    override fun loadLabel(packageName: String): String? =
        try {
            packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    override fun loadIcon(packageName: String): Bitmap? =
        try {
            packageManager.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    override fun loadPackagesForUid(uid: Int): List<String> = packageManager.getPackagesForUid(uid)?.toList().orEmpty()

    private companion object {
        const val ICON_PX = 128
    }
}

/**
 * [AppLabels] with bounded in-memory LRUs for cosmetic labels and icons (H15). A package the source does
 * not know is cached too (label = the package name, no icon). UID ownership is read fresh on every lookup.
 * Loads run on [ioDispatcher]; a cosmetic cache hit returns without switching context.
 */
class CachedAppLabels(
    private val source: AppLabelSource,
    maxLabels: Int = DEFAULT_MAX_LABELS,
    maxIcons: Int = DEFAULT_MAX_ICONS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AppLabels {
    private val labels = Lru<Resolved<String>>(maxLabels)
    private val icons = Lru<Resolved<Bitmap>>(maxIcons)

    override suspend fun label(packageName: String): String {
        labels.get(packageName)?.let { return it.value ?: packageName }
        val loaded = withContext(ioDispatcher) { source.loadLabel(packageName) }
        labels.put(packageName, Resolved(loaded))
        return loaded ?: packageName
    }

    override suspend fun icon(packageName: String): Bitmap? {
        icons.get(packageName)?.let { return it.value }
        val loaded = withContext(ioDispatcher) { source.loadIcon(packageName) }
        icons.put(packageName, Resolved(loaded))
        return loaded
    }

    /** Current visible owners, distinct and sorted by name; membership is never cached. */
    override suspend fun packagesForUid(uid: Int): List<String> {
        if (uid % PER_USER_UID_RANGE < FIRST_APPLICATION_UID) return emptyList() // shared platform UIDs own no single app
        return withContext(ioDispatcher) {
            source.loadPackagesForUid(uid).distinct().sorted()
        }
    }

    /** A cached lookup; [value] null is a cached "not found". */
    private class Resolved<V : Any>(
        val value: V?,
    )

    private class Lru<V : Any>(
        private val maxEntries: Int,
    ) {
        private val map =
            object : LinkedHashMap<String, V>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>): Boolean = size > maxEntries
            }

        @Synchronized
        fun get(key: String): V? = map[key]

        @Synchronized
        fun put(
            key: String,
            value: V,
        ) {
            map[key] = value
        }
    }

    private companion object {
        const val DEFAULT_MAX_LABELS = 512
        const val DEFAULT_MAX_ICONS = 96
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
        const val PER_USER_UID_RANGE = 100_000
        const val FIRST_APPLICATION_UID = 10_000
    }
}
