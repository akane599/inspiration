rev 1 — draft, pending audit (plan-review Opus + review-audit GPT).

# Phase 3 — Smart Insight Layer: rule engine, wakelock dictionary, Battery Story (story US-3)

Source spec: `TEMPLATE.md` §4 and §7 Aşama 3 (Turkish); §5 Ekran 1 item 2 defines the Story card. The Phase 2 contract (`docs/plans/phase-2.md`, E1–E13, rev 2 + SQ-34/35) still holds. The US-2 constraints carry over:
- Screen intervals come from `history_event`, because broadcasts coalesce for frozen apps on API 35+.
- Per-UID validity (`UidDelta.valid`) is shown, never hidden.

## Outcome
`:core:diagnostics` turns a `SnapshotDiff` plus the `history_event` rows for the same interval into:
1. **Typed insights** from four deterministic rules (§4: Night Vampire, Awake Violation, Modem Drain, Sensor Leak). Each insight carries its evidence numbers. A rule whose inputs are missing returns `NotEvaluable(reason)`, never a guess.
2. **Explanations** for wakelock, kernel-wakelock, wakeup-reason and alarm names, from a bundled dictionary with deterministic matching.
3. **A Battery Story**: one plain-language paragraph built only from measured facts. A sentence whose fact is missing is omitted.

These are proven by JVM tests on synthetic diffs and the 9 real proto fixtures, plus an orchestrator device run (`PROBE_INSIGHTS`) on API 36/37.

## Non-goals (owning phase)
- Phase 4/5: screens, charts, ViewModels, Shizuku actions. A dictionary `action` hint is data only; nothing executes it.
- Insights aren't persisted; they're computed on demand from stored snapshots. No notifications or background evaluation; zero-observer rules (§6) are unchanged.
- No ML, no network, no remote dictionary updates.
- No standby-bucket / exit-reason clients. The Phase 2 non-goals list deferred them to Phase 3; this phase defers them again to Phase 5, where the toolkit reads and writes buckets. No rule needs them.

## Decisions
| # | Decision | Why |
|---|---|---|
| F1 | **Module:** `:core:diagnostics` (Android library) → `implementation(project(":core:telemetry-engine"))`. The rule engine, matcher and story builder are pure Kotlin with no `Context`, tested on the JVM. Android-only pieces (`AndroidSensorCatalog`, resource-backed text) live in `diagnostics/android/`. `:app` adds `implementation(project(":core:diagnostics"))`. | Matches E11 layering; no DI framework (D8). |
| F2 | **Data extension (schema v1, edited in place per E9).** The trimmed `batterystats.proto` gains AOSP's: `SystemProto.phone_signal_strength` (repeated, name enum + `TimerProto total`), the signal-scanning timer, `SystemProto.Misc.mobile_radio_active_duration_ms` if not already present, and `UidProto.sensors` (repeated `Sensor { id, apportioned TimerProto, background TimerProto }`). Field numbers are copied **verbatim from AOSP `frameworks/base/core/proto/android/os/batterystats.proto`**, and the ticket cites the tag. Models: `SystemTimers` + `signalStrengthMs: List<Long>` (index = AOSP bin 0..4: none/poor/moderate/good/great; empty when absent) + `signalScanningMs: Long?`; `UidSnapshot` + `sensors: List<UidSensor>`; `UidDelta` + `sensors: List<UidSensor>` (a negative value makes the UidDelta invalid, as SQ-35 does). Schema: `snapshot_system` adds `signalBin0Ms..signalBin4Ms?`, `signalScanningMs?`; a new table `snapshot_uid_sensor(snapshotId FK cascade, uid, handle, totalMs, bgMs, count) PK(snapshotId, uid, handle)`. | Rules 3 and 4 have no inputs today (recon of SnapshotModels / trimmed proto, 2026-10-17). |
| F3 | **Interval metrics** (`telemetry-engine/interval/IntervalMetrics.kt`, pure): `IntervalMetricsCalculator.compute(events: List<HistoryEvent>, fromWallMs, toWallMs)` returns `IntervalMetrics`. Screen state at `from` is the last SCREEN_* event before `from`; if it's unknown, that span counts as `unknownMs`. `screenOnMs` and `screenOffMs` accumulate from SCREEN_ON/OFF; `chargingMs` from PLUGGED/UNPLUGGED; level drop is split by screen state using the LEVEL events (each level step is attributed to the screen state in force at that event); `startLevel` and `endLevel` come from the nearest LEVEL at or before each bound. A RESET/START/SHUTDOWN inside the interval is reported in `discontinuities` without ending the computation. Rates (`%/h`) are only computed for a state with ≥ 15 min of time; otherwise they're null. | Story and rule denominators need screen-split drain. History is the source of truth for boundaries (E5, US-2 constraint). |
| F4 | **Rules (pinned thresholds; every rule is evaluated only with `validity.batteryStats` true and only over UidDeltas with `valid`).** **R1 Night Vampire:** for UID ≥ 10000 with byProcessState present: bgShare = (bgMah + cachedMah) / totalMah > 0.60 **and** fgActivityMs < 60 000 **and** totalMah ≥ max(2.0, 0.01 × device discharge in the interval). FGS mAh is excluded from the numerator (a user-visible service such as music isn't a vampire). Severity RED. **R2 Awake Violation:** interval must contain ≥ 30 min of screen-off. awakeRatio = sleep.screenOffAwakeMs / sleep.screenOffMs > 0.08 → one `AwakeViolation` card with up to three culprits: the top partial wakelock (by Δ duration, any UID), the top kernel wakelock (by Δ duration), and the top wakeup alarm (by Δ count). A missing kind is simply absent. Severity AMBER, RED above 0.20. **R3 Modem Drain:** device components `MOBILE_RADIO` > 0.35 × `SCREEN` **and** MOBILE_RADIO ≥ 5 mAh. Evidence: the signal-bin time distribution (Δ, percent of the interval) and the scanning Δ; when absent, the insight still fires with `signalAvailable = false`. Severity AMBER. **R4 Sensor Leak:** evaluated on a screen-off interval only (a reference resolved as `SinceScreenOff`, or an input flag `screenOffOnly`). For each valid UID ≥ 10000: GPS (handle −10000) Δ totalMs ≥ 5 min, or any accelerometer/gyroscope handle (via `SensorCatalog`) with Δ totalMs ≥ 10 % of the screen-off time and ≥ 10 min. An unknown handle type is reported as `OTHER`, and OTHER never fires on its own. Severity RED for GPS, AMBER otherwise. | §4 thresholds made concrete; noise floors stop trivial flags. FGS exclusion and the floors are this plan's choices, open to the audit. |
| F5 | **Rule output shape:** `sealed interface Insight { val rule: RuleId; val severity: Severity; val subjectUid: Int? }` with data classes `NightVampire`, `AwakeViolation`, `ModemDrain`, `SensorLeak`. `DiagnosticReport(insights, notEvaluable: Map<RuleId, String>, invalidUidCount: Int)`. Ordering: severity desc, then evidence magnitude (mAh or ms) desc; deterministic tie-break by uid/name. | Typed and render-agnostic for Phase 5 MVI. |
| F6 | **Dictionary:** `core/diagnostics/src/main/res/raw/diagnostic_dictionary.json` = `{ "version": 1, "entries": [ { "id", "kinds": ["partial"\|"kernel"\|"wakeup_reason"\|"alarm"], "match": { "type": "exact"\|"prefix"\|"suffix"\|"contains"\|"regex", "value" }, "category": "system"\|"sync"\|"job"\|"alarm"\|"network"\|"modem"\|"audio"\|"sensor"\|"display"\|"dexopt"\|"other", "title": {"en","tr"}, "body": {"en","tr"}, "action": null\|"bg_dexopt"\|"standby_bucket"\|"appops_wake_lock" } ] }`. **Precedence:** exact > longest prefix/suffix > contains (longest value) > regex (file order); ties go by file order. Matching is case-sensitive, except kernel and wakeup_reason names, which are matched lowercase. The **≥ 60 initial entries** must include AOSP tag conventions (`*job*/…`, `*sync*/…`, `*alarm*`, `*launch*`, `*dexopt*`, `*walarm*:`, `NetworkStats`, `PowerManagerService.WakeLocks`, `GOOGLE_C2DM`, `wlan_rx_wake`/`qcom_rx_wakelock`, `ipa_ws`, `[timerfd]`, `alarmtimer`, `eventpoll`) and the §4 examples. Wording is conservative ("commonly", "usually"): no device-specific claims and no promised fix. The loader validates on load: an unknown kind or type, a bad regex, or missing en/tr makes the load fail in tests. **Localized text lives in the JSON (en + tr), not strings.xml**: a documented exception to the strings.xml rule, because entries are data and need no `getIdentifier`. The locale is chosen from `Configuration.locales[0]`, with en as the fallback. | §4.1. Deterministic precedence makes the oracle testable. |
| F7 | **Battery Story:** `BatteryStoryBuilder.build(metrics: IntervalMetrics, sleep: SleepStats?, report: DiagnosticReport?)` returns `BatteryStory(sentences: List<StorySentence>)`. The sentences are typed (`Summary(hours, levelUsedPct)`, `ScreenOnRate(pctPerHour, rating)`, `ScreenOffRate(pctPerHour, rating)`, `DeepSleep(ratio)`, `Anomalies(count, topRule)`); a sentence is omitted when its input is null. Ratings: screen-on %/h ≤ 10 NORMAL, ≤ 15 HIGH, else VERY_HIGH; screen-off %/h ≤ 0.8 EXCELLENT, ≤ 2 NORMAL, ≤ 4 HIGH, else VERY_HIGH. `AndroidStoryWriter` renders the paragraph from `res/values/strings.xml` (en) and `res/values-tr/strings.xml` (tr), with plurals and locale-aware number formatting (1 decimal for rates and the ratio). | §5 Ekran 1 item 2 example: "%8.2 (Normal) … %0.5 (Kusursuz) … %94.6". |
| F8 | **SensorCatalog:** `fun interface SensorCatalog { fun kindOf(handle: Int): SensorKind }` (GPS, ACCELEROMETER, GYROSCOPE, OTHER). `AndroidSensorCatalog(context)` maps `SensorManager.getSensorList(TYPE_ALL)` by `Sensor.getHandle()`, a hidden API reached through the existing HiddenApiBypass and wrapped in try/catch → OTHER. Handle −10000 is GPS. | AOSP BatteryStats records sensors by HAL handle. |
| F9 | **Debug probe:** `PROBE_INSIGHTS` (extra `reference_kind`, as PROBE_DIFF) logs JSON: the report (rule, severity, uid, key numbers), notEvaluable, the story's sentence types plus its rendered en paragraph, and 3 dictionary lookups for the top wakelocks. | Orchestrator device evidence. |

## Shared surface (pinned)
```kotlin
// telemetry-engine (T1)
data class UidSensor(val handle: Int, val totalMs: Long, val bgMs: Long, val count: Long)
// SystemTimers += val signalStrengthMs: List<Long>, val signalScanningMs: Long?
// UidSnapshot += val sensors: List<UidSensor>;  UidDelta += val sensors: List<UidSensor>

// telemetry-engine/interval (T2)
data class IntervalMetrics(val fromWallMs: Long, val toWallMs: Long, val screenOnMs: Long, val screenOffMs: Long,
    val unknownMs: Long, val chargingMs: Long, val startLevel: Int?, val endLevel: Int?,
    val levelDropScreenOn: Int, val levelDropScreenOff: Int, val screenOnPctPerHour: Double?,
    val screenOffPctPerHour: Double?, val discontinuities: List<Long>)
object IntervalMetricsCalculator { fun compute(events: List<HistoryEvent>, fromWallMs: Long, toWallMs: Long): IntervalMetrics }

// diagnostics (T3)
enum class EntryKind { PARTIAL, KERNEL, WAKEUP_REASON, ALARM }
data class Explanation(val id: String, val category: String, val title: String, val body: String, val action: String?)
class DiagnosticDictionary(entries: List<DictionaryEntry>) { fun explain(kind: EntryKind, name: String, locale: Locale): Explanation? }
object DictionaryLoader { fun parse(json: String): List<DictionaryEntry> }   // throws on invalid

// diagnostics (T4)
enum class RuleId { NIGHT_VAMPIRE, AWAKE_VIOLATION, MODEM_DRAIN, SENSOR_LEAK }
enum class Severity { AMBER, RED }
data class DiagnosticInput(val diff: SnapshotDiff, val metrics: IntervalMetrics?, val screenOffOnly: Boolean)
class DiagnosticRuleEngine(private val sensors: SensorCatalog) { fun evaluate(input: DiagnosticInput): DiagnosticReport }

// diagnostics (T5)
class BatteryStoryBuilder { fun build(metrics: IntervalMetrics?, sleep: SleepStats?, report: DiagnosticReport?): BatteryStory }
```

## Tickets (Gradle ≤ 2 concurrent; dispatch order T1+T2 → T3 → T4+T5 → T6)
| T | Title | Category | Files | Depends | Verify |
|---|---|---|---|---|---|
| T1 | Signal-strength, scanning and per-UID sensor data: proto trim, parser, models, differ, schema v1 columns/table, store mapping | coding.normal, **highStakes** (schema) | core/telemetry-engine/src/main/proto/**, …/telemetry/{dump,snapshot,diff,capture}/**, matching tests; core/database/** | — | `:core:telemetry-engine:testDebugUnitTest :core:database:testDebugUnitTest :app:assembleDebug`. Fixture counts: report per-fixture signal bins / sensor entries; a field that's absent in all 9 fixtures must be called out (design-reopen). Differ: a negative sensor Δ → UidDelta invalid. Round trip: the store round-trips sensors and signal columns |
| T2 | IntervalMetricsCalculator | coding.normal | core/telemetry-engine/src/{main,test}/kotlin/…/telemetry/interval/** | — | `:core:telemetry-engine:testDebugUnitTest`: unknown leading state, on/off split, level attribution, charging excluded from drain, a RESET inside, the < 15 min → null rate, an empty event list |
| T3 | Dictionary JSON (≥ 60 entries, en+tr) + loader + matcher + module wiring (diagnostics → telemetry-engine dep; JVM test deps already in the convention) | coding.normal | core/diagnostics/** (except rules/story packages), core/diagnostics/build.gradle.kts | — | `:core:diagnostics:testDebugUnitTest`: every entry loads; precedence cases; the §4 examples resolve; kernel lowercase; invalid JSON is rejected; tr/en fallback |
| T4 | DiagnosticRuleEngine (R1–R4) + SensorCatalog + AndroidSensorCatalog | coding.normal | core/diagnostics/src/{main,test}/kotlin/…/diagnostics/{rules,sensor,android}/** | T1, T2, T3 (module wiring) | `:core:diagnostics:testDebugUnitTest`: each rule fires and stays quiet at its boundaries (0.60/60 s/2 mAh; 0.08/30 min; 0.35/5 mAh; 5 min GPS, 10 %+10 min accel/gyro); invalid UID skipped; NotEvaluable reasons; ordering |
| T5 | BatteryStoryBuilder + AndroidStoryWriter + strings en/tr | coding.normal | core/diagnostics/src/{main,test}/kotlin/…/diagnostics/story/**, core/diagnostics/src/main/res/values*/strings.xml | T2, T3 (module wiring); uses the T4 types via the pinned surface (stub `DiagnosticReport` until T4 lands: T5 dispatches after T4 if parallel overlap is risky) | `:core:diagnostics:testDebugUnitTest`: sentence omission, rating boundaries, Robolectric render en + tr matching the §5 example numbers |
| T6 | App wiring: AppContainer (dictionary lazy-loaded off the main thread, rule engine, story), debug `PROBE_INSIGHTS`, R8 keep for the dictionary model if reflection is used (prefer org.json, no reflection) | coding.normal | app/src/main/kotlin/…/AppContainer.kt, app/build.gradle.kts (project dep), app/src/debug/** | T4, T5 | `:app:assembleDebug :app:minifyReleaseWithR8 :app:testDebugUnitTest` + orchestrator device run on API 36/37 |

Review budget: T1 (schema + proto field numbers: an unchecked consumer is a wrong field number silently reading the wrong timer) gets a GPT `review-audit`. T3 dictionary wording gets a read-only `review-audit` for factual overreach. The other tickets have deterministic oracles.

## Design-reopen evidence
- The AOSP field numbers can't be confirmed from source, or the 9 fixtures carry none of the new fields → R3/R4 ship with `NotEvaluable("source_absent")` on those APIs, and the evidence is recorded.
- `Sensor.getHandle()` isn't reachable through HiddenApiBypass on API 36/37 → R4 is limited to GPS (−10000), with the others reported as OTHER.
- History lacks SCREEN events on a device → F3 `unknownMs` dominates; the Story omits the on/off rates and the plan records that.
