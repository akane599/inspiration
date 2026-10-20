package com.akane.inspiration.xray

import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.diff.ResolvedReference
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.dump.UidWakelock
import com.akane.inspiration.telemetry.snapshot.SnapshotDiff
import com.akane.inspiration.telemetry.snapshot.UidDelta
import kotlinx.collections.immutable.toImmutableList

/**
 * Pure mapping from an interval's [SnapshotDiff] to what the screen shows. Nothing here estimates: a figure
 * the data does not provide stays null and the UI draws "—".
 */
object XRayMapper {
    /** Stands for every component outside [FEATURED_COMPONENTS] that has energy. */
    const val OTHER_KEY = "OTHER"

    /** The components of TEMPLATE.md §5 Ekran 2, by their battery-stats names. */
    val FEATURED_COMPONENTS: List<String> =
        listOf("SCREEN", "CPU", "MOBILE_RADIO", "WIFI", "IDLE", "BLUETOOTH", "GNSS", "SENSORS")

    private const val MAX_LISTED = 5

    /**
     * Device components with total mAh and share of the listed total. Components the diff marks invalid are
     * unavailable, like ones the snapshots do not carry. Shown ranked by energy, unavailable ones last in the
     * spec's order. Empty when the interval has no usable component data at all.
     */
    fun components(diff: SnapshotDiff): List<ComponentRow> {
        if (!diff.validity.batteryStats) return emptyList()
        val usable =
            diff.deviceComponentsMah.filter { (key, mah) ->
                key !in diff.validity.invalidDeviceComponents && mah.isFinite() && mah >= 0.0
            }
        if (usable.isEmpty()) return emptyList()
        val total = usable.values.sum()

        fun row(
            key: String,
            mah: Double?,
        ) = ComponentRow(key, mah, if (mah != null && total > 0.0) mah / total * PERCENT else null)

        val featured = FEATURED_COMPONENTS.map { key -> row(key, usable[key]) }
        val otherMah = usable.filterKeys { it !in FEATURED_COMPONENTS }.values.sum()
        val rows = if (otherMah > 0.0) featured + row(OTHER_KEY, otherMah) else featured
        val (present, missing) = rows.partition { it.mah != null }
        return present.sortedByDescending { it.mah } + missing
    }

    /**
     * Apps ranked by energy, biggest first. Empty when the interval has no per-app data ([perUidAvailable]
     * false, H14) or the statistics are invalid. A UID whose package changed inside the interval is left out:
     * its total would count the new package from zero. A row's [AppRow.packageName] is still batterystats' reported
     * name, a process name and only a hint: the caller resolves it against the UID's installed packages and fills in
     * the label before the row is shown or acted on. Icons load per visible row.
     */
    fun apps(
        diff: SnapshotDiff,
        perUidAvailable: Boolean,
    ): List<AppRow> {
        if (!perUidAvailable || !diff.validity.batteryStats) return emptyList()
        val usable = diff.uids.filter { it.valid && !it.packageChanged && it.totalMah.isFinite() && it.totalMah > 0.0 }
        val sum = usable.sumOf { it.totalMah }
        return usable
            .sortedWith(compareByDescending<UidDelta> { it.totalMah }.thenBy { it.uid })
            .map { toRow(it, sum, diff) }
    }

    /**
     * How many apps [apps] leaves out for want of a usable baseline: the diagnostic report's skipped UIDs (invalid
     * delta or changed package) for the same diff. Zero where no app could be listed at all, since the screen
     * already explains that case.
     */
    fun hiddenApps(
        diff: SnapshotDiff,
        perUidAvailable: Boolean,
        skippedInvalidUids: Int,
    ): Int = if (perUidAvailable && diff.validity.batteryStats) skippedInvalidUids.coerceAtLeast(0) else 0

    /** The slice banner's data; null unless the selection is a time slice. [resolved] is null while no interval resolves. */
    fun slice(
        selected: ReferenceKind,
        resolved: ResolvedReference?,
    ): SliceInfo? {
        val slice = selected as? ReferenceKind.TimeSlice ?: return null
        return SliceInfo(
            requestedFromMs = slice.fromWallMs,
            requestedToMs = slice.toWallMs,
            coveredFromMs = resolved?.coveredFromWallMs,
            coveredToMs = resolved?.coveredToWallMs,
        )
    }

    fun groupOf(componentKey: String): ComponentGroup =
        when (componentKey) {
            "SCREEN" -> ComponentGroup.SCREEN
            "CPU", "WAKELOCK" -> ComponentGroup.CPU
            "MOBILE_RADIO", "WIFI", "BLUETOOTH", "GNSS" -> ComponentGroup.RADIOS
            else -> ComponentGroup.OTHER
        }

    private fun toRow(
        delta: UidDelta,
        appsTotal: Double,
        diff: SnapshotDiff,
    ): AppRow {
        val positive = delta.componentsMah.filter { it.value.isFinite() && it.value > 0.0 }
        val slices =
            ComponentGroup.entries
                .map { group -> GroupSlice(group, positive.filterKeys { groupOf(it) == group }.values.sum()) }
                .filter { it.mah > 0.0 }
        // A share is of the larger of the total and the listed parts, the base StackedBar scales the slices by. The
        // total also counts components that are not listed, but the system UID's nets out the power AOSP reattributes
        // to apps (a negative component the usage read drops), leaving its parts above its total (audit B-23).
        val shareBase = maxOf(delta.totalMah, positive.values.sum())
        val components =
            positive.entries
                .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
                .map { ComponentRow(it.key, it.value, it.value / shareBase * PERCENT) }
        val alarms =
            delta.wakeupAlarms
                .takeIf { diff.validity.dumpCounters }
                .orEmpty()
                .ifEmpty { delta.alarmDumpWakeups.takeIf { diff.validity.alarmDumpCounters }.orEmpty() }
        return AppRow(
            uid = delta.uid,
            packageName = delta.packageName,
            label = null,
            totalMah = delta.totalMah,
            sharePct = if (appsTotal > 0.0) delta.totalMah / appsTotal * PERCENT else 0.0,
            slices = slices.toImmutableList(),
            components = components.toImmutableList(),
            processStates =
                ProcessStateMah(
                    foreground = delta.fgMah?.takeIf { it.isFinite() },
                    background = delta.bgMah?.takeIf { it.isFinite() },
                    foregroundService = delta.fgsMah?.takeIf { it.isFinite() },
                    cached = delta.cachedMah?.takeIf { it.isFinite() },
                ),
            dumpCountersAvailable = diff.validity.dumpCounters,
            alarmsAvailable = diff.validity.dumpCounters || diff.validity.alarmDumpCounters,
            cpuMs = delta.cpuUserMs + delta.cpuSystemMs,
            foregroundMs = delta.fgActivityMs,
            wakelocks =
                delta.partialWakelocks
                    .takeIf { diff.validity.dumpCounters }
                    .orEmpty()
                    .filter { it.durationMs > 0 || it.count > 0 }
                    .sortedWith(compareByDescending<UidWakelock> { it.durationMs }.thenBy { it.name })
                    .take(MAX_LISTED)
                    .map { WakelockRow(it.name, it.durationMs, it.count) }
                    .toImmutableList(),
            alarms =
                alarms
                    .filter { it.count > 0 }
                    .sortedWith(compareByDescending<UidAlarm> { it.count }.thenBy { it.tag })
                    .take(MAX_LISTED)
                    .map { AlarmRow(it.tag, it.count) }
                    .toImmutableList(),
        )
    }

    private const val PERCENT = 100.0
}
