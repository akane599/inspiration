package com.akane.inspiration.telemetry.store

/** A user-created marker; [snapshotId] is the FULL snapshot captured when it was created. */
data class Marker(
    val id: Long,
    val snapshotId: Long,
    val label: String,
    val wallMs: Long,
)

/** A persisted live reading (one row of `telemetry_sample`). */
data class TelemetrySample(
    val wallMs: Long,
    val elapsedRealtimeMs: Long,
    val bootCount: Int,
    val currentMa: Int?,
    val voltageMv: Int?,
    val watts: Double?,
    val temperatureDeciC: Int?,
    val levelPct: Int?,
    val plugged: Boolean?,
    val thermalStatus: Int? = null,
)

/** One battery-health observation; [wallMs] is unique, inserting the same instant again replaces it. */
data class HealthSample(
    val wallMs: Long,
    val fccMah: Double,
    val designMah: Double,
    val cycleCount: Int?,
    val measuredMah: Double? = null,
)

/** One persisted charge session (the read side of `upsertChargeSession`). */
data class ChargeSessionRecord(
    val statsStartMs: Long,
    val startHistoryTimeMs: Long,
    val startWallMs: Long,
    val endWallMs: Long,
    val startPct: Int,
    val endPct: Int,
    val deltaChargeUah: Long?,
    val integratedMah: Double?,
    val maxTemperatureDeciC: Int?,
    val estimateMah: Double?,
    val independent: Boolean,
    val accepted: Boolean,
    val rejectReason: String?,
)

/** The persisted [ToolkitActionStatus.code] values as compile-time constants, so the store's retention SQL is built from them. */
object ToolkitActionStatusCodes {
    const val PENDING = "pending"
    const val DONE = "done"
    const val REFUSED = "refused"
    const val FAILED = "failed"
    const val RESTORED = "restored"
    const val CANCELLED = "cancelled"
}

/**
 * Lifecycle of an audited toolkit write. [code] is the persisted form: an explicit constant that must
 * never change, independent of the enum's name or ordinal.
 */
enum class ToolkitActionStatus(val code: String) {
    PENDING(ToolkitActionStatusCodes.PENDING),
    DONE(ToolkitActionStatusCodes.DONE),
    REFUSED(ToolkitActionStatusCodes.REFUSED),
    FAILED(ToolkitActionStatusCodes.FAILED),
    RESTORED(ToolkitActionStatusCodes.RESTORED),
    CANCELLED(ToolkitActionStatusCodes.CANCELLED),
    ;

    companion object {
        /** Returns null for an unknown stored code so callers can skip rows written by a newer build. */
        fun fromCode(code: String): ToolkitActionStatus? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Audit row for a privileged toolkit write. [id] is 0 until inserted. [code] is the service's int result
 * code, [beforeRaw]/[afterRaw] the raw values read back around the write, [restoresId] the action this
 * row undoes.
 */
data class ToolkitActionRecord(
    val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val kind: String,
    val pkg: String? = null,
    val op: String,
    val param: String? = null,
    val status: ToolkitActionStatus,
    val code: Int? = null,
    val beforeRaw: String? = null,
    val afterRaw: String? = null,
    val restoresId: Long? = null,
)
