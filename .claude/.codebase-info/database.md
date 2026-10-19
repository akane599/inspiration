# Database
*Last Updated: 2026-10-19*

## Room setup

`:core:database` owns Room schema **v5** and depends on `:core:telemetry-engine` for domain models and `SnapshotStore`. [`InspirationDatabase.kt`](../../core/database/src/main/kotlin/com/akane/inspiration/database/InspirationDatabase.kt) exports the schema, uses `Room.databaseBuilder` with `addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)`, and explicitly sets `JournalMode.WRITE_AHEAD_LOGGING`. KSP and the Room Gradle plugin write one snapshot per version to [`core/database/schemas/com.akane.inspiration.database.InspirationDatabase/`](../../core/database/schemas/com.akane.inspiration.database.InspirationDatabase/) (`1.json`–`5.json`).

| Migration | Change |
|---|---|
| 1 → 2 | Rebuilds `battery_health_sample` (copy, drop, rename) |
| 2 → 3 | Clears `charge_session` and `battery_health_sample.measuredMah`: derived estimates cannot be certified across history discontinuities |
| 3 → 4 | `CREATE INDEX IF NOT EXISTS index_snapshot_trigger_wallMs ON snapshot(trigger, wallMs)` narrows trigger lookups (originally audit F-19); current ordering is described below |
| 4 → 5 | Adds nullable `snapshot.uidIdentityUserId`, `uidIdentityUserSerial`, `uidIdentityReaderEpoch`, `uidIdentityPackageChangeSequence` and `snapshot_uid.ownerInstallFingerprint`. Preserves legacy rows with SQL NULL proof; never infers historical identity from a package hint (audit A-08) |

[`RoomSnapshotStore.kt`](../../core/database/src/main/kotlin/com/akane/inspiration/database/store/RoomSnapshotStore.kt) is the domain adapter. Snapshot insertion and child-row writes run in a Room transaction; reads reassemble `BatterySnapshot`, availability, system timers, UID data, components, wakelocks, alarms, and CPU frequencies. Callers that need only a time use the row-only reads (`latestClockByTrigger`, `unplugWindowSnapshots`) instead of full snapshots. History inserts use conflict-ignore semantics, and a snapshot's duplicate wakelocks are merged in one batch (`mergeWakelocks`) by adding duration/count values. `setHistoryCursor` moves **forward only** within a stats session (a late, older write never undoes a newer one; a new `statsStartMs` starts its own cursor).

## Tables and ownership

Entities are defined in [`Entities.kt`](../../core/database/src/main/kotlin/com/akane/inspiration/database/Entities.kt):

- `snapshot` stores FULL/LIGHT identity, trigger, clock validity tuple, battery values, usage truncation count and nullable `UidIdentityScope` fields. `snapshot_uid.ownerInstallFingerprint` stores a versioned SHA-256 fingerprint of the complete package/install owner group; `RoomSnapshotStore` accepts only known fingerprint formats within a valid scope covering that full UID.
- `snapshot_system` stores parsed batterystats timers and discharge values, plus Phase 3 `signalBin0Ms..signalBin4Ms`, `signalScanningMs` and `mobileRadioActiveMs` (all nullable; null bins mean the dump carried none); it is optional and cascades from `snapshot`.
- `snapshot_availability` records source availability/reason for usage, dump, kernel, CPU, history, or light reads.
- `snapshot_uid_sensor` (Phase 3) stores per-UID sensor usage `(snapshotId, uid, handle, totalMs, bgMs, count)` with primary key `(snapshotId, uid, handle)`; it cascades from `snapshot` and is read/written through `SnapshotDao.insertUidSensors`/`uidSensorsForSnapshot`.
- `snapshot_uid`, `snapshot_component`, `snapshot_wakelock`, `snapshot_alarm`, and `snapshot_cpu_freq` store per-UID, component, merged wakelock/alarm, and CPU time-in-state details. Their foreign keys cascade with the parent snapshot.
- `marker` references a snapshot with `RESTRICT`, so a marker protects its snapshot during retention pruning.
- `history_event` stores stats-session history with a unique `(historyTimeMs, kind, wallMs)` key; `history_cursor` stores the singleton paging cursor.
- `telemetry_sample` stores charging live readings; `telemetry_hourly` stores their retention aggregate.
- `charge_session` stores capacity segments/verdicts with a unique `(statsStartMs, startHistoryTimeMs)` key; `battery_health_sample` stores FCC/design/cycle-count health samples keyed by `wallMs` (`HealthDao`).
- `capture_miss` records bounded full-capture failures by wall time and trigger.
- `toolkit_action` (Phase 5) is the audit row for every privileged toolkit write (`ToolkitActionEntity`; explicit status/kind codes, before/after raw values, `restoresId`, indexed by status). The store maps it to `ToolkitActionRecord`/`ToolkitActionStatus` (PENDING, DONE, FAILED, REFUSED, RESTORED, CANCELLED). `kind` values and the retained-row count come from `ToolkitAuditLog` in telemetry-engine (`appop`, `bucket`, `doze`, `force_stop`, `dexopt`; `RETAINED_ROWS = 500`), which the retention SQL also uses.

The schema follows the contracts in [`docs/plans/phase-2.md`](../../docs/plans/phase-2.md) and, for the signal/sensor additions, [`docs/plans/phase-3.md`](../../docs/plans/phase-3.md) F2.

**Schema history.** v1 was edited in place for Phase 3 (decision E9; no version bump), so a dev install from before then must be cleared (`adb shell pm clear com.akane.inspiration`, or the `device-probe` skill's `--clear --grant`). Every later change is a numbered migration with no destructive fallback; add the next one the same way (bump `version`, add `MIGRATION_n_m`, commit the exported JSON). There is no export or user-facing database screen.

## DAO and retention behavior

[`Daos.kt`](../../core/database/src/main/kotlin/com/akane/inspiration/database/Daos.kt) exposes:

- `SnapshotDao`: insert/read snapshots and children, query latest/nearest snapshots, `latestByTrigger(trigger, currentBootCount)` (the existing trigger index narrows candidates; known current-boot rows rank first by `elapsedRealtimeMs DESC`, then insertion `id DESC`, with insertion order as fallback when that boot has no row; no wall-clock ordering). `RoomSnapshotStore` receives the current-boot provider from `AppContainer` and shares this lookup with `latestClockByTrigger`. `SnapshotDao` also exposes `between(from, to)` (bare snapshot rows in a wall-time range, exposed as `SnapshotStore.snapshotsBetween` with empty per-UID lists), `unplugWindow(from, to)` (one SQL query returning only the rows `UnplugSummaryCalculator` needs for "now": from the newest screen/boot capture at or before the unplug mark; exposed as `unplugWindowSnapshots` and read by the status notification tick), and `mergeWakelocks` (batch insert-ignore, then an `ON CONFLICT ... DO UPDATE` sum only for the conflicting rows).
- `firstAtOrAfter` matches `statsStartMs` as a `BETWEEN` range: `RoomSnapshotStore` passes the shared ±2 s battery-stats session tolerance.
- `newestWallMsWithCompleteSource(kind, source)` returns the newest FULL snapshot wall time whose availability row for a source is available with no reason. `SnapshotStore.historyHorizonWallMs()` uses it as the history horizon.
- `MarkerDao`: insert and resolve marker-to-snapshot references.
- `HistoryDao`: insert-ignore events, query wall-time ranges, `lastOfEachKindBefore(beforeWallMs)` (the newest event of each kind with a known wall time (`wallMs >= 0`) strictly before a bound, oldest first, in one query; the store exposes it as `lastHistoryOfEachKindBefore`, which seeds the timeline state without scanning all older history), and read/upsert the cursor.
- `ToolkitActionDao`: insert (with `insertAndPrune`), update, newest-N and by-status queries for the `toolkit_action` audit table. `pruneTo(keep)` never deletes PENDING rows, DONE/RESTORED override rows, or FAILED override rows that moved their target (`afterRaw` known and ≠ `beforeRaw`); `deleteSettled(ids)` (chunked, PENDING always kept) is how `ToolkitActions` drops closed chain prefixes beyond the newest 500 rows (`SnapshotStore.deleteToolkitActions`).
- `TelemetryDao`: insert live samples and read hourly aggregates.
- `ChargeSessionDao`: upsert segments and return accepted estimates chronologically.
- `CaptureMissDao`: record misses.
- `MaintenanceDao`: downsample samples by hour, delete samples, unmarked snapshots, history, and misses older than 30 days in one transaction; `minClockOffsetMs(bootCount)` returns the boot's earliest `wallMs - elapsedRealtimeMs` frame.

Pruning is called by app-open and charging-job captures through `SnapshotStore.pruneRetention(clocks)` ([`RetentionPrune.kt`](../../core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/store/RetentionPrune.kt)), not by a resident loop. It prunes at the earlier of the wall clock and "this boot's earliest frame + elapsedRealtime", so a forward wall-clock jump cannot age a boot's rows out (audit F-06); after a reboot, or with boot count 0, it trusts the wall clock. Snapshot children cascade; marker-referenced snapshots remain until the marker is removed by a future policy.

## Tests and privacy boundary

[`InspirationDatabaseTest.kt`](../../core/database/src/test/kotlin/com/akane/inspiration/database/InspirationDatabaseTest.kt) checks database opening, WAL, cascades, schema behavior, migrations through 4 → 5 (legacy values preserved, identity proof NULL, index and foreign keys retained), and (`EXPLAIN QUERY PLAN`) that trigger lookup searches the existing index; the boot/elapsed ordering may need a temporary sort. [`RoomSnapshotStoreTest.kt`](../../core/database/src/test/kotlin/com/akane/inspiration/database/store/RoomSnapshotStoreTest.kt) checks identity-proof round trips and rejection of unknown/malformed proof, availability, history, merge behavior, references, current-boot trigger chronology under wall-clock changes, the unplug window and pruning under Robolectric; `store/HistoryCursorTest.kt` covers the forward-only cursor and `store/ToolkitActionRetentionTest.kt` the audit retention.

The app sets `android:allowBackup="false"` and points to [`data_extraction_rules.xml`](../../app/src/main/res/xml/data_extraction_rules.xml), excluding cloud backup and device transfer before production database writes (`backup_rules.xml` / `fullBackupContent` were removed: they do nothing on minSdk 31).
