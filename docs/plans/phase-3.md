audited: 2026-10-17. Reviewers: SQ-37 plan-review (Claude Opus/medium): APPROVE WITH FIXES. SQ-38 review-audit (GPT-5.6 Terra/medium, focused on F2/F3/F4/F7): APPROVE WITH FIXES. All blockers and fixes are applied below (rev 2).

# Phase 3 — Smart Insight Layer: rule engine, wakelock dictionary, Battery Story (story US-3)

Source spec: `TEMPLATE.md` §4 and §7 Aşama 3 (Turkish); §5 Ekran 1 item 2 defines the Story card. The Phase 2 contract (`docs/plans/phase-2.md`, E1–E13, rev 2 + SQ-34/35) still holds. The US-2 constraints carry over:
- Screen intervals come from `history_event`, because broadcasts coalesce for frozen apps on API 35+.
- Per-UID validity (`UidDelta.valid`) is shown, never hidden.

## Outcome
`:core:diagnostics` turns a `SnapshotDiff` plus the `history_event` rows for the same **covered** interval into:
1. **Typed insights** from four deterministic rules (§4: Night Vampire, Awake Violation, Modem Drain, Sensor Leak). Each insight carries its evidence numbers. A rule whose inputs are missing or invalid returns `NotEvaluable(reason)`, never a guess and never silence.
2. **Explanations** for wakelock, kernel-wakelock, wakeup-reason and alarm names, from a bundled dictionary with deterministic matching.
3. **A Battery Story**: one plain-language paragraph built only from measured facts. A sentence whose fact is missing is omitted.

These are proven by:
- JVM tests on synthetic diffs, synthetic protos and the 3 real batterystats fixtures;
- an orchestrator device run (`PROBE_INSIGHTS`) on API 36/37.

Emulator limitation (SQ-38 wire count): none of the 3 batterystats fixtures carries signal bins, scanning timers or UID sensors. On the emulator, expect R3 to fire only with `signalAvailable = false`, and R4 to be `NotEvaluable("source_absent")`. The hardware path is unit-proven only.

## Non-goals (owning phase)
- Phase 4/5: screens, charts, ViewModels, Shizuku actions. A dictionary `action` hint is data only; nothing executes it.
- Insights aren't persisted; they're computed on demand from stored snapshots. No notifications or background evaluation; zero-observer rules (§6) are unchanged.
- No ML, no network, no remote dictionary updates.
- No standby-bucket / exit-reason clients. The Phase 2 non-goals list deferred them to Phase 3; this phase defers them again to Phase 5, where the toolkit reads and writes buckets. No rule needs them.

## Decisions
| # | Decision | Why |
|---|---|---|
| F1 | **Module:** `:core:diagnostics` (Android library) → `api(project(":core:telemetry-engine"))`; `api` because public signatures expose `SnapshotDiff`/`SleepStats`/`IntervalMetrics`. Test deps are declared in the module, as the other modules do; the convention plugin adds none: `testImplementation` junit, robolectric, androidx-test-core, kotlinx-coroutines-test (all already in the catalog), plus `testOptions.unitTests.isIncludeAndroidResources = true`. org.json is a stub on the plain JVM, so loader and writer tests run under Robolectric. Rule engine, matcher and story builder are pure Kotlin with no `Context`. Android-only classes: `diagnostics/sensor/AndroidSensorCatalog` (T4) and `diagnostics/story/AndroidStoryWriter` (T5). `:app` adds `implementation(project(":core:diagnostics"))`. | E11 layering; D8 (no DI). SQ-37: no test deps by convention (`AndroidLibraryConventionPlugin.kt`). |
| F2 | **Data extension (schema v1, edited in place per E9).** Confirmed at AOSP android-15.0.0_r36 `frameworks/base/core/proto/android/os/batterystats.proto` (SQ-38): `SystemProto.Misc.mobile_radio_active_duration_ms = 5`; `SystemProto.phone_signal_strength = 16` → `PhoneSignalStrength { name = 1 (vendored as **int32**: wire-compatible with the telephony enum, avoids vendoring enums.proto); total = 2 TimerProto }`; `SystemProto.signal_scanning = 21` (TimerProto); `UidProto.sensors = 21` → `Sensor { id = 1; apportioned = 2 TimerProto; background = 3 TimerProto }`; GPS id −10000 (`BatteryStats.java` GPS_SENSOR). **Models:** `SystemTimers` + `signalStrengthMs: List<Long>` (index = bin 0..4, empty when absent) + `signalScanningMs: Long?` + `mobileRadioActiveMs: Long?`; `UidSnapshot` + `sensors: List<UidSensor>`; `UidDelta` + `sensors` (a negative value makes the UidDelta invalid, as SQ-35 does). **SnapshotDiff gains a system-timer delta:** `signalStrengthMs: List<Long>` (empty if either side lacks bins), `signalScanningMs: Long?`, `mobileRadioActiveMs: Long?`; a negative value gives null plus a problem string. **Schema:** `snapshot_system` + `signalBin0Ms..signalBin4Ms?`, `signalScanningMs?`, `mobileRadioActiveMs?`; new `snapshot_uid_sensor(snapshotId FK → snapshot.id ON DELETE CASCADE, uid, handle, totalMs, bgMs, count) PK(snapshotId, uid, handle)`, registered in `InspirationDatabase` entities, with a DAO insert/fetch, both store mappings (write as in `RoomSnapshotStore.kt:40–69`, read as at `:188–237`) and the exported `1.json`. **Dev-install hazard:** the in-place edit changes `identityHash`, and there's no destructive fallback, so every existing install must be cleared before running the new build (`adb shell pm clear com.akane.inspiration`, or uninstall); this also applies after any revert. | Rules 3 and 4 have no inputs today. SQ-37 blocker (no diff field for R3 evidence); SQ-38 (shapes, mapping/cascade). |
| F3 | **Interval metrics** (`telemetry-engine/interval/IntervalMetrics.kt`, pure): `IntervalMetricsCalculator.compute(events, fromWallMs, toWallMs)`. The caller passes history from wall 0 (as `ReferenceResolver.kt:40` does) and the diff's **covered** bounds (`coveredFromWallMs`/`coveredToWallMs`), not the requested boundary, so metrics and diff describe the same span. **Screen state:** the last SCREEN_* event at or before `from`; while it's unknown, that span counts as `unknownMs`. **Levels:** seeded from the `levelPct` of the last event of **any** kind at or before each bound, because every event carries the current level and LEVEL is only emitted on change (`BatteryHistory.kt:142,166–171`). Drops are applied only on LEVEL events, each attributed to the screen state in force. For events at the same timestamp, the SCREEN transition is processed before LEVEL (walker order, `:133–143`). Across RESET/START, the level is reseeded and nothing is attributed across the gap; the timestamp goes into `discontinuities`. Charging time (PLUGGED → UNPLUGGED) is excluded from drain. `levelUsedPct` = sum of attributed drops (not start − end), so charging inside the interval doesn't cancel usage. Rates (`%/h`) are computed only for a state with ≥ 15 min of time, else null. | SQ-38 F3 fix; SQ-37 covered-interval and levelUsed fixes. |
| F4 | **Rules.** Common inputs: `DiagnosticInput(diff, metrics, perUidAvailable)`. Gates: `validity.batteryStats` false → R1/R3/R4 NotEvaluable("battery_stats_invalid"). `perUidAvailable` false (a LIGHT endpoint, `ReferenceResolver.kt:32,61`) → R1/R4 NotEvaluable("per_uid_unavailable"). Only `UidDelta.valid` UIDs are considered, and the report counts the skipped ones. **Screen-off-only eligibility is derived, not passed in:** `screenOffOnly = metrics != null && metrics.screenOnMs == 0 && metrics.unknownMs == 0 && metrics.screenOffMs ≥ 30 min`. **R1 Night Vampire:** UID ≥ 10000, byProcessState present; (bgMah + cachedMah) / totalMah > 0.60 **and** fgActivityMs < 60 000 **and** totalMah ≥ max(2.0, 0.01 × interval device discharge). FGS is excluded from the numerator (a user-visible service isn't a vampire). RED. **R2 Awake Violation:** needs `diff.sleep` with screenOffMs ≥ 30 min; awakeRatio = screenOffAwakeMs / screenOffMs > 0.08 → AMBER (RED > 0.20). **Culprits** (top partial wakelock by Δ duration, top kernel wakelock by Δ duration (only if `validity.kernelCounters` and not in `invalidKernelWakelocks`), top wakeup alarm by Δ count) are attached as `culpritScope = SCREEN_OFF_ONLY` **only when `screenOffOnly`**. Otherwise they're attached as `culpritScope = WHOLE_INTERVAL_CONTRIBUTORS`, which the UI must label "contributors over the whole interval", never "cause". **R3 Modem Drain:** NotEvaluable("component_invalid") if MOBILE_RADIO or SCREEN is in `invalidDeviceComponents`; NotEvaluable("screen_power_zero") if SCREEN ≤ 0; else fires when MOBILE_RADIO > 0.35 × SCREEN **and** MOBILE_RADIO ≥ 5 mAh. Evidence comes from the diff's system-timer delta (bins as a percent of the summed bins, scanning ms, radio-active ms); `signalAvailable = false` when the bins are empty. AMBER. **R4 Sensor Leak:** only when `screenOffOnly`, else NotEvaluable("not_screen_off_interval"). No sensor entries in either snapshot → NotEvaluable("source_absent"). Per valid UID ≥ 10000: GPS (−10000) Δ totalMs ≥ 5 min → RED; accelerometer/gyroscope (via `SensorCatalog`) Δ totalMs ≥ max(10 min, 10 % of metrics.screenOffMs) → AMBER. OTHER never fires. | §4 made concrete. SQ-38 blocker (R2 misattribution), R3 zero-screen, R4 eligibility; SQ-37 validity gates. |
| F5 | **Rule output types live in `diagnostics/model/` and are created by T3**, so T4 and T5 compile against the same file: `RuleId`, `Severity`, `CulpritScope`, `sealed interface Insight` (`NightVampire`, `AwakeViolation`, `ModemDrain`, `SensorLeak`), `Culprit`, `DiagnosticReport(insights, notEvaluable: Map<RuleId, String>, skippedInvalidUids: Int)`. T4 fills them and doesn't redefine them. Ordering: severity desc, then evidence magnitude desc, then uid/name. | SQ-37 blocker (T4/T5 type ownership). |
| F6 | **Dictionary:** `core/diagnostics/src/main/res/raw/diagnostic_dictionary.json` = `{ "version": 1, "entries": [ { "id", "kinds": [...], "match": { "type": "exact"\|"prefix"\|"suffix"\|"contains"\|"regex", "value" }, "category", "title": {"en","tr"}, "body": {"en","tr"}, "action": null\|"bg_dexopt"\|"standby_bucket"\|"appops_wake_lock" } ] }`. **Precedence:** exact > longest prefix/suffix > longest contains > regex (file order); ties go by file order. Kernel and wakeup_reason names are matched lowercase; the rest are case-sensitive. **≥ 60 entries**, including the AOSP tag conventions (`*job*/`, `*sync*/`, `*alarm*`, `*launch*`, `*dexopt*`, `*walarm*:`, `NetworkStats`, `PowerManagerService.WakeLocks`, `GOOGLE_C2DM`, `wlan_rx_wake`, `qcom_rx_wakelock`, `ipa_ws`, `[timerfd]`, `alarmtimer`, `eventpoll`) and the §4 examples. Wording is conservative: no device-specific claims, no promised fix. Load fails on an unknown kind or type, a bad regex, missing en/tr, or a duplicate id. Localized text stays in the JSON: a documented exception to the strings.xml rule (an SQ-37 contrarian proposed strings keyed by id; it was rejected because a hundred-plus entries × 2 locales in strings.xml plus an id↔resource map adds a second source of truth, and the test-enforced en/tr completeness check replaces lint's MissingTranslation). Locale = `Configuration.locales[0]`, en fallback. | §4.1; deterministic oracle. |
| F7 | **Battery Story:** `BatteryStoryBuilder.build(metrics: IntervalMetrics?, sleep: SleepStats?, report: DiagnosticReport?)` returns `BatteryStory(sentences)`, with typed sentences `Summary(hours, levelUsedPct)`, `ScreenOnRate(pctPerHour, rating)`, `ScreenOffRate(pctPerHour, rating)`, `DeepSleep(ratio)`, `Anomalies(count, topRule)`. A sentence is omitted when its input is null; null metrics → only `DeepSleep`/`Anomalies`. Ratings: screen-on %/h ≤ 10 NORMAL, ≤ 15 HIGH, else VERY_HIGH; screen-off %/h ≤ 0.8 EXCELLENT, ≤ 2 NORMAL, ≤ 4 HIGH, else VERY_HIGH. `AndroidStoryWriter` renders from `res/values/strings.xml` (en) + `res/values-tr/strings.xml` (tr), with plurals and locale-aware 1-decimal formatting. | §5 Ekran 1 item 2 example. |
| F8 | **SensorCatalog:** `fun interface SensorCatalog { fun kindOf(handle: Int): SensorKind }` (GPS, ACCELEROMETER, GYROSCOPE, OTHER). `AndroidSensorCatalog(context)` maps `SensorManager.getSensorList(TYPE_ALL)` by `Sensor.getHandle()` using **plain reflection**. Hidden-API exemptions are already process-wide (`InspirationApplication.kt:26`), so diagnostics needs no hiddenapibypass dependency. Any throwable → OTHER. −10000 → GPS. | SQ-37 F8 fix. |
| F9 | **Debug probe:** `PROBE_INSIGHTS` (extra `reference_kind`, as PROBE_DIFF) logs JSON: the report (rule, severity, uid, key numbers, culpritScope), notEvaluable, the story's sentence types plus its rendered en paragraph, and dictionary lookups for the top 3 wakelocks. Before the device run: `pm clear` (F2 hazard). | Orchestrator device evidence. |

## Shared surface (pinned)
```kotlin
// telemetry-engine (T1a)
data class UidSensor(val handle: Int, val totalMs: Long, val bgMs: Long, val count: Long)
// SystemTimers += signalStrengthMs: List<Long>, signalScanningMs: Long?, mobileRadioActiveMs: Long?
// UidSnapshot += sensors: List<UidSensor>;  UidDelta += sensors: List<UidSensor>
// SnapshotDiff += signalStrengthMs: List<Long>, signalScanningMs: Long?, mobileRadioActiveMs: Long?

// telemetry-engine/interval (T2)
data class IntervalMetrics(val fromWallMs: Long, val toWallMs: Long, val screenOnMs: Long, val screenOffMs: Long,
    val unknownMs: Long, val chargingMs: Long, val startLevel: Int?, val endLevel: Int?, val levelUsedPct: Int,
    val levelDropScreenOn: Int, val levelDropScreenOff: Int, val screenOnPctPerHour: Double?,
    val screenOffPctPerHour: Double?, val discontinuities: List<Long>)
object IntervalMetricsCalculator { fun compute(events: List<HistoryEvent>, fromWallMs: Long, toWallMs: Long): IntervalMetrics }

// diagnostics/dictionary (T3)
enum class EntryKind { PARTIAL, KERNEL, WAKEUP_REASON, ALARM }
data class Explanation(val id: String, val category: String, val title: String, val body: String, val action: String?)
class DiagnosticDictionary(entries: List<DictionaryEntry>) { fun explain(kind: EntryKind, name: String, locale: Locale): Explanation? }
object DictionaryLoader { fun parse(json: String): List<DictionaryEntry> }   // throws on invalid

// diagnostics/model (T3 creates; T4 and T5 consume)
enum class RuleId { NIGHT_VAMPIRE, AWAKE_VIOLATION, MODEM_DRAIN, SENSOR_LEAK }
enum class Severity { AMBER, RED }
enum class CulpritScope { SCREEN_OFF_ONLY, WHOLE_INTERVAL_CONTRIBUTORS }
data class Culprit(val kind: EntryKind, val name: String, val uid: Int?, val durationMs: Long?, val count: Long?)
sealed interface Insight { val rule: RuleId; val severity: Severity; val subjectUid: Int? }
data class NightVampire(override val severity: Severity, override val subjectUid: Int, val packageName: String?, val totalMah: Double, val bgShare: Double, val fgActivityMs: Long) : Insight
data class AwakeViolation(override val severity: Severity, val awakeRatio: Double, val screenOffMs: Long, val culpritScope: CulpritScope, val culprits: List<Culprit>) : Insight
data class ModemDrain(override val severity: Severity, val mobileRadioMah: Double, val screenMah: Double, val signalAvailable: Boolean, val signalBinPct: List<Double>, val scanningMs: Long?, val radioActiveMs: Long?) : Insight
data class SensorLeak(override val severity: Severity, override val subjectUid: Int, val packageName: String?, val sensor: SensorKind, val durationMs: Long) : Insight
data class DiagnosticReport(val insights: List<Insight>, val notEvaluable: Map<RuleId, String>, val skippedInvalidUids: Int)
// (rule/subjectUid overrides fixed per class; SensorKind lives in diagnostics/model too)

// diagnostics/rules (T4)
data class DiagnosticInput(val diff: SnapshotDiff, val metrics: IntervalMetrics?, val perUidAvailable: Boolean)
class DiagnosticRuleEngine(private val sensors: SensorCatalog) { fun evaluate(input: DiagnosticInput): DiagnosticReport }

// diagnostics/story (T5)
class BatteryStoryBuilder { fun build(metrics: IntervalMetrics?, sleep: SleepStats?, report: DiagnosticReport?): BatteryStory }
```

## Tickets (Gradle ≤ 2 concurrent; dispatch order T1a+T2 → T1b+T3 → T4 → T5 → T6)
T1 is split per SQ-37: about 15 files across two modules. T4 → T5 is sequential, because both edit diagnostics files.

| T | Title | Category | Files | Depends | Verify |
|---|---|---|---|---|---|
| T1a | Proto trim (F2 fields, AOSP r36 numbers) + parser + models + SnapshotDiff system-timer delta + differ sensors + capturer merge | coding.normal | core/telemetry-engine/src/main/proto/android/os/batterystats.proto, …/telemetry/{dump,snapshot,diff,capture}/**, matching tests (+ a synthetic proto test fixture) | — | `:core:telemetry-engine:testDebugUnitTest :app:assembleDebug`. A synthetic proto with bins/scanning/radio-active/UID sensors (GPS + 2 HAL handles) parses exactly. The 3 real batterystats fixtures still parse (fields absent → empty/null). Diff: bin/scanning Δ, a negative → null + problem, a negative sensor Δ → UidDelta invalid. **review-audit** bound (field numbers) |
| T2 | IntervalMetricsCalculator | coding.normal | core/telemetry-engine/src/{main,test}/kotlin/…/telemetry/interval/** | — | `:core:telemetry-engine:testDebugUnitTest`: unknown leading state; on/off split; seeding from non-LEVEL events; same-timestamp SCREEN_OFF + LEVEL; RESET between levels (no false drop); charging excluded and levelUsed = drop sum; < 15 min → null rate; an empty list |
| T1b | Schema v1 columns + `snapshot_uid_sensor` entity/DAO/mapping + exported 1.json | coding.normal, **highStakes** | core/database/** | T1a | `:core:database:testDebugUnitTest :app:assembleDebug`: round trip of signal columns + sensors; deleting a parent snapshot cascades the sensor rows (prune test); exported 1.json updated |
| T3 | Diagnostics module wiring (api dep, test deps, includeAndroidResources) + `model/` types (pinned) + dictionary JSON (≥ 60 en+tr) + loader + matcher | coding.normal | core/diagnostics/build.gradle.kts, core/diagnostics/src/main/res/raw/**, core/diagnostics/src/{main,test}/kotlin/…/diagnostics/{model,dictionary}/** | — (T1a for the `api` dep's new types is not needed at compile) | `:core:diagnostics:testDebugUnitTest` (Robolectric): every entry loads; precedence; the §4 examples resolve; kernel lowercase; invalid JSON / duplicate id / missing tr rejected; locale fallback. **review-audit** of the dictionary wording for factual overreach |
| T4 | DiagnosticRuleEngine R1–R4 + SensorCatalog + AndroidSensorCatalog | coding.normal | core/diagnostics/src/{main,test}/kotlin/…/diagnostics/{rules,sensor}/** | T1a, T2, T3 | `:core:diagnostics:testDebugUnitTest`: each rule's boundaries (0.60 / 60 s / 2 mAh; 0.08 / 0.20 / 30 min; 0.35 / 5 mAh / SCREEN = 0; 5 min GPS; max(10 min, 10 %) accel/gyro); every NotEvaluable reason; R2 culpritScope both ways; kernel culprit skipped when kernelCounters invalid; invalid UIDs skipped and counted; ordering |
| T5 | BatteryStoryBuilder + AndroidStoryWriter + strings en/tr | coding.normal | core/diagnostics/src/{main,test}/kotlin/…/diagnostics/story/**, core/diagnostics/src/main/res/values/strings.xml, core/diagnostics/src/main/res/values-tr/strings.xml | T2, T3, T4 | `:core:diagnostics:testDebugUnitTest`: sentence omission; rating boundaries; Robolectric en + tr render reproducing the §5 example numbers |
| T6 | App wiring: diagnostics dep, AppContainer (dictionary loaded lazily off main), debug `PROBE_INSIGHTS` | coding.normal | app/build.gradle.kts (project dep), app/src/main/kotlin/…/AppContainer.kt, app/src/debug/** | T1b, T4, T5 | `:app:assembleDebug :app:minifyReleaseWithR8 :app:testDebugUnitTest` + orchestrator device run on API 36/37 after `pm clear` |

Review budget:
- T1a gets a GPT `review-audit`. A wrong field number is an unchecked consumer that would silently read the wrong timer.
- T3's dictionary wording gets a read-only `review-audit` for factual overreach.
- T1b is highStakes for the schema; the orchestrator verifies it against the cascade test.
- The other tickets have deterministic oracles.

## Design-reopen evidence
- A real device (none available; only emulators) shows the F2 fields absent, or field numbers differ on API 36/37 → R3 keeps firing without signal data, and R4 goes to `NotEvaluable("source_absent")` with that evidence recorded. Fixture absence alone does **not** trigger this.
- `Sensor.getHandle()` is unreachable via reflection on API 36/37 → R4 is GPS-only; the others are reported as OTHER.
- History lacks SCREEN events on a device → `unknownMs` dominates: the Story omits the on/off rates, R2 reports `WHOLE_INTERVAL_CONTRIBUTORS`, and R4 is NotEvaluable.

## Rejected alternatives
- **Schema v2 + debug-only destructive fallback** (SQ-37 contrarian 1). E9 keeps v1 mutable until release, and a version bump now would leave a dead v1 → v2 step in the history. Instead, `pm clear` is a required step in every device run (F2/F9).
- **R2 culprits labelled "cause" over mixed intervals.** Rejected by both reviewers; replaced by the derived `culpritScope`.
