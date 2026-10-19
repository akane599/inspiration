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
    fun doze(state: DozeState): DozeInfo =
        DozeInfo(
            profile = state.profile,
            raw = state.raw,
            effective = state.effectiveMs.map { (key, ms) -> DozeValue(key, ms) }.toImmutableList(),
        )

    fun controls(state: AppToolkitState): AppControls =
        AppControls(
            ops =
                AppOp.entries
                    .map { op ->
                        val mode = state.appOps[op]
                        // A live `default` is enforced as restricted; showing it as such lets the user repair it with allow.
                        OpControl(op = op, ignored = mode == AppOpMode.IGNORE || mode == AppOpMode.DEFAULT, editable = mode != null)
                    }.toImmutableList(),
            bucket = state.bucket,
            bucketRaw = state.bucketRaw,
            updatedSystemApp = state.updatedSystemApp,
        )

    fun overrideItem(
        override: Override,
        label: String?,
    ): OverrideItem =
        OverrideItem(
            actionId = override.actionId,
            kind = override.kind,
            label = label,
            what =
                when (override.kind) {
                    OverrideKind.APP_OP -> {
                        OverrideWhat.AppOpIgnored(
                            override.appOp,
                            when (override.currentRaw?.trim()) {
                                "ignore", "default" -> true
                                "allow" -> false
                                else -> null
                            },
                        )
                    }

                    OverrideKind.BUCKET -> {
                        OverrideWhat.Bucket(bucketOfRaw(override.currentRaw))
                    }

                    OverrideKind.DOZE -> {
                        OverrideWhat.Doze(profileOfRaw(override.currentRaw))
                    }
                },
            sinceMs = override.sinceMs,
            restorable = override.restorable,
            unverified = override.unverified,
        )

    private fun bucketOfRaw(raw: String?): StandbyBucket? {
        val value = raw?.trim()?.toIntOrNull() ?: return null
        return StandbyBucket.entries.firstOrNull { it.rawValue == value }
    }

    /** Unset (null or empty) is Standard: a Standard override over a custom original reads back unset. */
    private fun profileOfRaw(raw: String?): DozeProfile? {
        val value = raw?.takeIf { it.isNotEmpty() }
        return DozeProfile.entries.firstOrNull { it.constants == value }
    }

    /**
     * Decodes an audit row. The strings mirror how `ToolkitActions` stores its rows (kind, op and param are
     * its own explicit tokens); anything this build does not recognise becomes [HistoryWhat.Other].
     */
    fun history(
        record: ToolkitActionRecord,
        target: String?,
    ): HistoryItem =
        HistoryItem(
            id = record.id,
            what = whatOf(record),
            target = target,
            status = record.status,
            startedAtMs = record.startedAt,
            isRestore = record.restoresId != null,
        )

    private fun whatOf(record: ToolkitActionRecord): HistoryWhat =
        when (record.kind) {
            "appop" -> {
                HistoryWhat.AppOpSet(
                    op = AppOp.entries.firstOrNull { it.argv == record.op },
                    ignored = AppOpMode.entries.firstOrNull { it.argv == record.param }?.let { it == AppOpMode.IGNORE },
                )
            }

            "bucket" -> {
                HistoryWhat.BucketSet(StandbyBucket.entries.firstOrNull { it.argv == record.param })
            }

            "doze" -> {
                when (record.op) {
                    "apply_profile" -> {
                        HistoryWhat.DozeApplied(
                            DozeProfile.entries.firstOrNull { it.name.equals(record.param, ignoreCase = true) },
                        )
                    }

                    "restore_raw" -> {
                        HistoryWhat.DozeRestored
                    }

                    else -> {
                        HistoryWhat.Other
                    }
                }
            }

            "force_stop" -> {
                HistoryWhat.ForceStop
            }

            "dexopt" -> {
                HistoryWhat.Dexopt
            }

            else -> {
                HistoryWhat.Other
            }
        }

    fun problem(outcome: ToolkitOutcome<*>): Problem =
        when (outcome) {
            is ToolkitOutcome.Refused -> Problem.Refused(outcome.reason)
            is ToolkitOutcome.Unavailable -> Problem.Unavailable(outcome.reason)
            is ToolkitOutcome.Failed -> Problem.Failed(outcome.code)
            is ToolkitOutcome.Done -> Problem.Failed(null)
        }
}
