package com.akane.inspiration.telemetry.app

import android.graphics.Bitmap
import com.akane.inspiration.ipc.model.KernelBatterySnapshot
import com.akane.inspiration.telemetry.live.KernelUnits

/**
 * Outcome of `refresh()`: a FULL capture plus history ingest, debounced and mutex-guarded by the container.
 * Nothing here is an error to show as a crash; screens use it to decide between a spinner end, a hint and a retry.
 */
sealed interface RefreshResult {
    /** A capture was taken and stored at [wallMs]. */
    data class Refreshed(
        val wallMs: Long,
    ) : RefreshResult

    /** A refresh succeeded less than a minute ago ([lastRefreshWallMs]); nothing was captured now. */
    data class Debounced(
        val lastRefreshWallMs: Long,
    ) : RefreshResult

    /** The capture did not complete (deadline, Shizuku not ready, service unavailable). Retrying is allowed. */
    data class Failed(
        val reason: String,
    ) : RefreshResult
}

/**
 * The kernel battery nodes already converted to the units the Health screen shows (mAh, never the raw µAh).
 * A null field means the kernel does not expose it. See [KernelUnits] for the conversions.
 */
data class KernelCapacitySnapshot(
    val chargeCounterMah: Double?,
    val chargeFullMah: Double?,
    val designMah: Double?,
    val cycleCount: Int?,
    val levelPct: Int?,
    val temperatureDeciC: Int?,
) {
    companion object {
        /** Null when the kernel read was unavailable. */
        fun from(kernel: KernelBatterySnapshot?): KernelCapacitySnapshot? {
            if (kernel == null || !kernel.available) return null
            val capacity = KernelUnits.kernelCapacity(kernel)
            return KernelCapacitySnapshot(
                chargeCounterMah = KernelUnits.uahToMah(kernel.chargeCounterRaw),
                chargeFullMah = capacity.chargeFullMah,
                designMah = capacity.designMah,
                cycleCount = capacity.cycleCount,
                levelPct = kernel.capacityPercent,
                temperatureDeciC = kernel.temperatureDeciC,
            )
        }
    }
}

/**
 * One installed package with a launcher activity, as the Toolkit app picker receives it. The picker adds the
 * restricted packages and sets every restricted flag from its own override read (F-19), so this row carries none.
 */
data class AppEntry(
    val packageName: String,
    val label: String,
)

/**
 * Package labels and icons, resolved in-process through `PackageManager` and cached in memory. Both calls
 * may touch the package manager, so call them from a coroutine; a cache hit returns without a context switch.
 * An unknown package gets its package name as label and a null icon.
 */
interface AppLabels {
    suspend fun label(packageName: String): String

    suspend fun icon(packageName: String): Bitmap?

    /**
     * The visible installed packages that run as app [uid], in any order. Empty for system and shared platform
     * UIDs (app id below 10000), which own no single app, and for UIDs no visible package owns. Screens name a
     * UID through [packageForUid] (or [identifyingPackage] over this list), never by picking from it directly.
     */
    suspend fun packagesForUid(uid: Int): List<String> = emptyList()
}

/**
 * The installed package that identifies app [uid]: the one rule every screen uses to name a battery-stats UID, the
 * [identifyingPackage] among [AppLabels.packagesForUid]. Null when no visible package runs as [uid] (system and
 * shared platform UIDs included), or when several do and batterystats' [reported] process belongs to none of them.
 */
suspend fun AppLabels.packageForUid(
    uid: Int,
    reported: String?,
): String? = identifyingPackage(packagesForUid(uid), reported)

/**
 * Which of a UID's [owners] (its installed packages) identifies it. batterystats' [reported] name
 * (`UidBatteryConsumer.getPackageWithHighestDrain()`) is a *process* name such as `com.example.chat:push`, or null,
 * so it is only a hint. Without its `:process` suffix it names the owner it equals, else the longest owner it extends
 * by a dotted suffix (`com.google.android.gms.persistent`), the way an app names its own extra processes. Otherwise the
 * UID's only owner identifies it. A UID several packages share (`android:sharedUserId`) whose hint names none of them,
 * such as the media providers' `android.process.media`, has no single app to name or act on, so the answer is null,
 * never one owner picked by name (B-08). The answer is always one of [owners], never the reported name itself.
 */
fun identifyingPackage(
    owners: Collection<String>,
    reported: String?,
): String? {
    val process = reported?.substringBefore(':')
    return owners.firstOrNull { it == process }
        ?: process?.let { name -> owners.filter { name.startsWith("$it.") }.maxByOrNull { it.length } }
        ?: owners.singleOrNull()
}
