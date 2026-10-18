# Database
*Last Updated: 2026-10-18*

## Room setup

`:core:database` owns Room schema v1 and depends on `:core:telemetry-engine` for domain models and `SnapshotStore`. [`InspirationDatabase.kt`](../../core/database/src/main/kotlin/com/akane/inspiration/database/InspirationDatabase.kt) exports the schema, uses `Room.databaseBuilder`, and explicitly sets `JournalMode.WRITE_AHEAD_LOGGING`. KSP and the Room Gradle plugin write the schema snapshot to [`core/database/schemas/com.akane.inspiration.database.InspirationDatabase/1.json`](../../core/database/schemas/com.akane.inspiration.database.InspirationDatabase/1.json).

[`RoomSnapshotStore.kt`](../../core/database/src/main/kotlin/com/akane/inspiration/database/store/RoomSnapshotStore.kt) is the domain adapter. Snapshot insertion and child-row writes run in a Room transaction; reads reassemble `BatterySnapshot`, availability, system timers, UID data, components, wakelocks, alarms, and CPU frequencies. History inserts use conflict-ignore semantics, cursors are upserted, and duplicate wakelocks are merged by adding duration/count values.

## Tables and ownership

Entities are defined in [`Entities.kt`](../../core/database/src/main/kotlin/com/akane/inspiration/database/Entities.kt):

- `snapshot` stores FULL/LIGHT identity, trigger, clock validity tuple, battery values, and usage truncation count.
- `snapshot_system` stores parsed batterystats timers and discharge values, plus Phase 3 `signalBin0Ms..signalBin4Ms`, `signalScanningMs` and `mobileRadioActiveMs` (all nullable; null bins mean the dump carried none); it is optional and cascades from `snapshot`.
- `snapshot_availability` records source availability/reason for usage, dump, kernel, CPU, history, or light reads.
- `snapshot_uid_sensor` (Phase 3) stores per-UID sensor usage `(snapshotId, uid, handle, totalMs, bgMs, count)` with primary key `(snapshotId, uid, handle)`; it cascades from `snapshot` and is read/written through `SnapshotDao.insertUidSensors`/`uidSensorsForSnapshot`.
- `snapshot_uid`, `snapshot_component`, `snapshot_wakelock`, `snapshot_alarm`, and `snapshot_cpu_freq` store per-UID, component, merged wakelock/alarm, and CPU time-in-state details. Their foreign keys cascade with the parent snapshot.
- `marker` references a snapshot with `RESTRICT`, so a marker protects its snapshot during retention pruning.
- `history_event` stores stats-session history with a unique `(historyTimeMs, kind, wallMs)` key; `history_cursor` stores the singleton paging cursor.
- `telemetry_sample` stores charging live readings; `telemetry_hourly` stores their retention aggregate.
- `charge_session` stores capacity segments/verdicts with a unique `(statsStartMs, startHistoryTimeMs)` key.
- `capture_miss` records bounded full-capture failures by wall time and trigger.
- `toolkit_action` (Phase 5) is the audit row for every privileged toolkit write (`ToolkitActionEntity`; explicit status/kind codes, before/after raw values, `restoresId`, indexed by status). The store maps it to `ToolkitActionRecord`/`ToolkitActionStatus` (PENDING, DONE, FAILED, REFUSED, RESTORED, CANCELLED) and prunes to a bounded number of rows.

The schema follows the contracts in [`docs/plans/phase-2.md`](../../docs/plans/phase-2.md) and, for the signal/sensor additions, [`docs/plans/phase-3.md`](../../docs/plans/phase-3.md) F2.

**Schema v1 was edited in place for Phase 3** (decision E9; no version bump, no destructive fallback). The Room `identityHash` changed, so any existing dev install must be cleared before running a new build: `adb shell pm clear com.akane.inspiration` (or uninstall), also after a revert. The `device-probe` skill's `--clear --grant` does this. There is no export or user-facing database screen in Phase 2.

## DAO and retention behavior

[`Daos.kt`](../../core/database/src/main/kotlin/com/akane/inspiration/database/Daos.kt) exposes:

- `SnapshotDao`: insert/read snapshots and children, query latest/nearest snapshots, `between(from, to)` (bare snapshot rows in a wall-time range, exposed as `SnapshotStore.snapshotsBetween` with empty per-UID lists, used by the status notification), and merge wakelocks with an `ON CONFLICT ... DO UPDATE` sum.
- `MarkerDao`: insert and resolve marker-to-snapshot references.
- `HistoryDao`: insert-ignore events, query wall-time ranges, `lastOfEachKindBefore(beforeWallMs)` (the newest event of each kind strictly before a bound, oldest first, in one query; the store exposes it as `lastHistoryOfEachKindBefore`, which seeds the timeline state without scanning all older history), and read/upsert the cursor.
- `ToolkitActionDao`: insert (with `insertAndPrune`), update, newest-N and by-status queries for the `toolkit_action` audit table.
- `TelemetryDao`: insert live samples and read hourly aggregates.
- `ChargeSessionDao`: upsert segments and return accepted estimates chronologically.
- `CaptureMissDao`: record misses.
- `MaintenanceDao`: downsample samples by hour, delete samples, unmarked snapshots, history, and misses older than 30 days in one transaction.

Pruning is called by app-open and charging-job captures, not by a resident loop. Snapshot children cascade; marker-referenced snapshots remain until the marker is removed by a future policy.

## Tests and privacy boundary

[`InspirationDatabaseTest.kt`](../../core/database/src/test/kotlin/com/akane/inspiration/database/InspirationDatabaseTest.kt) checks database opening, WAL, cascades, and schema behavior. [`RoomSnapshotStoreTest.kt`](../../core/database/src/test/kotlin/com/akane/inspiration/database/store/RoomSnapshotStoreTest.kt) checks round trips, availability, history/cursor, merge behavior, references, and pruning under Robolectric.

The app sets `android:allowBackup="false"` and points to [`backup_rules.xml`](../../app/src/main/res/xml/backup_rules.xml) and [`data_extraction_rules.xml`](../../app/src/main/res/xml/data_extraction_rules.xml), excluding cloud backup and device transfer before production database writes.
