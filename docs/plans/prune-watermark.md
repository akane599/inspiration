# Plan: a stored prune watermark for history ingest (Room v6 → v7)

Status: draft for review (SQ-7). Implementation ticket: SQ-8. Evidence and alternatives: the SQ-28 spike report (`~/.claude/sidequest/projects/inspiration-86964c18/assets/tk_mvgtqmla_84666588/plan.md`), read at 2b9ebbb.

## Problem

`SnapshotCapturer.persistPage` (SnapshotCapturer.kt:145-151) keeps a history event only if `wallMs >= retentionNowMs(clocks) - RETENTION_WINDOW_MS`. The cutoff is recomputed on every page. `MaintenanceDao.prune` (Daos.kt:504-518) deletes `wallMs < cutoff` at its own "now".

A history read always walks from the start of the framework buffer (BatteryHistory.kt:73-77), and re-pages from history time 0 after any label change (a wall-clock step moves `statsStartMs`), every stats reset, and the one-time SQ-6 rewind. A re-paged event is identical to its stored copy (`UNIQUE(historyTimeMs, kind, wallMs)`, Entities.kt:257), so only rows that are no longer stored can come back: pruned rows, and events an earlier ingest skipped.

They do come back when the ingest cutoff at the re-page is lower than a cutoff that was applied earlier. That happens after:
1. a manual backward clock set, or a bad NITZ/NTP value;
2. a boot with the clock at its floor (no RTC) before network time;
3. a snapshot taken in that early-clock window. `minClockOffsetMs(boot)` then pins `retentionNowMs` months low for the rest of the boot, and the NTP correction relabels and re-pages from 0. This is the worst case;
4. a prune at a forward-jumped "now" after a reboot, followed by the correction.

A resurrected row gets a new autoincrement id after newer rows. `historySessions` orders by id (RoomSnapshotStore.kt:196-206), so chronology breaks (the N-13 invariant). The R-15 seeks (`newestOfEachKindBefore`) can return the ghost as the latest state before a bound: a ghost PLUGGED pairs with a later UNPLUGGED in the charge job and the unplug summary.

## Why not a single wall-clock watermark

Storing `W = max(cutoff ever applied)` and filtering every event by `max(cutoffNow, W)` breaks case 4. After a jump of more than 30 d is corrected, `W > realNow - 30 d`, so every genuinely new event is dropped until real time reaches W: weeks of history ingest gone. W must apply only to positions the ingest has already seen.

`historyTimeMs` is the framework's history clock (BatteryHistory.kt:225). It doesn't move with wall-clock steps or relabels, so "already seen" can be keyed on it. The live cursor can't serve: `advanceCursor` overwrites it under a new label and `resumeCursor` sets it to 0 (Daos.kt:296-316), so it sits below positions already processed during any re-page. A separate monotonic mark is needed.

## Design

### State
New singleton table, Room v7:

```
history_watermark(
  id INTEGER PRIMARY KEY NOT NULL,          -- always 1
  seenThroughHistoryTimeMs INTEGER NOT NULL, -- H: max historyTimeMs of any page the ingest processed
  prunedBeforeWallMs INTEGER NOT NULL        -- W: max history retention cutoff ever applied
)
```

Both values only go up (`MAX(old, new)`).

### Ingest filter
Keep event `e` iff

```
e.wallMs >= cutoffNow && (e.historyTimeMs > H || e.wallMs >= W)
```

- New positions (`ht > H`) are judged by the current cutoff only, as today. Case 4 can't stop ingest.
- Old positions (`ht <= H`) must also clear W, so nothing below a cutoff that was ever applied comes back, whatever the clock does now.
- `cutoffNow` stays `retentionNowMs(clocks) - RETENTION_WINDOW_MS`, computed by the capturer.

The keep predicate is one small pure function in `:core:telemetry-engine` (`store/`), shared by the Room store and the default store implementation.

### Where the values advance
- **W in the prune:** `MAX(W, cutoffWallMs)` inside `MaintenanceDao.prune`'s `@Transaction`, using the 30-day history cutoff (not the hourly one).
- **W in the ingest:** `MAX(W, cutoffNow)` whenever a page is persisted. Otherwise an event skipped at a cutoff above the last prune's could come back on a later re-page.
- **H in the ingest:** `MAX(H, page.events.last().historyTimeMs)` for every available page with events, taken before filtering (the same value the cursor gets). Pages are ascending, so judging a page against the H stored before it is correct.

### One transaction per page
Today `insertHistory` and `setHistoryCursor` are separate transactions (SnapshotCapturer.kt:149-150), and `historyIngestLock` serializes ingests only, so a prune can land between two pages. New seam:

```kotlin
// SnapshotStore
suspend fun persistHistoryPage(statsStartMs: Long, events: List<HistoryEvent>, cutoffWallMs: Long)
```

- The default implementation keeps today's behaviour (filter on the cutoff, `insertHistory`, `setHistoryCursor`), so the existing fakes keep compiling.
- `RoomSnapshotStore` overrides it in one `withTransaction`: read (H, W), filter with the shared predicate, `insertIgnore`, `advanceCursor`, then advance H and W.
- `SnapshotCapturer.persistPage` calls it inside the existing `NonCancellable` block.

### Migration 6 → 7 (`MIGRATION_6_7`, chained after `MIGRATION_5_6`)
- `CREATE TABLE history_watermark (...)`.
- Seed one row when the database has history or a cursor:
  - `H0 = MAX(COALESCE(cursor id 1 lastHistoryTimeMs, 0), COALESCE(MAX(history_event.historyTimeMs), 0))`;
  - `W0 = COALESCE(MIN(history_event.wallMs) WHERE wallMs >= 0, 0)`. Positions below the oldest stored row were pruned or never stored, so this is safe. It closes the band until the first prune after the upgrade.
- An empty database gets `(1, 0, 0)` or no row (read as `(0, 0)`). Either way every comparison then behaves as today. The review picks one.
- Export `schemas/.../7.json`.

### History clock restarts
If the framework buffer is wiped (a history-clearing reset), new events can have `ht <= H`. They pass as long as `wall >= W`, which is normal because W tracks the current cutoff.

The remaining double fault is a huge W from a jump-then-correct *plus* a restart. That drops new events until `wall >= W` or `ht > H`. An optional guard: a non-truncated page whose last `ht` is below the stored H proves the history no longer reaches H. Treat that page as all-new and set H to its last `ht`. This needs `truncated` passed to the store. **Open for review:** include the guard in SQ-8, or leave it out.

## Interactions
- **R-15 `history_event(kind, wallMs)` index and the newest-of-kind seeks:** no query or index change. The watermark only gates inserts, so the seeks stop returning ghost rows.
- **N-13 insertion-order runs:** the invariant this protects.
- **N-14 boot detection:** independent. H is history time, W is wall time and only rises.
- **SQ-6 rewind:** unchanged. `resumeCursor` lowers the cursor, not H. Restated events at old positions still land when `wall >= W`. A database upgraded straight from a pre-SQ-6 build still rewinds.
- **SQ-20 `STOPPED_STATE_EXIT`, SQ-23 `readConsistently`, SQ-25 `unplugWindow`:** reads only, not affected.
- **Out of scope:** making `retentionNowMs` itself robust to an early-clock snapshot pinning `minClockOffsetMs` (case 3). With the watermark it can no longer resurrect rows. File it separately if wanted.

## SQ-8 boundaries
- **Files:**
  - `core/database`: `Entities.kt`, `Daos.kt`, `InspirationDatabase.kt`, `RoomSnapshotStore.kt`, `schemas/` (7.json), and the tests.
  - `core/telemetry-engine`: `store/SnapshotStore.kt`, a new `store/HistoryWatermark.kt` (the predicate), `capture/SnapshotCapturer.kt`, `store/RetentionPrune.kt` (KDoc only), and the tests.
  - `app/src/test` for one Room-backed scenario.
- No change to `retentionNowMs`, the prune's deletion cutoffs, the cursor semantics, or any read path.
- Stale comments to fix in the same change: SnapshotCapturer.kt:137-144, RetentionPrune.kt:27. The map (`database.md`, `communication.md`) is updated by the orchestrator after integration.

## Acceptance tests
**Migration** (`InspirationDatabaseTest`, the `createExportedSchema(old, version = 6)` pattern):
- A real v6 database with history rows (including `wallMs = -1`) and cursor rows 1 and 2 opens at v7 with:
  - the table present and the seed values as above;
  - every history row and both cursor rows kept;
  - `newestOfEachKindBefore` still using its index.
- An empty v6 database gets the neutral values.

**DAO:**
- A prune raises W to its cutoff and a later prune at a lower "now" leaves W unchanged.
- `persistHistoryPage` commits the insert, cursor, H and W together.
- A relabel's cursor overwrite leaves H unchanged.

**Scenarios** (`SnapshotCapturerTest` with a FakeStore that implements prune and the watermark, plus one Room-backed run):
1. A backward step after a prune, then a relabel re-page: the pruned row stays gone and the new event is stored.
2. Jump then correct: new events (`ht > H`, wall at real now) are stored, and old pruned rows are not. Negative control: the naive filter fails this test.
3. Early clock with a pinned boot frame, then a re-page after NTP: nothing comes back.
4. An event skipped (never stored) with wall in `[W_prune, cutoff_ingest)` doesn't come back after a later lowered cutoff.
5. SQ-6 rewind on an upgraded database: restated events with `wall >= W` at old positions are still added.
6. History clock restart (`ht <= H`, wall at real now): stored.
7. A partial re-page across captures: events in `(cursor, H]` in a later capture are still treated as old.
8. The existing SnapshotCapturerTest cases at :261, :285 and :310 stay green.

**Verify:** `:core:database:testDebugUnitTest :core:telemetry-engine:testDebugUnitTest :app:testDebugUnitTest`.

## Not verified
Taken from repo evidence and memory, not framework source:
- a relabel on every wall-clock change;
- `historyTimeMs` continuing across stats resets on API 31–35;
- the existence of a history-clearing reset.

The review can confirm the first two on redroid (`adb shell date` set backward, then a `PROBE_CAPTURE`).
