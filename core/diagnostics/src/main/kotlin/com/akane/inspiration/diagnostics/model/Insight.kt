package com.akane.inspiration.diagnostics.model

import com.akane.inspiration.diagnostics.dictionary.EntryKind

enum class RuleId { NIGHT_VAMPIRE, AWAKE_VIOLATION, MODEM_DRAIN, SENSOR_LEAK }

/** Declaration order is ascending severity. */
enum class Severity { AMBER, RED }

enum class CulpritScope { SCREEN_OFF_ONLY, WHOLE_INTERVAL_CONTRIBUTORS }

enum class SensorKind { GPS, ACCELEROMETER, GYROSCOPE, OTHER }

data class Culprit(
    val kind: EntryKind,
    val name: String,
    val uid: Int?,
    val durationMs: Long?,
    val count: Long?,
)

sealed interface Insight {
    val rule: RuleId
    val severity: Severity
    val subjectUid: Int?
}

data class NightVampire(
    override val severity: Severity,
    override val subjectUid: Int,
    val packageName: String?,
    val totalMah: Double,
    val bgShare: Double,
    val fgActivityMs: Long,
) : Insight {
    override val rule: RuleId get() = RuleId.NIGHT_VAMPIRE
}

data class AwakeViolation(
    override val severity: Severity,
    val awakeRatio: Double,
    val screenOffMs: Long,
    val culpritScope: CulpritScope,
    val culprits: List<Culprit>,
) : Insight {
    override val rule: RuleId get() = RuleId.AWAKE_VIOLATION
    override val subjectUid: Int? get() = null
}

data class ModemDrain(
    override val severity: Severity,
    val mobileRadioMah: Double,
    val screenMah: Double,
    val signalAvailable: Boolean,
    val signalBinPct: List<Double>,
    val scanningMs: Long?,
    val radioActiveMs: Long?,
) : Insight {
    override val rule: RuleId get() = RuleId.MODEM_DRAIN
    override val subjectUid: Int? get() = null
}

data class SensorLeak(
    override val severity: Severity,
    override val subjectUid: Int,
    val packageName: String?,
    val sensor: SensorKind,
    val durationMs: Long,
) : Insight {
    override val rule: RuleId get() = RuleId.SENSOR_LEAK
}

data class DiagnosticReport(
    val insights: List<Insight>,
    val notEvaluable: Map<RuleId, String>,
    val skippedInvalidUids: Int,
)
