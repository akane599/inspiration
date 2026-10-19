# Patterns
*Last Updated: 2026-10-19*

These patterns are established across the Phase 1 IPC boundary, the Phase 2 snapshot pipeline, the Phase 3 insight layer, the Phase 4 design-system charts, and the Phase 5 screens and toolkit.

## Explicit availability

Cross-process DTOs and merged snapshots retain `available` plus nullable machine-readable `reason` fields. Missing permissions, framework APIs, sysfs nodes, dumps, history, and remote binders remain observable without exceptions crossing the AIDL boundary. `DumpParse.Ok`/`Unavailable` and `BinderResult.Ok`/`Failed` keep source-specific failure information until the domain layer chooses a fallback.

## Validity instead of clamping

`SnapshotDiffer` validates the clock/session tuple before producing deltas. Batterystats values need the same stats session and a nondecreasing stats duration; "same session" is the one shared predicate in [`BatteryStatsSession.kt`](../../core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/BatteryStatsSession.kt): `statsStartMs` within ±2 s. The differ, `dischargeDelta`, the capturer's skew check and the Room `firstAtOrAfter` range query all use that predicate. Kernel/clock values need a matching boot count and increasing elapsed time. Negative per-UID or device-component deltas invalidate that part and are reported, not clamped. Application UIDs (app ID ≥ 10000 within each Android user) need known capture-time `UidIdentityScope`s at both endpoints with the same user ID and user serial, a scope covering the UID, the same boot with increasing elapsed time, and equal owner/install fingerprints. The reader epoch and package-change sequence are deliberately **not** compared across snapshots (F-02): they differ after every service restart or package update, and comparing them hid every app on real phones. Missing proof is `uid_identity_unknown:<uid>`; a changed fingerprint is `uid_identity_changed:<uid>`. A reported package hint rotating within a shared UID never resets counters to zero. Truncation at either endpoint invalidates all per-UID usage; device components derived from an incomplete UID sum are omitted. Missing dump/alarm observations are not zero baselines: those counters are withheld without discarding independently comparable usage energy.

[`UidIdentityReader.kt`](../../core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/source/UidIdentityReader.kt) brackets acquisition with fresh user serial and package-change sequence reads. Within one capture, an unchanged epoch, serial and sequence prove that the owner reads are consistent. The fingerprint (full UID, user serial, complete sorted owner group with install times) carries identity across captures.
- **Owner reads:** one bulk `installedOwners()` read (`getInstalledPackages(0)`, counted as one call) fingerprints every covered app UID locally. The per-UID `getPackagesForUid`/`getPackageInfo` loop runs only when the bulk read returns null, in caller priority order (highest usage energy first), so a budget cut drops only negligible rows.
- **Budgets:** identity work has a ≤500 ms additional caller budget (50/400/50 ms opening/owners/closing) and ≤1,024 attempted calls. Incomplete owner groups omit their fingerprints.
- **Physical gate:** a fair `Mutex`, so overlapping captures take turns within their phase budgets instead of failing each other (F-23). It is released only when the native call returns, in a `finally`. A caller that times out or is cancelled mid-call leaves an abandoned-call token: until that call returns, new calls fail fast and proofs are void, so repeated captures can't pile up identity transactions. A timed-out or throwing call rotates the epoch. Legacy Room rows stay unknown, never backfilled from current installed packages.

Light snapshots provide a same-boot deep-sleep fallback when dump timers are unavailable. Comparable accumulated batterystats timers may preserve screen-off sleep across a reboot in one stats epoch; raw kernel clocks and CPU-frequency residency do not. Sleep Lab uses the shared `reportableDeepSleepRatio()` gate rather than rejecting persisted sleep merely because `kernelCounters` is false.

## Deterministic merging

`SnapshotCapturer` merges usage, dump, alarm, kernel, and CPU sources by UID and source availability. Duplicate wakelocks are grouped by name, blank names become `<unnamed>`, and alarm counts are summed by UID/tag. Room repeats these invariants at the persistence boundary with conflict-updating wakelocks and conflict-ignored history rows.

## Pull-first capture and bounded work

Capture is triggered by app start, broadcasts, boot/package replacement, a persisted charging job, or a debug marker. There is no polling loop or resident foreground service. `CapturePolicy` chooses light work for screen transitions and cold broadcasts; full work has an explicit deadline, records a `capture_miss` on timeout, and history paging is capped. `withTelemetryService()` binds only while a capture needs the shell service; `holdBinding()` is the explicit long-lived UI exception.

## Deadlines that bind blocking calls

A Binder transaction ignores `Thread.interrupt`, so `withContext`/`runInterruptible` cannot end it at a deadline. `AndroidTelemetrySource.binderCall` runs each service call on the IO executor inside `suspendCancellableCoroutine`: the caller resumes as soon as it is cancelled, the abandoned call finishes on its own thread, and a late result is freed through `resume(value) { … release }` (a dump's pipe fd is closed). No new call starts after the deadline. Each history page is persisted under `NonCancellable` as soon as it is read, so pages read in time survive a later timeout. A broadcast capture's outer guard is the read deadline plus a persist budget; if the guard cancels anyway, the miss is recorded as `cancelled` and the light fallback still runs under `NonCancellable`. Bind and dump-read timeouts are separate (5 s / 2 s). Kernel-only reads additionally go through `kernelBatterySnapshot(timeoutMs)`, sharing one binding-plus-transaction deadline between live telemetry and the Health fallback (2 s); timeout returns null and external cancellation propagates. This bounds caller time, not the lifetime of a stuck native transaction. Test fakes for these paths must block, not `delay()`: `AndroidTelemetrySourceDeadlineTest` and `ShizukuManagerReadyWaitTest` show the shape.

## Monotonic time for durations

Durations and deadlines use `elapsedRealtime`, never wall-clock differences: retention ages rows in the boot's earliest clock frame (`pruneRetention`), the dexopt hold deadline and screen-on segments run on `elapsedMs`. Wall time is for display and for matching rows, not for measuring.

## Persistence boundaries

`RoomSnapshotStore` writes a snapshot and its child tables inside a transaction. Room is configured for WAL, schema export is enabled, and marker foreign keys restrict deletion while snapshot children cascade. `MaintenanceDao.prune()` downsamples old live samples to hourly rows, removes data older than 30 days (`RETENTION_WINDOW_MS`), keeps `telemetry_hourly` for 365 days, and preserves marker-referenced snapshots. History ingest skips rows that the prune would delete at the same `retentionNowMs`. Backup is disabled by the app manifest and extraction rules before production writes.

## NotEvaluable instead of guessing

Each diagnostic rule returns either insights or a machine-readable reason (`DiagnosticReport.notEvaluable`), never a guess or silence: invalid batterystats, missing per-UID data (LIGHT endpoint), invalid components, absent sensor/signal sources. Only `UidDelta.valid` UIDs are evaluated and skipped ones are counted. R2 culprits carry `CulpritScope`, so a mixed screen-on/off interval is labelled "contributors over the whole interval", never "cause". The Battery Story omits any sentence whose fact is missing. `IntervalAnalyzer` always passes `endsNow = false` for stored/cached coverage, so historical fallback intervals are not narrated as "the last" hours.

## Deterministic dictionary matching

Explanations come from a bundled JSON dictionary with fixed precedence (exact > longest prefix/suffix > longest contains > regex, then file order), load-time validation that fails on malformed entries, and name normalization (job variant letter, alarm prefixes). Localized text is test-enforced en/tr complete rather than in strings.xml.

## Interval metrics from history

`IntervalMetricsCalculator` derives screen on/off time and level drops from `history_event` over the diff's covered bounds, because broadcasts coalesce for frozen apps. Levels are seeded from any event kind, drops apply only on LEVEL events, charging time is excluded, and RESET/START boundaries are recorded as discontinuities rather than attributed. Rates are null below 15 minutes of state time.

**RESET baseline (N-01).** A RESET is followed, at the same instant, by the screen and doze state it restates. Stored order keeps the RESET ahead of that state (`ORDER BY wallMs, id`, and fakes keep list order), so a period that spans a reset is cut there instead of becoming unknown. A bare legacy RESET's screen state is unknown.

**Reference windows (N-02, N-12).** `ReferenceResolver` starts "since" windows at the newest history event. A capture within `CAPTURE_SLACK_MS` (10 s) after that event counts as the event's own. A later capture starts the window only when the history is provably stale: a snapshot between the two reverts the event, or the capture is more than the slack past the history horizon. A marker reference must precede the end snapshot.

**History horizon (F-04).** The last known state is carried only up to the history horizon: the wall time of the newest FULL snapshot whose `history` availability row is available with no reason (`SnapshotStore.historyHorizonWallMs()`, one `SnapshotDao.newestWallMsWithCompleteSource` query; helpers in [`HistoryHorizon.kt`](../../core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/store/HistoryHorizon.kt)).
- A tail up to `HISTORY_HORIZON_GRACE_MS` (2 min: the 60 s refresh debounce plus slack) stays known.
- A longer tail is `unknownMs` from the horizon itself. That turns off the Summary and `SCREEN_OFF_ONLY` gates, and `TimelineBuilder` closes lanes there.
- An interval whose end snapshot ingested history needs no lookup.

## Chart draw-phase state and static/overlay split

Charts in `:core:designsystem` keep per-frame interaction state (cursor, brush, selection) in draw-phase state (`mutableLongStateOf`/`mutableFloatStateOf`) read only inside draw/layout lambdas, so dragging never recomposes. The readout row is an isolated child reading through a lambda. Static content (series paths, axis, hypnogram bars) is recorded once per cache build into an Offscreen `GraphicsLayer`; only the cursor/selection overlay is drawn on top each frame (SQ-60: this removed per-frame path re-rasterization, frame p50 32 -> 16 ms on the API 36 emulator). There is no allocation in the per-frame draw path. New chart components must keep the static/overlay split; gesture logic lives in the pure `ScrubGestureReducer`.
- **Edge grab (F-08):** a Down within half the 48 dp target of a selection edge enters `PressedOnHandle` without consuming the event. It becomes a delta-relative handle drag only after horizontal touch slop. Vertical movement releases the gesture to the parent scroll, and an Up before slop is an ordinary tap.
- **Data reloads (F-12):** the `pointerInput` loop is keyed stably and reads the window through `rememberUpdatedState`, so a reload never restarts a gesture. Each gesture keeps the scale captured at Down.
- **Tick labels (F-13):** chosen from the selected tick step (`niceTimeTickSet().dayAligned`), not the window length. Day steps show dates. Sub-day steps show times, with the date at midnight ticks.
- **Selection edges (N-21, N-24):** only selection edges inside the window are drawn or grabbable, and a selection that no longer overlaps the window is neither dimmed nor adjusted (`TimeRanges`). The slice-control step scales with the chart window.
- **Text and zones (N-19, N-22, N-23):**
  - Axis and readout values are formatted through resources and `TelemetryFormat`.
  - The system zone is remembered (`SystemZone`), not rebuilt on every recomposition.
  - Hypnogram a11y text carries dates whenever the window spans two local dates.
- **JVM-testable glue (N-28):** gesture glue (`ScrubGestures`) and axis-label placement (`TimeAxis`) are pulled out of composables so JVM tests reach them.
- **Single points (F-14):** a one-point series draws a dot. Models use sanitizing factories instead of constructor `require`. Chart strings live only in `strings_chart.xml` (en/tr). [`SliceControls.kt`](../../core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/scrub/SliceControls.kt) adds standard focusable buttons for interval creation, one-minute boundary adjustments and clearing through the same caller-owned `onSelectionChange` callback; window/minimum-duration checks disable impossible adjustments without adding a second selection state.

## MVI with visibility-driven work

Each feature has a `val`-only `State` (ImmutableList collections), a sealed `Intent`, and one-shot sealed `Effect`s (`RefreshFailed`, `ResetFinished`) shown as snackbars, never state. The Activity-scoped ViewModel outlives the tab, so device reads are tied to `Visible`/`Hidden` intents sent from `LifecycleStartEffect { onStopOrDispose }` in the Route (H13), not to the ViewModel's lifetime; pending confirmations and in-flight work are dropped on `Hidden`. Never `WhileSubscribed(5000)`. A ViewModel sees only its `XxxDependencies` interface, so tests use fakes of it.

**Failure containment.** All five feature ViewModels wrap their store and `ToolkitActions` calls in the shared [`attempt {}`](../../core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/app/Attempt.kt) (`telemetry.app.attempt`: value or null, cancellation rethrown) and turns null into a degraded card or a notice, never a crash; do not add private copies. Long-lived scopes (the application scope, the dexopt hold supervisor) carry a logging `CoroutineExceptionHandler`, and `reconcileOnEachReady` takes an `onFailure` so one failed reconcile does not end the collector.

**Analysis tagged by selection.** `AnalysisState.Ready(analysis, kind)` names the `ReferenceKind` it answers; consumers read it through `forSelection(selected)`, which maps a `Ready` for another selection to `Loading`, so a new selection never shows the previous interval's story or apps. A reference kind alone is not a measurement-cache key: Sleep Lab retains a failed-refresh hypnogram only for the same covered endpoints; X-Ray closes detail/control/confirmation work on non-ready analysis or when the full UID/package row disappears, and refreshes surviving detail from the new app row. Heavy shaping (timeline build, chart series) runs on an injected `shapingDispatcher` (`Dispatchers.Default` in production), never on Main.

**Visit-scoped effects.** Sleep Lab, Cockpit and Health do this on `Hidden`, without cancelling the shared capture:
- clear the departed visit's refresh ownership (`userWaiting`) and progress;
- drain queued effects;
- gate effect sends on being visible.

A later explicit refresh may join that running capture, and only the waiting visible visit gets its failure notice. Routes collect effects under `repeatOnLifecycle(STARTED)`.

**Recompute without a capture (F-10).** `AnalysisSession.recompute()` merges an explicit request into the tick stream and keeps the current state until the result replaces it, with no Loading flicker. Cockpit, X-Ray and Sleep Lab expose it as `recomputeAnalysis()` on their dependency interfaces. Retry/Capture/pull call it when the analysis is Failed and after a refresh returns Debounced, Failed or null.

**Live gauge staleness (F-07).** The Cockpit gauge returns to dashes after `LIVE_STALE_MS` (9.5 s) without a reading, on a live-source failure, or when Shizuku leaves Ready. A single missed reading never flashes dashes.

**Live gauge charging state (N-10).** "Charging" comes from the battery's own status and the measured current, never from the plug alone. A plugged battery can be full, held at a charge limit, or losing to the load.
- On a charger, a current within `CHARGING_DRAIN_DEADBAND_MA` (50 mA, `CockpitMappers`) of zero is noise and has no time to empty.
- A label or time left decided against the deadband holds until the current is `DEADBAND_HYSTERESIS_MA` (10 mA) back inside it, so it cannot flip on every reading.

**Live current calibration (N-05).** Unit, sign convention and cell layout are device constants. `PowerCalibrator` learns them across every reading, so one near-zero or skewed sample never re-decides them.
- **Persistence:** what is settled goes to `SharedPreferencesCalibrationStore` (prefs `power_calibration`), keyed to `Build.FINGERPRINT`. A new build's process starts with the old locks as guesses only.
- **Learning:** an unplugged first reading of 50–20,000 is taken as mA. A lock is re-learned after 3 disputes in a row. A vote needs at least `CLEAR_MAJORITY_VOTES` (6).
- **Unsupported unit:** a current whose unit nothing supports yet is neither shown ("—") nor stored.

**Package identity.** A batterystats UID is named by an installed package, never by the reported process name: `AppLabels.packageForUid(uid, reported)` returns `reported` only when `PackageManager` lists it for the UID, else the alphabetically first package (`packagesForUid`), else null. `CachedAppLabels` caches only cosmetic labels/icons; `packagesForUid` reads membership fresh, including after earlier empty results. This display lookup is not capture-time UID continuity proof. A confirmation dialog pins its target (`uid`, package) when it is planned, and the confirm runs against that pin, not a re-read. X-Ray keeps analytics separate from package-only user-0 controls: other-user UIDs get `ActionsState.UnsupportedUser`, with checks before reads, planning, confirmation and execution.

## Health evidence and optional live persistence

`HealthInputs` distinguishes a failed read (null) from a successful empty list; when every source fails and there are no live points, Health is Error rather than Empty. Observed unplug/replug boundaries outrank stale history and gap heuristics; ending a visible visit clears its live points and observed edges. `chargeSession` fills gaps from history without bridging continuous foreground runs, and `wearAnalysis` uses the predecessor's source cap (30 s foreground / 20 min history), not the successor's. Device thermal severity at or above MODERATE is an observed status, not proof of charging throttling or a universal temperature limit. A failed optional `LiveTelemetry.insertSamples` is contained with `attempt {}` so later readings and writes continue; cancellation still propagates.

## Refresh: one debounced, mutex-guarded entry point

Stored views follow `StoreTicks`, not the refresh result: a tick follows every capture that ingested history, whatever ran it (see "Refresh and store ticks" in [`architecture.md`](architecture.md)). A new entry point that ingests history must tick it, as `captureMarker` and the charge job do. `RefreshCoordinator.refresh()` serialises captures, returns `Debounced` within 60 s of a success, replays a cached `Failed` for 12 s after a failure (so Visible/onStart bursts against a hung service queue nothing), and runs the capture `NonCancellable`. A Shizuku transition to Ready clears the failure backoff and then calls `refreshIfLastFailed()`, which re-runs a refresh only if the last capture failed (e.g. the cold-start one made before the binder arrived). Nothing schedules a refresh. `ShizukuManager.withTelemetryService` waits out `NotRunning` within its timeout (a cold process gets the Shizuku binder after `start()`); acquire-or-wait is decided under `bindingLock`, where Ready is published (F-16).

**Dead binder recovery (F-17).** After a slow unbind, the timeout rebind shares the old process's Shizuku connection stub, whose once-only `died()` is spent on the old process. The manager records that (`spentStubGeneration`) and links its own death recipient to each accepted binder. A `RemoteException` on a dead binder (`!isBinderAlive`) goes through the same handler, which drops the binding only while the stub can no longer report; the next use binds a fresh stub.

`MainActivity.onStart` calls `shizukuManager.refresh()` because a grant made in the Shizuku app sends no callback; permission state is always re-read, never latched.

## Toolkit writes: plan, confirm, audit, read back

Writes go plan → (confirm when risky) → PENDING `toolkit_action` row → privileged call → settle from the service's own before/after → read-back `verify`. `restore`, `planRestore` and `resetAll` return a target to the original of its chain (`chainOriginal`), and `activeOverrides()` lists only changes that read-back shows are still in effect; a target already at its original is not an override and nothing is written. Chain membership comes from the **observed** before/after the service returns, not from the row status: a write reported FAILED that still moved its target to a third value stays in the chain (and in retention), and a same-value write over an existing override does not hide it. A partially effective failed restore is also a link, not a closed chain: its persisted typed intended value anchors the original even if older history was pruned or its confirmed before-value was external. Doze profile writes require an original replayable by a fresh executor (`DozeRaw.isRestorable`), not only one remembered by the current process. `reconcile()` finalizes crash-orphaned PENDING rows. Outcomes are not binary: a write that may have committed but could not be confirmed (`UNVERIFIED`) stays PENDING so it remains in the restore chain, and nothing is written on top of an unsettled row for the same target. The write is never taken from an Intent or URI; the UI names a package only from the in-app list. Warnings, refusals and unavailability are typed enums whose wording lives in the UI's strings.

**Write outcomes and unreadable overrides:**
- **Never dispatched (N-07):** a write known never to have been dispatched is final at once, FAILED with no `after`, so it never joins a chain or holds the dexopt card in Unknown. Examples: no service, a bind timeout, or a dead binder or stale API at the API check. A lost reply after dispatch keeps the row PENDING.
- **Unreadable overrides (N-06):** an override whose read-back is refused or fails, or whose PENDING row could not be settled, stays listed with `Override.unverified`, never dropped. Nothing is written over an unsettled row; Reset all reports it `UNVERIFIED`.
- **Protected apps:** a protected app accepts only the undo of Inspiration's own change, and only when that undo lifts a restriction. The service enforces the lift-only half and stays stateless; the client refuses any new change.

**bg-dexopt cancel (N-03).** The cancel command is chosen per API level, and bg-dexopt is refused below API 33. A cancel counts as confirmed only when our client process exits; a timed-out cancel is unverified. A user-cancelled row settles from the service status, and the destroy hook skips the cancel before our client has started.

**App-op `allow` vs `default` (F-01, toolkit API 3).** Android enforces `default` as restricted for `RUN_ANY_IN_BACKGROUND`, so every permissive write, restore and reset writes `AppOpMode.ALLOW` (code 3).
- The service refuses mode `DEFAULT` (2) as a write.
- `getAppOps` "No operations." parses as allow.
- Allow and default are distinct states, and a live `default` renders as restricted.
- A recorded legacy `default` original restores as allow.
- A closed 0.2.2 chain whose restore wrote `default` still counts as an override while the live mode reads `default` (`legacyDefaultRestore`), so Reset all repairs it.

**User-0 containment (F-05).** The executor's shared `gate()` refuses every package read, write and plan with `UNSUPPORTED_USER` (14) when the app's own user (`toolkitUserId(appUid)`) is not 0. Doze and dexopt stay available.

**Doze compare-and-set (F-06/F-18).**
- A custom doze original replaced via Balanced then Standard stays an active override and restores through the service compare-and-set.
- An apply carries the value the user decided against (`ApplyDoze.expectedBefore` = `SeenDoze`) and is refused STALE if that value changed.
- The VM builds or confirms a doze apply only while the doze card is Loaded.

Dexopt admission runs outside the client mutex, but its durable PENDING row is protected by `dexoptStartsInFlight` until admission settles; an earlier job's terminal status or cancel cannot settle a new start. While admission is unresolved, a non-running service status maps to Unknown. Admission settlement and local-ID cleanup are cancellation-fenced. The binding-hold supervisor logs/retries audit-query failures and retains its independent monotonic 60-minute-plus-2-minute cap, even with no UI collector.

## Design-system components over raw Material

Screens use `ScreenHeader`/`CardTitle`, `MetricBar` (not `LinearProgressIndicator`), `SegmentedToggle`, `MetricValue(shrinkToFit)`/`MetricTile`, `TimeSliceBanner`, `LiveGauge(chargeState = null)` for an unknown state, `MaterialTheme.statusColors` for ok/warning/neutral, and `TelemetryFormat` for every number, unit, duration and date (U+2212 minus). No raw colors, `.dp`/`.sp` or hard-coded strings in screen files; text is en+tr.

## Fakes over mocks

IPC and telemetry unit tests inject binder sources, service binders, file roots, clocks, pipe factories, telemetry sources, and stores. [`core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/client/Fakes.kt`](../../core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/client/Fakes.kt) supplies recording/fake adapters; [`SnapshotCapturerTest.kt`](../../core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/capture/SnapshotCapturerTest.kt) uses fake source/store seams. Prefer small deterministic fakes over a mocking framework.

## Privileged boundary

Keep framework, history, and sysfs access in the shell `TelemetryUserService`; the app process coordinates through `ShizukuManager` and consumes DTOs. Preserve the caller-UID check, dump target allowlist, parcel budget, history bounds, and pipe cleanup. Do not add a generic command runner or move privileged reads or writes into Compose/ViewModel code. Privileged writes exist only as typed AIDL txns 20–30 with explicit int codes (no Kotlin enum crosses AIDL), `matchEntire` package validation, hard-deny rules, the caller-UID check first and one write lock (H11); features reach them only through `ToolkitActions`, never `ToolkitGateway` (H12).
