package com.akane.inspiration.di

import com.akane.inspiration.telemetry.app.AppEntry
import java.text.Collator
import java.util.Locale

/** Builds the Toolkit app picker: launchable packages plus the ones the user already restricted (H15). */
object AppEntries {
    /**
     * @param launchable packages with a launcher activity
     * @param restricted packages with a toolkit override still in effect; listed even without a launcher
     *   so they can be restored
     * @param excluded packages that are never offered (our own, the Shizuku server)
     */
    suspend fun build(
        launchable: Set<String>,
        restricted: Set<String>,
        excluded: Set<String>,
        locale: Locale,
        label: suspend (String) -> String,
    ): List<AppEntry> {
        val collator = Collator.getInstance(locale)
        return (launchable + restricted)
            .filter { it !in excluded }
            .map { pkg ->
                AppEntry(
                    packageName = pkg,
                    label = label(pkg),
                    launchable = pkg in launchable,
                    restricted = pkg in restricted,
                )
            }
            .sortedWith(compareBy<AppEntry, String>(collator) { it.label }.thenBy { it.packageName })
    }
}
