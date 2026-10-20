package com.akane.inspiration.di

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/** Every broadcast after which a package's label or icon may differ, or the package may be gone or back. */
internal val PACKAGE_CHANGE_ACTIONS =
    listOf(
        Intent.ACTION_PACKAGE_ADDED,
        Intent.ACTION_PACKAGE_REPLACED,
        Intent.ACTION_PACKAGE_CHANGED,
        Intent.ACTION_PACKAGE_REMOVED,
    )

/** The data scheme of [PACKAGE_CHANGE_ACTIONS]: `package:<name>`. */
internal const val PACKAGE_SCHEME = "package"

/**
 * The package a broadcast says changed, from its [action] and its data URI's [scheme] and [schemeSpecificPart]; null
 * for any other broadcast or one without a package name.
 */
internal fun changedPackage(
    action: String?,
    scheme: String?,
    schemeSpecificPart: String?,
): String? = schemeSpecificPart?.takeIf { action in PACKAGE_CHANGE_ACTIONS && scheme == PACKAGE_SCHEME && it.isNotBlank() }

/**
 * Registers one receiver for [PACKAGE_CHANGE_ACTIONS] on the application context, for the life of the process, and
 * calls [onPackageChanged] with each changed package (B-18). Not exported: these broadcasts come from the system,
 * which reaches a not-exported receiver; other apps cannot. Call it once per cache it keeps current.
 */
internal fun registerPackageChangeReceiver(
    context: Context,
    onPackageChanged: (String) -> Unit,
) {
    val filter =
        IntentFilter().apply {
            PACKAGE_CHANGE_ACTIONS.forEach(::addAction)
            addDataScheme(PACKAGE_SCHEME)
        }
    ContextCompat.registerReceiver(
        context.applicationContext,
        PackageChangeReceiver(onPackageChanged),
        filter,
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
}

private class PackageChangeReceiver(
    private val onPackageChanged: (String) -> Unit,
) : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val data = intent.data
        changedPackage(intent.action, data?.scheme, data?.schemeSpecificPart)?.let(onPackageChanged)
    }
}
