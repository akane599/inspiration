package com.akane.inspiration.toolkit

import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import com.akane.inspiration.telemetry.toolkit.BgDexoptCancelReason
import com.akane.inspiration.telemetry.toolkit.BgDexoptStatus
import com.akane.inspiration.telemetry.toolkit.OverrideKind
import com.akane.inspiration.telemetry.toolkit.ToolkitRefusal
import com.akane.inspiration.telemetry.toolkit.ToolkitUnavailable
import com.akane.inspiration.telemetry.toolkit.ToolkitWarning
import kotlinx.collections.immutable.persistentListOf

/** Fixed data for the screenshots: no clock, no Shizuku, no package manager. Times are 2026-03-02 UTC. */
internal object ToolkitPreviewData {
    private const val NOON_MS = 1_772_452_800_000L // 2026-03-02 12:00 UTC
    private const val HOUR_MS = 3_600_000L

    private val chatty = AppItem("com.example.chatty", "Chatty", restricted = true)
    private val maps = AppItem("com.example.maps", "Maps", restricted = false)

    val apps = AppsSection(
        phase = AppsPhase.READY,
        items = persistentListOf(
            chatty,
            AppItem("com.example.mail", "Mail", restricted = true),
            AppItem("com.example.calc", "Calculator", restricted = false),
            maps,
            AppItem("com.example.news", "News reader", restricted = false),
        ),
        total = 5,
    )

    private val balanced = DozeSection(
        load = DozeLoad.Loaded(
            DozeInfo(
                profile = DozeProfile.BALANCED,
                raw = "inactive_to=60000",
                effective = persistentListOf(
                    DozeValue("inactive_to", 60_000L),
                    DozeValue("light_after_inactive_to", 300_000L),
                    DozeValue("idle_after_inactive_to", 900_000L),
                    DozeValue("sensing_to", 30_000L),
                    DozeValue("locating_to", 15_000L),
                    DozeValue("motion_inactive_to", 120_000L),
                ),
            ),
        ),
        notice = ToolkitNotice.Applied(Change.DozeApplied(DozeProfile.BALANCED)),
    )

    private val custom = balanced.copy(
        load = DozeLoad.Loaded((balanced.load as DozeLoad.Loaded).info.copy(profile = null)),
        notice = null,
    )

    private val overrides = persistentListOf(
        OverrideItem(1, OverrideKind.APP_OP, "Chatty", OverrideWhat.AppOpIgnored(AppOp.RUN_ANY_IN_BACKGROUND), NOON_MS - 26 * HOUR_MS, true),
        OverrideItem(2, OverrideKind.BUCKET, "Mail", OverrideWhat.Bucket(StandbyBucket.RESTRICTED), NOON_MS - 5 * HOUR_MS, true),
        OverrideItem(3, OverrideKind.DOZE, null, OverrideWhat.Doze(DozeProfile.BALANCED), NOON_MS - HOUR_MS, true),
        OverrideItem(4, OverrideKind.APP_OP, "Maps", OverrideWhat.AppOpIgnored(AppOp.WAKE_LOCK), NOON_MS - 2 * HOUR_MS, false),
    )

    private val history = persistentListOf(
        HistoryItem(9, HistoryWhat.DozeApplied(DozeProfile.BALANCED), null, ToolkitActionStatus.DONE, NOON_MS - HOUR_MS, false),
        HistoryItem(8, HistoryWhat.AppOpSet(AppOp.WAKE_LOCK, true), "Maps", ToolkitActionStatus.DONE, NOON_MS - 2 * HOUR_MS, false),
        HistoryItem(7, HistoryWhat.ForceStop, "Chatty", ToolkitActionStatus.FAILED, NOON_MS - 3 * HOUR_MS, false),
        HistoryItem(6, HistoryWhat.BucketSet(StandbyBucket.RESTRICTED), "Mail", ToolkitActionStatus.DONE, NOON_MS - 5 * HOUR_MS, false),
        HistoryItem(5, HistoryWhat.AppOpSet(AppOp.RUN_ANY_IN_BACKGROUND, false), "Chatty", ToolkitActionStatus.RESTORED, NOON_MS - 20 * HOUR_MS, true),
        HistoryItem(4, HistoryWhat.Dexopt, null, ToolkitActionStatus.REFUSED, NOON_MS - 22 * HOUR_MS, false),
    )

    val ready = ToolkitState(
        status = ToolkitPhase.READY,
        doze = balanced,
        apps = apps,
        overrides = overrides,
        history = history,
        dexopt = DexoptSection(status = BgDexoptStatus.Finished(0, NOON_MS - 22 * HOUR_MS)),
    )

    val customDoze = ready.copy(doze = custom)

    val dexoptRunning = ready.copy(
        dexopt = DexoptSection(status = BgDexoptStatus.Running(NOON_MS - HOUR_MS / 2), notice = ToolkitNotice.Applied(Change.DexoptStarted)),
    )

    val dexoptUnknown = ready.copy(dexopt = DexoptSection(status = BgDexoptStatus.Unknown))

    val dexoptUnplugged = ready.copy(
        dexopt = DexoptSection(
            status = BgDexoptStatus.Cancelled(BgDexoptCancelReason.UNPLUGGED, NOON_MS - HOUR_MS),
            notice = ToolkitNotice.Rejected(Problem.Refused(ToolkitRefusal.NOT_PLUGGED)),
        ),
    )

    /** Nothing is changed: the doze profile is the system's own and no action has left a result line behind. */
    private val untouchedDoze = DozeSection(
        load = DozeLoad.Loaded(
            DozeInfo(
                profile = DozeProfile.STANDARD,
                raw = null,
                effective = persistentListOf(
                    DozeValue("inactive_to", 1_800_000L),
                    DozeValue("light_after_inactive_to", 300_000L),
                    DozeValue("idle_after_inactive_to", 1_800_000L),
                ),
            ),
        ),
    )

    val noChanges = ready.copy(
        doze = untouchedDoze,
        overrides = persistentListOf(),
        history = persistentListOf(),
        apps = AppsSection(phase = AppsPhase.READY, query = "zzz", total = 5),
        dexopt = DexoptSection(status = BgDexoptStatus.Idle),
    )

    val loading = ToolkitState(status = ToolkitPhase.LOADING)

    val failed = ToolkitState(status = ToolkitPhase.FAILED, history = history)

    val dozeFailed = ready.copy(
        doze = DozeSection(load = DozeLoad.Unavailable(Problem.Unavailable(ToolkitUnavailable.NO_SERVICE))),
        apps = AppsSection(phase = AppsPhase.FAILED),
    )

    val blockedGrant = ToolkitState(status = ToolkitPhase.BLOCKED, blocked = BlockedReason.PERMISSION_REQUIRED, history = history)

    val blockedDenied = ToolkitState(status = ToolkitPhase.BLOCKED, blocked = BlockedReason.PERMISSION_DENIED)

    val resetReport = ready.copy(
        overrides = persistentListOf(overrides[3]),
        resetReport = ResetReport(
            persistentListOf(
                ResetLine(overrides[0], ResetResult.Restored),
                ResetLine(overrides[1], ResetResult.Restored),
                ResetLine(overrides[3], ResetResult.NotRestored(Problem.Refused(ToolkitRefusal.NOT_RESTORABLE))),
            ),
        ),
    )

    val detail = AppDetail(
        app = chatty,
        controls = ControlsState.Loaded(
            AppControls(
                ops = persistentListOf(
                    OpControl(AppOp.RUN_ANY_IN_BACKGROUND, ignored = true, editable = true),
                    OpControl(AppOp.RUN_IN_BACKGROUND, ignored = false, editable = true),
                    OpControl(AppOp.WAKE_LOCK, ignored = false, editable = true),
                ),
                bucket = StandbyBucket.WORKING_SET,
                bucketRaw = 20,
                updatedSystemApp = false,
            ),
        ),
        notice = ToolkitNotice.Applied(Change.AppOpSet(AppOp.RUN_ANY_IN_BACKGROUND, ignored = true)),
    )

    val detailCustom = detail.copy(
        app = maps,
        controls = ControlsState.Loaded(
            AppControls(
                ops = persistentListOf(
                    OpControl(AppOp.RUN_ANY_IN_BACKGROUND, ignored = false, editable = false),
                    OpControl(AppOp.RUN_IN_BACKGROUND, ignored = false, editable = true),
                    OpControl(AppOp.WAKE_LOCK, ignored = true, editable = true),
                ),
                bucket = null,
                bucketRaw = 15,
                updatedSystemApp = true,
            ),
        ),
        notice = ToolkitNotice.Rejected(Problem.Refused(ToolkitRefusal.STALE)),
    )

    val detailLoading = AppDetail(app = maps, controls = ControlsState.Loading)

    val detailFailed = AppDetail(app = maps, controls = ControlsState.Unavailable(Problem.Refused(ToolkitRefusal.READ_FAILED)))

    val confirmWakeLock = Confirmation(
        request = ToolkitRequest.SetAppOp(AppOp.WAKE_LOCK, ignored = true),
        target = "Chatty",
        warnings = persistentListOf(ToolkitWarning.WAKE_LOCK_IGNORE),
    )

    val confirmForceStop = Confirmation(
        request = ToolkitRequest.ForceStop,
        target = "Chatty",
        warnings = persistentListOf(ToolkitWarning.FORCE_STOP, ToolkitWarning.UPDATED_SYSTEM_APP),
    )

    val confirmDozeStale = Confirmation(
        request = ToolkitRequest.ApplyDoze(DozeProfile.STANDARD),
        target = null,
        warnings = persistentListOf(ToolkitWarning.DOZE_OVER_CUSTOM),
        stale = true,
    )

    val confirmReset = Confirmation(
        request = ToolkitRequest.ResetAll,
        target = null,
        warnings = persistentListOf(ToolkitWarning.RESTORE_STATE_CHANGED),
        count = 3,
    )
}
