package com.akane.inspiration.toolkit

import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.toolkit.AppToolkitState
import com.akane.inspiration.telemetry.toolkit.DozeState
import com.akane.inspiration.telemetry.toolkit.Override
import com.akane.inspiration.telemetry.toolkit.OverrideKind
import com.akane.inspiration.telemetry.toolkit.ToolkitOutcome
import kotlinx.collections.immutable.toImmutableList

/** Pure mapping from the toolkit's models to the screen's. Nothing here touches a service. */
internal object ToolkitMapper {
    fun doze(state: DozeState): DozeInfo = DozeInfo(
        profile = state.profile,
        raw = state.raw,
        effective = state.effectiveMs.map { (key, ms) -> DozeValue(key, ms) }.toImmutableList(),
    )

    fun controls(state: AppToolkitState): AppControls = AppControls(
        ops = AppOp.entries.map { op ->
            val mode = state.appOps[op]
            OpControl(op = op, ignored = mode == AppOpMode.IGNORE, editable = mode != null)
        }.toImmutableList(),
        bucket = state.bucket,
        bucketRaw = state.bucketRaw,
        updatedSystemApp = state.updatedSystemApp,
    )

    /**
     * An app op that reads back as the default is not an override any more (the user switched it back by
     * hand); the others are. Buckets and doze are always overrides while the toolkit's value is in effect.
     */
    fun isStillOverride(override: Override): Boolean = when (override.kind) {
        OverrideKind.APP_OP -> override.currentRaw != "allow" && override.currentRaw != AppOpMode.DEFAULT.argv
        else -> true
    }

    fun overrideItem(override: Override, label: String?): OverrideItem = OverrideItem(
        actionId = override.actionId,
        kind = override.kind,
        label = label,
        what = when (override.kind) {
            OverrideKind.APP_OP -> OverrideWhat.AppOpIgnored(override.appOp)
            OverrideKind.BUCKET -> OverrideWhat.Bucket(bucketOfRaw(override.currentRaw))
            OverrideKind.DOZE -> OverrideWhat.Doze(profileOfRaw(override.currentRaw))
        },
        sinceMs = override.sinceMs,
        restorable = override.restorable,
    )

    private fun bucketOfRaw(raw: String?): StandbyBucket? {
        val value = raw?.trim()?.toIntOrNull() ?: return null
        return StandbyBucket.entries.firstOrNull { it.rawValue == value }
    }

    private fun profileOfRaw(raw: String?): DozeProfile? =
        raw?.let { value -> DozeProfile.entries.firstOrNull { it.constants == value } }

    /**
     * Decodes an audit row. The strings mirror how `ToolkitActions` stores its rows (kind, op and param are
     * its own explicit tokens); anything this build does not recognise becomes [HistoryWhat.Other].
     */
    fun history(record: ToolkitActionRecord, target: String?): HistoryItem = HistoryItem(
        id = record.id,
        what = whatOf(record),
        target = target,
        status = record.status,
        startedAtMs = record.startedAt,
        isRestore = record.restoresId != null,
    )

    private fun whatOf(record: ToolkitActionRecord): HistoryWhat = when (record.kind) {
        "appop" -> HistoryWhat.AppOpSet(
            op = AppOp.entries.firstOrNull { it.argv == record.op },
            ignored = AppOpMode.entries.firstOrNull { it.argv == record.param }?.let { it == AppOpMode.IGNORE },
        )
        "bucket" -> HistoryWhat.BucketSet(StandbyBucket.entries.firstOrNull { it.argv == record.param })
        "doze" -> when (record.op) {
            "apply_profile" -> HistoryWhat.DozeApplied(
                DozeProfile.entries.firstOrNull { it.name.equals(record.param, ignoreCase = true) },
            )
            "restore_raw" -> HistoryWhat.DozeRestored
            else -> HistoryWhat.Other
        }
        "force_stop" -> HistoryWhat.ForceStop
        "dexopt" -> HistoryWhat.Dexopt
        else -> HistoryWhat.Other
    }

    fun problem(outcome: ToolkitOutcome<*>): Problem = when (outcome) {
        is ToolkitOutcome.Refused -> Problem.Refused(outcome.reason)
        is ToolkitOutcome.Unavailable -> Problem.Unavailable(outcome.reason)
        is ToolkitOutcome.Failed -> Problem.Failed(outcome.code)
        is ToolkitOutcome.Done -> Problem.Failed(null)
    }
}
