# Plan: a stored prune watermark for history ingest (Room v6 → v7)

Status: **rev 2, reviewed (SQ-7, approved with corrections; folded in below).** Implementation ticket: SQ-8. Evidence and rejected alternatives: the SQ-28 spike report (`~/.claude/sidequest/projects/inspiration-86964c18/assets/tk_mvgtqmla_84666588/plan.md`). The review findings are SQ-7 comments, Findings 1–5. Code references are at fbd9ced.

## Problem

`SnapshotCapturer.persistPage` (SnapshotCapturer.kt:145-151) keeps a history event only if `wallMs >= retentionNowMs(clocks) - RETENTION_WINDOW_MS`. The cutoff is recomputed on every page. `MaintenanceDao.prune` (Daos.kt:504-518) deletes `wallMs < cutoff` at its own "now".

A history read always walks from the start of the framework buffer (BatteryHistory.kt:73-77). It re-pages from history time 0 after any label change (a wall-clock step moves `statsStartMs`), after every stats reset, and once for the SQ-6 rewind. A re-paged event is identical to its stored copy (`UNIQUE(historyTimeMs, kind, wallMs)`, Entities.kt:257), so only rows that are no longer stored can come back: pruned rows, and events an earlier ingest skipped.

They come back when the ingest cutoff at the re-page is lower than a cutoff that was applied earlier. That happens after:
1. a manual backward clock set, or a bad NITZ/NTP value;
2. a boot with the clock at its floor (no RTC), before network time;
3. a snapshot taken in that early-clock window. `minClockOffsetMs(boot)` then pins `retentionNowMs` months low for the rest of the boot, and the NTP correction relabels and re-pages from 0. This is the worst case;
4. a prune at a forward-jumped "now" after a reboot, followed by the correction.

A resurrected row gets a new autoincrement id after newer rows. `historySessions` orders by id (RoomSnapshotStore.kt:196-206), so chronology breaks (the N-13 invariant). Through `historySessions` → `ChargeSegmentExtractor` (CapacityEstimator.kt:44-54), a ghost PLUGGED pairs with a later UNPLUGGED. The R-15 seeks order by wall (Daos.kt:257-265, 331-336). They return a ghost only when it is the newest row of its kind by wall before the bound, as in the still-running-charge case (SnapshotCapturerTest:285, ChargingCaptureRunner.kt:53).

## Why not a single wall-clock watermark

Storing `W = max(cutoff ever applied)` and filtering every event by `max(cutoffNow, W)` breaks case 4. After a jump of more than 30 d is corrected, `W > realNow - 30 d`, so every genuinely new event is dropped until real time reaches W. That is weeks of history ingest lost. W must apply only to positions the ingest has already seen.

`historyTimeMs` is the framework's history clock (BatteryHistory.kt:225). It does not move with wall-clock steps or relabels. On redroid API 35 it also continued across a stats reset and across a reboot (SQ-7 Finding 5). So "already seen" can be keyed on it. The live cursor can't serve: `advanceCursor` overwrites it under a new label and `resumeCursor` sets it to 0 (Daos.kt:296-317). During a re-page it therefore sits below positions already processed. A separate mark is needed.

## Design

### State
New singleton table, Room v7:

```
history_watermark(
  id INTEGER PRIMARY KEY NOT NULL,           -- always 1
  seenThroughHistoryTimeMs INTEGER NOT NULL, -- H: max historyTimeMs of any page the ingest processed
  prunedBeforeWallMs INTEGER NOT NULL        -- W: max history retention cutoff ever applied
)
```

**No row means neutral:** a missing row reads as `H = W = Long.MIN_VALUE`.
- A fresh install creates v7 from the entities and never runs `MIGRATION_6_7` (there is no `onCreate` callback, InspirationDatabase.kt:54-65). So the no-row state always exists, and every reader and writer handles it.
- With `MIN_VALUE` the keep predicate reduces exactly to today's `wall >= cutoffNow`. `(0, 0)` would not: an event at ht = 0 would count as "old", and the tests use ht = 0 and negative cutoffs.

W only rises. H only rises, except through the restart guard below.

### Keep predicate
Keep event `e` iff

```
e.wallMs >= cutoffNow && (e.historyTimeMs > H || e.wallMs >= W)
```

- **New positions** (`ht > H`) are judged by the current cutoff only, as today, so case 4 can't stop ingest.
- **Old positions** (`ht <= H`) must also clear W. Nothing below a cutoff that was ever applied comes back, whatever the clock does now.
- **Invariant:** overestimating H only puts more positions under W, which is wall-based and safe. H becomes unsafe only by underestimating. The migration seed and the per-page "last ht before filtering" rule never underestimate.
- **Trade-off (case 4):** genuinely recent rows that a forward-jumped prune deleted stay gone. Today they come back out of order.
- `cutoffNow` stays `retentionNowMs(clocks) - RETENTION_WINDOW_MS`, computed by the capturer.

The predicate is one small pure function in `:core:telemetry-engine` (`store/HistoryWatermark.kt`). Both the Room store and the default implementation use it.

### History clock restart guard
On API 35 a stats reset clears the buffer but history time continues (SQ-7 Finding 5). API 31–34 behaviour is unknown, and a lost history file can also restart the clock. Without a guard, a restart under an inflated W is a silent stall that feeds itself: H only rises, so every later event sits at or below H.

- `restarted = !truncated && events.isNotEmpty() && events.last().historyTimeMs < H`.
- A non-truncated page proves the iterator reached the end of a history that no longer reaches H (BatteryHistory.kt:73-84, AndroidTelemetrySource.kt:226-236). A failed read gives an unavailable page, not this one.
- When the guard fires, judge the whole page by `cutoffNow` only and set `H = last ht`. W still takes `MAX(W, cutoffNow)` and never falls.
- This is the only rule that lowers H.
- Don't key it on `sessionChanged`: if the first page after a reset is truncated, detection has to happen on a later page that is not a relabel.

### Where the values advance
- **W in the prune**, inside `MaintenanceDao.prune`'s `@Transaction`, with the 30-day history cutoff (not the hourly one). Create the row on demand and never touch H:
  ```sql
  INSERT INTO history_watermark(id, seenThroughHistoryTimeMs, prunedBeforeWallMs)
  VALUES (1, <Long.MIN_VALUE>, :cutoffWallMs)
  ON CONFLICT(id) DO UPDATE SET prunedBeforeWallMs = MAX(prunedBeforeWallMs, excluded.prunedBeforeWallMs)
  ```
  Daos.kt:46 already uses `ON CONFLICT DO UPDATE`. Never use REPLACE or a whole-entity `@Upsert`: that resets H, the same trap the spike rejected for cursor columns.
- **W in the ingest:** `MAX(W, cutoffNow)` whenever a page is persisted. Otherwise an event skipped at a cutoff above the last prune's could come back on a later re-page.
- **H in the ingest:** `MAX(H, last ht before filtering)` for every available page with events (the same value the cursor gets), or the guard's value. Pages are ascending, so judging a page against the H stored before it is correct.
- The ingest writes (H, W) as a read-modify-write of the whole row inside the page transaction.

### One transaction per page, and the page counter
Today `insertHistory` and `setHistoryCursor` are separate transactions (SnapshotCapturer.kt:149-150). `historyIngestLock` serializes ingests only, so a prune can land between two pages. New seam:

```kotlin
// SnapshotStore
/** Returns how many events the keep predicate kept (before insert-ignore dedupe). */
suspend fun persistHistoryPage(statsStartMs: Long, events: List<HistoryEvent>, truncated: Boolean, cutoffWallMs: Long): Int
```

- **Default implementation:** keeps today's behaviour (filter on the cutoff, `insertHistory`, `setHistoryCursor`, return the kept count), so the existing fakes keep compiling.
- **`RoomSnapshotStore`** overrides it in one `withTransaction`: read the row, apply the guard and the predicate, `insertIgnore`, `advanceCursor`, then write the row back. Atomicity is a review property: insert-ignore makes failure hard to inject in a test.
- **`SnapshotCapturer.persistPage`** calls it once per available page, inside the existing `NonCancellable` block, with the unfiltered events.
- **`HistoryPageCounter` (HIGH, SQ-7 Finding 2).** The production capturer writes through `HistoryPageCounter(store)` (AppContainer.kt:103, 110-116), a `SnapshotStore by store` wrapper that overrides only `insertHistory`. Kotlin delegation also forwards members with default bodies. Without a fix, Room's `persistHistoryPage` bypasses the counter, and a missed app-open or marker capture that stored pages stops ticking (`withMissTick`, HistoryPageCounter.kt:30-41).
  - The counter overrides `persistHistoryPage`: it forwards the call, then counts a page when the result is > 0. That matches today's "filtered events non-empty".
  - Move or keep its `insertHistory` override and test as appropriate (nothing in the capturer calls `insertHistory` any more).

### Migration 6 → 7 (`MIGRATION_6_7`, chained after `MIGRATION_5_6`)
- `CREATE TABLE`: copy the text verbatim from the exported `7.json` `createSql`. Room validates the migrated table against the entity on open.
- Seed only when there is something to seed:
  ```sql
  INSERT INTO history_watermark
  SELECT 1, <H0>, <W0>
  WHERE EXISTS(SELECT 1 FROM history_cursor WHERE id = 1) OR EXISTS(SELECT 1 FROM history_event)
  ```
  - `H0 = MAX(COALESCE(cursor id 1 lastHistoryTimeMs, 0), COALESCE(MAX(history_event.historyTimeMs), 0))`.
  - `W0 = COALESCE((SELECT MIN(wallMs) FROM history_event WHERE wallMs >= 0), <Long.MIN_VALUE>)`.
- Both subqueries are index seeks: MAX(historyTimeMs) uses the unique index (Entities.kt:257), and MIN(wallMs) uses `index_history_event_wallMs` (:256).
- W0 narrows the band until the first prune after the upgrade. It doesn't close it: rows stored after the last pre-upgrade prune can carry walls below that prune's cutoff. On the app path, the first prune runs right after the first ingest (AppContainer.kt:154-157).
- W0 is safe for SQ-6. The restated screen and doze events share the RESET row's (ht, wall) (BatteryHistory.kt:124, 165-170), and earlier builds stored that RESET row. So whenever the RESET row survives, its wall is at least W0 and the rewind still adds the restated rows.
- Export `schemas/.../7.json`.

## Interactions
- **R-15 `history_event(kind, wallMs)` index and the newest-of-kind seeks:** no query or index change; `history_event` is untouched. The watermark only gates inserts, so the seeks stop returning ghost rows. `intervalHistory` and `unplugWindow` are reads and are unaffected.
- **N-13 insertion-order runs:** this is the invariant the change protects.
- **N-14 boot detection:** independent. The boot frame keys on bootCount (Daos.kt:500-502), and `STOPPED_STATE_EXIT` reads snapshot clocks.
- **SQ-6 rewind:** unchanged. `resumeCursor` lowers the cursor, not H. A database upgraded straight from a pre-SQ-6 build still rewinds.
- **SQ-20, SQ-23, SQ-25:** reads only, not affected.
- **Out of scope:** making `retentionNowMs` itself robust to an early-clock snapshot pinning `minClockOffsetMs` (case 3). With the watermark it can no longer resurrect rows.

## SQ-8 boundaries
**Files:**
- `core/database`:
  - `Entities.kt`, `Daos.kt`, `InspirationDatabase.kt`, `store/RoomSnapshotStore.kt`;
  - `schemas/` (7.json);
  - tests: `InspirationDatabaseTest.kt`, `store/HistoryCursorTest.kt` or a new `store/HistoryWatermarkTest.kt`, plus a shared test helper if `createExportedSchema` moves.
- `core/telemetry-engine`:
  - `store/SnapshotStore.kt` (the seam, and the KDoc at :249);
  - new `store/HistoryWatermark.kt`;
  - `capture/SnapshotCapturer.kt`;
  - `store/RetentionPrune.kt` (KDoc only);
  - tests.
- `app`: `capture/HistoryPageCounter.kt` and `capture/HistoryPageCounterTest.kt`.

**Not changed:** `retentionNowMs`, the prune's deletion cutoffs, cursor semantics, and every read path.

**Also in SQ-8:**
- Fix the stale comments at SnapshotCapturer.kt:137-144 and RetentionPrune.kt:27.
- Bump `assertEquals(6, sqlite.version)` at InspirationDatabaseTest.kt:248 and :301 to 7.
- The orchestrator updates the map (`database.md`, `communication.md`) after integration.

## Acceptance tests
All scenario tests run on the real Room store, not a fake copy of the logic. `core/database` `HistoryCursorTest` already drives `SnapshotCapturer` over `RoomSnapshotStore` (Robolectric sdk 35, :38-51, :132-191). Use it, or a sibling `HistoryWatermarkTest` that reuses its harness.

**telemetry-engine (three tests):**
- A predicate truth table, with the boundaries: wall == cutoffNow is kept, ht == H counts as old, wall == W is kept, plus the guard cases.
- `persistPage` hands `statsStartMs`, the unfiltered events, `truncated` and the cutoff to `persistHistoryPage` once per available page.
- The default `persistHistoryPage` still filters, then calls `insertHistory` and `setHistoryCursor`, and returns the kept count.

**Migration** (`InspirationDatabaseTest`, the `createExportedSchema(old, version = 6)` pattern):
- A real v6 database with history rows (including `wallMs = -1`) and cursor rows 1 and 2 opens at v7 with:
  - the table present;
  - the seed values above;
  - every history row and both cursor rows kept;
  - `assertNewestOfEachKindSeeksTheIndex` still passing.
- An empty v6 database gets no row.
- A database created at v7 has no row.

**DAO:**
- A prune on a no-row database creates (`MIN_VALUE`, cutoff).
- A later prune at a lower "now" leaves W unchanged.
- A prune never touches H.
- The first page on a no-row database behaves exactly like today and creates the row.
- A relabel's cursor overwrite leaves H unchanged.

**Scenarios** (Room harness):

| # | Scenario | Expected | On the base (fbd9ced) |
|---|---|---|---|
| 1 | A backward step after a prune, then a relabel re-page | The pruned row stays gone; the new event is stored | red |
| 2 | Jump then correct: a prune at jumpedNow (no boot frame), then the clock corrected, then a re-page with new events (`ht > H`, wall at real now) | New events stored; old pruned rows not | Old-row half red. New-row half green, but red on the naive control: swap the predicate to `wall >= max(cutoffNow, W)` and see it fail on an assertion |
| 3 | Early clock with a pinned boot frame (`bootCount > 0` plus a stored snapshot of that boot with the early offset), then a re-page after NTP | Nothing comes back | red |
| 4 | An event skipped (never stored) with wall in `[W_prune, cutoff_ingest)`, then a later lowered cutoff | It doesn't come back | red |
| 5 | SQ-6 rewind through the real `MIGRATION_6_7`: a v6 database with history and cursor id 1 but no id-2 marker; ingest a session whose RESET-restated rows sit at old positions with wall ≥ W0, beside a band event below W0 | Restated rows added once; band event not added | green |
| 6 | History clock restart, non-truncated (`ht <= H`, wall at real now) | Stored | green |
| 7 | Partial re-page across captures: page 1 of a relabel lowers the cursor below H | Events in `(cursor, H]` in a later capture are still old | red |
| 8 | Existing SnapshotCapturerTest :261, :285, :310 and HistoryPageCounterTest | Unchanged | green |
| G1 | W inflated by a prune at now + 60 d, then a restart page (`ht <= H`, wall at real now, non-truncated) | Events stored; H drops | — |
| G2 | The same page, truncated | Nothing stored; H unchanged | — |
| G3 | A non-truncated relabel re-page that reaches H, with a pruned old position in it | The guard doesn't fire; the row stays out | — |

**Verify:**
1. `bash .claude/scripts/gradle-slot.sh :core:database:testDebugUnitTest :core:telemetry-engine:testDebugUnitTest :app:testDebugUnitTest --max-workers=4`
2. `git diff --exit-code -- core/database/schemas` (a stale or missing 7.json shows as a diff).
3. The orchestrator's full gate compiles the other `SnapshotStore` fakes in core/diagnostics and feature/*.

## Verification status
**Confirmed on redroid API 35** (SQ-7 Finding 5):
- History time continues across a stats reset and across a reboot.
- A stats reset clears the buffer.

**Unverified:**
- API 31–34 reset behaviour. The restart guard covers a clock restart.
- A relabel on every wall-clock change. The design doesn't depend on it: with no relabel there is no re-page from 0, so there is no band. Don't test it on redroid: it is privileged and shares the host clock, so `adb shell date` would step the host. A physical device would be needed.
