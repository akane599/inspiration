# Architecture
*Last Updated: 2026-10-21*

## Runtime shape

The repository is a single Android application assembled from `:app`, convention-backed `:core:*` libraries, and convention-backed `:feature:*` libraries. Phase 2 adds a pull-first snapshot pipeline: light snapshots use local battery state and clocks; full snapshots combine BatteryUsageStats, parsed framework dumps, kernel battery values, CPU time-in-state, and optional battery history before persistence.

```
MainActivity.onStart → InspirationApplication.captureAppOpen() → shizukuManager.refresh() (re-read permission, off Main) → AppContainer.refresh()
                     └→ RefreshCoordinator (mutex, 60 s debounce, 12 s failure backoff)
                          └→ SnapshotCapturer.captureFull(APP_OPEN, ingestHistory = true)
                               then (NonCancellable) afterStored = pruneRetention + status tick, finally onStored = StoreTicks.tick()
                          ├→ AndroidTelemetrySource (binderCall: deadline ends a blocking call)
                          │    └→ ShizukuManager.withTelemetryService() (waits out NotRunning)
                          │         └→ TelemetryUserService (shell UID)
                          │              ├→ BatteryUsageStats + history
                          │              ├→ allowlisted batterystats/alarm dumps
                          │              └→ kernel/sysfs and CPU readers
                          └→ RoomSnapshotStore → InspirationDatabase (WAL)

RuntimeCaptureReceiver ──light/full policy (full misses fall back to light)──┘
BootCaptureReceiver ──light BOOT / PACKAGE_REPLACED snapshot + arm JobScheduler
ChargingCaptureJobService ──one full read (history ingest, reused for the snapshot) + prune; StoreTicks.tick() once history is stored; re-arms from finally
AppContainer marker (captureMarker) ──full capture; ticks StoreTicks in finally once history is stored
```

`MainActivity` triggers an app-open refresh through `AppContainer.refresh()`, which is the one debounced, mutex-guarded entry point every screen shares. `InspirationApplication` registers the non-exported runtime receiver and creates `AppContainer`, which owns the application scope (SupervisorJob + IO) and creates `AndroidTelemetrySource`, `SnapshotCapturer`, the Room-backed `SnapshotStore`, `LiveTelemetry`, `AnalysisSession` and `ToolkitActions`.

## Layers and responsibilities

- **Application shell — `:app`**: manifest (incl. `QUERY_ALL_PACKAGES`), launcher activity hosting `AppShell` (bottom `NavigationBar` below 600 dp, `NavigationRail` from 600 dp, five destinations, no navigation library), `AppContainer` manual DI, `di/` (`RefreshCoordinator`, `ReconcileOnReady`, `Preloaded`, label cache, timeline seeding), capture policy, runtime and boot receivers, persisted charging `JobService`, backup exclusions, and debug probes plus `DesignGalleryActivity`. UI is themed by `:core:designsystem`.
- **Feature layer — `:feature:dashboard`, `:feature:xray`, `:feature:sleeplab`, `:feature:health`, `:feature:toolkit`**: MVI screens (`XxxRoute` → `XxxViewModel` → `XxxScreen`). Each depends only on its own pinned `XxxDependencies` interface, implemented by `AppContainer`; none depends on `:app` or another feature. See [`modules.md`](modules.md).
- **Telemetry layer — `:core:telemetry-engine`**: snapshot models, clock/source abstractions, trimmed protobuf parsing, full/light capture and merge, snapshot diff and reference resolution, capacity estimation, live telemetry, the persisted live-current calibration (`PowerCalibrator`, `SharedPreferencesCalibrationStore`), `SnapshotStore` (incl. `lastHistoryOfEachKindBefore`), the app-facing shared types (`telemetry/app`) and the toolkit write layer (`ToolkitActions`, `ToolkitGateway`).
- **Persistence layer — `:core:database`**: Room 2.8.5 schema v7 (numbered migrations and nullable capture-time UID identity proof, see [`database.md`](database.md)), entities/DAOs, explicit WAL configuration, domain mappers, history cursor/deduplication, markers, charge sessions, capture misses, the `toolkit_action` audit table, and 30-day pruning/downsampling (hourly aggregates kept 365 days).
- **IPC layer — `:core:shizuku-ipc`**: Shizuku lifecycle and ref-counted binding, AIDL contract, parcelable telemetry/history DTOs, framework binder readers, allowlisted dumps, battery history, shell-service authorization, and the typed toolkit executor (`ipc/toolkit/`, txns 20–30).
- **Stub layer — `:core:hidden-api-stub`**: compile-time declarations for selected hidden Android interfaces used by IPC clients.
- **Insight layer — `:core:diagnostics`**: pure-Kotlin rule engine (R1–R4), wakelock/alarm dictionary, sensor catalog, and Battery Story builder/writer (en/tr) over `SnapshotDiff` plus `IntervalMetrics`; Phase 5 adds `analysis/` (`AnalysisSession`, `IntervalAnalyzer`, `TimelineBuilder`). See [`modules.md`](modules.md).
- **Build layer — `build-logic/convention`**: shared Android library, Compose library, and feature convention plugins.

## Capture and persistence flow

1. `CapturePolicy.forBroadcast` selects a light or full plan (no broadcast capture ingests history). With the telemetry service warm, every broadcast takes a FULL capture under a 2-second deadline. Cold, with Shizuku Ready, SCREEN_OFF and a SCREEN_ON that ends a screen-off of at least 30 min (`MIN_SCREEN_OFF_MS`, measured from the stored screen captures by `AppContainer.screenOffForMs()`) bind the service on demand and take a FULL capture under a 6-second deadline (`BROADCAST_BIND_FULL_DEADLINE_MS`), so the screen-off interval has batterystats and per-UID data at both ends (audit R-03); a miss stores a LIGHT row. Everything else (power and battery broadcasts, a short-lock SCREEN_ON, Shizuku not Ready) is LIGHT. `RuntimeCaptureReceiver` sizes its guard for the costliest plan and settles the SCREEN_ON plan inside the capture coroutine; each capture ticks the status notification.
2. Boot writes a light BOOT snapshot and a package update writes a light PACKAGE_REPLACED snapshot (audit N-14). Both arm the persisted charging job. Consumers take a new boot only from a changed `bootCount` or `elapsedRealtime` running backwards, never from the trigger. Android 15 also sends `BOOT_COMPLETED` when an app leaves the stopped state; `SnapshotCapturer.captureLight` stores such a capture as `STOPPED_STATE_EXIT` when `SnapshotClock.sameBootAs` shows the clock continues the newest stored capture's boot (SQ-20; a real reboot is mislabeled only if a capture already ran in the new boot). The job ingests history, skips duplicate work for the current plug session, captures a full CHARGE_JOB snapshot when needed, prunes old data, and re-arms itself with its minimum latency. `ChargingCaptureScheduler` serialises immediate arms and the job's generation-check-plus-rearm submission, so a stale run cannot replace a newer immediate arm with a delayed one. `ChargeHealthPipeline` leaves missing design capacity retryable and re-evaluates legacy `missing_design_capacity` segments from their retained evidence.
3. `AndroidTelemetrySource.full()` reads the batterystats dump first (it syncs external stats), then `BatteryUsageStats` with a 5 s max stats age (`USAGE_MAX_STATS_AGE_MS`) so the query reuses that sync instead of forcing a second one (B-22), then the alarm dump, kernel values, CPU times, and paged history through `withTelemetryService`; `light()` reads sticky battery state, BatteryManager values, and clocks without the Shizuku service. A retained `UidIdentityReader` brackets the full UID acquisition with fresh public PackageManager/UserManager identity reads in the app process (≤500 ms additional caller budget, ≤1,024 attempted calls); this is separate from cosmetic app-label caching.
4. `SnapshotCapturer` merges partial sources, records source availability and session skew, persists a `BatterySnapshot` with optional `UidIdentityScope` and per-UID owner/install fingerprints, and records misses instead of retrying in a loop. Missing, incomplete, changed or cross-user identity proof never becomes a trusted application-UID delta; parcel truncation invalidates per-UID interval usage without discarding independently measured device totals. History ingests are serialised (`historyIngestLock`). Each page is persisted as soon as it is read, skips events the retention prune would delete now, advances the forward-only cursor and deduplicates in Room. Insertion order is therefore chronology, and `historySessions()` splits runs by it (audit N-13).
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
refreshTicks (AppContainer.lastRefreshWallMs = StoreTicks.lastWallMs) ┴→ AnalysisSession → IntervalAnalyzer → StateFlow<AnalysisState>
                                                         └→ Cockpit, X-Ray, Sleep Lab (shared, passive flows; read via forSelection(selected))
AppContainer.timeline(from, to) = withContext(Default) { TimelineEvents.withSeeds(store.lastHistoryOfEachKindBefore(from), store.historyBetween(from, to))
                                  → TimelineBuilder.build } → shaped on shapingDispatcher → Cockpit chart / Sleep Lab hypnogram
```

- **Visibility drives work (H13, zero observer effect).** Nothing polls except the visible Cockpit (1.5 s `liveReadings`) and Health (only while charging); a screen leaving composition stops its reads and releases `holdBinding()`. `ToolkitActions` polls dexopt status every 5 s only while a job is RUNNING and collected, and holds the binding itself for that job. `AnalysisSession` computes only while its `analysis` StateFlow has subscribers (it keeps the last state when they leave), so background process starts do no analysis. Never `WhileSubscribed(5000)`.
- **Refresh and store ticks (audit N-04).** Every screen's `refresh()` goes through `RefreshCoordinator`.
  - `StoreTicks` ticks after every capture in the process that ingested history: a refresh, a marker, the charge job, or the debug probe. A refresh's tick fires after its prune even when the prune throws.
  - Its strictly increasing value is the pinned `lastRefreshWallMs` of `CockpitDependencies`/`HealthDependencies`. It re-runs the `AnalysisSession`, and the visible Cockpit and Health re-read the store on each tick.
  - The refresh debounce keeps its own clock (`RefreshCoordinator.lastRefreshWallMs`).
  - Cockpit and Sleep Lab follow the shared analysis only while visible (audit N-09). A transition of Shizuku to Ready clears the failure backoff, re-runs the last refresh if it failed (`refreshIfLastFailed`), then reconciles PENDING toolkit rows (`reconcileOnEachReady`, whose `onFailure` keeps the collector alive).
- **Threading.** `AppContainer.applicationScope` (`newApplicationScope()`: SupervisorJob + IO + a logging `CoroutineExceptionHandler`), `AndroidTelemetrySource.full()` and `LiveTelemetry` run on IO; `ShizukuManagerImpl.withTelemetryService` runs the block on its IO dispatcher; timeline building runs on `Dispatchers.Default` and chart/hypnogram shaping on the ViewModels' injected `shapingDispatcher`; the bundled dictionary loads once through `Preloaded` and never parses on the caller's thread.
- **Insets.** Each shell layout owns the system-bar and IME insets (see `modules.md`); screens receive an already-inset area.

## Privileged data flow

1. `ShizukuManagerImpl` watches Shizuku state: `NotInstalled`, `NotRunning`, `UnsupportedServer` below API 13, `PermissionRequired`, `PermissionDenied` and `Ready`. Permission is re-read on `refresh()`, never latched.
   - **Binder first (audit N-08):** the binder decides the state, not the manager app, because Sui (root) supplies a binder with no manager app installed. The manager package only tells `NotInstalled` from `NotRunning` while this process has never had a binder; after one, a lost binder is `NotRunning`. Binder-backed states (`PermissionRequired`, `PermissionDenied`, `Ready`) carry a `ShizukuBackend` (SHIZUKU/SUI) from `ShizukuFacade.isSui()` (`rikka.sui.Sui.isSui()`, shipped inside the Shizuku api 13.1.5). The UI names that backend; binder-less states say "Shizuku or Sui" (SQ-10, SQ-26, SQ-29).
   - **Binding:** the user service is bound on demand through `withTelemetryService`, and `holdBinding()` is the explicit UI keep-warm holder. The last release lingers 3 s before unbinding, so a tab handover keeps one process. After an unbind, the next bind waits for the old service's death (2 s fallback).
   - **Stub-cleanup fence (audit A-01, N-11):** every death that drops a binding, not only an unbind's, sets `stubCleanupFence`. It stays set until a non-immediate Main post runs after Shizuku 13.1.5 has cleared and evicted the dying connection stub. The timeout fallback preserves a live reference against one late death but is not a full cleanup fence.
   - **Daemon death:** it is a transport loss. It drops `applicationInfo`, so nothing binds against the dead daemon before a new binder brings Ready.
   - **Transaction queue (audit R-08):** the bind and unbind binder calls are queued under `bindingLock` and issued by one IO drainer with the lock released, so Main never waits for the Shizuku server; `refresh()` results are ordered by tickets. See `patterns.md`.
   - **State reads off Main (audit B-10):** `refresh()` is `suspend` and reads on the injected IO dispatcher; `start()`, the binder-received/dead and permission-result listeners (which Shizuku posts to Main) and `requestPermission()` launch their reads on IO, so no Shizuku server or PackageManager call runs on the caller's thread. `requestPermission()` asks for the dialog only when the fresh state is `PermissionRequired`; a request that throws is a transport loss only within the transport epoch it was decided in. A read still running when `stop()` returns publishes nothing.
2. Shizuku launches `TelemetryUserService` under the shell UID. It resolves the app UID and rejects every AIDL call unless `Binder.getCallingUid()` matches it.
3. Authorized calls read framework services (`batterystats`, plus the batterystats and alarm dumps) through binder clients, selected sysfs nodes, CPU frequency paths and `BatteryUsageStats` history. The calls are read txns 1–3, 6 and 8, typed toolkit txns 20–30 and `getApiVersion` 31 (per H11). Txns 4, 5 and 7 are retired; the debug probe reads `powerstats` and `deviceidle` in-process.
4. Results cross the AIDL boundary as parcelable DTOs with `available` and machine-readable `reason` fields. Dumps use an allowlisted `DumpTarget` and a capped pipe; arbitrary command execution is not exposed. Writes exist only as the typed toolkit calls (fixed absolute argv, `--user 0`, validated package names, hard-deny rules, one write lock); see "Toolkit write path" in [`modules.md`](modules.md).

The debug receiver exercises permission, IPC, capture, diff, direct readers, dumps, and timing. See [`entry-points.md`](entry-points.md) and [`onboarding.md`](onboarding.md).

## Product boundaries

Features are read-mostly: the only privileged writes are the five user-initiated toolkit actions (app-op, standby bucket, Doze profile, force-stop, bg-dexopt), each confirmed when risky and recorded in `toolkit_action`. No telemetry-resident/foreground service, WorkManager loop, or DI framework exists; DI is manual (`AppContainer`). The optional status-notification FGS is a separate presentation path driven by status ticks, with no telemetry polling or Shizuku binding (see [`entry-points.md`](entry-points.md)). Device-specific sysfs availability, hidden API signatures, synthetic emulator batteries, and history availability remain runtime assumptions recorded in [`PROGRESS.md`](../../PROGRESS.md). Design and decisions: [`docs/plans/phase-5.md`](../../docs/plans/phase-5.md) (H1–H18), [`phase-3.md`](../../docs/plans/phase-3.md) (F1–F9), [`phase-2.md`](../../docs/plans/phase-2.md).
