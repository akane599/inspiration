package com.akane.inspiration.di

import com.akane.inspiration.telemetry.app.AppEntry
import java.text.Collator
import java.util.Locale

/**
 * Builds the Toolkit app picker's installed list: launchable packages only. The screen adds the restricted ones
 * (listed even without a launcher so they can be restored, H15) from its own override read (F-19).
 */
object AppEntries {
    /**
     * @param launchable packages with a launcher activity
     * @param excluded packages that are never offered (our own, the Shizuku server)
     */
    suspend fun build(
        launchable: Set<String>,
        excluded: Set<String>,
        locale: Locale,
        label: suspend (String) -> String,
    ): List<AppEntry> {
        val collator = Collator.getInstance(locale)
        return launchable
            .filter { it !in excluded }
            .map { pkg -> AppEntry(packageName = pkg, label = label(pkg)) }
            .sortedWith(compareBy<AppEntry, String>(collator) { it.label }.thenBy { it.packageName })
    }
}
