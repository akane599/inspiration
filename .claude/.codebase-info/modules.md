# Modules
*Last Updated: 2026-10-17*

The module graph is declared in [`settings.gradle.kts`](../../settings.gradle.kts). Shared Android configuration lives in [`build-logic/convention`](../../build-logic/convention).

## Application and build logic

| Module | Plugin / role | Status |
|---|---|---|
| `:app` | Android application; Compose shell, manifests, capture wiring, container, and debug probes/gallery. `app/ui/theme` was removed; the theme comes from `:core:designsystem` | Implemented Phase 1 shell plus Phase 2 capture/persistence wiring; Phase 4 migrated to the design system |
| `build-logic:convention` | `inspiration.android.library`, `.library.compose`, and `.feature` convention plugins | Implemented shared configuration |

`:app` depends on all five feature modules, `:core:designsystem`, `:core:shizuku-ipc`, `:core:telemetry-engine`, `:core:database`, and `:core:diagnostics`. Telemetry-engine is now a production implementation dependency rather than debug-only probe support.

## Core modules

| Module | Role | Status |
|---|---|---|
| `:core:hidden-api-stub` | Compile-only declarations for selected hidden Android framework interfaces | Implemented stubs used by IPC clients |
| `:core:shizuku-ipc` | Shizuku lifecycle/binding, AIDL, shell user service, framework readers, sysfs readers, DTOs, history, and allowlisted dumps | Implemented Phase 1 IPC plus Phase 2 history and usage-budget support |
| `:core:telemetry-engine` | Protobuf dump parser, snapshot models/capture, diff/reference resolution (now with signal-bin, scanning, radio-active and per-UID sensor deltas), `interval/IntervalMetrics`, capacity, live telemetry, calibration input, and store interface | Implemented Phase 2 pipeline plus Phase 3 interval metrics and signal/sensor fields |
| `:core:database` | Room schema v1 (edited in place for Phase 3: signal columns and `snapshot_uid_sensor`), DAOs, WAL database, domain mappers, and retention/downsampling store | Implemented Phase 2 persistence plus Phase 3 signal/sensor columns |
| `:core:diagnostics` | Deterministic insight layer: dictionary, rule engine, sensor catalog, Battery Story | Implemented Phase 3 (JVM-tested; device-probed via `PROBE_INSIGHTS`) |
| `:core:designsystem` | Leaf UI module (depends on no other project module): theme tokens, components, charts | Implemented Phase 4 (JVM + screenshot tested) |

`core:telemetry-engine` exposes `core:shizuku-ipc`; `core:diagnostics` exposes `core:telemetry-engine` via `api` because its public signatures use `SnapshotDiff`, `SleepStats` and `IntervalMetrics`. `core:database` implements telemetry-engine's `SnapshotStore`. The feature modules receive telemetry-engine, diagnostics and designsystem dependencies through `AndroidFeatureConventionPlugin`. `core:designsystem` exposes the Compose BOM, material3 and kotlinx-collections-immutable as `api` because its public signatures use `MaterialTheme` extensions, `TextStyle`, `Color` and `ImmutableList`; it uses the `inspiration.android.library.compose` convention and the compose-screenshot plugin.

## `:core:diagnostics` (Phase 3)

Package root `com.akane.inspiration.diagnostics` under [`core/diagnostics/src/main/kotlin`](../../core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics). Design: [`docs/plans/phase-3.md`](../../docs/plans/phase-3.md).

| Package | Contents |
|---|---|
| `dictionary/` | `DictionaryLoader.parse(json)` (throws on unknown kind/type, bad regex, missing en/tr, duplicate id), `DictionaryEntry`, `EntryKind` (PARTIAL, KERNEL, WAKEUP_REASON, ALARM), `DiagnosticDictionary.explain(kind, name, locale)`. Matching precedence: exact > longest prefix/suffix > longest contains > regex, ties by file order; kernel/wakeup names are lowercased. Normalization: partial-wakelock `*job*<variant letter>/` collapses to `*job*/`, and `*walarm*:`/`*alarm*:` prefixes are stripped so a specific tag entry wins, with `walarm_prefix`/`alarm_prefix` as the fallback. Data: [`res/raw/diagnostic_dictionary.json`](../../core/diagnostics/src/main/res/raw/diagnostic_dictionary.json) (72 entries, en+tr text kept in the JSON, not strings.xml) |
| `model/` | `RuleId`, `Severity` (AMBER/RED), `CulpritScope` (SCREEN_OFF_ONLY/WHOLE_INTERVAL_CONTRIBUTORS), `SensorKind`, `Culprit`, `sealed Insight` (`NightVampire`, `AwakeViolation`, `ModemDrain`, `SensorLeak`), `DiagnosticReport(insights, notEvaluable, skippedInvalidUids)`, `InsightOrder` (severity desc, magnitude desc, uid/name) |
| `rules/` | `DiagnosticInput(diff, metrics, perUidAvailable)`; `DiagnosticRuleEngine(SensorCatalog).evaluate()` runs R1 Night Vampire, R2 Awake Violation, R3 Modem Drain, R4 Sensor Leak. Gates: invalid batterystats validity gives R1/R3/R4 `battery_stats_invalid`; `perUidAvailable == false` gives R1/R4 `per_uid_unavailable`; only valid UIDs are evaluated and skipped ones counted; `screenOffOnly` is derived from metrics (no screen-on, no unknown time, ≥ 30 min screen-off). Other NotEvaluable reasons include `component_invalid`, `screen_power_zero`, `not_screen_off_interval`, `source_absent` |
| `sensor/` | `SensorCatalog.kindOf(handle)` (fun interface) and `AndroidSensorCatalog(context)`, which maps `SensorManager` handles by reflection; GPS is handle −10000; any failure maps to OTHER |
| `story/` | `BatteryStoryBuilder.build(metrics, sleep, report)` returns `BatteryStory` of typed sentences (omitted when input is missing); `AndroidStoryWriter` renders them from `res/values/strings.xml` and `res/values-tr/strings.xml` |

Rules and story are pure Kotlin; only `AndroidSensorCatalog`, `AndroidStoryWriter` and dictionary loading tests need Android/Robolectric (`unitTests.isIncludeAndroidResources = true`). Insights are computed on demand, never persisted.

## `:core:designsystem` (Phase 4)

Package root `com.akane.inspiration.designsystem` under [`core/designsystem/src/main/kotlin`](../../core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem). Design: [`docs/plans/phase-4.md`](../../docs/plans/phase-4.md).

| Package | Contents |
|---|---|
| `theme/` | `InspirationTheme` wraps plain `MaterialTheme(colorScheme, shapes, typography)` (`MaterialExpressiveTheme`/`motionScheme` are internal in material3 1.4.0; effects use `tween(150 ms)`, spatial motion `spring(0.9f, StiffnessMediumLow)`). Dark AMOLED is the default; dynamic accent is off. `Color`, `TelemetryColors` (semantic telemetry hues, chart axis/label colors), `Type` (Inter static TTFs, `tnum` on title*/label* and numeric styles), `Shape`, `Spacing` (`MaterialTheme.spacing`) |
| `component/` | `InspirationCard`, `MetricValue`, `SemanticDot`, `LegendRow` |
| `chart/model/` | `ChartModels`, `ChartLabels`: models with internal constructors and sanitizing factories (no `require` in constructors) |
| `chart/math/` | `Scales`, `TimeTicks`, `SeriesMath` (min/max decimation, selection normalization), `ScrubGestureReducer` (pure gesture state machine: Pressed/Scrubbing/Brushing, `HandleEdge`) |
| `chart/draw/` | `TimeAxis` (`drawTimeAxis` draws at y=0, callers translate), `SelectionDim` (shared dim alpha 0.6 toward `surfaceContainer`) |
| `chart/scrub/` | `SynchronizedScrubbingChart` (public entry, stacked lanes sharing one time axis), `ScrubChartRenderer`, `ScrubChartMath`, `ScrubChartState`, `ScrubGestures`, `ScrubReadout` (isolated readout child reading cursor state through a lambda) |
| `chart/hypnogram/` | `SleepHypnogram`, `HypnogramMath` |

Resources: chart strings only in [`strings_chart.xml`](../../core/designsystem/src/main/res/values/strings_chart.xml) (en, plus `values-tr`); Inter Regular/Medium/SemiBold in `res/font/` ([`FONTS.md`](../../core/designsystem/FONTS.md), license `licenses/OFL-Inter.txt`). Tests: JVM tests under `src/test` (math, models, contrast) and screenshot tests under `src/screenshotTest` (`TokenSheet`, `ComponentSheet`, `ScrubChartScreenshots`, `HypnogramScreenshots`) with references in `src/screenshotTestDebug/reference`, package-scoped per area (theme, component, chart/scrub, chart/hypnogram).

## Feature modules

| Module | Intended surface | Status |
|---|---|---|
| `:feature:dashboard` | Battery cockpit/dashboard | Module configuration only |
| `:feature:xray` | Component attribution and telemetry details | Module configuration only |
| `:feature:sleeplab` | Screen-off/deep-sleep analysis | Module configuration only |
| `:feature:health` | Battery health and aging | Module configuration only |
| `:feature:toolkit` | User actions and fixes | Module configuration only |

The only current screen implementation is `HomeRoute`/`HomeScreen` under `:app` (plus the debug-only `DesignGalleryActivity`); feature module names are reserved boundaries, not evidence that screens already exist.
