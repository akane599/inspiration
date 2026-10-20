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
 * [AppLabels] with bounded in-memory LRUs for cosmetic labels and icons (H15). Only what the source found is
 * cached: a package it does not know (not installed, or not yet) is labelled with its name, has no icon, and is
 * asked again on the next lookup, so a reinstalled app gets its label back at once (B-18). [invalidate] drops one
 * package's entries; the container calls it for every install, update, change and removal
 * ([registerPackageChangeReceiver]), so a renamed or updated app is read again. UID ownership is read fresh on every
 * lookup. Loads run on [ioDispatcher]; a cosmetic cache hit returns without switching context.
 */
class CachedAppLabels(
    private val source: AppLabelSource,
    maxLabels: Int = DEFAULT_MAX_LABELS,
    maxIcons: Int = DEFAULT_MAX_ICONS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AppLabels {
    private val labels = PackageLru<String>(maxLabels)
    private val icons = PackageLru<Bitmap>(maxIcons)

    override suspend fun label(packageName: String): String =
        labels.getOrLoad(packageName) { withContext(ioDispatcher) { source.loadLabel(packageName) } } ?: packageName

    override suspend fun icon(packageName: String): Bitmap? =
        icons.getOrLoad(packageName) { withContext(ioDispatcher) { source.loadIcon(packageName) } }

    /** Current visible owners, distinct and sorted by name; membership is never cached. */
    override suspend fun packagesForUid(uid: Int): List<String> {
        if (uid % PER_USER_UID_RANGE < FIRST_APPLICATION_UID) return emptyList() // shared platform UIDs own no single app
        return withContext(ioDispatcher) {
            source.loadPackagesForUid(uid).distinct().sorted()
        }
    }

    /** Forgets [packageName]'s label and icon, and keeps a read already in flight from caching the old ones. */
    fun invalidate(packageName: String) {
        labels.invalidate(packageName)
        icons.invalidate(packageName)
    }

    private companion object {
        const val DEFAULT_MAX_LABELS = 512
        const val DEFAULT_MAX_ICONS = 96
        const val PER_USER_UID_RANGE = 100_000
        const val FIRST_APPLICATION_UID = 10_000
    }
}

/**
 * A bounded least-recently-used map from package name to a found value. A load that finds nothing is not cached.
 * [invalidate] removes one package and voids every load still in flight, so a read that started before a package
 * change cannot store what it read: that read's caller still gets its answer, and the next lookup reads again.
 */
internal class PackageLru<V : Any>(
    private val maxEntries: Int,
) {
    private val map =
        object : LinkedHashMap<String, V>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>): Boolean = size > maxEntries
        }

    /** Bumped by every [invalidate]; a load stores its value only when no invalidation happened while it ran. */
    private var invalidations = 0L

    /** The cached value for [key], else what [load] finds (cached when not null); [load] runs outside the lock. */
    suspend fun getOrLoad(
        key: String,
        load: suspend () -> V?,
    ): V? {
        val startedAt =
            synchronized(this) {
                map[key]?.let { return it }
                invalidations
            }
        val loaded = load() ?: return null
        synchronized(this) {
            if (invalidations == startedAt) map[key] = loaded
        }
        return loaded
    }

    fun invalidate(key: String) {
        synchronized(this) {
            invalidations++
            map.remove(key)
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
