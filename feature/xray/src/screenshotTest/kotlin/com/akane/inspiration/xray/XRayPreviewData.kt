package com.akane.inspiration.xray

import com.akane.inspiration.diagnostics.dictionary.EntryKind
import com.akane.inspiration.diagnostics.dictionary.Explanation
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.toolkit.OverrideKind
import com.akane.inspiration.telemetry.toolkit.ToolkitRefusal
import com.akane.inspiration.telemetry.toolkit.ToolkitWarning
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Fixed data for the screenshots: no clock, no Shizuku, no package manager. Times are 2026-03-02 UTC. */
internal object XRayPreviewData {
    private const val FROM_MS = 1_772_438_400_000L // 2026-03-02 08:00 UTC
    private const val TO_MS = FROM_MS + 6 * 3_600_000L

    val components = persistentListOf(
        ComponentRow("SCREEN", 142.3, 46.6),
        ComponentRow("CPU", 88.0, 28.8),
        ComponentRow("MOBILE_RADIO", 41.2, 13.5),
        ComponentRow("WIFI", 18.9, 6.2),
        ComponentRow("GNSS", 8.4, 2.8),
        ComponentRow("SENSORS", 3.9, 1.3),
        ComponentRow("IDLE", 2.5, 0.8),
        ComponentRow("BLUETOOTH", null, null),
    )

    private fun app(
        uid: Int,
        pkg: String?,
        label: String?,
        total: Double,
        share: Double,
        parts: List<Pair<String, Double>>,
        wakelocks: List<WakelockRow> = emptyList(),
        alarms: List<AlarmRow> = emptyList(),
    ): AppRow = AppRow(
        uid = uid,
        packageName = pkg,
        label = label,
        totalMah = total,
        sharePct = share,
        slices = ComponentGroup.entries
            .map { group -> GroupSlice(group, parts.filter { XRayMapper.groupOf(it.first) == group }.sumOf { it.second }) }
            .filter { it.mah > 0.0 }
            .toImmutableList(),
        components = parts.map { ComponentRow(it.first, it.second, it.second / total * 100.0) }.toImmutableList(),
        processStates = ProcessStateMah(foreground = total * 0.55, background = total * 0.35, foregroundService = total * 0.1, cached = null),
        cpuMs = 14 * 60_000L + 20_000L,
        foregroundMs = 52 * 60_000L,
        wakelocks = wakelocks.toImmutableList(),
        alarms = alarms.toImmutableList(),
    )

    val chatty = app(
        uid = 10_214,
        pkg = "com.example.chatty",
        label = "Chatty",
        total = 62.4,
        share = 39.0,
        parts = listOf("CPU" to 31.0, "MOBILE_RADIO" to 17.5, "WIFI" to 5.4, "SCREEN" to 8.5),
        wakelocks = listOf(
            WakelockRow("*alarm*:com.example.chatty.SYNC", 21 * 60_000L, 38),
            WakelockRow("NlpWakeLock", 40_000L, 6),
        ),
        alarms = listOf(AlarmRow("com.example.chatty.KEEPALIVE", 112), AlarmRow("com.example.chatty.SYNC", 9)),
    )

    val apps = persistentListOf(
        chatty,
        app(10_101, "com.example.maps", "Maps", 38.1, 23.8, listOf("SCREEN" to 20.0, "GNSS" to 8.4, "CPU" to 9.7)),
        app(10_177, "com.example.stream", "Stream", 25.0, 15.6, listOf("SCREEN" to 16.0, "WIFI" to 5.0, "CPU" to 4.0)),
        app(10_052, "com.example.browser", "Browser", 11.2, 7.0, listOf("CPU" to 6.2, "SCREEN" to 3.0, "MOBILE_RADIO" to 2.0)),
        app(1_000, null, null, 3.4, 2.1, listOf("CPU" to 2.4, "SENSORS" to 1.0)),
    )

    val ready = XRayState(
        status = XRayStatus.READY,
        components = components,
        apps = apps,
    )

    val slice = SliceInfo(
        requestedFromMs = FROM_MS,
        requestedToMs = TO_MS,
        coveredFromMs = FROM_MS + 25 * 60_000L,
        coveredToMs = TO_MS,
        gapMs = 25 * 60_000L,
    )

    val needsSnapshots = ready.copy(tab = XRayTab.APPS, perUidAvailable = false, apps = persistentListOf(), slice = slice)

    val loaded = ActionsState.Loaded(AppControls(restricted = false, restrictionEditable = true, bucket = StandbyBucket.WORKING_SET, bucketRaw = 20))

    val restrictedControls = ActionsState.Loaded(AppControls(restricted = true, restrictionEditable = true, bucket = StandbyBucket.RARE, bucketRaw = 40))

    val override = OverrideRow(
        actionId = 7L,
        kind = OverrideKind.APP_OP,
        appOp = AppOp.RUN_ANY_IN_BACKGROUND,
        bucket = null,
        sinceMs = TO_MS - 3_600_000L,
        restorable = true,
    )

    val detail = AppDetail(app = chatty, actions = loaded)

    val detailWithOverride = AppDetail(
        app = chatty,
        actions = restrictedControls,
        overrides = persistentListOf(override),
        notice = ActionNotice.Applied(Change.Restricted),
    )

    val detailBlocked = AppDetail(app = chatty, actions = ActionsState.Blocked(BlockedReason.PERMISSION_REQUIRED))

    val detailRefused = AppDetail(
        app = chatty,
        actions = loaded,
        notice = ActionNotice.Rejected(Problem.Refused(ToolkitRefusal.PROTECTED_APP)),
    )

    val detailNoPackage = AppDetail(app = apps.last(), actions = ActionsState.NoPackage)

    val confirmForceStop = Confirmation(
        request = ActionRequest.ForceStop,
        target = "Chatty",
        warnings = persistentListOf(ToolkitWarning.FORCE_STOP),
    )

    val confirmRestrict = Confirmation(
        request = ActionRequest.SetRestricted(true),
        target = "Chatty",
        warnings = persistentListOf(ToolkitWarning.RUN_ANY_IN_BACKGROUND_IGNORE, ToolkitWarning.UPDATED_SYSTEM_APP),
    )

    val explain: Explainer = { kind, name ->
        when {
            kind == EntryKind.PARTIAL && name.startsWith("*alarm*") ->
                Explanation("wl-alarm", "wakelock", "Held while an alarm ran", "An alarm woke the phone and the app kept it awake while it worked.", null)
            kind == EntryKind.ALARM && name.endsWith("KEEPALIVE") ->
                Explanation("alarm-keepalive", "alarm", "Wakes the phone to stay connected", "The app asks to wake the phone regularly so its connection does not drop.", null)
            else -> null
        }
    }
}
