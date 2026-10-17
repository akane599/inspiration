# Patterns
*Last Updated: 2026-10-17*

These patterns are established across the Phase 1 IPC boundary, the Phase 2 snapshot pipeline, and the Phase 3 insight layer.

## Explicit availability

Cross-process DTOs and merged snapshots retain `available` plus nullable machine-readable `reason` fields. Missing permissions, framework APIs, sysfs nodes, dumps, history, and remote binders remain observable without exceptions crossing the AIDL boundary. `DumpParse.Ok`/`Unavailable` and `BinderResult.Ok`/`Failed` keep source-specific failure information until the domain layer chooses a fallback.

## Validity instead of clamping

`SnapshotDiffer` validates the clock/session tuple before producing deltas: matching `statsStartMs` and nondecreasing stats duration are required for batterystats values; matching boot count and increasing elapsed time are required for kernel/clock values. Negative per-UID or device-component deltas invalidate that part and are reported, not clamped. Missing UIDs are zero; a package change for a UID ≥ 10000 is treated as a new UID. Light snapshots provide a same-boot deep-sleep fallback when dump timers are unavailable.

## Deterministic merging

`SnapshotCapturer` merges usage, dump, alarm, kernel, and CPU sources by UID and source availability. Duplicate wakelocks are grouped by name, blank names become `<unnamed>`, and alarm counts are summed by UID/tag. Room repeats these invariants at the persistence boundary with conflict-updating wakelocks and conflict-ignored history rows.

## Pull-first capture and bounded work

Capture is triggered by app start, broadcasts, boot/package replacement, a persisted charging job, or a debug marker. There is no polling loop or resident foreground service. `CapturePolicy` chooses light work for screen transitions and cold broadcasts; full work has an explicit deadline, records a `capture_miss` on timeout, and history paging is capped. `withTelemetryService()` binds only while a capture needs the shell service; `holdBinding()` is the explicit long-lived UI exception.

## Persistence boundaries

`RoomSnapshotStore` writes a snapshot and its child tables inside a transaction. Room is configured for WAL, schema export is enabled, and marker foreign keys restrict deletion while snapshot children cascade. `MaintenanceDao.prune()` downsamples old live samples to hourly rows, removes data older than 30 days, and preserves marker-referenced snapshots. Backup is disabled by the app manifest and extraction rules before production writes.

## NotEvaluable instead of guessing

Each diagnostic rule returns either insights or a machine-readable reason (`DiagnosticReport.notEvaluable`), never a guess or silence: invalid batterystats, missing per-UID data (LIGHT endpoint), invalid components, absent sensor/signal sources. Only `UidDelta.valid` UIDs are evaluated and skipped ones are counted. R2 culprits carry `CulpritScope`, so a mixed screen-on/off interval is labelled "contributors over the whole interval", never "cause". The Battery Story omits any sentence whose fact is missing.

## Deterministic dictionary matching

Explanations come from a bundled JSON dictionary with fixed precedence (exact > longest prefix/suffix > longest contains > regex, then file order), load-time validation that fails on malformed entries, and name normalization (job variant letter, alarm prefixes). Localized text is test-enforced en/tr complete rather than in strings.xml.

## Interval metrics from history

`IntervalMetricsCalculator` derives screen on/off time and level drops from `history_event` over the diff's covered bounds, because broadcasts coalesce for frozen apps. Levels are seeded from any event kind, drops apply only on LEVEL events, charging time is excluded, and RESET/START boundaries are recorded as discontinuities rather than attributed. Rates are null below 15 minutes of state time.

## Fakes over mocks

IPC and telemetry unit tests inject binder sources, service binders, file roots, clocks, pipe factories, telemetry sources, and stores. [`core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/client/Fakes.kt`](../../core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/client/Fakes.kt) supplies recording/fake adapters; [`SnapshotCapturerTest.kt`](../../core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/capture/SnapshotCapturerTest.kt) uses fake source/store seams. Prefer small deterministic fakes over a mocking framework.

## Privileged boundary

Keep framework, history, and sysfs access in the shell `TelemetryUserService`; the app process coordinates through `ShizukuManager` and consumes DTOs. Preserve the caller-UID check, dump target allowlist, parcel budget, history bounds, and pipe cleanup. Do not add a generic command runner or move privileged reads into Compose/ViewModel code.
