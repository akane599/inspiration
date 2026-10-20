# Inspiration — Whole-Repository Audit (2026-10-20, independent findings)

- **Source state:** `main` at `c74d8f8eadc8087570dac7cb3aece971bd1defa2`. The working tree differs from HEAD only in
  non-application files (`.claude/settings.json` with Sidequest disabled for review mode; untracked
  `.claude/kit/resource-policy.json`).
- **Scope:** all application source in `:app` (main, debug, tests), `:core:{hidden-api-stub, shizuku-ipc,
  telemetry-engine, diagnostics, database, designsystem}`, `:feature:{dashboard, xray, sleeplab, health, toolkit}`,
  `build-logic`, Gradle configuration, manifests, AIDL, protos and Room schemas v1–v7 (≈37k lines main, ≈46k lines test).
  Kit tooling (`android-kit-codex/`, `.claude/`, `.agents/`, `.codex/`) is out of scope.
- **Method:** source-only reconnaissance → one shared tooling baseline → nine parallel read-only subsystem reviewers
  → coordinator deduplication by root cause → three independent validators (disjoint batches, clean scratch copies,
  executable reproductions) → this report. Finding IDs use the `B-` prefix.
- **Blindness:** limited. See §5 for the contamination ledger; the coordinator and five of nine reviewers had some
  prior-assessment exposure. Every finding below was re-derived from current source by a validator with no known
  prior-assessment exposure; 11 were reproduced by a failing unit test.

---

## 1. Executive Summary & System Health Scorecard

No CRITICAL or HIGH defects survived validation. The privileged surface is in good shape: every AIDL entry point checks
the caller UID, every privileged command is an argv list with validated package names, processes are drained with
timeouts, `CancellationException` is preserved, Room migrations 1→7 match the exported schemas, and there is no
destructive migration fallback. The confirmed defects are concentrated in one pattern: **the app turns partial or
ambiguous data into a confident answer**.

| Dimension | Score | Justification |
|---|---|---|
| Architecture & Modularity | **7/10** | Clean module graph (features never depend on each other, convention plugins, manual DI). However, UID→package attribution, app-UID classification and "may this restore run?" rules are each implemented differently in several modules (B-06, B-08, B-16). |
| Correctness & Resilience | **6/10** | No crash or corruption paths were found. But four separate paths report complete/clean/specific results from capped, unproven or missing data (B-02, B-03, B-04, B-07), and the Cockpit chart loses its readout at both window edges (B-05). |
| Concurrency & Resource Safety | **8/10** | Deadline-bound binder calls, write-ahead toolkit rows under `NonCancellable`, bounded process I/O. Remaining issues are narrow: a tiny check-then-write race (B-13), untracked X-Ray write jobs (B-12), and Shizuku IPC on Main (B-10). |
| Security & Privilege Hygiene | **8/10** | Caller-UID checks, argv-only commands, `--user 0` containment, a DUMP-gated debug probe, an obscured-touch guard on every privileged control, and backup excluded. The one privileged-write defect is B-01: Reset all can overwrite a system-managed value it never set. |
| Performance & Efficiency | **7/10** | No hot-loop or render problems were found. There is avoidable system_server and IPC load: a full `BatteryUsageStats` query per history page and on API 31–33 (B-09), hundreds of PackageManager IPCs per Sleep Lab emission (B-17), and possibly a forced external-stats sync on every FULL capture (B-22). |

*Scores are qualitative judgments, not measured guarantees.*

**Top 3 systemic risks**
1. **Incompleteness collapses into confidence.** The engine has a rich validity model (`SourceAvailability`,
   `DiffValidity`, `valid` per UID), but each consumer flattens it. Examples:
   - A history read cut off at its page cap is stored as complete (B-02).
   - An interval in which no app could be checked yields "No anomalies detected" (B-03).
   - A missing source is reported as "device restarted" or "less than 30 min screen-off" (B-04).
   - An unplausible charge counter becomes "0 min left" (B-07).

   This is the defect class most likely to recur, because each new consumer re-decides how to collapse validity.
2. **The toolkit's audit trail can claim changes Inspiration never made.** A write-ahead row reconciled long after the
   fact absorbs whatever value the target drifted to. Reset all then issues a privileged write to "undo" it (B-01).
   X-Ray and Toolkit also disagree on when a restore may run, so the one write a protected app accepts silently does
   nothing in X-Ray (B-06).
3. **App identity resolution has no single owner.** Each of these is a local rule that should come from one shared
   resolver:
   - Shared UIDs are labelled with the alphabetically first owner (B-08).
   - The rule engine treats per-user platform UIDs as apps (B-16).
   - Labels and icons are cached for the life of the process, including "not installed" results (B-18).
   - Sleep Lab re-resolves every UID on each emission (B-17).

---

## 2. Confirmed Findings (CRITICAL → HIGH → MEDIUM → LOW)

No CRITICAL or HIGH findings.

### MEDIUM

- **[B-01] [MEDIUM] A late toolkit reconcile records a drifted value as Inspiration's change; Reset all then overwrites
  a system-managed value**
  - **Category:** Logic Bug (privileged write path)
  - **Location:**
    - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt:1494-1529`
      (`reconcileRow`; the Mismatch branch is 1517-1526, the Matched branch 1507-1515)
    - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitModels.kt:257-274`
      (`changedTarget`, `landedElsewhere`)
    - Callers and consumers:
      - `ToolkitActions.kt:700-704` (Reset all guard), `:711-718` (write-ahead), `:828-852` (`settle`)
      - `ToolkitActions.kt:1036` (`decodeRestore`), `:1124` (`partialRestore`), `:1192-1249` (`computeOverrides`),
        `:1485-1491` (`reconcileTargets`)
      - `app/src/main/kotlin/com/akane/inspiration/AppContainer.kt:369-375` (reconcile on every Shizuku Ready)
  - **Confidence:** Confirmed. Reproduced by a failing unit test (validator V3).
  - **Root cause & execution trace:**
    1. `run()` inserts a PENDING row with `beforeRaw = prep.current` before the binder call (`:711-714`). That row is
       left PENDING in three cases:
       - the process dies before the reply;
       - the reply is lost after dispatch (`settle`: `if (reply.sent) pending.copy(status = ToolkitActionStatus.PENDING, …)`);
       - the service answers UNAVAILABLE/UNVERIFIED (`:845-849`).
    2. The row is settled by `reconcile()` on the next Shizuku Ready, or by `reconcileTargets` before the next toolkit
       use. On a non-root phone, Ready may be hours or days later (Shizuku stays down after reboot until the user
       restarts it).
    3. `reconcileRow` settles any third value without considering how old the row is:
       ```kotlin
       is Verdict.Mismatch -> {
           audit.update(row.copy(status = ToolkitActionStatus.FAILED, endedAt = now,
               code = ToolkitCode.NOT_EFFECTIVE, afterRaw = verdict.current))
       ```
    4. `landedElsewhere` (`after != before`) makes `changedTarget` true. As a result:
       - `computeOverrides` lists the row as Inspiration's change, with `previousRaw = beforeRaw`;
       - `decodeRestore` / `partialRestore` accept it;
       - the Reset all guard `sameState(restoring.kind, prep.current, restoring.afterRaw)` compares against the drifted
         value it just recorded, so it passes.
    5. The code contradicts itself. A write known to be unsent is finalized with `afterRaw = null` (`:836-837`)
       precisely so that later drift is "not ours", and `ToolkitActionsUnsentWriteTest.kt:31-55` asserts that Reset
       all must not write after an outage decay. A PENDING write that may equally never have run gets the opposite
       treatment.
    6. The Matched branch has the same flaw when the value decays all the way to the intended one: the row becomes
       DONE, and Reset all writes the old `before`.
  - **Failure scenario:**
    1. The user sets app X from ACTIVE to RARE. The reply is lost, or the app is killed mid-call, and the write never
       commits.
    2. The phone reboots and Shizuku is not restarted for a day. X's standby bucket decays on its own to FREQUENT.
    3. On the next Ready the row becomes an override "Frequent, was Active". Inspiration never caused this.
    4. Reset all, or Restore, forces X back to ACTIVE with `set-standby-bucket`, replacing the platform's usage-based
       bucket. The same applies to `device_idle_constants` rewritten by another tool.

    Reproduction `ValidatorReproLateReconcileDriftTest`: the settled row is
    `status=FAILED, code=31, beforeRaw=10, afterRaw=30`, the override is `restorable=true`, and
    `writes=[setBucket(com.example.chatty,active)]`.
  - **Concrete fix:** attribute a read-back to the write only when the reconcile is prompt. Otherwise settle the row
    as unverified with no `after`, which `landedElsewhere` already excludes and Reset all already skips:
    ```kotlin
    // ToolkitActions.kt
    /** A read-back later than this after the write cannot tell a clamped write from later drift. */
    private const val RECONCILE_ATTRIBUTION_MS = 60_000L // > bind deadline + command timeout

    private suspend fun reconcileRow(row: ToolkitActionRecord): Boolean {
        val now = nowMs()
        val prompt = now - row.startedAt in 0..RECONCILE_ATTRIBUTION_MS
        // ... verdict as today ...
        when (verdict) {
            is Verdict.Matched -> if (prompt) { /* unchanged DONE + RESTORED handling */ } else {
                audit.update(row.copy(status = ToolkitActionStatus.FAILED, endedAt = now,
                    code = ToolkitClientCode.UNVERIFIED, afterRaw = null))
            }
            is Verdict.Mismatch -> audit.update(row.copy(status = ToolkitActionStatus.FAILED, endedAt = now,
                code = if (prompt) ToolkitCode.NOT_EFFECTIVE else ToolkitClientCode.UNVERIFIED,
                afterRaw = if (prompt) verdict.current else null))
            // Unreachable / Unverifiable unchanged
        }
        return true
    }
    ```
    Supporting changes:
    - Adjust `ToolkitActionsObservedChangeTest.aReconciledWriteThatLandedOnAThirdValueStaysInItsChain` to use a prompt
      `startedAt`.
    - Add the late-reconcile test as a regression, asserting that `resetAll()` writes nothing.
    - Optionally list stale unverified rows in the UI as "unverified change; restore individually", behind an explicit
      confirmation.

- **[B-02] [MEDIUM] A history ingest that stops at the 8-page cap is recorded as complete, so analytics treat unread
  history as known**
  - **Category:** Logic Bug (data integrity of derived analytics)
  - **Location:**
    - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/source/AndroidTelemetrySource.kt:211-233`,
      `:285-286`
    - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/capture/SnapshotCapturer.kt:227`, `:333`
    - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/store/HistoryHorizon.kt:33-40`
    - Consumers:
      - `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/IntervalAnalyzer.kt:63-64`
      - `core/database/src/main/kotlin/com/akane/inspiration/database/store/RoomSnapshotStore.kt:246-247`, which uses
        `core/database/src/main/kotlin/com/akane/inspiration/database/Daos.kt:201-212`
      - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/interval/IntervalMetrics.kt:61-64`
      - `.../telemetry/interval/IntervalHistory.kt:40`
      - `.../telemetry/diff/ReferenceResolver.kt:269,275-276`
      - `app/src/main/kotlin/com/akane/inspiration/AppContainer.kt:225`
      - `app/src/main/kotlin/com/akane/inspiration/capture/ChargingCaptureRunner.kt:50-67`, `CapturePolicy.kt:93-101`
  - **Confidence:** Confirmed. Reproduced by a failing unit test (V1) using the real `AndroidTelemetrySource` and
    `SnapshotCapturer`.
  - **Root cause & execution trace:**
    1. `readHistory` leaves the loop early only on
       `if (!page.available || !page.truncated || page.events.isEmpty()) return pages`. When all
       `MAX_HISTORY_PAGES = 8` pages of `HISTORY_PAGE_SIZE = 4_096` come back full, `repeat` ends with the last page
       still `truncated = true` and returns `pages`. Nothing records that newer events were left unread.
    2. `mergeFull` sets
       `SOURCE_HISTORY to SourceAvailability(read.history.any { it.available }, read.history.firstOrNull { !it.available }?.reason)`,
       which gives `(true, null)`.
    3. `ingestedHistory()` returns `kind == FULL && available && reason == null`, i.e. true. Its KDoc ("stored the
       battery history up to its own wall time") is false here.
    4. The consumers act on that:
       - `IntervalAnalyzer` uses `if (resolved.now.ingestedHistory()) resolved.now.clock.wallMs else …` as the horizon,
         and the Room horizon query selects this snapshot.
       - `IntervalMetrics` calls `walk.advanceTo(knownStateEndWallMs(knownTo, …))`, so the last stored screen, plug or
         doze state is stretched as **known** across the unread span instead of `advanceUnknownTo`.
       - `ReferenceResolver.pastHistoryHorizon` treats captures inside the gap as covered.
    5. `HistoryIngest.historyAvailable = read?.history?.any { it.available } == true` has the same flaw, and also when
       page k fails after k−1 good pages. `runChargeJobCapture` then picks the newest **stored** PLUGGED event, possibly
       an older session, and `chargeJobShouldRun` can skip this session's FULL capture. That decision is one-shot.
  - **Failure scenario:** the ingest backlog exceeds 32,768 emitted transition events. Typical cases:
    - the first app open on an install whose batterystats session spans weeks;
    - the first open after a long Shizuku outage;
    - any re-page from history time 0 after a session relabel.

    Until later ingests drain the backlog (APP_OPEN is debounced to 60 s, so this is transient):
    - Cockpit, Sleep Lab and the timeline carry a possibly weeks-old state ("screen off, unplugged") across the gap, so
      screen-on time, rates, the story and the "Since unplugged" anchor are wrong;
    - the charge job may skip this charge session.

    Reproduction `ValidatorReproHistoryPageCapTest` reports `availability=SourceAvailability(available=true, reason=null)`
    after 8 truncated pages.
  - **Concrete fix:**
    ```kotlin
    // SnapshotCapturer.mergeFull, replacing line 227
    SOURCE_HISTORY to read.history.let { pages ->
        val failed = pages.firstOrNull { !it.available }?.reason
        // readHistory stops at MAX_HISTORY_PAGES with the last page still full: newer events are unread.
        val capped = failed == null && pages.lastOrNull()?.let { it.truncated && it.events.isNotEmpty() } == true
        SourceAvailability(pages.any { it.available }, failed ?: if (capped) "page_cap" else null)
    },

    // HistoryIngest
    val historyAvailable: Boolean get() =
        read?.history?.let { it.isNotEmpty() && it.all { p -> p.available } && !it.last().truncated } == true
    ```
    `IntervalAnalyzer` then falls back to `store.historyHorizonWallMs()` (the last complete ingest), and the charge job
    falls back to `lastPowerConnectedWallMs`, which is its existing unavailable-history path.
    - Add the validator's page-cap test as a regression.
    - Consider continuing the drain in a follow-up ingest right away instead of waiting for the next trigger.

- **[B-03] [MEDIUM] When no application UID could be checked, the per-app rules count as "evaluated", the story says
  "No anomalies detected" and Sleep Lab shows "Nothing recorded"**
  - **Category:** Logic Bug
  - **Location:**
    - `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/rules/DiagnosticRuleEngine.kt:47-49,68,98-116,207-244`
    - `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/story/BatteryStoryBuilder.kt:36-38`
    - `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabMappers.kt:150-154,198-200,314-317`
    - `feature/sleeplab/src/main/res/values/strings.xml:48-50` (plus `values-tr`)
    - Upstream: `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/SnapshotDiffer.kt:52-54,177-189,224-244`
  - **Confidence:** Confirmed. Reproduced by a failing unit test (V1).
  - **Root cause & execution trace:**
    1. `SnapshotDiffer` marks every application UID `valid = false` in three cases:
       - either snapshot truncated its usage list (`if (incompleteUidUsage) observedUids.map { it.copy(valid = false) }`);
       - the UID-identity proof is missing at either end (the 50/400/50 ms `UidIdentityReader` budgets were missed, so
         `uidIdentity` is null);
       - the UID belongs to another Android user.
    2. The engine filters with `val validUids = diff.uids.filter { it.valid && !it.packageChanged }`, then returns
       `Outcome.Insights(items)` with an empty list from `nightVampire`. `sensorLeak` checks source presence over
       **all** UIDs (`input.diff.uids.none { it.sensors.isNotEmpty() }`) and then scans only `validUids`. The invalid
       UIDs are only counted, in `skippedInvalidUids`.
    3. `BatteryStoryBuilder` adds the all-clear on
       `if (report != null && (report.insights.isNotEmpty() || report.notEvaluable.isEmpty()))`, which renders
       `Anomalies(0, null)`, i.e. "No anomalies detected." It never reads `skippedInvalidUids`.
    4. In Sleep Lab, the app tabs drop the invalid UIDs silently and show "Nothing recorded for this period". The
       culprit note attributes skipped apps to "counters reset", which is wrong when identity was unproven.
  - **Failure scenario:** a screen-off-only interval (a night, or "Since screen off") whose endpoint missed its identity
    budget or truncated its usage parcel (512 KiB). Cockpit's story gives a confident all-clear and Sleep Lab's app tabs
    show nothing, while a real Night Vampire or Sensor Leak (the repro has 79 mAh of background drain plus 2 h of GPS)
    goes unreported. Reproduction output:
    `no application UID was checked (skippedInvalidUids=2, insights=[], notEvaluable={}) yet the story says: [Anomalies(count=0, topRule=null)]`.
  - **Concrete fix:**
    ```kotlin
    // DiagnosticRuleEngine.kt
    const val APPS_UNPROVEN = "apps_unproven"
    private fun isAppUid(uid: Int) = uid % 100_000 >= FIRST_APP_UID          // shared with B-16

    // evaluate(): after validUids
    val appsUnproven = diff.uids.any { isAppUid(it.uid) } && validUids.none { isAppUid(it.uid) }
    // nightVampire and sensorLeak, after the dumpCounters check:
    if (appsUnproven) return Outcome.NotEvaluable(APPS_UNPROVEN)
    ```
    - The story then omits the all-clear, because `notEvaluable` is no longer empty.
    - In Sleep Lab, add `excluded: Int` to `ListUi.Rows`, computed as
      `diff.uids.count { isAppUid(it.uid) && (!it.valid || it.packageChanged) }`. Show "N apps couldn't be checked for
      this period" instead of `sleeplab_list_empty` when the rows are empty and `excluded > 0`.
    - Reword `sleeplab_culprit_skipped_apps` (en/tr) so it does not assert a reset.

- **[B-04] [MEDIUM] Sleep Lab states a specific but wrong reason when a data source is missing ("less than 30 min
  screen-off", "the device restarted")**
  - **Category:** Logic Bug
  - **Location:**
    - `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/rules/DiagnosticRuleEngine.kt:34-35,125-127`
    - `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabMappers.kt:152,180,241,321-326`
    - `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabCulprits.kt:71`
    - `feature/sleeplab/src/main/res/values/strings.xml:57,74,76,128,130` (plus `values-tr`)
    - Upstream: `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/SnapshotDiffer.kt:29-37,455-493`,
      `.../capture/SnapshotCapturer.kt:226`
  - **Confidence:** Confirmed. Reproduced by three failing unit tests (V1).
  - **Root cause & execution trace:** `SnapshotDiffer` keeps the causes apart, but the consumers merge them:
    1. **Awake card.** `val sleep = diff.sleep ?: return Outcome.NotEvaluable(NO_SCREEN_OFF_TIME)` and
       `if (screenOffMs == null || screenOffMs < MIN_SCREEN_OFF_MS) return Outcome.NotEvaluable(NO_SCREEN_OFF_TIME)`
       give the same reason for "no figures" and for "short screen-off". Then `awakeSkipReason`:
       `NO_SCREEN_OFF_TIME -> AwakeSkipReason.ShortScreenOff`, which shows "This period has less than 30 min of
       screen-off time … Pick a longer period, such as a night."
       A LIGHT endpoint in the same boot (Shizuku not yet ready, or a missed bind deadline) invalidates the stats
       session, and `SnapshotDiffer` falls back to kernel clocks with `screenOffMs = null`.
    2. **CPU card.** `if (!diff.validity.cpuFreq) return CpuUi.Unavailable` shows "The device restarted…". But
       `cpuFreq` is also false within one boot whenever the `cpu` source is absent, i.e. the kernel exposes no
       `time_in_state` (`SOURCE_CPU to SourceAvailability(read.cpuTimes.isNotEmpty(), …)`). The accurate state,
       `CpuUi.NoData` ("The kernel did not report time per frequency"), is never chosen for that case.
    3. **Kernel tab.** `if (!diff.validity.kernelWakelocks) return ListUi.Unavailable` shows "device restarted",
       although `kernelWakelocks = kernelCounters && dumpCounters` is also false within one boot when the dump is
       missing.
    4. **Apps tab.** `if (!diff.validity.dumpCounters)` shows "Battery stats restarted", although it is also false for
       a missing dump or session skew.
  - **Failure scenario:** an 8-hour night where one endpoint is a LIGHT snapshot. The awake card tells the user the
    night had under 30 minutes of screen-off and to "pick a night", while the CPU card and kernel tab claim a restart
    that did not happen. On kernels without cpufreq stats, the CPU card says "device restarted" on every interval; the
    redroid test device is one (`/sys/devices/system/cpu/cpufreq` is empty). Reproduction outputs:
    - `Actual: NotEvaluable(reason=ShortScreenOff)` with `screenOffMs=null`;
    - `Actual: Unavailable` with `kernelCounters=true` (twice).
  - **Concrete fix:**
    ```kotlin
    // DiagnosticRuleEngine.kt
    const val NO_SCREEN_OFF_FIGURES = "no_screen_off_figures"
    val sleep = diff.sleep ?: return Outcome.NotEvaluable(NO_SCREEN_OFF_FIGURES)
    val screenOffMs = sleep.screenOffMs ?: return Outcome.NotEvaluable(NO_SCREEN_OFF_FIGURES)
    if (screenOffMs < MIN_SCREEN_OFF_MS) return Outcome.NotEvaluable(NO_SCREEN_OFF_TIME)

    // SleepLabState.kt
    enum class UnavailableCause { DeviceRestarted, StatsReset, NotCaptured }
    data class Unavailable(val cause: UnavailableCause) : ListUi<Nothing>
    // AwakeSkipReason += NoScreenOffFigures ("Battery stats don't cover this whole period, so screen-off time can't be measured.")

    // SleepLabMappers.kt
    private fun SnapshotDiff.unavailableCause() = when {
        !validity.kernelCounters -> UnavailableCause.DeviceRestarted
        !validity.batteryStats -> UnavailableCause.StatsReset
        else -> UnavailableCause.NotCaptured
    }
    internal fun cpuUi(diff: SnapshotDiff): CpuUi {
        if (!diff.validity.cpuFreq) return if (diff.validity.kernelCounters) CpuUi.NoData else CpuUi.Unavailable
        // ...
    }
    // awakeSkipReason: NO_SCREEN_OFF_FIGURES -> AwakeSkipReason.NoScreenOffFigures
    ```
    Pick the body copy per `cause` (en/tr). Update `SleepLabCulpritsTest.kt:114`, which currently asserts the wrong
    mapping.

- **[B-05] [MEDIUM] The Cockpit level line has no carry-in point at either window edge, so chart readouts go blank in
  normal use**
  - **Category:** Logic Bug
  - **Location:**
    - `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/TimelineBuilder.kt:47-70,95-103`
    - `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/math/SeriesMath.kt:43-45`
    - `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/scrub/ScrubReadout.kt:106-131`
    - `app/src/main/kotlin/com/akane/inspiration/di/TimelineEvents.kt:33-43`
    - `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitViewModel.kt:261-265`
  - **Confidence:** Confirmed by trace (V3). No reproduction.
  - **Root cause & execution trace:**
    1. LEVEL history events exist only when the level changes by 1%.
    2. `TimelineEvents.withSeeds` seeds the last screen, plug, idle, shutdown, reset and start events before the window,
       but no LEVEL event.
    3. `TimelineBuilder.points` keeps only in-window LEVEL events:
       `ordered.filter { it.kind == HistoryKind.LEVEL && it.wallMs in from..to }`.
    4. `valueAt` returns null outside `[first, last]`:
       `if (points.isEmpty() || tMs < points.first().tMs || tMs > points.last().tMs) return null`.
    5. The Cockpit window is a fixed 24 h ending now (`deps.timeline(to - CHART_WINDOW_MS, to)`). So the line starts at
       the first 1% step after `from` and ends at the last 1% step before now.
    6. Temperature points share the same filter.
  - **Failure scenario:**
    - Plugged in at 100% overnight, or idling: hours of the 24 h chart have no level line.
    - A cursor or brushed slice reaching "now" or the window start shows no level and no drop/gain readout, which is
      the Cockpit's primary readout.
    - On a slowly draining phone the right edge can be tens of minutes stale at all times.
  - **Concrete fix:** every `HistoryEvent` carries `levelPct` and `temperatureDeciC`, and the seeds already supply the
    newest events before `from`.
    ```kotlin
    // TimelineBuilder.build
    levelPoints = points(ordered, from, to, laneEnd) { it.levelPct.toFloat() },
    temperaturePoints = points(ordered, from, to, laneEnd) { it.temperatureDeciC / 10f },

    private fun points(
        ordered: List<HistoryEvent>, from: Long, to: Long, holdTo: Long, value: (HistoryEvent) -> Float,
    ): List<TimelinePoint> {
        val out = ArrayList<TimelinePoint>()
        // Any event at or before `from` (a seed) carries the level in force when the window opens.
        ordered.lastOrNull { it.wallMs <= from }?.let { out += TimelinePoint(from, value(it)) }
        ordered.filter { it.kind == HistoryKind.LEVEL && it.wallMs > from && it.wallMs <= to }
            .mapTo(out) { TimelinePoint(it.wallMs, value(it)) }
        // Hold the last value to where history is known complete, not past it.
        out.lastOrNull()?.let { last -> if (holdTo > last.tMs) out += TimelinePoint(holdTo, last.value) }
        return out
    }
    ```

- **[B-06] [MEDIUM] X-Ray shows an enabled "Restore" that silently does nothing whenever the app's controls can't be
  read (protected apps included)**
  - **Category:** Logic Bug
  - **Location:**
    - `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayViewModel.kt:347-356` (the guard at line 352),
      `:271`, `:287-299`
    - `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayDetail.kt:377-379`, `:605-611`
    - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt:242-249`, `:569`,
      `:1230-1231`
    - For contrast: `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitViewModel.kt:454-456`
  - **Confidence:** Confirmed. Reproduced by a failing unit test (V2). A control test in the same class confirms that
    `toolkit.restore` itself works.
  - **Root cause & execution trace:**
    1. For a protected package (role holder, IME, accessibility, always-on VPN), `ToolkitActions.appState` returns
       `Refused(PROTECTED_APP)`. `readActions` maps it to `ActionsState.Unavailable`.
    2. `computeOverrides` keeps a restriction's undo `restorable = true` ("the one write a protected app takes"), and
       `prepare` allows it (`:569`).
    3. The sheet draws the override card for `Unavailable` and `Loading`, with Restore `enabled = !detail.busy`.
    4. Tapping it sends `Act(Restore(id))`, and `act()` returns silently:
       ```kotlin
       if (detail.actions !is ActionsState.Loaded || deps.shizukuState.value.blockedReason() != null) return
       ```
    5. The same no-op happens while actions are Loading (`reloadDetail` keeps the old overrides) and after any failed
       read.
    6. The Toolkit screen's `accepts()` allows the same restore, so the two screens disagree.
  - **Failure scenario:**
    1. The user restricts an app in X-Ray.
    2. The app later becomes the default SMS app or keyboard.
    3. X-Ray lists the restriction with an enabled Restore. Tapping it does nothing, with no notice; the previous
       "Applied: Restricted" notice stays.
    4. The protected app stays restricted until the user happens to use the Toolkit screen.

    Reproduction: `Restore did nothing: writes=[setAppOp] runAnyInBackground=ignore notice=Applied(change=Restricted) … busy=false`.
  - **Concrete fix:**
    ```kotlin
    private fun act(request: ActionRequest) {
        val detail = _state.value.detail ?: return
        if (!isOwnerUser(detail.app.uid)) return
        val pkg = detail.app.packageName ?: return
        if (detail.busy || _state.value.confirmation != null) return
        if (deps.shizukuState.value.blockedReason() != null) return
        when (request) {
            // A restore needs no readable controls: lifting our restriction is the one write a protected app takes.
            is ActionRequest.Restore ->
                if (detail.overrides.none { it.actionId == request.actionId && it.restorable }) return
            else -> if (detail.actions !is ActionsState.Loaded) return
        }
        // ...
    }
    ```
    Better: move the "may this request run" rule into one shared function used by both `XRayViewModel` and
    `ToolkitViewModel.accepts()`.

- **[B-07] [MEDIUM] Cockpit time-to-empty treats any positive charge counter as µAh; a device reporting mAh shows
  "0 min left" on a full battery**
  - **Category:** Logic Bug
  - **Location:**
    - `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitMappers.kt:226-235`
    - Callers: `CockpitViewModel.kt:201-210,248`, `CockpitCards.kt:133-138`
    - Source: `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/source/AndroidTelemetrySource.kt:116-119`,
      `.../live/LiveTelemetry.kt:104`
    - For contrast: `.../calibration/ChargeCounterCheck.kt:18,38,123`
  - **Confidence:** Confirmed. Reproduced by two failing unit tests (V2). The external premise is that some kernels
    report `BATTERY_PROPERTY_CHARGE_COUNTER` in mAh. The engine itself already assumes they do
    (`MIN_MICROAMP_HOUR_COUNTER = 20_000`, and `ChargeCounterCheckTest` models a mAh kernel). Device prevalence is not
    measured.
  - **Root cause & execution trace:**
    1. The counter passes through unchanged; only `Long.MIN_VALUE` is dropped.
    2. `remainingMs` extrapolates
       `if (drainMa != null && counterUah != null && counterUah > 0) counterUah * UAH_PER_MA_TO_MS / drainMa`.
    3. It then prefers that value over the system estimate whenever it is under a week:
       `extrapolated?.takeIf { it <= MAX_EXTRAPOLATED_REMAINING_MS } ?: systemEstimateMs…`.
    4. The engine's own plausibility floor (`ChargeCounterCheck`) is not applied here.
  - **Failure scenario:** counter 3,000 (mAh) at a 300 mA drain gives 36,000 ms. The gauge shows "0 min" left on a
    60–100% battery and hides the valid system estimate. Reproduction:
    `time left from a 3,000 counter at 300 mA was 36000 ms`; `expected:<28800000> but was:<36000>`.
  - **Concrete fix:**
    ```kotlin
    // Expose and reuse the engine's floor (ChargeCounterCheck.MIN_MICROAMP_HOUR_COUNTER).
    val extrapolated =
        if (drainMa != null && counterUah != null && counterUah > MIN_MICROAMP_HOUR_COUNTER) {
            counterUah * UAH_PER_MA_TO_MS / drainMa
        } else {
            null
        }
    ```
    Optionally also require `counterUah * 100 / levelPct` to fall within a plausible capacity range.

### LOW

- **[B-08] [LOW] A shared-UID drain is labelled, and acted on, as the alphabetically first owner package**
  - **Category:** Logic Bug
  - **Location:**
    - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/app/AppFacingModels.kt:86-98`
    - Callers: `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayViewModel.kt:221-228`, `:379`,
      `:412-421,445-476`; `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabViewModel.kt:162`
    - Hint source: `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/BatteryStatsClient.kt:269`
  - **Confidence:** Confirmed. The labelling is reproduced by a failing unit test (V2). The redroid device confirms
    the ownership: uid 10011 is shared by `com.android.mtp`, `providers.downloads`, `providers.downloads.ui`,
    `providers.media` and `soundpicker`, with process `android.process.media`.
  - **Root cause:** the hint is a process name (`packageWithHighestDrain`), not a package. The fallback is
    `reported?.substringBefore(':')?.takeIf { it in owners } ?: owners.minOrNull()`, so `android.process.media`
    becomes `com.android.mtp`.
  - **Failure scenario:**
    - A heavy download or media scan appears in X-Ray and Sleep Lab as "MTP Host".
    - For non-updated system packages, writes are refused (`ToolkitExecutor.hardDeny` → `DENY_SYSTEM`), so the impact
      there is the label only.
    - For updated system apps or third-party `sharedUserId` apps, the confirmation, force-stop and restrictions target
      the alphabetically first owner, not the drainer. `XRayViewModelTest.kt:299-312` asserts that behaviour.
  - **Concrete fix:**
    ```kotlin
    suspend fun AppLabels.packageForUid(uid: Int, reported: String?): String? {
        val owners = packagesForUid(uid)
        val proc = reported?.substringBefore(':')
        return owners.firstOrNull { it == proc }
            ?: proc?.let { p -> owners.filter { p.startsWith("$it.") }.maxByOrNull { it.length } }
            ?: owners.singleOrNull() // several owners, no match: no single app to name or act on
    }
    ```
    A null result gives `ActionsState.NoPackage`; label such rows "Shared by N apps".

- **[B-09] [LOW] History ingest issues avoidable full `BatteryUsageStats` queries (every page, and on API 31–33 where
  the walk can only fail)**
  - **Category:** Performance
  - **Location:**
    - `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/BatteryStatsClient.kt:80-116,152-178`
    - `.../ipc/client/BatteryHistory.kt:74-77`
    - `.../ipc/service/TelemetryUserService.kt:219-227`
    - Callers: `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/source/AndroidTelemetrySource.kt:86-90,211-233`
  - **Confidence:** The paging cost is Confirmed. The API 31–33 part is Plausible: the code's own KDoc and AOSP state
    that the history iterator implements `Iterator<HistoryItem>` only from API 34. This could not be checked here.
  - **Root cause:**
    - Each page makes a fresh `IBatteryStats.getBatteryUsageStats([…includeBatteryHistory()])`, so system_server
      rebuilds all power calculators plus a history copy.
    - The service then re-walks the history from its first record, skipping `record.time <= after`.
    - On API 31–33 the same full query is sent although `records()` ends in a `LinkageError` → `no_such_method`.
      There is no SDK gate (compare `PowerStatsClient.kt:62`, which returns `Reasons.API`).
  - **Failure scenario:**
    - On Android 12/13, every APP_OPEN, MARKER and CHARGE_JOB capture costs system_server a full usage computation
      with a history copy whose result is always discarded.
    - On any version, draining a backlog costs up to 8 full queries.
  - **Concrete fix:**
    ```kotlin
    fun readHistory(statsStartMs: Long, afterHistoryTimeMs: Long, maxEvents: Int): BatteryHistorySnapshot {
        // BatteryStatsHistoryIterator is an Iterator only from API 34; below it the walk can only fail.
        if (sdkInt < API_HISTORY_ITERATOR /* 34 */) return BatteryHistorySnapshot.unavailable(Reasons.API)
        // ...
    }
    ```
    For paging, keep one `BatteryUsageStats` (and its iterator) open per ingest inside the service, and return
    successive pages from it instead of re-querying. Alternatively, raise the page size so the steady state is one
    query.

- **[B-10] [LOW] Shizuku state refresh makes synchronous binder calls on the main thread**
  - **Category:** Concurrency (main-thread IPC)
  - **Location:**
    - `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/ShizukuManagerImpl.kt:175-178,194-211,219-228,433-497`
    - Callers on Main:
      - `app/src/main/kotlin/com/akane/inspiration/InspirationApplication.kt:38`
      - `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitViewModel.kt:134`
      - `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitViewModel.kt:206`
      - `app/src/main/kotlin/com/akane/inspiration/AppContainer.kt:238`
  - **Confidence:** Confirmed (V3, `javap` of Shizuku api 13.1.5).
  - **Root cause:** `updateState()` makes these calls on the caller's thread:
    - `Shizuku.pingBinder()`, which is IPC on every call;
    - `checkSelfPermission()` and `shouldShowRequestPermissionRationale()`, IPC until granted;
    - `managerApp.isInstalled()` and the first `ownApplicationInfo`, PackageManager IPC.

    That thread is Main in three cases: `start()` from `Application.onCreate`; the Shizuku binder-received and
    permission-result listeners, which the library posts to `MAIN_HANDLER`; and `requestPermission()` from UI intents.
    `InspirationApplication.kt:59-61` deliberately moves `refresh()` off Main for exactly this reason, but these three
    paths were missed.
  - **Failure scenario:** with a slow or wedged Shizuku server, tapping "Grant" or a binder event janks the UI, and in
    the worst case causes an ANR.
  - **Concrete fix:**
    ```kotlin
    private fun refreshAsync() { scope.launch(ioDispatcher) { updateState() } }
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshAsync() }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshAsync() }
    override fun requestPermission() {
        if (!started) return
        scope.launch(ioDispatcher) {
            updateState()
            if (mutableState.value !is ShizukuState.PermissionRequired) return@launch
            try { facade.requestPermission(PERMISSION_REQUEST_CODE) }
            catch (_: RuntimeException) { synchronized(bindingLock) { transportLostLocked() } }
        }
    }
    // start(): register listeners, then refreshAsync()
    ```
    The existing ticket ordering already serializes concurrent refreshes. The debug `ProbeReceiver` must then await the
    state instead of reading it right after `start()`.

- **[B-11] [LOW] Tapping the Cockpit chart to stop a fling clears the app-wide time slice**
  - **Category:** Logic Bug (gesture handling)
  - **Location:**
    - `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/scrub/ScrubGestures.kt:54`,
      `:85`, `:114-123`
    - `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/math/ScrubGestureReducer.kt:264-269`
    - Callers:
      - `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitScreen.kt:48`
      - `CockpitChartCard.kt:43-55`
      - `CockpitViewModel.kt:133`
      - `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/AnalysisSelection.kt:19-21`
  - **Confidence:** Confirmed by code plus foundation 1.12.1 bytecode trace (W9, V2). Not reproduced on a device.
  - **Root cause:**
    1. While `verticalScroll` is flinging (or its overscroll is animating), the scroll node consumes the new Down on
       the Initial pass (`DragGestureNode.processInitialDownState` when `startDragImmediately()`). It never consumes
       the Up.
    2. The chart accepts the consumed Down: `awaitFirstDown(requireUnconsumed = false)`. Its consumed-change check in
       `pointerChangeEvent` applies only to later changes.
    3. `up(Pressed)` commits `SelectionCommit.Clear` when a selection exists. That becomes `CockpitIntent.ClearSlice`,
       then `AnalysisSelection.clearSlice()`.
  - **Failure scenario:** a user with a brushed slice flings the Cockpit and taps the chart to stop it. The slice
    shared by Cockpit, X-Ray and Sleep Lab resets to "Since unplugged", and every screen recomputes.
  - **Concrete fix:**
    ```kotlin
    val down = awaitFirstDown(requireUnconsumed = false)
    // An ancestor took this press (a flinging scroll stopping on it): not a tap, brush or scrub of the chart.
    if (down.isConsumed) return@awaitEachGesture
    ```

- **[B-12] [LOW] X-Ray write jobs are untracked: a reopened sheet re-enables controls during an in-flight write**
  - **Category:** Concurrency
  - **Location:** `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayViewModel.kt:232-247`, `:250-285`,
    `:369`, `:404`, `:440-443`, `:474-475`
  - **Confidence:** Confirmed for X-Ray (V2). The companion stale-read race in `ToolkitViewModel.kt:372-377` vs
    `:685-693` is Plausible with a very narrow window.
  - **Root cause:**
    1. Writes are started with `viewModelScope.launch { executeContained(…) }` and no reference is kept.
    2. `closeApp` leaves the write running, and `openApp` builds a fresh `AppDetail(busy = false)`.
    3. A second write can then be queued. The first write's completion sets `busy = false` by uid/package while the
       second is still running.
    4. A Visible-triggered `reloadDetail` read can land after the write's own read-back and show stale controls.
  - **Failure scenario:** stale or enabled controls and notices during a write. There is no unsafe write: every write
    is re-planned under the `ToolkitActions` mutex on a fresh read and audited. Toolkit blocks this case with a
    screen-level `busy`; X-Ray doesn't.
  - **Concrete fix:**
    ```kotlin
    private var writeJob: Job? = null
    private var writeTarget: Pair<Int, String>? = null
    private fun writing(uid: Int, pkg: String?) = writeJob?.isActive == true && writeTarget == (uid to pkg)
    private fun launchWrite(uid: Int, request: ActionRequest, pkg: String, confirmed: Boolean) {
        writeTarget = uid to pkg
        writeJob = viewModelScope.launch {
            try { executeContained(uid, request, pkg, confirmed) } finally { writeTarget = null }
        }
    }
    // openApp: AppDetail(app = app, actions = ActionsState.Loading, busy = writing(app.uid, app.packageName))
    // reloadDetail: skip the controls read while writing(uid, pkg); the write's read-back updates the controls.
    ```

- **[B-13] [LOW] RefreshCoordinator: a failure cleared on Shizuku Ready can be written back, so the promised re-run
  returns the cached failure**
  - **Category:** Concurrency
  - **Location:**
    - `app/src/main/kotlin/com/akane/inspiration/di/RefreshCoordinator.kt:66-69`, `:77-81`, `:90-91`, `:101`,
      `:113-116`
    - Caller: `app/src/main/kotlin/com/akane/inspiration/AppContainer.kt:369-377`
  - **Confidence:** Confirmed. Reproduced by a failing unit test (V3): `expected:<2> but was:<1>`.
  - **Root cause:**
    - Line 101,
      `lastFailure = (result as? RefreshResult.Failed)?.takeIf { epoch == backoffEpoch }?.let { nowWallMs() to it }`,
      checks the epoch, calls `nowWallMs()`, then writes.
    - `clearFailureBackoff()` (`backoffEpoch += 1; lastFailure = null`) is lock-free and runs on
      `applicationScope`/IO, concurrently with `refresh()` on Main.
    - A clear landing between the check and the write is lost. `refreshIfLastFailed()` then hits the 12 s backoff and
      returns the cached `Failed` without capturing.
  - **Failure scenario:** rare (a window of a few instructions). After Shizuku becomes Ready, screens keep the stale
    failure until the next visit or the backoff expiry.
  - **Concrete fix:** record the epoch with the failure and ignore stale ones at the backoff check.
    ```kotlin
    private data class FailureMark(val epoch: Int, val atMs: Long, val result: RefreshResult.Failed)
    @Volatile private var lastFailure: FailureMark? = null
    // line 101:
    lastFailure = (result as? RefreshResult.Failed)?.let { FailureMark(epoch, nowWallMs(), it) }
    // backoff check (lines 90-91):
    val failure = lastFailure?.takeIf { it.epoch == backoffEpoch }
    ```

- **[B-14] [LOW] Charge & Health waits up to 2 s for the kernel read before showing purely local data**
  - **Category:** Performance (latency)
  - **Location:**
    - `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthViewModel.kt:372-379`
    - `app/src/main/kotlin/com/akane/inspiration/AppContainer.kt:260-263,381`
    - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/source/AndroidTelemetrySource.kt:154-159`
    - `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/ShizukuManagerImpl.kt:286,356-362,452-456`
  - **Confidence:** Confirmed (V2).
  - **Root cause:**
    - `load()` awaits `attempt { deps.kernelBattery() }` before its four Room reads.
    - When Shizuku is installed but not running (`started && NotRunning`), `awaitServiceUse` suspends until the 2 s
      `KERNEL_READ_TIMEOUT_MS`.
  - **Failure scenario:** after every reboot on a non-root phone, opening Charge & Health shows the loading card for
    2 s, and every later reload is delayed 2 s, although the wear chart and recorded curve come from Room.
  - **Concrete fix:** inside `coroutineScope` in `load()`, start
    `val kernel = async { attempt { deps.kernelBattery() } }`, run the Room reads, then `kernel.await()`.

- **[B-15] [LOW] The Health live curve freezes after a backwards wall-clock step**
  - **Category:** Logic Bug
  - **Location:** `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthViewModel.kt:94-101`, `:245`,
    `:267`, `:307-316`, `:330-335`
  - **Confidence:** Confirmed (V2).
  - **Root cause:**
    - `LiveBuffer.add` rejects `point.wallMs <= newestMs`, where `wallMs` is `System.currentTimeMillis()`.
    - `newestMs` survives until the screen is hidden or the charger replugged.
    - `onReading` returns before `markLive()`, so after 14 s the curve shows "Recorded".
    - No `TIME_SET` handling exists.
  - **Failure scenario:** charging with the screen open, a manual time change or a large NTP correction backwards by Δ
    freezes the live curve for Δ.
  - **Concrete fix:** order on `reading.elapsedRealtimeMs`, which `LiveReading` already carries. When wall time goes
    back but elapsed time moved forward in the same boot, call `live.clear()`, re-anchor `observedPlugMs`, and add the
    point.

- **[B-16] [LOW] The rule engine classifies app UIDs by raw value, so per-user platform UIDs are treated as apps**
  - **Category:** Logic Bug
  - **Location:**
    - `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/rules/DiagnosticRuleEngine.kt:107`, `:220`,
      `:258`
    - For contrast:
      - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/snapshot/UidIdentity.kt:37`
      - `app/src/main/kotlin/com/akane/inspiration/di/CachedAppLabels.kt:78`
      - `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabDiagnostics.kt:156-160`
  - **Confidence:** Confirmed code path (V1). Whether BatteryStats reports secondary-user platform UIDs is device
    dependent; redroid has no second user.
  - **Root cause:**
    - The engine uses `uid.uid < FIRST_APP_UID` and `validUids.filter { it.uid >= FIRST_APP_UID }` on the raw UID.
    - `isApplicationUid` everywhere else uses `uid % 100_000 >= 10_000`.
    - No user filter exists upstream (`BatteryStatsClient.kt:260`, `SnapshotCapturer.kt:180`).
    - So `1001000` (system in user 10) is "valid" without owner proof, yet passes the app check.
  - **Failure scenario:** on work-profile or multi-user devices, system-server energy or sensors in another user can be
    reported as a Night Vampire or Sensor Leak "app" insight. Actions are still blocked by owner-user checks.
  - **Concrete fix:** use one shared helper, `uid % 100_000 >= FIRST_APP_UID` (the same helper as in B-03), at both
    sites.

- **[B-17] [LOW] Sleep Lab makes one uncached PackageManager IPC per UID on every analysis emission**
  - **Category:** Performance
  - **Location:**
    - `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabViewModel.kt:75-78`, `:134-166`
    - `app/src/main/kotlin/com/akane/inspiration/di/CachedAppLabels.kt:41`, `:76-82`
  - **Confidence:** Confirmed (V3). Latency not measured.
  - **Root cause:**
    - `withOwnerPackages` maps every `analysis.diff.uids` entry, one after another, through `packageForUid`.
    - Each call costs a `withContext(IO)` hop plus one `getPackagesForUid` IPC, and membership is never cached.
    - It runs on each refresh, revisit and config change.
    - Only at most 43 owners are displayed (20 + 20 rows, 3 culprits).
    - The KDoc at `:157` ("appLabels keeps the bounded cache") is wrong for UID membership.
  - **Failure scenario:** 100+ sequential IPCs and dispatcher hops per update on a typical phone, delaying the screen.
  - **Concrete fix:** build rows and culprits first, then resolve owners only for
    `(rows + culprits).mapNotNull { it.uid }.distinct()`. Alternatively, cache membership keyed by
    `(uid, packageChangeSequence)`.

- **[B-18] [LOW] App labels and icons are cached for the life of the process, including "not installed" results**
  - **Category:** Logic Bug (stale cache)
  - **Location:** `app/src/main/kotlin/com/akane/inspiration/di/CachedAppLabels.kt:59-74`. Consumers:
    - `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitViewModel.kt:336,348`
    - `app/src/main/kotlin/com/akane/inspiration/AppContainer.kt:306`
    - `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayViewModel.kt:227`
  - **Confidence:** Confirmed (V2).
  - **Root cause:**
    - `label()` and `icon()` store `Resolved(null)` for missing packages and never evict.
    - No `PACKAGE_ADDED/REMOVED/REPLACED/CHANGED` receiver exists; only `MY_PACKAGE_REPLACED` is handled.
    - The foreground status service keeps the process alive for days.
  - **Failure scenario:** the Toolkit history labels an uninstalled package. After the user reinstalls it, X-Ray and
    the Toolkit picker show the raw package name and a monogram (and sort it wrongly) until the 512-entry LRU evicts it
    or the process dies. Renamed apps keep their old labels.
  - **Concrete fix:**
    - Don't cache negative label/icon results.
    - Add `fun invalidate(pkg: String)`.
    - Register one `RECEIVER_NOT_EXPORTED` receiver in `AppContainer` for `ACTION_PACKAGE_ADDED/REPLACED/CHANGED/REMOVED`
      (scheme `package`) that calls `invalidate`.

- **[B-19] [LOW] The Cockpit marker sheet reports a list-read failure as a capture failure, inviting a duplicate
  marker**
  - **Category:** Logic Bug (error reporting)
  - **Location:**
    - `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitViewModel.kt:304-323`, `:335-343`
    - `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitMarkerSheet.kt:112-118`, `:132-134`
    - `feature/dashboard/src/main/res/values/strings.xml:80-82`
  - **Confidence:** Confirmed (V2). The trigger is rare: a SQLite error, because `attempt` turns any exception into
    null.
  - **Root cause:** `loadMarkers` sets `failed = markers == null` and lists `markers.orEmpty()`. The same flag also
    represents a failed `markNow` capture.
  - **Failure scenario:** after a successful capture, a failing list read shows "That didn't work. Check that Shizuku or
    Sui is connected" plus "No markers yet". The user taps again, which runs another full capture and stores a
    duplicate marker.
  - **Concrete fix:** split the state into `captureFailed` (set only by `markNow`) and `listFailed`. On a list failure,
    keep the previous markers and show a separate "Couldn't load markers" note.

- **[B-20] [LOW] Dump writer threads in the shell-UID service have no timeout**
  - **Category:** Resource Leak (bounded)
  - **Location:** `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/service/TelemetryUserService.kt:241-256`,
    `:447-452`
  - **Confidence:** Confirmed (V3).
  - **Root cause:**
    - Each `openDump` starts a writer thread that calls the system dump with no timeout or interrupt.
    - Closing the pipe unblocks a dump that is writing (EPIPE), but not one that hangs before its first write.
  - **Failure scenario:** a hung system `dumpsys` (2 per FULL capture) leaves threads in the service while a screen
    holds the binding. This is bounded by the capture debounce and by the service exiting 3 s after its last user.
  - **Concrete fix:** run dump writers on a single bounded executor and refuse a new dump of the same target while one
    is in flight. Alternatively, track writer threads and have `destroy()` / the client timeout interrupt them and
    close the write end.

- **[B-21] [LOW] "Restore all N changes?" counts overrides that Reset all will not restore**
  - **Category:** Logic Bug (copy)
  - **Location:**
    - `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitViewModel.kt:404`
    - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt:1211`, `:1231`,
      `:1269`, `:1394-1402`
  - **Confidence:** Confirmed (V3). Partly mitigated: the dialog body says unrestorable items stay listed, and the
    result reports restored/total.
  - **Root cause:** the count is `overrides.size`. It includes `restorable = false` and unverified overrides, which
    `resetAll` skips.
  - **Concrete fix:** `overrides.count { it.restorable && it.verified }` in the title. Mention the rest in the body.

- **[B-22] [LOW] Every FULL capture queries usage with `maxStatsAgeMs = 0`, which may force an external-stats sync each
  time**
  - **Category:** Performance (observer effect)
  - **Location:**
    - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/source/AndroidTelemetrySource.kt:61`
    - `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/BatteryUsageAdapter.kt:66`
    - Frequency: `app/src/main/kotlin/com/akane/inspiration/capture/CapturePolicy.kt:31-52`
  - **Confidence:** Plausible. The code facts are confirmed. That maxAge 0 triggers a modem/Wi-Fi/BT controller sync in
    `BatteryStatsService` is AOSP knowledge not verified here. The only rationale in code is a pointer to a design
    document outside the audited source.
  - **Root cause:**
    - `readBatteryUsage(0L)` is hard-coded and runs on every FULL capture: SCREEN_OFF while ready, qualifying
      SCREEN_ON, APP_OPEN, MARKER, CHARGE_JOB.
    - The same capture also takes a `batterystats --proto` dump, which already syncs.
  - **Failure scenario:** a likely second back-to-back external-stats sync per capture, i.e. extra radio-controller
    wakeups attributable to the battery app itself.
  - **Concrete fix:** read the dump first, then query usage with a maxAge of a few seconds so it reuses that sync.
    Alternatively, document the trade-off next to the call.

- **[B-23] [LOW] Negative power components are dropped, so the system UID's breakdown can exceed its total**
  - **Category:** Logic Bug
  - **Location:**
    - `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/BatteryUsageAdapter.kt:139-155`
    - `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayMapper.kt:108-116`
    - Related: `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/SnapshotDiffer.kt:317,495-498`
  - **Confidence:** Plausible. The filter (`value > 0.0`, where the comment says only zeros are dropped) and the
    division by `totalMah` are confirmed. That AOSP records `REATTRIBUTED_TO_OTHER_CONSUMERS` as negative on UID 1000,
    and that it is included in `getConsumedPower()`, is unverified here.
  - **Failure scenario:** X-Ray's detail for the System UID shows component shares that add up to more than 100%
    (`StackedBar` itself scales safely). The `device_from_uid_sum` double count is practically unreachable on API 31+.
  - **Concrete fix:**
    - Compute X-Ray shares against the sum of positive components.
    - Keep id 17 out of the display rows.
    - If negatives are ever retained, exclude id 17 from `hasNegative`, so UID 1000 is not invalidated on every diff.

### Candidates rejected during validation (for transparency)

| Candidate | Why rejected |
|---|---|
| Exact `statsStartMs` equality in `BatteryHistory.kt:67` forces re-paging | Both sides come from the same persisted platform field. Re-paging after a relabel is documented and deduplicated (watermark + unique index). |
| Inconsistent stored `status` vocabulary (sysfs vs BatteryManager names) | No production code reads `status` from a persisted snapshot. Listed as debt in §3. |
| `BgDexoptController.supervise` RuntimeException → FINISHED without a system cancel | Nothing in `supervise` throws a RuntimeException in practice, and a second job cannot start in the gap. Hardening only (§3). |
| `Preloaded.get()` `runBlocking` on Main | The wait is bounded (a 39.9 KB bundled JSON load started in `Application.onCreate`), and a malformed bundle fails the build's Robolectric test. |
| `XRayViewModel` `packageForUid` without `attempt` | No realistic exception reaches it (`QUERY_ALL_PACKAGES` is declared; a dead system_server kills the app anyway). Hardening only (§3). |
| Work-profile rows indistinguishable in X-Ray | Other-user application UIDs are always invalid (`UidIdentityScope.covers`) and never listed; actions are also refused by `isOwnerUser`. |

---

## 3. Dead Code, Redundancy & Technical Debt Cleanup

Each item was verified with a scoped `rg` over `app core feature` (production sources, excluding tests) by the
reviewer named in parentheses.

**Unused or test-only production code**
- **Debug-probe-only clients in the main source set of `:core:shizuku-ipc`** (W1):
  - The classes: `PowerStatsClient`, `DeviceIdleReader`, `DeviceIdleClient`, `PowerManagerClient`, `IdleFlags`, the
    `BinderResult` sealed type (but not `withBinder`/`failureReason` in the same file, which production uses), and the
    `@Parcelize` `DeviceIdleSnapshot`, `PowerMonitorSnapshot` and `PowerMonitorReading`.
  - Their only caller is `app/src/debug/.../ProbeReceiver.kt:570-581`.
  - The stubs that exist only for them: `IDeviceIdleController`, `IPowerManager` and `IPowerStatsService`, plus their
    `-dontwarn` lines.
  - Fix: move all of these to a debug-only source set.
- **AIDL parcelable declarations** `model/ClusterFreqTime.aidl`, `HistoryEvent.aidl` and `UidUsage.aidl` are not
  referenced by any AIDL interface (W1).
- **Test-only:**
  - `ShizukuManager.stop()` (W1)
  - `RefreshCoordinator.lastRefreshWallMs` (W5)
  - `HealthMappers.lastChargeSpan` and `HealthViewModel.heldLivePoints` (W7)
  - `DozeRaw.AOSP_KEYS` (W2)
  - `Explanation.category` (W6)
- **No production caller** (W4):
  - `RoomSnapshotStore.unplugWindowSnapshots` and the interface default.
  - `RoomSnapshotStore.insertHistory` / `setHistoryCursor`. These are non-atomic duplicates of the transactional
    `persistHistoryPage`, reachable only from tests and `PreviewDependencies`, so they risk drift.
  - `TelemetryDao.hourly`, `SnapshotDao.byId` and `latestByTrigger` are test/debug-only, as their comments say.
- **Unused fields and accessors:**
  - `KernelCapacitySnapshot.chargeCounterMah`, `levelPct` and `temperatureDeciC` are never read in main (W7).
  - `AppContainer.database` and `analysisSession` are public with no external reader (W5).
- **Unreachable branch:** `AwakeCheckUi.Violation.needsFullSnapshots = !perUid` and its culprit filter
  (`SleepLabMappers.kt:296,315`). A LIGHT endpoint always makes AWAKE_VIOLATION not evaluable (W6). The
  "needs full snapshots" screenshot shows an unreachable state.
- **Unused resources:**
  - `health_unit_volt` (`feature/health/src/main/res/values*/strings.xml`, W7)
  - `xray_app_icon_placeholder` (`feature/xray/src/main/res/values*/strings.xml`, W8)
- **Design-system API unused in production** (W9):
  - `InspirationTheme(dynamicAccent)` and `withAccentFrom`.
  - The `*Container` telemetry/status colors.
  - `SynchronizedScrubbingChart`'s `onScrub`/`cursorMs` and `SleepHypnogram`'s `selection`/`cursorMs`; only the debug
    gallery and screenshots use them.

**Duplicate utilities**
- X-Ray and Toolkit each define their own copy of:
  - `monogram` (each with an identical `MonogramTest`)
  - `currentLocale`
  - the check-mark canvas
  - `BlockedReason` / `Problem` / `blockedReason()`

  These belong in `:core:designsystem` or a shared feature-support module (W8).
- Clock and tick formatting is implemented twice:
  - `SleepHypnogram.kt:477-505` builds `DateTimeFormatter.ofPattern` per call and uses the `>= 2 days` threshold.
  - `ScrubChartMath.kt:125-157` uses the cached formatter and the `> 2 days` threshold.
  - A 48-hour window therefore labels midnight ticks differently in the two charts (W9).
- The "may this restore run" admission rule is implemented separately in X-Ray and Toolkit; see B-06.

**Hardening and debt (not defects today)**
- FULL snapshots persist raw sysfs status text while LIGHT snapshots store BatteryManager names
  (`SnapshotCapturer.kt:250-259`). Normalize the value before the first stored-status consumer appears.
- `BgDexoptController.kt:226`: use `if (client === process) client = null` in `finally`.
- `XRayViewModel.kt:225`: wrap `packageForUid` in `attempt` like Sleep Lab does, and catch `SecurityException` in
  `CachedAppLabels.loadPackagesForUid`.
- `TelemetryUserService.destroy()` accepts its own UID as a caller. This has little value and could be restricted to the
  Shizuku server.

**Test-suite integrity**
- **Tautological or near-tautological tests:**
  - `SystemZoneTest.kt:41-46` only checks JDK `ZoneId` equality.
  - `BatteryUsageMapperTest.kt:55` compares the table to a hard-coded copy of itself.
  - `TelemetryModelsTest.kt:11`
  - `ToolkitGatewayTest.kt:235-240` checks that a fake's reply passes through.
  - `StatusTicksTest.kt:62-69` checks configuration only.
- **Tests that lock in the defects above:**
  - `ToolkitActionsObservedChangeTest.kt:136-163` (B-01)
  - `SleepLabCulpritsTest.kt:114` (B-04)
  - `XRayViewModelTest.kt:299-312` (B-08)
  - `HealthViewModelTest.kt:1466-1500` asserts the rejection behind B-15
  - `HealthKernelFallbackTest.kt:38-74` encodes the serial order behind B-14
- **Unreachable fixtures:** `SleepLabCulpritsTest.kt:91-97` and `SleepLabMappersTest.kt:333-347` build `perUid = false`
  together with valid system timers, which production cannot produce.
- **Highest-risk blind spots:**
  - No test reaches the history page cap (B-02).
  - No feature-level test asserts that privileged controls carry `filterTouchesWhenObscured`.
  - The `Framework*` reflection adapters (`FrameworkBatteryStatsCalls`, `FrameworkBatteryHistory`,
    `FrameworkBatteryUsageAdapter`) have no JVM coverage.
  - The `ChargingCaptureJobService` and receiver `goAsync` wiring are untested; only the extracted helpers are covered.
  - There is no Compose pointer test through a scrolling parent (B-11).

---

## 4. Prioritized Remediation Roadmap

**Phase 1: Immediate hotfixes (isolated commits, each with the validator's repro as its regression test)**
1. **B-01:** stop attributing late reconcile read-backs. This is a privileged write over a system-managed value.
2. **B-02:** mark capped history ingests incomplete, and fix `historyAvailable`.
3. **B-03 and B-16:** make per-app rules not evaluable when no app UID is proven, and add the shared `isAppUid`.
4. **B-04:** give Sleep Lab cause-specific not-evaluable and unavailable states.
5. **B-06:** let X-Ray accept a listed restorable Restore.
6. **B-07:** apply the charge-counter plausibility floor in `remainingMs`.
7. **B-05:** carry the level point in at both window edges.

**Phase 2: Structural and concurrency hardening**
- **B-10:** run every Shizuku state refresh on IO.
- **B-12:** track X-Ray write jobs.
- **B-13:** epoch-tagged failure mark in `RefreshCoordinator`.
- **B-14:** start the Health kernel read in parallel with the Room reads.
- **B-15:** order the live buffer on elapsed time.
- **B-11:** ignore a consumed Down in the chart gesture.
- **B-20:** bound the dump writers.
- **B-09 and B-22:** history/usage query efficiency, starting with the SDK gate for API < 34.

**Phase 3: Architectural refactoring and test coverage**
- **App identity:** introduce one resolver for UID → package, label, icon and app-UID classification, and make it the
  single place for:
  - process-name matching and the shared-UID policy (B-08);
  - cache invalidation on package broadcasts (B-18);
  - bounded ownership lookups (B-17);
  - UID classification (B-16).
- **Admission rules:** share one admission rule between X-Ray and Toolkit (B-06, B-21).
- **Validity:** make the "partial data" contract explicit at the consumer boundary. For example, a
  `Completeness`/`NotEvaluable(reason)` that every UI mapper must render, so new consumers cannot silently collapse
  validity (systemic risk 1).
- **Tests:**
  - page-cap and API-gate tests for history;
  - JVM fakes for the `Framework*` reflection adapters;
  - instrumented-style Robolectric tests for the job service and receivers;
  - a feature-level assertion that privileged controls are touch-guarded;
  - replace the tautological tests listed in §3.
- **Cleanup:** move the debug-probe-only IPC clients out of the main source set, and remove the dead code in §3.

---

## 5. Coverage Ledger, Audit Isolation and Freeze

### Tools actually run (coordinator, real repository)
| Check | Command | Result |
|---|---|---|
| Unit tests (all 11 modules), forced rerun | `bash .claude/scripts/gradle-slot.sh testDebugUnitTest --rerun --continue --max-workers=4` | PASS. 1,932 tests freshly executed, 0 failures/errors/skipped (log `.claude/kit/logs/gradle.eh2ZazRA.log`). |
| Unit tests + lint (first pass) | `… testDebugUnitTest lintDebug --continue --max-workers=4` | PASS (up to date from an earlier build of the same HEAD; log `gradle.xhHmaXX1.log`). |
| Lint (all modules) | `lintDebug` reports | Only version-currency warnings: OldTargetApi (targetSdk 36), Gradle 9.8.1, androidx.annotation 1.11.0, Kotlin plugins 2.4.21, hiddenapibypass 6.2, Robolectric 4.17. No lint baselines and no disabled checks. |
| Screenshot validation | `… validateDebugScreenshotTest --continue --max-workers=4` | PASS. 196 tests, 0 failures. `:app` re-executed; the other six modules were up to date on unchanged inputs (log `gradle.TuFpHF3n.log`). |
| Secret scan | `rg` for key/token/private-key patterns over source roots | Nothing found; `local.properties` is gitignored. |
| Source recon | `.claude/skills/full-repo-audit/scripts/repo-recon.sh` | Inventory of 470 files. The filter skipped `ToolkitActionsAuditFailureTest.kt` because of its name; it was explicitly assigned to W2. |
| Device (read-only) | `ANDROID_ADB_SERVER_PORT=5038 ANDROID_SERIAL=localhost:5555 adb shell …` (`getprop`, `dumpsys`, `pm list`, `ps`, `ls /sys`) | redroid15_x86_64, API 35, release 0.2.6 installed. No battery (`power_supply` empty), no cpufreq stats, no second user. No `am`/`input`/install was used. |

Validator reproductions ran only in scratch copies of the source (`GRADLE_SLOTS=1`, `--max-workers=2`). All 11
reproduction tests failed as predicted; their modules' other tests passed. The test sources and logs are kept in the
coordinator scratchpad (V1/V2/V3 `repo/**/ValidatorRepro*Test.kt`, `repo/.claude/kit/logs/`).

### Subsystems examined
| # | Subsystem | Reviewer coverage |
|---|---|---|
| W1 | Shizuku connection, UserService, binder clients, AIDL, hidden-API stubs | All main files read fully; Shizuku 13.1.5 bytecode inspected. |
| W2 | Toolkit write path (shizuku-ipc toolkit, `ToolkitActions` ~2,000 lines, Toolkit VM, audit table) | All main files read fully; most VM test bodies skimmed. |
| W3 | Telemetry engine (capture, sources, diff, calibration, capacity, dump, interval, live, status, protos) | All main files read fully; test bodies largely grepped. |
| W4 | Room DB v1–v7, store, capture receivers/job/scheduler | All main files read fully; migrations diffed against exported JSON by script. |
| W5 | App shell, DI, status FGS, manifests, debug entry points, build/R8/signing | All read fully; the release merged manifest and R8 `seeds`/`mapping` checked. |
| W6 | Diagnostics engine, dictionary, story; Sleep Lab | All main files read fully. |
| W7 | Cockpit and Charge & Health | All main files read fully; en/tr strings compared by script. |
| W8 | X-Ray; Toolkit Compose UI | All main files read fully. |
| W9 | Design system (charts, gestures, touch guard, formatting, theme) | All main files except `theme/Color.kt` (grep only); foundation 1.12.1 / M3 1.4.0 bytecode inspected. |

### Unavailable checks and remaining gaps
- **No CVE database scan.** osv-scanner, trivy, grype, dependency-check, detekt, ktlint and semgrep are not installed.
  Dependency currency comes from lint only.
- **Kotlin compiler warnings** were not collected (the build ran with `-q` and was up to date).
- **No on-device behaviour below API 35:** the API 31–34 gates and argv choices, the history iterator shape (B-09),
  and real standby-bucket decay (B-01) are unverified on a device. AOSP facts behind B-22 and B-23 are unverified (no
  framework sources offline).
- **Not observable on redroid:** live charge-counter units (B-07), fling gestures (B-11) and on-screen copy (B-04).
  Redroid has no battery and no cpufreq, and UI driving was not permitted.
- **Partially read:** screenshot-test sources, most reference PNGs, and many test bodies were only skimmed.
  Test-integrity findings therefore cover the files actually read.
- **Not validated** (minor leads, not counted as findings):
  - `TelemetryUserService.kt:367` passes a negative `cycle_count` through;
  - `SnapshotCapturer.kt:124` loads a full snapshot only for its clock;
  - `MainActivity.onStart` calls `ensureScheduled` (a JobScheduler IPC) on Main;
  - `readConsistently` holds Room's write slot during analysis compute.
- **No exhaustive coverage is claimed.**

### Contamination ledger (recorded before any project summary was read; updated as exposure occurred)
- **Coordinator (this session): exposed.**
  - Before any action, the SessionStart hook injected the generated map index (`.claude/.codebase-info/INDEX.md`). It
    summarizes earlier remediation waves by ID range and topic.
  - Also present: `CLAUDE.md`, the live rules (they mention an earlier audit's worker contamination), auto memory
    (`MEMORY.md`; `redroid-test-device.md`, read for device access), and a git-status snapshot listing five recent
    commit subjects, two of which cite earlier review IDs.
  - A manifest comment citing an earlier review ID was read during recon.
  - Mid-audit context compactions re-ran the SessionStart hook, which injected the map index into the coordinator
    again. The latest injection came after this draft's findings were written and before the freeze. It also named
    earlier remediation topics, for example a charge-counter unit check. No finding, severity or fix was changed
    after that injection; the only edit made afterwards was adding this line.
  - **Not opened before freeze:** `AUDIT_REPORT.md` (archived unread by the guard), `PROGRESS.md`, `docs/`, the other
    map documents, the board, and git history (no `log`/`blame`/`show`).
  - The coordinator wrote neutral briefs (source topology, the generic checklist, the six failure domains). It passed
    no prior IDs or conclusions to any worker. After validation, it spot-checked the source lines it quotes in this
    report.
- **Subsystem reviewers (9 × `audit-reviewer`, fresh contexts, no transcript inheritance):**
  - All received harness-injected `CLAUDE.md`, the `MEMORY.md` index, live-rules text, and the git-status snapshot.
  - **W2, W3, W4, W6:** each worker's own context was compacted mid-run, and a SessionStart hook then injected the map
    index into it. Each states it did not use that material.
  - **W2:** a mistaken unscoped `rg` printed about 8 lines of `AUDIT_REPORT.md`, 3 of `PROGRESS.md` and 6 of
    `docs/plans`. W2 states its finding predates that output.
  - **W5:** an unscoped `rg` printed about 5 lines of `AUDIT_REPORT.md`.
  - **W1, W7, W8, W9:** no prior-assessment material beyond the shared harness injections.
  - Source comments and test names throughout the code cite earlier review IDs (dozens per subsystem). Reviewers
    treated them as claims only.
- **Validators (3 × `audit-reviewer`, fresh contexts):**
  - Each worked from a scratch copy with prior-assessment files removed.
  - None reported prior-assessment exposure, beyond `CLAUDE.md`/live-rules injections and one run of the kit's
    `resource-policy.py` (host figures only).
  - Every finding above was re-derived by a validator. Rejected candidates are listed in §2.
- **Classification:** started as *fresh workers; coordinator exposed*. It is downgraded to **contaminated/not blind
  (partial)**, because four reviewers received the map index mid-run and two saw prior-report fragments. The
  independent findings rest on current-source traces and reproductions by validators with no known prior-assessment
  exposure. No claim of full blindness is made.
- **Process gap observed:** the compaction-time SessionStart hook reaches `audit-reviewer` subagents. Using
  `audit-reviewer` alone does not keep long-running blind workers clean.
- **Freeze:** this document is frozen by `audit-guard.py freeze` as `independent.md` in the guard session directory.
  Its SHA-256 is recorded in `freeze.json` and repeated in the post-freeze delta. Prior reports and history were opened
  only after the freeze.
- **Integrity:** checked by the guard at freeze and at publish (protected skills, kit files and project instructions,
  plus the original report).


## Audit Delta (post-freeze comparison)

*This delta was written after the independent findings above were frozen: guard `freeze` produced `independent.md` with
sha256 `548380d55e4f5ecc0291e1d57ec6cc2bab2d59cf4833318a5689a791ea89e272`. The following were opened only after that
freeze:*
- *the archived previous report (`.git/audit-history/run-7e96ng__/previous-report.md`: the R-series audit of `dbd0347`,
  with its N-series delta and remediation log);*
- *`PROGRESS.md`;*
- *git history (`git log dbd0347..HEAD`, `git log -S`, `git merge-base --is-ancestor`).*

*Nothing below changes the frozen findings. Anything discovered after the freeze is labelled as such.*

### Boundary and inputs
- **Previous report:** a blind audit of `dbd0347` (2026-10-20) with R-01…R-21 (3 MEDIUM, 18 LOW). It was remediated on
  `remediate/audit-2026-10-20` (all 21 marked FIXED) and shipped in 0.2.5. Its remediation log listed 6 open follow-ups,
  and its delta carried 16 follow-ups from the N-series.
- **Source compared:** `dbd0347..HEAD` is 69 commits. 49 of them touch application code:
  - 25 R-fix commits;
  - the 0.2.5 bump `f63b9bb`;
  - 22 board follow-ups (SQ-1…SQ-29);
  - the 0.2.6 bump `da2ee6e`.

  The remaining commits touch only `.claude/`, `CLAUDE.md`, `PROGRESS.md`, `AUDIT_REPORT.md` and docs. This audit
  therefore reviewed the 0.2.6 application source.
- **Commit ancestry:** every fix commit cited by R-01…R-21, and every SQ commit cited below, is an ancestor of HEAD
  (`git merge-base --is-ancestor`, 49 SHAs).
- **Who did what:**
  - A post-freeze mapping agent (an `audit-reviewer` with prior material in its context, so not blind) checked each
    R-fix, follow-up and §3 row against current source.
  - The coordinator traced the origins of the new findings with `git log -S` and spot-checked the mapping where it
    touches the frozen findings (`UnplugSummary.kt`, `SleepHypnogram.kt`).

### Per-finding comparison with R-01…R-21

"Verified resolved within stated checks" is used only when both of the following hold:
- an independent reviewer in this audit positively examined the code path and recorded it as sound (frozen §1/§5);
- its regression test passed in this audit's forced rerun of 1,932 tests.

"Fix present" otherwise rests on source and test presence only. Absence from the frozen report is never treated as
proof.

| Prior | Classification | Current evidence / relation to the frozen findings |
|---|---|---|
| R-01 UIDs absent from a complete reference are invalid | fix present in source; verification limited. **Adjacent to new B-03.** | `SnapshotDiffer.kt:227-243` (`refMembershipComplete`, `provenZeroBaseline`); tests `SnapshotDifferTest.kt:69,110-129`. Its systemic risk ("`skippedInvalidUids` never surfaced") was addressed in X-Ray only (SQ-21 `8c9b082`) and in the Sleep Lab culprit note. B-03 shows the remaining case: when *no* app UID is valid, the rules still count as evaluated, the story says "No anomalies detected", and the Sleep Lab note blames a counter reset. |
| R-02 force-stop alarm reset invalidates the UID | fix present in source; verification limited | `SnapshotDiffer.kt:314-319`; tests `SnapshotDifferTest.kt:684-729`. |
| R-03 night interval never evaluable | fix present in source; verification limited. **Adjacent to new B-04.** | Gate slack `DiagnosticRuleEngine.kt:82-83`; bind-on-demand FULL screen captures `CapturePolicy.kt:37-51`; `NightIntervalAnalysisTest.kt:69`. The LIGHT fallback still happens when the 6 s bind deadline is missed. In that case SQ-17's new awake card (`b2549c7`) maps the resulting `NO_SCREEN_OFF_TIME` to "less than 30 min screen-off" (B-04). |
| R-04 toolkit outcome lost across Hidden | fix present in source; verification limited (different mechanism: `stillShown` target match). **Same theme as new B-12.** | `ToolkitViewModel.kt:660,672`; test `ToolkitViewModelTest.kt:259`. X-Ray never had the equivalent ownership of in-flight writes: its write jobs are untracked, and a reopened sheet re-enables controls (B-12). |
| R-05 post-dispatch audit failure shown as failed | fix present in source; verification limited. **Same "audit-trail honesty" contract as new B-01.** | `ToolkitActions.kt:727,747-761`; `ToolkitActionsAuditFailureTest.kt`. B-01 is a different root: a late reconcile attributes later drift to an unconfirmed write. |
| R-06 FGS start/stop race crash | verified resolved within stated checks (JVM only) | `StatusStartDecision.kt:35,73-75`; `StatusStartDecisionTest.kt:69-109`. FGS start/stop was examined as sound by this audit's app-shell reviewer. No instrumented or device check. |
| R-07 undrained command output reported complete | verified resolved within stated checks | `CommandRunner.kt:160,184` (`reachedEof`); `ProcessCommandRunnerTest.kt:182,195`. Process drain/timeout was examined as sound (frozen §1). |
| R-08 bind/unbind on Main under `bindingLock` | bind/unbind part verified resolved within stated checks. **Residual independently rediscovered as B-10.** | Drainer `ShizukuManagerImpl.kt:602,612-614` was examined as sound by the IPC reviewer; `ShizukuManagerImplTest.kt:1401-1486`. The prior remediation's open follow-up ("binder calls still made on Main outside bind/unbind (permission and ping)") is exactly B-10: `start()`, the Shizuku listeners and `requestPermission()` still run `updateState()` on Main. It was found without seeing that note. |
| R-09 mAh/h over partial history time | fix present in source; verification limited | `CockpitMappers.kt:151,153`; `CockpitMappersTest.kt:135-150`. B-07 is a different root (time-to-empty units) in the same mapper. |
| R-10 tapjacking / overlay hiding | verified resolved within stated checks (no overlay app exercised) | `MainActivity.kt:57`, `AndroidManifest.xml:9`; the frozen security review recorded `HIDE_OVERLAY_WINDOWS` and the guard on every privileged control. |
| R-11 handle drag at window bound | fix present in source; verification limited. **Adjacent to new B-11.** | `ScrubGestureReducer.kt:288-294`; `ScrubGestureReducerTest.kt:244-268`. B-11 is another gesture-layer root: a consumed Down (fling-stop tap) clears the slice. |
| R-12 marker job untracked | fix present in source; verification limited. **Adjacent to new B-19.** | `CockpitViewModel.kt:83,299,328`; tests `:900,929`. B-19 is the remaining marker-sheet defect: one `failed` flag serves both capture and list-read failures. |
| R-13 stale Empty/Failed for a new selection | fix present in source; verification limited | `AnalysisSession.kt:35,41,49-55`; `AnalysisSessionTest.kt:146-170`. |
| R-14 status notification misses a tick | fix present in source; verification limited | `StatusTicks.kt:37`; `StatusTicksTest.kt:74`. The frozen §3 flags a *different* test in that file (`StatusTicksTest.kt:62-69`) as configuration-only. |
| R-15 every analysis loads the whole history | fix present in source; verification limited | `IntervalAnalyzer.kt:44,67`, `IntervalHistory.kt:21-25`, index `history_event(kind, wallMs)`; `IntervalHistoryEquivalenceTest`. Room migrations 1→7 were examined as sound. B-02 and B-09 sit in the history *ingest* path, not this read path. |
| R-16 touch-guard wiring untested | fix present (shared guard tested with real MotionEvents). **Residual independently rediscovered.** | `ObscuredTouchGuard.kt:118`; `ObscuredTouchGuardUiTest.kt:74-90`. The frozen §3 blind spot still holds: no feature-level test asserts that each privileged control applies the guard. |
| R-17 R8 gate / string class name | class-literal part verified within stated checks; release-gate part partial | `ShizukuManagerImpl.kt:553` `TelemetryUserService::class.java.name`. The R8 keep rules, seeds and mapping were checked by the app-shell reviewer. `minifyReleaseWithR8` is run by the `release` skill and recorded in PROGRESS baselines, not by a build-logic task. This audit did not run `assembleRelease`. |
| R-18 chain predicate vs prune SQL | fix present in source; verification limited | `Daos.kt:399-438`; `ToolkitRetentionParityTest.kt:121,128`. |
| R-19 wall-clock deadline tests | fix present in source; verification limited | `AndroidTelemetrySourceDeadlineTest.kt:82-83,539-574` (virtual time). No flake in the forced rerun. |
| R-20 diagnostics failure-path tests | fix present in source; verification limited | `AndroidSensorCatalogTest.kt:92-111`. The frozen §3 names a different gap: `Framework*` reflection adapters have no JVM coverage. |
| R-21 unfailable charge-runner assertion | fix present in source; verification limited | `ChargingCaptureRunnerTest.kt:11-34`. |

**Summary for R-01…R-21:**
- 0 still reproducible as originally reported.
- 5 verified resolved within stated checks: R-06, R-07, R-10, the bind/unbind part of R-08, and the class-literal part
  of R-17.
- 2 independently rediscovered as residuals:
  - R-08 → B-10 (the prior remediation's own open follow-up);
  - R-16 → the frozen feature-level touch-guard blind spot.
- 15 fix present in source with verification limited. Of these, R-01→B-03, R-03→B-04, R-04→B-12, R-05→B-01, R-11→B-11
  and R-12→B-19 sit next to new findings with different roots.
- 0 not assessed.

### Prior remediation's open follow-ups (6)
| # | Status now | Relation to frozen findings |
|---|---|---|
| 1 Show `skippedInvalidUids` in X-Ray | fixed by SQ-21 `8c9b082` (`XRayViewModel.kt:192`, `XRayScreen.kt:388-392`) | B-03 is the unsurfaced remainder: the story and rules, and Sleep Lab's empty tabs. |
| 2 Battery-node fallback picks a `scope=Device` peripheral | fixed by SQ-19 `c2e2439` (`TelemetryUserService.kt:321`) | — |
| 3 §3 dead-code cleanup | **still open**; no cleanup commit since `dbd0347` | Independently rediscovered in the frozen §3 (debug-only IPC clients and stubs, `DozeRaw.AOSP_KEYS`, `dynamicAccent`/`withAccentFrom`, test-only DAO members). See the dead-code list below. |
| 4 `ToolkitExecutor` value reads ignore `complete` | fixed by SQ-22 `96b1e45` (`ToolkitExecutor.kt:378,389`) | Post-freeze note: `dozeEffective` (`:412`) still checks `dump.ok` only. A cut dump can only read as "not effective", so it fails safe. |
| 5 Analysis reads not in one transaction | fixed by SQ-23 `15010d3` (`RoomSnapshotStore.kt:63` `database.withTransaction`) | The frozen report's unvalidated lead "`readConsistently` holds Room's write slot during analysis compute" originates in this fix. Whether Room's `withTransaction` takes the writer connection here was not verified. |
| 6 R-08 residual: Main-thread permission/ping IPC; Shizuku stub cache | **still open** (stub cache is upstream) | **Independently rediscovered as B-10.** |

### N-series follow-ups 1–16 (carried by the previous delta)

Fixed by SQ commits:
- 1: SQ-1 `4136f2d`
- 2: SQ-25 `83317a2`
- 3: SQ-3 `de98768`
- 4: SQ-4 `e939aae`
- 5: SQ-5 `e5d6488`
- 6: SQ-6 `81618b5` and SQ-8 `7dd6190` (Room v7 watermark)
- 9: SQ-12 `096006b` (source only; the APK was not inspected)
- 11: SQ-14 `6f7d5b6`
- 12: SQ-15 `b5104b2`
- 15: SQ-19 `c2e2439`
- 16: SQ-20 `8a24ea1`

Partly open:
- **7:** Sui wording fixed (SQ-10/26/29); the shared-stub kill question is unaddressed.
- **8:** bg-dexopt: 2 of 3 fixed by SQ-11 `724bdef`.
- **10:** chart nits: 4 of 5 targeted by SQ-13 `c6213af` / SQ-14. **The "one date rule" item is only partly done, which
  the frozen §3 rediscovered independently.**
  - The hypnogram's accessibility labels use the shared `showsDates`.
  - Its tick rule still uses `window.durationMs >= TWO_DAYS_MS` (`SleepHypnogram.kt:495`), while the scrub chart uses
    `durationMs > 2 * DAY_MS` (`ScrubChartMath.kt:125`).
- **13:** fixed for the two named fakes by SQ-16 `dceb84d`; `AnalysisFixtures.kt:73` still ignores `statsStartMs`.
- **14:** culprit card built by SQ-17 `b2549c7`; the gap banner is still unbuilt (`ResolvedReference.gapMs` has no
  production reader).

SQ-17 is also the origin of B-04's awake-card mapping, and of the unreachable `needsFullSnapshots` branch listed in
the frozen §3.

### Origins of the new findings (post-freeze `git log -S`)
| Finding | Introduced by | Note |
|---|---|---|
| B-01 late reconcile attribution | `ac4a3f0` (SQ-75, original reconcile) combined with `cb9e096` (**F-01/F-13 fix**: chain membership from observed before/after) | The **N-07 fix** `0c0b5f1` finalized *unsent* writes with `afterRaw = null` but left the late-reconcile path attributing drift. |
| B-02 history page cap recorded complete | `98f9a5e` (original capture pipeline); `historyAvailable` from `0919a2d` | Predates all four audits; never reported before. |
| B-03 all-invalid apps → "No anomalies" | All-clear condition from `5cc15f7` (DIAG-1 fix); per-app filter from `03b5802` (SQ-43) | The skipped count was surfaced for X-Ray only (SQ-21). |
| B-04 Sleep Lab wrong-cause copy | Awake card `b2549c7` (**SQ-17, landed after the previous audit**); CPU/kernel copy from `418ad69` (SQ-83) | — |
| B-05 timeline level has no carry-in point | `d93bb42` (SQ-73); touched by `81618b5` (SQ-6) | — |
| B-06 X-Ray Restore no-op | X-Ray guard from `c426b0d` (SQ-82) | Lift-only undo on protected apps came from the **N-06 fix** and was widened by SQ-14 `6f7d5b6`. Only the Toolkit screen's admission was updated. |
| B-07 charge-counter units | `acec85a` (SQ-81) | SQ-5 `e5d6488` added the `ChargeCounterCheck` floor for the live current but did not apply it to time-to-empty. |
| B-08 shared-UID first owner | **F-09 fix** `627b424` (`owners.minOrNull()`) | Regression introduced by an earlier audit fix. |
| B-13 backoff race | `1c09bce` (SQ-102); touched by the **F-04 fix** `e89d42b` | — |
| B-15 live curve freezes on clock step-back | **N-18 fix** `7574ad2` (`point.wallMs <= newestMs`) | Regression introduced by an earlier audit fix. |
| B-16 raw-UID app check | `03b5802` (SQ-43) | — |
| B-11, B-22, B-23 | `5b00be5` (SQ-55), `98f9a5e`, `c426b0d`/`b3f4923` | All original code. |

### Pattern worth noting for remediation
The previous delta found that two of three MEDIUM findings traced back to earlier audit fixes. The same pattern holds
here:
- **Regressions introduced by earlier fixes:** B-08 (F-09), B-15 (N-18), and B-01, where F-01/F-13 meets the original
  reconcile and N-07 covered only the unsent case.
- **Fixes applied at one consumer of a shared contract:**
  - B-06: the protected-app undo was admitted in Toolkit, not X-Ray.
  - B-03: the skipped-UID count went to X-Ray, not the story or the rules.
  - B-07: the charge-counter floor went into calibration, not time-to-empty.
- **New feature mapping one reason to one cause:** B-04 (SQ-17).

Each change passed its own tests, because those tests assert the new invariant at the consumer that was changed. For
`/audit-remediate`:
1. With each fix, `rg` every consumer of the changed contract and add a test at each:
   - both ViewModels' admission rules;
   - all `skippedInvalidUids`/`valid` consumers;
   - all `NotEvaluable` reason → copy mappers;
   - all charge-counter readers.
2. Re-read the earlier fix's intent before changing its code. F-09: never act on a process name. N-18: don't duplicate
   points storage already returned. N-07: an unsent write is not ours.

### Dead code: comparison with the prior §3
- **Consistent, independently rediscovered:**
  - debug-only IPC clients, `@Parcelize` models and stubs in `main`;
  - `DozeRaw.AOSP_KEYS`;
  - `TelemetryDao.hourly` / `SnapshotDao.byId`;
  - `InspirationTheme(dynamicAccent)` / `withAccentFrom`;
  - `Explanation.category`;
  - the stored status-text normalization.
- **Prior rows still present but not in the frozen report (delta-only):**
  - write-only `capture_miss` table;
  - `WearUi.NotEnough.sampleCount`;
  - unused `BarRole` entries;
  - `testImplementation(libs.room.testing)` with no `MigrationTestHelper`;
  - `:app`'s redundant `lifecycle-runtime-compose` / `lifecycle-viewmodel-compose`;
  - the unused `withTimeoutOrNull` import (`ScrubGestures.kt:18`, not re-verified);
  - `ModemDrain.signalBinPct/scanningMs/radioActiveMs`, read only by the probe.
- **No longer dead:**
  - the duplicated touch guard (consolidated by R-16);
  - `Culprit`, `CulpritScope` and `Explanation.action` (read by SQ-17's card);
  - `skippedInvalidUids` (read by X-Ray and Sleep Lab);
  - `kotlinx-collections-immutable` in `:app` (debug gallery);
  - `BatteryState.status`/`currentMa` (read by `UnplugSummary` from the in-memory light snapshot).
- **New in the frozen §3 only:**
  - unreferenced AIDL parcelable declarations;
  - `ShizukuManager.stop()`;
  - `unplugWindowSnapshots`;
  - the non-atomic `insertHistory`/`setHistoryCursor`;
  - the `RefreshCoordinator`/`AppContainer`/Health test hooks;
  - two unused string resources;
  - `KernelCapacitySnapshot` fields;
  - the X-Ray/Toolkit duplicate helpers.

### Scorecard and baseline comparison
| Dimension | Previous (R-series, `dbd0347`) | This audit (`c74d8f8`) |
|---|---|---|
| Architecture | 8 | 7 |
| Correctness | 7 | 6 |
| Concurrency | 8 | 8 |
| Security | 8 | 8 |
| Performance | 8 | 7 |

- The scores are qualitative and come from different runs.
- The lower Correctness and Architecture scores reflect:
  - 7 MEDIUM findings instead of 3;
  - a recurring "collapse incompleteness into confidence" pattern across four consumers;
  - three regressions or partial propagations of earlier fixes.
- No CRITICAL or HIGH finding was made in either audit.
- **Baseline match:** PROGRESS records the 0.2.6 gate at `da2ee6e` as 1,932 unit tests / 0 failing, 196 screenshots
  and 7 lint warnings (version notices). This audit's forced rerun at `c74d8f8` reproduces 1,932 / 0 and 196 / 0, and
  its lint output shows only version-currency warnings. `c74d8f8` differs from `da2ee6e` only in kit/docs files.
- `assembleRelease` was not re-run by this audit.

### Post-freeze observations (not frozen findings)
- **`UnplugSummary.kt:374,375,393` `clock!!`** (added by SQ-25 `83317a2`): traced and safe as built.
  - Captures always carry a clock.
  - `addSuspended` returns on a cross-boot span before reading history points.
  - Points between two same-boot captures always get a clock from `historyClock`'s first branch.

  The invariant is implicit. A `?: return` would make it fail-safe. Not a defect.
- **Board/docs gap:** PROGRESS "Audit status" still lists the 2026-10-19 audit as the last full-repo audit. It does not
  record the R-series audit (0.2.5) or this one. Audit mode does not edit it; `/wrap-up` or the remediation should.
- **Process gap (repeated):** the compaction-time SessionStart hook injected the map index into four `audit-reviewer`
  workers and, twice, into the coordinator during this run. The previous audit recorded the same mechanism for one
  worker. This is a kit-level isolation gap: `audit-reviewer` alone does not keep long-running blind workers clean.
