# Architecture
*Last Updated: 2026-10-18*

## Runtime shape

The repository is a single Android application assembled from `:app`, convention-backed `:core:*` libraries, and convention-backed `:feature:*` libraries. Phase 2 adds a pull-first snapshot pipeline: light snapshots use local battery state and clocks; full snapshots combine BatteryUsageStats, parsed framework dumps, kernel battery values, CPU time-in-state, and optional battery history before persistence.

```
MainActivity.onStart → InspirationApplication.captureAppOpen() → AppContainer.refresh()
                     └→ RefreshCoordinator (mutex, 60 s debounce, 12 s failure backoff)
                          └→ SnapshotCapturer.captureFull(APP_OPEN, ingestHistory = true) + store.prune
                          ├→ AndroidTelemetrySource
                          │    └→ ShizukuManager.withTelemetryService()
                          │         └→ TelemetryUserService (shell UID)
                          │              ├→ BatteryUsageStats + history
                          │              ├→ allowlisted batterystats/power/alarm dumps
                          │              └→ kernel/sysfs and CPU readers
                          └→ RoomSnapshotStore → InspirationDatabase (WAL)

RuntimeCaptureReceiver ──light/full policy (full misses fall back to light)──┘
BootCaptureReceiver ──light snapshot + arm JobScheduler
ChargingCaptureJobService ──one full read (history ingest, reused for the snapshot) + prune; re-arms from finally
```

`MainActivity` triggers an app-open refresh through `AppContainer.refresh()`, which is the one debounced, mutex-guarded entry point every screen shares. `InspirationApplication` registers the non-exported runtime receiver and creates `AppContainer`, which owns the application scope (SupervisorJob + IO) and creates `AndroidTelemetrySource`, `SnapshotCapturer`, the Room-backed `SnapshotStore`, `LiveTelemetry`, `AnalysisSession` and `ToolkitActions`.

## Layers and responsibilities

- **Application shell — `:app`**: manifest (incl. `QUERY_ALL_PACKAGES`), launcher activity hosting `AppShell` (bottom `NavigationBar` below 600 dp, `NavigationRail` from 600 dp, five destinations, no navigation library), `AppContainer` manual DI, `di/` (`RefreshCoordinator`, `ReconcileOnReady`, `Preloaded`, label cache, timeline seeding), capture policy, runtime and boot receivers, persisted charging `JobService`, backup exclusions, and debug probes plus `DesignGalleryActivity`. UI is themed by `:core:designsystem`.
- **Feature layer — `:feature:dashboard`, `:feature:xray`, `:feature:sleeplab`, `:feature:health`, `:feature:toolkit`**: MVI screens (`XxxRoute` → `XxxViewModel` → `XxxScreen`). Each depends only on its own pinned `XxxDependencies` interface, implemented by `AppContainer`; none depends on `:app` or another feature. See [`modules.md`](modules.md).
- **Telemetry layer — `:core:telemetry-engine`**: snapshot models, clock/source abstractions, trimmed protobuf parsing, full/light capture and merge, snapshot diff and reference resolution, capacity estimation, live telemetry, calibration input, `SnapshotStore` (incl. `lastHistoryOfEachKindBefore`), the app-facing shared types (`telemetry/app`) and the toolkit write layer (`ToolkitActions`, `ToolkitGateway`).
- **Persistence layer — `:core:database`**: Room 2.8.5 schema v1, entities/DAOs, explicit WAL configuration, domain mappers, history cursor/deduplication, markers, charge sessions, capture misses, the `toolkit_action` audit table, and 30-day pruning/downsampling.
- **IPC layer — `:core:shizuku-ipc`**: Shizuku lifecycle and ref-counted binding, AIDL contract, parcelable telemetry/history DTOs, framework binder readers, allowlisted dumps, battery history, shell-service authorization, and the typed toolkit executor (`ipc/toolkit/`, txns 20–30).
- **Stub layer — `:core:hidden-api-stub`**: compile-time declarations for selected hidden Android interfaces used by IPC clients.
- **Insight layer — `:core:diagnostics`**: pure-Kotlin rule engine (R1–R4), wakelock/alarm dictionary, sensor catalog, and Battery Story builder/writer (en/tr) over `SnapshotDiff` plus `IntervalMetrics`; Phase 5 adds `analysis/` (`AnalysisSession`, `IntervalAnalyzer`, `TimelineBuilder`). See [`modules.md`](modules.md).
- **Build layer — `build-logic/convention`**: shared Android library, Compose library, and feature convention plugins.

## Capture and persistence flow

1. `CapturePolicy` selects a light or full plan. SCREEN_ON/OFF always write light snapshots; power and battery broadcasts use full capture only when the telemetry service is already warm, otherwise they fall back to light. Full broadcast work is bounded by a 2-second deadline.
2. Boot/package-replaced writes a light BOOT snapshot and arms the persisted charging job. The job ingests history, skips duplicate work for the current plug session, captures a full CHARGE_JOB snapshot when needed, prunes old data, and re-arms itself with its minimum latency.
3. `AndroidTelemetrySource.full()` reads usage, dumps, kernel values, CPU times, and paged history through `withTelemetryService`; `light()` reads sticky battery state, BatteryManager values, and clocks without Binder.
4. `SnapshotCapturer` merges partial sources, records source availability and session skew, persists a `BatterySnapshot`, and records misses instead of retrying in a loop. History pages advance the persisted cursor and deduplicate in Room.
5. `RoomSnapshotStore` writes the snapshot and child rows transactionally. `SnapshotDiffer`, `ReferenceResolver`, and capacity code consume stored snapshots/history for explicit validity, sleep, interval, and capacity calculations.

## Insight flow (Phase 3, on demand)

```
ReferenceResolver → (ref, now, covered bounds, perUidAvailable)
SnapshotDiffer.diff(ref, now) ─────────────────────┐
store.historyBetween(0, MAX) → IntervalMetricsCalculator.compute(events, coveredFrom, coveredTo) ─┤
                                                    └→ DiagnosticRuleEngine.evaluate(DiagnosticInput) → DiagnosticReport
                                                         └→ BatteryStoryBuilder.build(metrics, sleep, report) → AndroidStoryWriter
DiagnosticDictionary.explain(kind, name, locale) ← wakelock/kernel/alarm names
```

Screen state for metrics comes from `history_event`, not broadcasts. Nothing in this flow is persisted. In production it runs inside the one process-wide `AnalysisSession` (see below); the debug `PROBE_INSIGHTS` runs the same chain on demand.

## Screen flow (Phase 5)

```
MainActivity → AppShell(selected Destination) → XxxRoute(container as XxxDependencies)
   LifecycleStartEffect: Visible … onStopOrDispose: Hidden → XxxViewModel (Activity-scoped)
AnalysisSelection ┐
refreshTicks (RefreshCoordinator.lastRefreshWallMs) ┴→ AnalysisSession → IntervalAnalyzer → StateFlow<AnalysisState>
                                                         └→ Cockpit, X-Ray, Sleep Lab (shared, passive flows)
AppContainer.timeline(from, to) = TimelineEvents.withSeeds(store.lastHistoryOfEachKindBefore(from), store.historyBetween(from, to))
                                  → TimelineBuilder.build → Cockpit chart / Sleep Lab hypnogram
```

- **Visibility drives work (H13, zero observer effect).** Nothing polls except the visible Cockpit (1.5 s `liveReadings`) and Health (only while charging); a screen leaving composition stops its reads and releases `holdBinding()`. `ToolkitActions` polls dexopt status every 5 s only while a job is RUNNING and collected, and holds the binding itself for that job. `AnalysisSession` computes only while its `analysis` StateFlow has subscribers (it keeps the last state when they leave), so background process starts do no analysis. Never `WhileSubscribed(5000)`.
- **Refresh.** Every screen's `refresh()` goes through `RefreshCoordinator`; a successful refresh advances `lastRefreshWallMs`, which re-runs the `AnalysisSession`. A transition of Shizuku to Ready clears the failure backoff and reconciles PENDING toolkit rows (`reconcileOnEachReady`).
- **Threading.** `AppContainer.applicationScope` (`newApplicationScope()`: SupervisorJob + IO + a logging `CoroutineExceptionHandler`), `AndroidTelemetrySource.full()` and `LiveTelemetry` run on IO; `ShizukuManagerImpl.withTelemetryService` runs the block on its IO dispatcher; the bundled dictionary loads once through `Preloaded` and never parses on the caller's thread.
- **Insets.** Each shell layout owns the system-bar and IME insets (see `modules.md`); screens receive an already-inset area.

## Privileged data flow

1. `ShizukuManagerImpl` watches Shizuku state but binds the user service on demand through `withTelemetryService`; `holdBinding()` is the explicit UI keep-warm holder. The last release lingers 3 s before unbinding (tab handover keeps one process). After an unbind, the next bind waits for the old service's death (2 s fallback), because shizuku-api 13.1.5 shares one connection stub per service class and delivers the old death to the new binding (SQ-114/118).
2. Shizuku launches `TelemetryUserService` under the shell UID. It resolves the app UID and rejects every AIDL call unless `Binder.getCallingUid()` matches it.
3. Authorized calls (read txns 1–6 and 8, typed toolkit txns 20–30, `getApiVersion` 31, per H11; txn 7 stays retired) read framework services (`batterystats`, `powerstats`, `deviceidle`, `power`) through binder clients, selected sysfs nodes, CPU frequency paths, and `BatteryUsageStats` history.
4. Results cross the AIDL boundary as parcelable DTOs with `available` and machine-readable `reason` fields. Dumps use an allowlisted `DumpTarget` and a capped pipe; arbitrary command execution is not exposed. Writes exist only as the typed toolkit calls (fixed absolute argv, `--user 0`, validated package names, hard-deny rules, one write lock); see "Toolkit write path" in [`modules.md`](modules.md).

The debug receiver exercises permission, IPC, capture, diff, direct readers, dumps, and timing. See [`entry-points.md`](entry-points.md) and [`onboarding.md`](onboarding.md).

## Product boundaries

Features are read-mostly: the only privileged writes are the five user-initiated toolkit actions (app-op, standby bucket, Doze profile, force-stop, bg-dexopt), each confirmed when risky and recorded in `toolkit_action`. No resident/foreground service, WorkManager loop, or DI framework exists; DI is manual (`AppContainer`). Device-specific sysfs availability, hidden API signatures, synthetic emulator batteries, and history availability remain runtime assumptions recorded in [`PROGRESS.md`](../../PROGRESS.md). Design and decisions: [`docs/plans/phase-5.md`](../../docs/plans/phase-5.md) (H1–H18), [`phase-3.md`](../../docs/plans/phase-3.md) (F1–F9), [`phase-2.md`](../../docs/plans/phase-2.md).
