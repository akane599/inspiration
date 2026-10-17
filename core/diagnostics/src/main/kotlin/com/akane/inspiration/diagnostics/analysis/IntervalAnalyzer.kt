package com.akane.inspiration.diagnostics.analysis

import com.akane.inspiration.diagnostics.model.DiagnosticReport
import com.akane.inspiration.diagnostics.rules.DiagnosticInput
import com.akane.inspiration.diagnostics.rules.DiagnosticRuleEngine
import com.akane.inspiration.diagnostics.story.BatteryStory
import com.akane.inspiration.diagnostics.story.BatteryStoryBuilder
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.diff.ReferenceResolver
import com.akane.inspiration.telemetry.diff.ResolvedReference
import com.akane.inspiration.telemetry.diff.SnapshotDiffer
import com.akane.inspiration.telemetry.interval.IntervalMetrics
import com.akane.inspiration.telemetry.interval.IntervalMetricsCalculator
import com.akane.inspiration.telemetry.snapshot.SnapshotDiff
import com.akane.inspiration.telemetry.store.SnapshotStore

/** Everything one interval produces: the resolved snapshot pair, its diff, metrics, rule report and story. */
data class IntervalAnalysis(
    val resolved: ResolvedReference,
    val diff: SnapshotDiff,
    val metrics: IntervalMetrics,
    val report: DiagnosticReport,
    val story: BatteryStory,
)

/** resolve -> diff -> metrics -> rules -> story for one [ReferenceKind]; the production form of the debug probe flow. */
class IntervalAnalyzer(
    private val store: SnapshotStore,
    private val ruleEngine: DiagnosticRuleEngine,
    private val storyBuilder: BatteryStoryBuilder = BatteryStoryBuilder(),
) {
    private val resolver = ReferenceResolver(store)

    /** Null when [kind] has no resolvable interval at [nowWallMs]. */
    suspend fun analyze(kind: ReferenceKind, nowWallMs: Long): IntervalAnalysis? {
        val resolved = resolver.resolve(kind, nowWallMs) ?: return null
        // IntervalMetricsCalculator.compute requires to >= from.
        if (resolved.coveredToWallMs < resolved.coveredFromWallMs) return null

        val diff = SnapshotDiffer.diff(resolved.ref, resolved.now)
        val events = store.historyBetween(0, nowWallMs).sortedBy { it.wallMs }
        val metrics = IntervalMetricsCalculator.compute(events, resolved.coveredFromWallMs, resolved.coveredToWallMs)
        val report = ruleEngine.evaluate(DiagnosticInput(diff, metrics, resolved.perUidAvailable))
        val story = storyBuilder.build(metrics, diff.sleep, report)
        return IntervalAnalysis(resolved, diff, metrics, report, story)
    }
}
