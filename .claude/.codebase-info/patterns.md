# Patterns
*Last Updated: 2026-10-19*

These patterns are established across the Phase 1 IPC boundary, the Phase 2 snapshot pipeline, the Phase 3 insight layer, the Phase 4 design-system charts, and the Phase 5 screens and toolkit.

## Explicit availability

Cross-process DTOs and merged snapshots retain `available` plus nullable machine-readable `reason` fields. Missing permissions, framework APIs, sysfs nodes, dumps, history, and remote binders remain observable without exceptions crossing the AIDL boundary. `DumpParse.Ok`/`Unavailable` and `BinderResult.Ok`/`Failed` keep source-specific failure information until the domain layer chooses a fallback.

## Validity instead of clamping

`SnapshotDiffer` validates the clock/session tuple before producing deltas: matching `statsStartMs` and nondecreasing stats duration are required for batterystats values; matching boot count and increasing elapsed time are required for kernel/clock values. Negative per-UID or device-component deltas invalidate that part and are reported, not clamped. Missing UIDs are zero; a package change for a UID ≥ 10000 is treated as a new UID. Light snapshots provide a same-boot deep-sleep fallback when dump timers are unavailable.

## Deterministic merging

`SnapshotCapturer` merges usage, dump, alarm, kernel, and CPU sources by UID and source availability. Duplicate wakelocks are grouped by name, blank names become `<unnamed>`, and alarm counts are summed by UID/tag. Room repeats these invariants at the persistence boundary with conflict-updating wakelocks and conflict-ignored history rows.

## Pull-first capture and bounded work

Capture is triggered by app start, broadcasts, boot/package replacement, a persisted charging job, or a debug marker. There is no polling loop or resident foreground service. `CapturePolicy` chooses light work for screen transitions and cold broadcasts; full work has an explicit deadline, records a `capture_miss` on timeout, and history paging is capped. `withTelemetryService()` binds only while a capture needs the shell service; `holdBinding()` is the explicit long-lived UI exception.

## Deadlines that bind blocking calls

A Binder transaction ignores `Thread.interrupt`, so `withContext`/`runInterruptible` cannot end it at a deadline. `AndroidTelemetrySource.binderCall` runs each service call on the IO executor inside `suspendCancellableCoroutine`: the caller resumes as soon as it is cancelled, the abandoned call finishes on its own thread, and a late result is freed through `resume(value) { … release }` (a dump's pipe fd is closed). No new call starts after the deadline. Each history page is persisted under `NonCancellable` as soon as it is read, so pages read in time survive a later timeout. A broadcast capture's outer guard is the read deadline plus a persist budget; if the guard cancels anyway, the miss is recorded as `cancelled` and the light fallback still runs under `NonCancellable`. Bind and dump-read timeouts are separate (5 s / 2 s). Test fakes for these paths must block, not `delay()`: `AndroidTelemetrySourceDeadlineTest` and `ShizukuManagerReadyWaitTest` show the shape.

## Monotonic time for durations

Durations and deadlines use `elapsedRealtime`, never wall-clock differences: retention ages rows in the boot's earliest clock frame (`pruneRetention`), the dexopt hold deadline and screen-on segments run on `elapsedMs`. Wall time is for display and for matching rows, not for measuring.

## Persistence boundaries

`RoomSnapshotStore` writes a snapshot and its child tables inside a transaction. Room is configured for WAL, schema export is enabled, and marker foreign keys restrict deletion while snapshot children cascade. `MaintenanceDao.prune()` downsamples old live samples to hourly rows, removes data older than 30 days, and preserves marker-referenced snapshots. Backup is disabled by the app manifest and extraction rules before production writes.

## NotEvaluable instead of guessing

Each diagnostic rule returns either insights or a machine-readable reason (`DiagnosticReport.notEvaluable`), never a guess or silence: invalid batterystats, missing per-UID data (LIGHT endpoint), invalid components, absent sensor/signal sources. Only `UidDelta.valid` UIDs are evaluated and skipped ones are counted. R2 culprits carry `CulpritScope`, so a mixed screen-on/off interval is labelled "contributors over the whole interval", never "cause". The Battery Story omits any sentence whose fact is missing.

## Deterministic dictionary matching

Explanations come from a bundled JSON dictionary with fixed precedence (exact > longest prefix/suffix > longest contains > regex, then file order), load-time validation that fails on malformed entries, and name normalization (job variant letter, alarm prefixes). Localized text is test-enforced en/tr complete rather than in strings.xml.

## Interval metrics from history

`IntervalMetricsCalculator` derives screen on/off time and level drops from `history_event` over the diff's covered bounds, because broadcasts coalesce for frozen apps. Levels are seeded from any event kind, drops apply only on LEVEL events, charging time is excluded, and RESET/START boundaries are recorded as discontinuities rather than attributed. Rates are null below 15 minutes of state time.

## Chart draw-phase state and static/overlay split

Charts in `:core:designsystem` keep per-frame interaction state (cursor, brush, selection) in draw-phase state (`mutableLongStateOf`/`mutableFloatStateOf`) read only inside draw/layout lambdas, so dragging never recomposes. The readout row is an isolated child reading through a lambda. Static content (series paths, axis, hypnogram bars) is recorded once per cache build into an Offscreen `GraphicsLayer`; only the cursor/selection overlay is drawn on top each frame (SQ-60: this removed per-frame path re-rasterization, frame p50 32 -> 16 ms on the API 36 emulator). There is no allocation in the per-frame draw path. New chart components must keep the static/overlay split; gesture logic lives in the pure `ScrubGestureReducer`. Models use sanitizing factories instead of constructor `require`. Chart strings live only in `strings_chart.xml` (en/tr).

## MVI with visibility-driven work

Each feature has a `val`-only `State` (ImmutableList collections), a sealed `Intent`, and one-shot sealed `Effect`s (`RefreshFailed`, `ResetFinished`) shown as snackbars, never state. The Activity-scoped ViewModel outlives the tab, so device reads are tied to `Visible`/`Hidden` intents sent from `LifecycleStartEffect { onStopOrDispose }` in the Route (H13), not to the ViewModel's lifetime; pending confirmations and in-flight work are dropped on `Hidden`. Never `WhileSubscribed(5000)`. A ViewModel sees only its `XxxDependencies` interface, so tests use fakes of it.

**Failure containment.** All five feature ViewModels wrap their store and `ToolkitActions` calls in the shared [`attempt {}`](../../core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/app/Attempt.kt) (`telemetry.app.attempt`: value or null, cancellation rethrown) and turns null into a degraded card or a notice, never a crash; do not add private copies. Long-lived scopes (the application scope, the dexopt hold supervisor) carry a logging `CoroutineExceptionHandler`, and `reconcileOnEachReady` takes an `onFailure` so one failed reconcile does not end the collector.

**Analysis tagged by selection.** `AnalysisState.Ready(analysis, kind)` names the `ReferenceKind` it answers; consumers read it through `forSelection(selected)`, which maps a `Ready` for another selection to `Loading`, so a new selection never shows the previous interval's story or apps. Heavy shaping (timeline build, chart series) runs on an injected `shapingDispatcher` (`Dispatchers.Default` in production), never on Main.

**Package identity.** A batterystats UID is named by an installed package, never by the reported process name: `AppLabels.packageForUid(uid, reported)` returns `reported` only when `PackageManager` lists it for the UID, else the alphabetically first package (`packagesForUid`), else null. A confirmation dialog pins its target (`uid`, package) when it is planned, and the confirm runs against that pin, not a re-read.

## Refresh: one debounced, mutex-guarded entry point

`RefreshCoordinator.refresh()` serialises captures, returns `Debounced` within 60 s of a success, replays a cached `Failed` for 12 s after a failure (so Visible/onStart bursts against a hung service queue nothing), and runs the capture `NonCancellable`. A Shizuku transition to Ready clears the failure backoff and then calls `refreshIfLastFailed()`, which re-runs a refresh only if the last capture failed (e.g. the cold-start one made before the binder arrived). Nothing schedules a refresh. `ShizukuManager.withTelemetryService` waits out `NotRunning` within its timeout (a cold process gets the Shizuku binder after `start()`), and `MainActivity.onStart` calls `shizukuManager.refresh()` because a grant made in the Shizuku app sends no callback; permission state is always re-read, never latched.

## Toolkit writes: plan, confirm, audit, read back

Writes go plan → (confirm when risky) → PENDING `toolkit_action` row → privileged call → settle from the service's own before/after → read-back `verify`. `restore`, `planRestore` and `resetAll` return a target to the original of its chain (`chainOriginal`), and `activeOverrides()` lists only changes that read-back shows are still in effect; a target already at its original is not an override and nothing is written. Chain membership comes from the **observed** before/after the service returns, not from the row status: a write reported FAILED that still moved its target to a third value stays in the chain (and in retention), and a same-value write over an existing override does not hide it. `reconcile()` finalizes crash-orphaned PENDING rows. Outcomes are not binary: a write that may have committed but could not be confirmed (`UNVERIFIED`) stays PENDING so it remains in the restore chain, and nothing is written on top of an unsettled row for the same target. The write is never taken from an Intent or URI; the UI names a package only from the in-app list. Warnings, refusals and unavailability are typed enums whose wording lives in the UI's strings.

## Design-system components over raw Material

Screens use `ScreenHeader`/`CardTitle`, `MetricBar` (not `LinearProgressIndicator`), `SegmentedToggle`, `MetricValue(shrinkToFit)`/`MetricTile`, `TimeSliceBanner`, `LiveGauge(isCharging = null)` for an unknown state, `MaterialTheme.statusColors` for ok/warning/neutral, and `TelemetryFormat` for every number, unit, duration and date (U+2212 minus). No raw colors, `.dp`/`.sp` or hard-coded strings in screen files; text is en+tr.

## Fakes over mocks

IPC and telemetry unit tests inject binder sources, service binders, file roots, clocks, pipe factories, telemetry sources, and stores. [`core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/client/Fakes.kt`](../../core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/client/Fakes.kt) supplies recording/fake adapters; [`SnapshotCapturerTest.kt`](../../core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/capture/SnapshotCapturerTest.kt) uses fake source/store seams. Prefer small deterministic fakes over a mocking framework.

## Privileged boundary

Keep framework, history, and sysfs access in the shell `TelemetryUserService`; the app process coordinates through `ShizukuManager` and consumes DTOs. Preserve the caller-UID check, dump target allowlist, parcel budget, history bounds, and pipe cleanup. Do not add a generic command runner or move privileged reads or writes into Compose/ViewModel code. Privileged writes exist only as typed AIDL txns 20–30 with explicit int codes (no Kotlin enum crosses AIDL), `matchEntire` package validation, hard-deny rules, the caller-UID check first and one write lock (H11); features reach them only through `ToolkitActions`, never `ToolkitGateway` (H12).
