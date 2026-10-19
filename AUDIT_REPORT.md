# Inspiration — Independent Whole-Repository Audit (blind phase)

- Repository: `/home/akane/projects/inspiration`, app id `com.akane.inspiration`
- Source revision: `5c0b83d375dbf4360e8035a7cdc0c0de20d23326` (application source == HEAD)
- Audit date: 2026-10-19
- Guard session: `.git/audit-history/run-ax4ji1gb`
- Mode: standalone review mode (Sidequest off), read-only. No application, skill or kit file was changed.

## 1. Executive Summary & System Health Scorecard

The codebase is well structured. Its module graph is clean, its privileged surface is small and typed, and its test suite is large and green: 1509 unit tests, 0 failures. The serious defects sit where two independent time sources meet, and where privileged commands assume one platform version.

The audit confirmed 28 findings: **4 HIGH, 6 MEDIUM and 18 LOW**. There are no CRITICAL findings. Nothing confirmed allows privilege escalation, crashes the app or corrupts the database schema or rows. The HIGH findings make the app's headline numbers wrong after routine daily events, or make a system-job control silently ineffective on Android 12/13.

| Area | Score /10 | Justification |
|---|---|---|
| Architecture & Modularity | **7** | The layering and module boundaries are clean and privileged reads are isolated in a shell-UID user service. But battery-history time and capture time are joined ad hoc in several consumers (N-01, N-02, N-13), and UI state can drift from the device or the database (N-04, N-06). |
| Correctness & Resilience | **5** | Four HIGH defects sit on everyday paths (full-charge unplug, screen off/on, app open, Android 12/13), and three were reproduced on redroid (N-01, N-02, N-04). Several "unknown" outcomes are shown as definite ones (N-06, N-07, N-15). |
| Concurrency & Resource Safety | **7** | No data race, leak or deadlock with production impact was confirmed. What remains is a narrow death-versus-bind window (N-11), Activity-lifetime analysis subscriptions (N-09) and shaping on the main thread every 3 s (N-18). |
| Security & Privilege Hygiene | **8** | Privileged commands are fixed argv with no shell interpolation, binder callers are UID-checked, backups exclude app data and no secrets are tracked. The hidden-API exemption is process-wide (N-26), and partial signing config breaks every build (N-25). |
| Performance & Efficiency | **7** | No algorithmic hot spot or polling drain was found. The issues are recomposition churn from an unstable `ZoneId` (N-19), main-thread reshaping on live ticks (N-18) and an unshrunk release APK (N-27). |

Scores are qualitative judgments based on this audit's coverage (see the Coverage ledger), not measurements.

**Top 3 systemic risks**
1. **Battery history and app captures are combined without a shared time-anchor model.**
   - A RESET record carries live screen, doze and plug state, but consumers treat it as a blank boundary (N-01).
   - Capture anchors are compared with history timestamps exactly, although the two systematically differ by milliseconds to seconds (N-02).
   - Stats sessions are grouped by an exact start time that the platform shifts (N-13).

   The result is a wrong screen-on/off split, a degenerate "since screen off" window and a dropped "last charge", after events that happen every day.
2. **Privileged paths encode one platform version and one Shizuku flavour.** The bg-dexopt cancel argv is only valid on API 34+, so on 31/32 the app reports a cancel that never happened (N-03). Sui-provided Shizuku is reported as not installed (N-08). The live-current scaling assumes one BatteryManager unit and sampling moment (N-05). None of these were testable on the only device available, API 35 redroid.
3. **Ambiguous outcomes are collapsed into confident UI states.**
   - Unsent, unreadable or failed-read-back toolkit writes become "pending", "no active changes" or "FAILED" (N-07, N-06, N-15).
   - Stale data is shown as current (N-04, N-09, N-17).
   - "Plugged" is shown as "Charging" (N-10).

   The user cannot tell what the device's real state is, and Reset all can act on phantom state.

## 2. Confirmed Findings

Every finding was produced by a fresh subsystem worker and then re-traced by a separate fresh validator. **Device-confirmed** means it was reproduced on redroid (Android 15). **Source-confirmed** means it was traced through code, library bytecode or AOSP framework source, but not run on a device. Locations are relative to the repository root.

### HIGH

- **[N-01] [HIGH] History consumers treat a battery-stats RESET as a state-less boundary, although at an unplug reset it carries the live screen and doze state and closes the charge. After every routine full-charge unplug, the first screen stretch is lost or mis-booked and Health's "last charge" is dropped**
  - **Category:** Logic Bug (one root cause, the RESET record semantics, with symptoms in the IPC walker and in three consumer modules)
  - **Severity rationale:** validator V2 rated the two symptoms MEDIUM each. Both fire on the same routine trigger: AOSP resets stats on unplug at ≥90% or full by default (`config_battery_stats.xml:26,28`). The default "Since unplugged" view and the Health charge card are wrong every morning, so the combined severity is HIGH.
  - **Location:**
    - Root cause: `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/BatteryHistory.kt:114-128` (`advance`), `:130-152` (`transitions`/`setBaseline`), `:187-196` (`event`).
    - Consumers:
      - `core/diagnostics/.../analysis/TimelineBuilder.kt:201-205` (with the no-op `close` at `:158-161`)
      - `core/telemetry-engine/.../interval/IntervalMetrics.kt:117-123, 132-155`
      - `core/diagnostics/.../analysis/{IntervalAnalyzer.kt:51-55, BatteryStoryBuilder.kt:29-30}`
      - `feature/dashboard/.../CockpitMappers.kt:119-121, 187-204`
      - `feature/sleeplab/.../SleepLabMappers.kt:80-89`
      - `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthMappers.kt:128-162` (`lastChargeSpan`; the RESET branch is at `:135-138`), consumed at `HealthViewModel.kt:220` and `HealthMappers.kt:174-186`
      - `app/.../di/TimelineEvents.kt:33-37`
    - Correct handling of the same record already exists in `core/telemetry-engine/.../capacity/CapacityEstimator.kt:84-86` ("The reset record already carries the unplug").
    - Tests that lock in the current behaviour: `BatteryHistoryTest.kt:120-122`, `IntervalMetricsCalculatorTest.kt:101-119`.
  - **Confidence:**
    - **Device-confirmed** for the dropped screen state on redroid (Android 15): `evidence/redroid-history-before.txt`, `redroid-release-db-history.txt`, `redroid-w7-1-metrics.txt`.
    - The record semantics are confirmed against AOSP `android15-release` (V2): non-UPDATE records are written whole, including `states`/`states2`. At an unplug reset, `plugType = 0` is set before RESET is written from `mHistoryCur`, which still carries the screen and plugged flags.
    - The Health charge-span symptom is confirmed by code trace (V2, re-read by the coordinator) but was not reproduced on a device.
    - **Refuted part:** the worker's claim that START gets the pre-shutdown wall time is true in code but invisible. AOSP writes START before BatteryService exists, with `states = 0, level = 0, plugType = 0`, so no consumer opens a span or a lane from it.
  - **Root Cause & Execution Trace:**
    1. `advance()` maps `CMD_RESET -> listOf(HistoryKind.RESET).also { setBaseline(record) }`, and START the same way. `setBaseline` stores `screenOn`/`idleMode` from `record.states`, but `event()` copies only level, charge, temperature, voltage and `plugType`.
    2. `transitions()` emits `SCREEN_ON`/`SCREEN_OFF` only *relative to* that baseline. A stream `RESET(+screen) … UPDATE(-screen)` therefore yields `RESET, SCREEN_OFF` and never a `SCREEN_ON`. The same happens after `CMD_OVERFLOW`'s `clearBaseline()`.
    3. `TimelineBuilder` comments "The fresh baseline carries plug state, but no screen or idle state". It closes every lane at RESET/START and reopens only plug, so the screen-on stretch after a reset has no segment, and the hypnogram treats it as screen-off.
    4. `IntervalMetricsCalculator` sets `screenOn = null` only for START. On RESET it keeps the last *stored* pre-reset state, which is stale because batterystats discards pre-reset records that were never ingested. Time and level drops are then charged to the wrong state, or to unknown.
    5. **Charge span:** `lastChargeSpan` handles `HistoryKind.RESET, HistoryKind.START -> { open = event.wallMs.takeIf { event.plugType != 0 } … }`. That discards an open PLUGGED span without recording it. At an unplug reset the RESET has `plugType 0`, so the stream `PLUGGED → RESET(plugType 0) → UNPLUGGED` yields no span. By then `baselineKnown` is true, so the UNPLUGGED branch cannot fall back either. Once the phone is unplugged (`hasLive` false), `effectiveChargeSpan` returns that history span unchanged.
  - **Device evidence:**
    - The Android 15 history begins `RESET:TIME … +running +wake_lock +screen +plugged`.
    - The installed release 0.2.3 database, 13 h after that reset with the screen on throughout, held a single `history_event` row: the RESET.
    - On a fresh debug install, `PROBE_INSIGHTS last_24h` over a window where the screen was on until 11:57:00 reported `screenOnMs 6048, unknownMs 34878`. The 34 878 ms is exactly window start → history `SCREEN_OFF`, so the screen-on stretch was counted as unknown.
  - **Failure / Exploit Scenario:**
    - **Overnight charge:** the battery charges past the reset threshold, and the user unplugs at 07:05 with the screen on. Batterystats resets, so the RESET record says `+screen`.
      - The default "Since unplugged" view shows no screen-on lane until the first screen-off.
      - The metrics book that stretch as screen-off: the last stored pre-reset state was the charge job's screen-off. The story then misgrades screen-off drain, and the screen-on rate disappears below 15 min.
      - The Cockpit tile, which uses batterystats' `screenOnMs`, disagrees with the chart.
    - **Same unplug, Health tab:** the overnight charge (PLUGGED 23:10 → RESET at 07:05) is dropped. The "last charge" span, the charge curve and the wear analysis fall back to the previous charge, or to nothing. Only a plug or unplug observed live while Health was open bypasses this.
  - **Concrete Fix:** emit the RESET baseline as explicit same-instant events, and close an open charge at a RESET that carries the unplug:
    ```kotlin
    // BatteryHistory.kt — HistoryWalker.advance (RESET only: AOSP writes START from a zeroed mHistoryCur)
    HistoryBits.CMD_RESET -> buildList {
        add(HistoryKind.RESET)
        add(if (record.states and HistoryBits.STATE_SCREEN_ON_FLAG != 0) HistoryKind.SCREEN_ON else HistoryKind.SCREEN_OFF)
        when (record.idleMode()) {
            HistoryBits.DEVICE_IDLE_MODE_LIGHT -> add(HistoryKind.LIGHT_IDLE_ON)
            HistoryBits.DEVICE_IDLE_MODE_DEEP -> add(HistoryKind.DEEP_IDLE_ON)
        }
    }.also { setBaseline(record) }

    // HealthMappers.lastChargeSpan
    HistoryKind.RESET -> {
        val start = open
        if (start != null && event.plugType == 0 && event.wallMs > start) last = ChargeSpan(start, event.wallMs)
        open = event.wallMs.takeIf { event.plugType != 0 }
        baselineKnown = true
    }
    ```
    - The `(historyTimeMs, kind, wallMs)` unique index accepts the extra kinds, and ties keep input order, so `TimelineBuilder` reopens the screen lane immediately after its RESET close. Correct the comment "carries … no screen or idle state".
    - For rows already stored, treat RESET like START in `IntervalMetrics` (`screenOn = null`), so the stretch reads unknown instead of wrong.
    - After `CMD_OVERFLOW`, emit the first record's state the same way instead of silently re-baselining.
    - Replace the tests that lock in the current behaviour (`BatteryHistoryTest.kt:120-122`, `IntervalMetricsCalculatorTest.kt:101-119`) with "RESET with screen on" cases. Add `PLUGGED → RESET(plugType 0) → UNPLUGGED` to the `lastChargeSpan` tests.
    - Device check: wake the screen, run `dumpsys batterystats --reset`, open the app, turn the screen off, then open the app again. `history_event` should show RESET, SCREEN_ON, then SCREEN_OFF.

- **[N-02] [HIGH] Reference windows built from battery history are never reconciled with capture anchors. "Since screen off" comes out null or degenerate in practice, and stale history beats a newer unplug capture**
  - **Category:** Logic Bug
  - **Location:**
    - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/ReferenceResolver.kt`: `:57-65` (`resolve`: `val end = store.lastAtOrBefore(boundary.toWallMs, null)` at `:60`, `ref` at `:64`), `:96-113` (`boundaryFor`), `:135-157` (`closestReference`/`precedes`), `:159-185` (`completedScreenOff` and its snapshot fallback).
    - Related: `app/src/main/kotlin/com/akane/inspiration/capture/RuntimeCaptureReceiver.kt:12-28`; `core/telemetry-engine/.../source/AndroidTelemetrySource.kt:104-114`; `core/database/.../Daos.kt:95-119`; `core/diagnostics/.../analysis/{IntervalAnalyzer.kt:42, AnalysisSelection.kt:10, AnalysisSession.kt:79-85}`; `app/.../capture/CapturePolicy.kt:81-92`.
    - Tests that place captures exactly on history timestamps: `SnapshotDifferTest.kt:886-941`.
  - **Confidence:**
    - The screen-off half is **device-confirmed** on redroid (`evidence/redroid-w4-1-db.txt`). AOSP `Notifier.java` posts the wake broadcast only once wake-up has begun (V2), which explains the capture lag.
    - The stale-history half is confirmed by call trace only (W4, V2). V2 rates that variant LOW on its own, because it needs a failed or skipped ingest after an unplug: Shizuku down after a reboot, or within the 60 s debounce.
  - **Root Cause & Execution Trace:**
    1. `SinceScreenOff` resolves `completedScreenOff(events)` to `Boundary(historyOff.wallMs, historyOn.wallMs)`. These are framework history timestamps.
    2. The only snapshots normally captured during a screen-off period are the LIGHT `SCREEN_OFF` and `SCREEN_ON` broadcast captures. `light()` reads the wall clock *after* broadcast delivery and a `registerReceiver` binder call, so the `SCREEN_ON` capture lands at `t1 + δ`.
    3. `store.lastAtOrBefore(boundary.toWallMs, null)` therefore skips the `SCREEN_ON` capture and returns an earlier row as `end`.
    4. `closestReference(off, end)` accepts only rows that `precedes(end)`. With the `SCREEN_OFF` capture as `end`, nothing qualifies, so the result is `null` and the UI shows `AnalysisState.Empty`. If some FULL row (a charge job) happened to fall inside, the window collapses onto it.
    5. The snapshot-only pair (`completedScreenOffFromSnapshots`) would work, but it is used only when history has no completed pair.
    6. `SinceUnplugged` and `ScreenOnSession` take the last history `UNPLUGGED`/`SCREEN_ON` and consult the `POWER_DISCONNECTED`/`SCREEN_ON` capture only when history has none. History is ingested only by APP_OPEN, MARKER or CHARGE_JOB FULL reads, and only while Shizuku works. `POWER_DISCONNECTED` always stores a row (LIGHT when the service is cold). So history can be older than the newest capture.
  - **Device evidence (redroid, debug build of `5c0b83d`):**
    - The screen was off from 11:57:00 to 11:57:39. Stored history: `SCREEN_OFF wall …020028` and `SCREEN_ON wall …059478`.
    - Captures: `LIGHT SCREEN_OFF …020054`, `FULL CHARGE_JOB …020064`, `LIGHT SCREEN_ON …059479` (1 ms after the history time) and `FULL APP_OPEN …059481`.
    - `PROBE_INSIGHTS reference_kind=since_screen_off` resolved `fromWallMs …020054 → toWallMs …020064`, which is **10 ms**. The real period was ≈39.4 s.
    - `PROBE_DIFF` reported `durationMs 10, batteryStatsValid false, problems ["battery_stats_session"]`.
    - Without the coincidental charge-job FULL, `end` is the `SCREEN_OFF` capture and the result is null.
  - **Failure / Exploit Scenario:**
    - With Shizuku running, every on-battery "Since screen off" selection after a normal screen-off/on cycle shows "Nothing to analyse yet" or a meaningless sliver. That is the primary sleep-drain question the app exists to answer.
    - In the stale-history variant: Shizuku stops after a reboot following an ingest on Day 1. The user charges overnight and unplugs on Day 2, which stores a LIGHT `POWER_DISCONNECTED` row. The app-open refresh then fails. The default "Since unplugged" window starts at Day 1 and spans the overnight charge, while the status notification (`UnplugSummaryCalculator`, which is snapshot-based) anchors at Day 2 and disagrees.
  - **Concrete Fix:** anchor every history boundary to the matching capture when one is close, and prefer the newer of history and capture:
    ```kotlin
    ReferenceKind.SinceScreenOff -> {
        val history = completedScreenOff(events)
        val captures = completedScreenOffFromSnapshots(nowWallMs)
        when {
            history == null -> captures
            captures != null && captures.toWallMs in history.toWallMs..(history.toWallMs + CAPTURE_SLACK_MS) ->
                Boundary(history.fromWallMs, captures.toWallMs)          // same SCREEN_ON, capture lands after it
            captures != null && captures.toWallMs > history.toWallMs -> captures   // history is stale
            else -> history
        }
    }
    ReferenceKind.SinceUnplugged -> {
        val history = events.lastOrNull { it.kind == HistoryKind.UNPLUGGED }?.wallMs
        val capture = store.latestClockByTrigger(CaptureTrigger.POWER_DISCONNECTED)?.wallMs?.takeIf { it <= nowWallMs }
        listOfNotNull(history, capture).maxOrNull()?.let { Boundary(it, nowWallMs) }
    }
    // ScreenOnSession: same max() rule with the SCREEN_ON trigger.
    ```
    Set `CAPTURE_SLACK_MS` to about the 10 s broadcast guard. Add resolver tests in which every capture is offset by +1 ms to +2 s from its history event, plus a stale-history test with an older `UNPLUGGED` and a newer `POWER_DISCONNECTED`.

- **[N-03] [HIGH] On Android 12, 12L and 13, bg-dexopt "cancel" does not cancel the system job. The unplug guard, the 60-minute cap, user Cancel and the destroy hook all fail, and on API 31/32 the app reports "cancelled"**
  - **Category:** Logic Bug (framework-version mismatch). The power-safety guard fails silently.
  - **Location:**
    - Argv definitions: `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/BgDexoptController.kt:10-11`.
    - Where the cancel is issued: destroy hook `:110-118`, unplug/cap guard `:165-181`, `ToolkitExecutor.kt:240-253` (user cancel), `TelemetryUserService.kt:152-164`.
    - Client side: `core/telemetry-engine/.../toolkit/ToolkitActions.kt:379-428, 1452-1459` and `feature/toolkit/.../ToolkitViewModel.kt:702-723`.
    - Tests that pin the argv: `ToolkitExecutorTest.kt:963-969`, `BgDexoptControllerTest.kt:50-66`.
  - **Confidence:** Confirmed against AOSP `android12-release`, `android12L-release` (= tag `android-12.1.0_r1`), `android13-release` and `android13-qpr3-release` sources (validator V1). Not run on an API 31–33 device.
  - **Root Cause & Execution Trace:**
    1. There is only one cancel argv: `internal val BG_DEXOPT_CANCEL_ARGV = listOf(CMD, "package", "bg-dexopt-job", "--cancel")`. Nothing on the start or cancel path checks `SDK_INT`. The only main-source `SDK_INT` uses are in BatteryStatsClient, PowerStatsClient, MainActivity and StatusNotificationService, and minSdk is 31.
    2. **API 31/32:**
       - `PackageManagerShellCommand.runDexoptJob()` collects every following argument with `getNextArg()` as a package name. So `--cancel` becomes the package list `["--cancel"]`, and the command runs another optimisation pass and prints `Success`.
       - The help text says a shell-started run "may overlap with the actual job but the job scheduler will not be able to cancel it". `BackgroundDexOptService` creates a fresh instance for each shell run, so no shell cancel exists on S at all.
    3. **API 33:**
       - `cancel-bg-dexopt-job` exists, but `bg-dexopt-job --cancel` still reaches `runDexoptJob` with `--cancel` as a package name.
       - `runBackgroundDexoptJob` first calls `waitForDexOptThreadToFinishLocked()`, so the "cancel" blocks until the app's own job ends.
       - Every cancel therefore times out after 10 s (`CommandRunner.kt:27`) and ties up a system_server binder thread.
    4. **API 34 and later:** `--cancel` works, both on the legacy path and in ART Service's `ArtShellCommand`. `cancel-bg-dexopt-job` is an alias on 33–36.
    5. In `supervise()`, `val cancel = runner.run(BG_DEXOPT_CANCEL_ARGV)` returns `ok` on 31/32, so `finish(if (cancel.ok || exited) BgDexoptState.CANCELLED else …)` records CANCELLED. The `finally { process.destroy() }` then kills only the `cmd` client, while system_server keeps optimising.
    6. In `cancelBgDexopt()`, `confirmed = out.ok` leads to `ToolkitResult.done()` and `endUserCancel(true)`. The client finalises the rows as CANCELLED and shows "Dexopt cancelled."
  - **Failure / Exploit Scenario:**
    - On an Android 12 phone, the user starts bg-dexopt while charging and then unplugs. The guard "cancels", the card says the job was cancelled because of the unplug, and a full dexopt of every package keeps running on battery for many minutes. Every further Cancel tap queues another dexopt pass.
    - On Android 13, every cancel takes 10 s and ends Unknown, and the job still runs on battery.
  - **Concrete Fix:**
    ```kotlin
    // BgDexoptController / ToolkitExecutor (service process)
    internal fun bgDexoptCancelArgv(sdkInt: Int): List<String>? = when {
        sdkInt >= 33 -> listOf(CMD, "package", "cancel-bg-dexopt-job")   // valid on 33..36
        else -> null                                                      // 31/32: a shell-started run is uncancellable
    }
    fun tryStart(): ToolkitResult {
        if (bgDexoptCancelArgv(sdkInt) == null) return ToolkitResult.refused(ToolkitCode.UNSUPPORTED_API)
        ...
    }
    ```
    - Inject `sdkInt = Build.VERSION.SDK_INT` into the controller and executor, and use the same argv in all three cancel sites.
    - Add `ToolkitCode.UNSUPPORTED_API`, bump `ToolkitApi.VERSION`, and hide or disable the card below API 33 with an explanation.
    - Replace the argv-pinning tests with tests parameterised over `sdkInt` 31, 32, 33 and 34.

- **[N-04] [HIGH] The Cockpit timeline (and Health's history-derived data) is loaded before the app-open history ingest finishes and is never reloaded afterwards, so the default chart misses everything since the previous open**
  - **Category:** Concurrency / state lifecycle (stale asynchronous result)
  - **Location:**
    - `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitViewModel.kt:178-217` (`startRefresh`; `loadTimeline()` at `:194`, `deps.refresh()` at `:195`, result handling `:196-211`)
    - `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthViewModel.kt:159-189` (`Debounced -> Unit` at `:176-178`)
    - Callers and state:
      - `app/src/main/kotlin/com/akane/inspiration/MainActivity.kt:32-39`
      - `InspirationApplication.kt:51-54`
      - `di/RefreshCoordinator.kt:63-96`
      - `AppContainer.kt:134-145, 172-184, 309-317`
      - `core/diagnostics/.../analysis/TimelineBuilder.kt:47-49`
      - `core/telemetry-engine/.../store/HistoryHorizon.kt:16-22`
      - `core/database/.../store/RoomSnapshotStore.kt:169-170`
    - Test that locks the behaviour in: `CockpitViewModelTest.kt:240-256`.
  - **Confidence:** Confirmed by call trace and lifecycle-runtime 2.11.0 bytecode (W8, V3), and **device-confirmed** on redroid for the warm-return path (`evidence/redroid-w8-1-timeline.txt` plus two screenshots).
    - The app was backgrounded for 13 min with the screen on, then reopened.
    - The app-open snapshot, with history ingested, was committed at 12:20:27.8, but the chart captured 20 s later still closed the screen-on lane at the previous horizon (11:57:45): a 5 px sliver.
    - After switching X-Ray → Cockpit, with no new database row, the same lane ran to "now" (12 px, to the right edge).

    The cold-start path was not exercised separately.
  - **Root Cause & Execution Trace:**
    1. `MainActivity.onStart()` calls `app.captureAppOpen()`, which launches `appContainer.refresh()` on the IO application scope. The refresh takes the coordinator mutex immediately and runs `captureFull(APP_OPEN, 10 s, ingestHistory = true)`.
    2. The capture waits up to 5 s for the service, reads usage, dumps, alarms, kernel and CPU, and pages history last. The horizon (`newestWallMsWithCompleteSource`) moves only when `store.insert` runs at the very end.
    3. The screen's `Visible` arrives later. lifecycle-runtime dispatches `ON_START` from `onActivityPostStarted`. On a cold start, `LifecycleStartEffect` registers only after the first composition.
    4. `startRefresh` first calls `loadTimeline()`, which reads Room against the *old* `historyHorizonWallMs()`. `TimelineBuilder` closes every lane at that horizon, and level and temperature points exist only for events that were already ingested.
    5. `deps.refresh()` then waits on the mutex and returns `Debounced`, because `last` was just set, or the cached `Failed` from the backoff. Only `Refreshed` reloads:
       ```kotlin
       is RefreshResult.Refreshed -> { loadTimeline() }
       is RefreshResult.Debounced -> { if (userWaiting && visible) deps.recomputeAnalysis() }
       ```
    6. `loadTimeline` has no other caller, and the ViewModel does not observe `lastRefreshWallMs`. A Shizuku-Ready `refreshIfLastFailed` likewise ticks only the analysis.
  - **Failure / Exploit Scenario:**
    - The bug is hit on every cold start, and on every warm return more than 60 s after the last refresh.
    - While discharging, only app-open and marker captures ingest history. So the right-hand part of the 24 h chart, back to the previous app open (possibly hours), shows no lanes and no battery line. Meanwhile the Story and the metrics, which are recomputed from the refresh tick, are current.
    - A pull-to-refresh started during the on-enter job joins that job and does not reload. Only a later pull, or a tab switch, fixes the chart.
  - **Concrete Fix:**
    - `Debounced` already carries `lastRefreshWallMs` (`AppFacingModels.kt:16`). Reload whenever the coordinator's tick is newer than the timeline's load:
      ```kotlin
      private var timelineLoadedAtWallMs = Long.MIN_VALUE   // set to the wall time at which loadTimeline() read Room
      ...
      val tick = when (result) {
          is RefreshResult.Refreshed -> result.wallMs
          is RefreshResult.Debounced -> result.lastRefreshWallMs
          else -> null
      }
      if (tick != null && tick >= timelineLoadedAtWallMs) loadTimeline()
      ```
      Alternatively, expose the refresh tick in `CockpitDependencies`/`HealthDependencies` and collect it between Visible and Hidden.
    - Apply the same change to Health.
    - Rewrite `CockpitViewModelTest.kt:240-256` around a fake coordinator whose app-open refresh is in flight when the screen becomes visible.

### MEDIUM

- **[N-05] [MEDIUM] Live current is re-scaled on every tick from a truncated BatteryManager value read at a different moment. Near-zero currents come out 1000× too large, and load swings get doubled**
  - **Category:** Logic Bug (measurement)
  - **Location:**
    - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/source/AndroidTelemetrySource.kt:137` (`currentMa = currentUa?.div(1_000)`), `:159-175` (`kernelReading`; `bmCurrentNowUa = light.currentMa?.times(1_000)` at `:169`)
    - `telemetry/calibration/PowerCalibrator.kt:40-46, 56-63, 97-109, 125-136`
    - `telemetry/live/LiveTelemetry.kt:33-47`
    - Consumers: `feature/dashboard/.../CockpitMappers.kt:148-179`, `feature/health/.../HealthMappers.kt:70-82, 304-305`, `app/.../capture/ChargingCaptureRunner.kt:144-179`
  - **Confidence:** Confirmed by trace plus a simulation of the calibrator arithmetic (W3, V2). The scripts are `scratchpad/workers/W3/sim.py` and `scratchpad/validation/V2/sim/c11.py`. Not run on a real fuel gauge; redroid has none.
  - **Root Cause & Execution Trace:**
    1. `light()` truncates the BatteryManager current to whole mA.
    2. `kernelReading()` calls `light()`, *then* does a service acquire plus a binder call for sysfs, and then rebuilds the BatteryManager current as `currentMa × 1000`.
    3. `LiveTelemetry.toReading` calibrates each tick from that single sample.
    4. `currentScaleFromComparison` accepts µA only when the sysfs/BatteryManager ratio is within 0.8–1.2. If either value is 0, or the ratio falls outside the band, it uses `currentScaleFromMagnitude`, which treats any |raw| ≤ 20,000 as mA.
    5. Separately, the dual-cell rule doubles the current whenever the time-skewed ratio lands in 0.4–0.6 at single-cell voltage.
    6. No plausibility clamp exists downstream. The debug probe passes raw µA, so on-device probing cannot show the bug.
  - **Failure / Exploit Scenario:** On a µA kernel at full charge or a charge limit, the battery current hovers at a few mA.

    | Raw sysfs / BatteryManager | Shown |
    |---|---|
    | 1.5 mA | 1500 mA |
    | 0.7 mA (BatteryManager truncates to 0) | 700 mA |
    | 15 mA against a skewed BatteryManager 11 mA | 15 000 mA |
    | 400 mA against 800 mA | 800 mA |

    With no skew at all, 2 995 of the raw values from 1 to 20 000 µA come out 1000×. Cockpit and Health show these values as current and watts. Plugged readings are persisted and plotted in the Health curves, and can feed `withRecordedCurrent` capacity integration. That integration is bounded by `CapacityEstimator`'s 0.5–1.2× design-capacity check.
  - **Concrete Fix:**
    ```kotlin
    // LightRead: keep the raw value; light(): currentUa = raw BATTERY_PROPERTY_CURRENT_NOW
    // kernelReading(): read BatteryManager immediately after the sysfs snapshot returns and pass it unmodified
    bmCurrentNowUa = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        ?.takeUnless { it == Int.MIN_VALUE },
    ```
    In `LiveTelemetry`, keep the scale once a ratio comparison above a noise floor (for example |current| ≥ 50 mA) succeeds, and reuse it. It is a device constant. Apply the dual-cell factor only after repeated agreement. Add calibrator tests with truncated and skewed inputs.

- **[N-06] [MEDIUM] An override whose read-back is refused or fails disappears from the Toolkit list, the X-Ray card and Reset all, while the restriction stays on the device and the UI says "No active changes"** *(blindness-limited, see §5)*
  - **Category:** Logic Bug (error that looks like success; the UI disagrees with the device)
  - **Location:**
    - Reads that go through the write gate: `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/ToolkitExecutor.kt:95-101, 132-138, 280-294, 317-321`; `ipc/toolkit/Inspection.kt:72-94`.
    - Where the engine drops overrides: `core/telemetry-engine/.../toolkit/ToolkitActions.kt:1114-1130` (`computeOverrides`), `:264-270` (`activeOverrides`), `:327-332` (`resetAll`), `:1660-1666` (`PROTECTION_UNKNOWN`→`PROTECTED_APP`).
    - UI: `feature/toolkit/.../ToolkitViewModel.kt:323-327, 382, 432-434`; `ToolkitSections.kt:249-250`; `feature/toolkit/src/main/res/values/strings.xml:80, 98`; `feature/xray/.../XRayViewModel.kt:278-280`; `XRayDetail.kt:375`.
    - Test fixture: `feature/toolkit/src/test/.../ToolkitFixtures.kt:98-99, 114-115`.
  - **Confidence:** Confirmed by call trace (worker W9 and validator V1). Not verified on a device whether app-ops or buckets survive a role or accessibility grant (V1: per-package app-op state is not touched by role grants).
  - **Root Cause & Execution Trace:**
    1. `getAppOps` and `getStandbyBucket` start with `when (val gate = gate(pkg)) { is Gate.Denied -> return gate.result … }`. The gate refuses role holders (home, dialer, SMS, assistant), the IME, accessibility services, device admins, the always-on VPN, persistent apps and non-updated system apps. It returns `PROTECTION_UNKNOWN` whenever the protected-set inspection is incomplete (`if (!out.complete) return null`).
    2. `computeOverrides` adds an override only when the read succeeded:
       ```kotlin
       if (current != null) activeOverride(row, current, rows)?.let { result += it }
       if (doze is ToolkitOutcome.Done) activeOverride(row, doze.value.raw, rows)?.let { result += it }
       ```
    3. `activeOverrides()` and `resetAll()` (`computeOverrides(rows).map { … resetOne(…) }`) both use that result. The ViewModel rebuilds `restrictedPackages` from it, and the section then renders `toolkit_overrides_empty`: "No active changes. Everything is as the system set it." X-Ray filters the same list.
    4. History still shows the audit row, but restore is accepted only for listed overrides (`ToolkitViewModel.kt:432-434`). Even if it were listed, the service gate would refuse the restore write.
  - **Failure / Exploit Scenario:**
    - The user restricts app X (`RUN_ANY_IN_BACKGROUND` set to `ignore`). Later X becomes the default keyboard, SMS app, home app or assistant, gets an enabled accessibility service, or becomes a device admin.
    - From then on every read of X is refused. Toolkit says nothing is changed, X-Ray shows only "protected app", and Reset all skips X. The restriction stays on the device, including after Inspiration is uninstalled.
    - A transient read failure (incomplete protected-set spawn, PARSE error, IPC timeout) empties the list in the same way until the next emission.
    - The fixture's `refuseWritesFor` keeps reads working, which the production gate does not, so `ToolkitViewModelTest.kt:908-942` asserts a state production cannot reach.
  - **Concrete Fix:** keep unreadable candidates, flagged, instead of dropping them:
    ```kotlin
    // ToolkitModels.kt
    data class Override(..., val unverified: ToolkitRefusal? = null)
    // ToolkitActions.computeOverrides, per row
    val read = if (row.kind == KIND_APPOP) ops else bucket
    when {
        current != null -> activeOverride(row, current, rows)?.let { result += it }
        read !is ToolkitOutcome.Done -> result += overrideOf(row, null, chainOriginal(row, rows))
            .copy(unverified = (read as? ToolkitOutcome.Refused)?.reason ?: ToolkitRefusal.UNREADABLE)
    }
    // same for doze when readDoze() is not Done
    ```
    - Show `toolkit_overrides_empty` only when no candidate is unverified.
    - Map `PROTECTION_UNKNOWN` to its own retryable refusal.
    - Decide whether a restore-direction write to the logged chain original may pass the protected gate.
    - Add a test that restricts MAPS, then adds MAPS to `device.protectedPackages`, and asserts that it stays listed and that Reset all reports it as NotRestored.

- **[N-07] [MEDIUM] Toolkit writes that never reached the service are recorded as uncertain PENDING writes. Reconcile can turn them into a phantom "our override" that Reset all then "restores", and an unsent dexopt start locks the card in Unknown**
  - **Category:** Logic Bug (state lifecycle across the IPC boundary)
  - **Location:**
    - Gateway: `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitGateway.kt:112-128`.
    - Manager: `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/ShizukuManagerImpl.kt:208-235`.
    - `ToolkitActions.kt`: `:670-672` (write-ahead PENDING), `:744-760` (`settle`), `:1300-1335` (`reconcileRow`), `:1082-1132` (chain links and `computeOverrides`), `:327-341` (Reset all), `:399, 1411-1475` (dexopt).
    - Callers: `feature/toolkit/.../ToolkitViewModel.kt:702-723`; `app/.../AppContainer.kt:308-316`.
  - **Confidence:** Confirmed by call trace (worker W5 and validator V1, which also found two variants). Not reproduced on a device.
  - **Root Cause & Execution Trace:**
    1. `withTelemetryService` returns null in two different situations:
       - **Before the block runs:** bind timeout (`:215-222`), `awaitServiceUse()` false, or a `RemoteException` from the first transaction (`service.apiVersion`).
       - **After it ran:** a generation change, or a `RemoteException` in the middle of the write.
    2. `ToolkitGateway.call` collapses both to `Unavailable(NO_SERVICE | STALE_SERVICE)` and never records whether `block(service)` was entered. The version gate returns `STALE_SERVICE` without dispatching anything.
    3. `run()` has already inserted a PENDING row with `beforeRaw = prep.current`, and `settle` keeps every `Unavailable` as PENDING.
    4. **Phantom override:**
       - On the next Ready, `reconcile` runs `reconcileRow`. If the target moved while Shizuku was down (for example, buckets decay on their own), it records `Verdict.Mismatch`, which becomes FAILED with `afterRaw = current`.
       - `landedElsewhere` makes that row a chain link, and `computeOverrides` lists it as the app's override.
       - Reset all's `resetOne` passes the freshness guard and writes the stale `beforeRaw`. For a bucket, that goes through `set-standby-bucket` as a user-forced bucket.
       - If the target drifted to the intended value instead, `Matched` marks the row DONE, and Reset all reverts a change the app never made.
    5. **Dexopt lock-out:**
       - An unsent start keeps PENDING (`keepPendingOnDone = true`). A fresh service process reports UNKNOWN, and `refreshDexoptLocked` keeps `Unknown` while anything is pending.
       - `startDexopt` returns early on `Unknown`. Only Cancel clears the state, and it runs a system-wide cancel (see N-03).
  - **Failure / Exploit Scenario:** The user sets app X to RARE. The UserService dies between `prepare`'s read and the write. Shizuku then stays down, which happens after every reboot on non-root devices, while X decays to 30. On Ready the row becomes "our override 10→30". Reset all forces X to ACTIVE, pinned by the user, through the very feature meant to undo the app's changes. Separately, after an idle unbind a cold bind that takes longer than 5 s leaves the dexopt card stuck in Unknown across restarts.
  - **Concrete Fix:** carry a `sent` bit through the gateway, and finalise only failures that are known to have happened before dispatch:
    ```kotlin
    data class Unavailable(val reason: GatewayUnavailable, val sent: Boolean = true) : GatewayReply<Nothing>
    private suspend fun <T : Any> call(block: (ITelemetryUserService) -> T?): GatewayReply<T> {
        var dispatched = false
        val reply = shizuku.withTelemetryService(bindTimeoutMs) { service ->
            if (service.apiVersion < ToolkitApi.VERSION) GatewayReply.Unavailable(STALE_SERVICE, sent = false)
            else { dispatched = true; block(service)?.let { GatewayReply.Reply(it) } ?: GatewayReply.Unavailable(STALE_SERVICE) }
        }
        return reply ?: GatewayReply.Unavailable(NO_SERVICE, sent = dispatched)
    }
    // settle: !reply.sent -> FAILED (dexopt: CANCELLED), endedAt = now, afterRaw = null (never a chain link)
    ```
    A `RemoteException` or generation change after `dispatched = true` must stay PENDING. Add a fake mode that fails before `writes += name`, and test that the row ends FAILED with no override listed and that an unsent dexopt start returns to Idle.

- **[N-08] [MEDIUM] When Shizuku is provided by Sui (root, no manager app), the live binder is reported as `NotInstalled`, so the app is unusable**
  - **Category:** Logic Bug (environment detection)
  - **Location:**
    - `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/ShizukuManagerImpl.kt:388-395` (package check before `pingBinder()`) and `:557-571`.
    - Provider: `app/src/main/AndroidManifest.xml:32-38`.
  - **Confidence:** Confirmed against the Shizuku provider/api 13.1.5 bytecode (W2, V1). Not run on a Sui device. Severity is LOW if Sui is deliberately unsupported.
  - **Root Cause & Execution Trace:**
    - `ShizukuProvider.<clinit>` sets `enableSuiInitialization = true`, and `onCreate()` calls `Sui.init(packageName)`, which calls `Shizuku.onBinderReceived` when the Sui bridge answers. The app never disables this: rg finds no `disableAutomaticSuiInitialization`.
    - `refreshState` looks up `moe.shizuku.privileged.api` before it pings the binder. On Sui that package is absent, so the state becomes `NotInstalled` even though the binder is alive.
  - **Failure / Exploit Scenario:** A Magisk/Sui user installs the app. Every screen says Shizuku is not installed, and no privileged feature ever runs. The only workaround is to install the manager app without starting it.
  - **Concrete Fix:** check `facade.pingBinder()` first and use the manager-package check only to tell NotInstalled from NotRunning when there is no binder. Then confirm on a Sui device that `bindUserService` works there. `TelemetryUserService.isCallerAllowed` compares only the app UID, so a root-UID service is not rejected. Alternatively, if Sui is out of scope, disable Sui initialisation explicitly and document that choice.

- **[N-09] [MEDIUM] The Cockpit and Sleep Lab ViewModels keep the shared analysis flow subscribed for the Activity's whole life. That defeats `AnalysisSession`'s observe-to-compute gating, so a return after a failed or debounced refresh shows a frozen interval**
  - **Category:** Architecture / state lifecycle
  - **Location:** `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitViewModel.kt:49-51, 79-104, 200-211`; `feature/sleeplab/.../SleepLabViewModel.kt:61-80`; `core/diagnostics/.../analysis/AnalysisSession.kt:85-92`; for contrast, `feature/xray/.../XRayViewModel.kt:96-108`; `CockpitRoute.kt:34-37`; `app/.../shell/AppShell.kt:80-81`.
  - **Confidence:** Confirmed by call trace (W8, V3).
  - **Root Cause & Execution Trace:**
    1. `AnalysisSession` runs its pipeline only while `subscriptionCount > 0`. Its design (and `AnalysisSessionTest.kt:229`) relies on a returning screen resubscribing, which triggers a recompute.
    2. Cockpit's `init` collects `combine(deps.analysis, …)` in `viewModelScope` for its whole life, and its KDoc says so. Sleep Lab collects `combine(deps.analysis, deps.selection.selected, visible).collectLatest { if (isVisible) … }`, so the upstream subscription is permanent there too.
    3. Both ViewModels are Activity-scoped (`viewModel(key = …)`, no NavHost). Cockpit is the start destination, so the subscription starts at first launch and never drops.
    4. The on-enter refresh recomputes only on `Refreshed`, or on `Debounced`/`Failed` with `userWaiting && visible`. `CockpitViewModelTest.kt:384-411` asserts 0 recomputes.
  - **Failure / Exploit Scenario:** The user leaves the app, Shizuku stops (after a reboot or an OEM kill), and they return hours later. The refresh returns `Failed` and nothing recomputes. Cockpit, Sleep Lab and X-Ray (which shares the session) show an analysis whose "now" is hours old as if it were current, although LIGHT broadcast snapshots have been stored since. The background cost (one analysis per late tick) is minor.
  - **Concrete Fix:** collect `deps.analysis` only between Visible and Hidden, as X-Ray's `visibleJob` does. In Sleep Lab: `visible.flatMapLatest { if (it) combine(deps.analysis, deps.selection.selected, ::Pair) else emptyFlow() }`. At minimum, call `deps.recomputeAnalysis()` on enter whenever the result is not `Refreshed`. Update `CockpitViewModelTest.kt:384-411`.

- **[N-10] [MEDIUM] The Cockpit gauge says "Charging" for a plugged-in battery that is draining or held at a charge limit, and hides time-left**
  - **Category:** Logic Bug (the displayed state contradicts the data)
  - **Location:** `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitMappers.kt:155, 178`; `CockpitCards.kt:124`; `core/designsystem/.../LiveGauge.kt:65-79`; `core/telemetry-engine/.../live/LiveTelemetry.kt:53-54, 64-70`.
  - **Confidence:** Confirmed by call trace (W8, V3). V3 raised the severity from LOW. `CockpitMappersTest.kt:239-260` has no plugged-with-negative-current case.
  - **Root Cause & Execution Trace:**
    - `charging = reading.plugged ?: (currentMa != null && currentMa > 0)`, so `plugged == true` always wins. Meanwhile `LiveTelemetry` normalises the current's sign from the battery status, documenting that "plugged alone cannot (a plugged battery can drain)".
    - `remainingMs` returns null whenever the phone is plugged in. `LiveGauge` maps `isCharging = true` to the charging colour and the `gauge_charging` label.
  - **Failure / Exploit Scenario:** Pixel/Samsung battery protection holds the level at 80% while plugged in ("not charging"), or a weak charger loses to load. The gauge shows "Charging" next to a negative mA value and "Time left: —", in the app's primary live readout.
  - **Concrete Fix:** derive the label from the battery status, or from the normalised current sign, falling back to `plugged` only when both are unknown. Add a distinct "plugged in, not charging" state. Allow time-left when plugged in with a negative current. Add mapper tests for both cases.

### LOW

- **[N-11] [LOW] An unexpected service death has neither the bind fence nor the rebind that the unbind path has, so a narrowly timed bind can attach to the dying stub**
  - **Category:** Concurrency
  - **Location:**
    - `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/ShizukuManagerImpl.kt:106-122` (unbind path, fenced), `:124-135` (unexpected path), `:457-479` (`bindIfRequiredLocked`, guard at `:464`), `:208-222` (waiters).
    - Binding holders: `CockpitViewModel.kt:125`, `HealthViewModel.kt:131`, `ToolkitGateway.kt:113,133`, `LiveTelemetry.kt:29-40`.
  - **Confidence:** The code paths and the Shizuku 13.1.5 ordering are confirmed by javap (W2, V1). Both trigger windows are narrow, and the race was not reproduced.
  - **Root Cause & Execution Trace:**
    1. `ShizukuServiceConnection.died()` posts `lambda$died$1` to Main. In one message, that lambda calls `onServiceDisconnected`, then `connections.clear()`, then `ShizukuServiceConnections.remove(this)`.
    2. The unbind path fences new binds with `awaitingUnboundDeath` and settles in a later Main post. The unexpected path only does `serviceBound = false; serviceGeneration += 1; serviceArgs = null; mutableTelemetryService.value = null`.
    3. A thread that wins `bindingLock` between the callback and `clear()` (for example, a LiveTelemetry poll on IO) binds through the cached dying stub. Its `connected()` then reaches an empty set, and the state stays `serviceBound = true, telemetryService == null`.
    4. A second trigger: a late "owed" death that arrives before the fallback bind connects goes through the same path, and nothing rebinds.
  - **Failure / Exploit Scenario:**
    - The service process is killed while Cockpit is open, and a poll lands in the microsecond window. Every capture, live tick and toolkit call then waits 5 s and returns null until all holders release (3 s idle plus 2 s settle).
    - The owed-death variant heals on the next acquisition.
  - **Concrete Fix:** reuse the unbind path's fence for unexpected deaths, and rebind only when users remain:
    ```kotlin
    serviceBound = false; serviceGeneration += 1; serviceArgs = null
    mutableTelemetryService.value = null
    awaitingUnboundDeath = true
    settleTimeout?.cancel()
    settleTimeout = scope.launch(Dispatchers.Main) {          // runs after Shizuku's clear()/remove()
        synchronized(bindingLock) {
            if (settleTimeout !== coroutineContext.job) return@launch
            settleTimeout = null
            if (awaitingUnboundDeath) finishUnbindSettleLocked() // rebinds if users/holders remain
        }
    }
    ```
    Add a regression test with the existing fake: `holdBinding()` → connected → `deliverServiceDied { holdBinding().close() }` → `runCurrent()` → connected → assert that the service is non-null.

- **[N-12] [LOW] A marker selected right after "Mark now" resolves to a zero-length self-pair (Ready with all-zero deltas) instead of Empty**
  - **Category:** Logic Bug
  - **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/ReferenceResolver.kt:64-65` (the marker path skips `precedes`), with the invariant KDoc at `:131-139`. Related: `core/diagnostics/.../analysis/IntervalAnalyzer.kt:44` (`<` lets equal endpoints through), `app/.../AppContainer.kt:207-211`, `feature/dashboard/.../CockpitViewModel.kt:269-287`, and the test `RoomSnapshotStoreTest.kt:606-621`.
  - **Confidence:** Confirmed (W4, V2).
  - **Root Cause & Execution Trace:**
    - `val ref = boundary.markerSnapshot ?: closestReference(...)`, guarded only by `ref.clock.wallMs > end.clock.wallMs`.
    - Right after creation, `lastAtOrBefore(now)` returns the marker's own FULL snapshot, so `ref == end`.
    - `SnapshotDiffer` flags only `kernel_clock_session`.
  - **Failure / Exploit Scenario:** The user marks now and taps the marker. Cockpit shows a "Ready" analysis with every delta 0 and dashes until the next capture.
  - **Concrete Fix:** after the `ref` line, add `if (!ref.precedes(end) && ref.id == end.id) return null`. Keep cross-boot markers, which the differ flags. Update the store test.

- **[N-13] [LOW] `historySessions()` groups history rows by exact `statsStartMs`, so a backward clock step splits one stats session, and the capacity estimator then walks the newer group first and loses that charge segment**
  - **Category:** Logic Bug
  - **Location:** `core/database/src/main/kotlin/com/akane/inspiration/database/store/RoomSnapshotStore.kt:159-164`. Related: `core/telemetry-engine/.../diff/BatteryStatsSession.kt:6-17`, `core/shizuku-ipc/.../client/BatteryHistory.kt:67-68`, `core/database/.../Entities.kt:255`, `core/telemetry-engine/.../capacity/CapacityEstimator.kt:56-61`, `app/.../capture/ChargingCaptureRunner.kt:115`.
  - **Confidence:** Confirmed against AOSP (`noteCurrentTimeChangedLocked` → `adjustStartClockTime`, which runs on every `ACTION_TIME_CHANGED` of ≥1 s) (W4, V2). Not run on a device.
  - **Root Cause & Execution Trace:**
    1. The service compares the session start exactly and re-pages from 0 under the shifted label.
    2. The unique index plus insert-IGNORE keeps old rows under the old label.
    3. `.groupBy { it.statsStartMs }` yields two keys, and `fromSessions` walks the groups sorted by key.
    4. After a backward step, the newer group sorts first.
  - **Failure / Exploit Scenario:** A network-time correction steps the clock back mid-charge. A charge whose PLUGGED is under the old label and whose UNPLUGGED is under the new one is never closed, so its capacity sample is silently lost. No wrong segment is produced.
  - **Concrete Fix:** walk one stream ordered by `(wallMs, historyTimeMs)`, with the label as metadata only. Alternatively, coalesce keys that satisfy `BatteryStatsSession.sameStart` before grouping. Do not canonicalise the stored cursor.

- **[N-14] [LOW] An app update (`MY_PACKAGE_REPLACED`) is recorded as a BOOT snapshot, which wipes the known screen state in the "since unplug" status summary**
  - **Category:** Logic Bug
  - **Location:** `app/src/main/kotlin/com/akane/inspiration/capture/BootCaptureReceiver.kt:17-33`; `app/src/main/AndroidManifest.xml:44`; `core/telemetry-engine/.../status/UnplugSummary.kt:82-94`; `core/database/.../Daos.kt:148-172`; `app/.../status/StatusNotificationService.kt:105-106`.
  - **Confidence:** Confirmed (W6, V2). rg finds no other BOOT consumer; analysis and diff are trigger-agnostic.
  - **Root Cause & Execution Trace:**
    - `BOOT_ACTIONS = setOf(ACTION_BOOT_COMPLETED, ACTION_MY_PACKAGE_REPLACED)`, and both are captured as `CaptureTrigger.BOOT`.
    - `UnplugSummaryCalculator.tally` sets `screen = null` on *any* BOOT row, even when `bootCount` is unchanged.
    - The DAO also treats `'BOOT'` as a screen anchor.
  - **Failure / Exploit Scenario:** An update lands at 01:00 while the phone is unplugged with the screen off, and the screen stays off until 07:00. The notification classes six hours of drain as unknown, and its rates come out null or skewed.
  - **Concrete Fix:** capture a package replace with its own `CaptureTrigger.PACKAGE_REPLACED`. Triggers are stored by name, so no migration is needed, but every exhaustive `when` must be checked. Alternatively, skip the snapshot on a replace and reset the screen state in `tally` only when `bootCount` changes.

- **[N-15] [LOW] A force-stop that did happen is reported FAILED when the read-back lookup fails**
  - **Category:** Logic Bug (breaks the result contract)
  - **Location:** `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/ToolkitExecutor.kt:152-158`; client `core/telemetry-engine/.../toolkit/ToolkitActions.kt:762-787`; `ToolkitResult.kt:83-88` (the `UNVERIFIED` contract).
  - **Confidence:** Confirmed by call trace (W1, V1).
  - **Root Cause & Execution Trace:** After `write.ok`, the code returns `else -> return ToolkitResult.failed(ToolkitCode.PARSE, before = before)` when `inspector.lookup` is not `Found`. `lookup` returns `Unknown` on a RuntimeException and `NotInstalled` on `NameNotFoundException`. The app-op, bucket and write-timeout paths return `unverified()` in the same situation. `settle` writes a FAILED row, and the UI shows "The command failed (code 32)."
  - **Failure / Exploit Scenario:** The package is uninstalled or updated, or a PackageManager call throws, in the milliseconds after a successful `am force-stop`. The app was stopped, but the log and notice say the action failed. This is rare and only misleading.
  - **Concrete Fix:** `else -> return unverified(before)`, plus a test next to "a force-stop that timed out … is unverified".

- **[N-16] [LOW] Tapping an app row while a doze or Restore plan is pending silently drops that request**
  - **Category:** Concurrency (UI state machine)
  - **Location:** `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitViewModel.kt:87-94, 335-341, 385-390`; `ToolkitApps.kt:87, 122-128`; `ToolkitSections.kt:99, 254, 259`; `core/telemetry-engine/.../toolkit/ToolkitActions.kt:274-283`.
  - **Confidence:** Confirmed by call trace (W9, V1).
  - **Root Cause & Execution Trace:** `act` sets `planning = true; busy = true`, clears the notice and launches `planOf`. Doze and Restore plans wait on the engine mutex, which an overrides recompute may be holding. The doze rows, override rows and Reset all are disabled while busy, but `AppRow` is not. `openApp` then calls `invalidatePlanning()` (`generation++`, which clears planning and busy), and the plan result is discarded at `if (prepared.generation != generation) return@launch` without any notice.
  - **Failure / Exploit Scenario:** The user taps a doze profile, or Restore, and then taps an app row while the busy line is showing. The spinner disappears and nothing is written, and the card still shows the old state. The user may believe the change went through.
  - **Concrete Fix:** In `openApp`, add `if (_state.value.busy) return`, and pass `enabled = !state.busy` to `AppRow`. Alternatively, invalidate only plans that are scoped to the app detail, and post `ToolkitNotice.Rejected(Problem.Refused(ToolkitRefusal.STALE))` for a dropped one.

- **[N-17] [LOW] Health shows a "Live" badge, and the charge duration keeps growing, while no live reading is arriving**
  - **Category:** Logic Bug (presentation)
  - **Location:** `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthViewModel.kt:128-144, 214`; `HealthMappers.kt:296-301, 362, 366-373`; `HealthCards.kt:111, 248`; `HealthRoute.kt:52-63`; `core/telemetry-engine/.../live/LiveTelemetry.kt:33`.
  - **Confidence:** Confirmed (W8, V3).
  - **Root Cause & Execution Trace:**
    - `val liveActive = liveJob != null`. `syncLive` starts the job whenever the screen is visible and the sticky `ACTION_BATTERY_CHANGED` says charging. `HealthDependencies` has no Shizuku state.
    - `LiveTelemetry` emits only when `kernelReading()` is non-null, which needs the service.
    - The mapper marks the curve Live and stretches `endMs` to now. Cockpit, by contrast, guards on `ShizukuState.Ready`.
  - **Failure / Exploit Scenario:** The Shizuku server dies part-way through a charge after samples were persisted. Health then shows a frozen curve labelled "Live", with a charge time that keeps counting up.
  - **Concrete Fix:** compute `liveActive` from reading freshness instead: `live.lastOrNull()?.wallMs?.let { nowMs - it <= LIVE_STALE_MS } == true`, the same rule Cockpit uses.

- **[N-18] [LOW] Health reshapes the whole 24 h sample set on the main thread on every 3 s live tick**
  - **Category:** Performance
  - **Location:** `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthViewModel.kt:152-157, 207-252`; `core/database/.../Daos.kt:263-267` (`samplesBetween`, unbounded); `core/telemetry-engine/.../live/LiveTelemetry.kt:34-40`.
  - **Confidence:** Confirmed by trace (W8, V3). Not profiled.
  - **Root Cause & Execution Trace:** `onReading` → `render()` runs on `Main.immediate`, because `flowOn(IO)` only moves the upstream. Each render maps, deduplicates and sorts every persisted sample plus the unbounded `live` list. It then recomputes the charge span, session, curve thinning, health card and wear analysis.
  - **Failure / Exploit Scenario:** During long charges with Health open, which persists a sample on every reading, O(n log n) work and allocations run on Main every 3 s. For a few thousand points that is milliseconds per tick: battery and jank cost on the screen that is measuring charging.
  - **Concrete Fix:**
    - Map persisted samples once in `load()`.
    - Append live points to an already sorted list.
    - Cap `live`.
    - Run the shaping on `Dispatchers.Default`, like Cockpit's `shapingDispatcher`.

- **[N-19] [LOW] Calling `ZoneId.systemDefault()` as a default parameter creates a new zone on every recomposition. That defeats skipping in the chart composables and rebuilds the hypnogram's draw cache**
  - **Category:** Performance
  - **Location:**
    - Default parameters: `feature/dashboard/.../CockpitScreen.kt:30`, `feature/sleeplab/.../SleepLabScreen.kt:55`, `feature/health/.../HealthScreen.kt:36`, `core/designsystem/.../chart/scrub/SynchronizedScrubbingChart.kt:75,101`, `chart/hypnogram/SleepHypnogram.kt:118, 202-231`, `component/TimeSliceBanner.kt:62,82`.
    - Routes that pass no zone: `CockpitRoute.kt:60`, `SleepLabRoute.kt:68`, `HealthRoute.kt:82`.
  - **Confidence:**
    - Confirmed for skipping and for the hypnogram cache: Android SDK 36 sources (`TimeZone.toZoneId` → `new ZoneRegion`), compiled `changedInstance(zone)` checks, and compose-ui 1.12.1 `DrawWithCacheElement` reference equality (W10, V3).
    - **Refuted** for the Cockpit scrub-chart cache: its `remember` is keyed with `changed` (equals).
  - **Root Cause & Execution Trace:** The repo has no Compose stability config, so `ZoneId` is unstable and compared by identity. Cockpit recomposes every 1.5 s from live readings, so `ChartCard` → `SynchronizedScrubbingChart` → `ScrubReadout`/`ScrubPlot` re-run instead of skipping. Every Sleep Lab recomposition creates a new `drawWithCache` lambda, which invalidates and re-records the hypnogram layer (text measurement, paths, ticks).
  - **Failure / Exploit Scenario:** Small, continuous wasted CPU work while Cockpit is visible, and a full hypnogram rebuild on every Sleep Lab state change, in a battery-measuring app. Nothing is visibly wrong.
  - **Concrete Fix:** add `compose-stability.conf` with `java.time.ZoneId` and `java.util.Locale`, wired through `composeCompiler { stabilityConfigurationFiles.add(...) }` in build-logic. Alternatively, hoist `val zone = remember { ZoneId.systemDefault() }` in the routes and refresh it on `ACTION_TIMEZONE_CHANGED`.

- **[N-20] [LOW] The Toolkit app picker re-sorts with `String.CASE_INSENSITIVE_ORDER`, which undoes the locale Collator, so Turkish and accented labels sort after Z**
  - **Category:** Logic Bug (locale)
  - **Location:** `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitViewModel.kt:281-298` (sort at `:290-296`); `app/src/main/kotlin/com/akane/inspiration/di/AppEntries.kt:22, 33`.
  - **Confidence:** Confirmed (W9, V3).
  - **Root Cause & Execution Trace:** `installedApps()` is collated with `Collator.getInstance(locale)`. `rebuildApps` then re-sorts with `.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }`, which compares UTF-16 code units: Ç is U+00C7 and Ş is U+015E, both after `z`.
  - **Failure / Exploit Scenario:** On a Turkish device (the app ships `values-tr`), Çiçeksepeti, Ödeal, Şok and Üçüncü are listed after Zara.
  - **Concrete Fix:** `.thenBy(Collator.getInstance(Locale.getDefault())) { it.label }`, created once per rebuild. Add a non-ASCII ordering test.

- **[N-21] [LOW] A Cockpit selection that has drifted outside the 24 h window is drawn with edge handles that cannot be grabbed**
  - **Category:** Logic Bug (interaction)
  - **Location:** `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/scrub/ScrubChartRenderer.kt:379-387` (clamped x); `chart/math/ScrubGestureReducer.kt:158-171` (unclamped hit test); `chart/scrub/SliceControls.kt:40-43`; `chart/math/SeriesMath.kt:69-76`; `feature/dashboard/.../CockpitViewModel.kt:44, 219-224`; `app/.../AppContainer.kt:149`. For contrast, `SleepHypnogram.kt:454-461` skips clamped edges.
  - **Confidence:** Confirmed (W10, V3).
  - **Root Cause & Execution Trace:**
    - The process-wide selection survives while the window slides to `[now−24h, now]` on each load.
    - `drawEdge` uses `scale.toX(startMs).coerceIn(0f, plotWidth)`, while `nearestHandle` uses the unclamped x, so a touch on the drawn handle starts a new brush instead.
    - `normalizeSelection` replaces a fully outside selection with `[windowStart, windowStart + 5 min]` for the accessibility buttons.
  - **Failure / Exploit Scenario:** A day-old slice in a still-alive process (the foreground service keeps it alive) dims the whole chart and shows two stacked, dead handles at the left edge. TalkBack announces the real slice, while the buttons edit an invented one.
  - **Concrete Fix:** draw an edge only when its time lies inside the window, and skip the scrim and handles when the selection does not overlap it. For a non-overlapping selection, offer only "Select interval" and "Clear".

- **[N-22] [LOW] Hypnogram accessibility text drops dates for windows of 24–48 h**
  - **Category:** Logic Bug (accessibility)
  - **Location:** `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/hypnogram/SleepHypnogram.kt:102, 471-475, 177-187, 499-519`; `ScrubChartMath.kt:104` (the scrub chart switches at 24 h); `SleepHypnogramClockTest.kt:51`.
  - **Confidence:** Confirmed (W10, V3).
  - **Root Cause & Execution Trace:** `clockFormatter` adds the date only at `>= TWO_DAYS_MS`. For a typical 26 h "since unplugged" window, `describe` therefore produces "Chart from 08:00 to 10:00".
  - **Failure / Exploit Scenario:** TalkBack users hear what sounds like a 2-hour chart when it covers 26 h, and the two charts follow different date rules.
  - **Concrete Fix:** include the date whenever start and end fall on different local dates in `zone`, or reuse `TelemetryFormat.range`. Update the threshold test.

- **[N-23] [LOW] Chart value-axis labels bypass localisation and `TelemetryFormat`**
  - **Category:** Logic Bug (localisation)
  - **Location:** `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/scrub/ScrubChartRenderer.kt:84` (`listOf("0%", "50%", "100%")`), `:88` (`"${it.toInt()}$NBSP$temperatureUnit"`); `values-tr/strings_chart.xml:29`; `TelemetryFormat.kt:19, 88`.
  - **Confidence:** Confirmed (W10, V3).
  - **Root Cause & Execution Trace:**
    - The renderer measures and draws literal strings: `val battery = listOf("0%", "50%", "100%").map(::measure)` and `measure("${it.toInt()}$NBSP$temperatureUnit")`.
    - The card readouts go through string resources (tr: `%%%1$d`) and `TelemetryFormat`, which emits U+2212 for negatives, so the two disagree in the same card.
  - **Failure / Exploit Scenario:**
    - Turkish users see "50%" on the axis next to "Pil %32" in the same card's readout.
    - Sub-zero temperatures render with an ASCII hyphen, unlike the U+2212 minus sign used everywhere else.
  - **Concrete Fix:** use a `chart_axis_percent` resource (`%1$d%%`, tr `%%%1$d`) or `NumberFormat.getPercentInstance(locale)`, and `TelemetryFormat.celsius(...)` for temperatures. Add `locale` to the label `remember` keys.

- **[N-24] [LOW] The accessible slice controls step by 1 minute on a 24 h chart**
  - **Category:** Logic Bug (accessibility)
  - **Location:** `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/scrub/SliceControls.kt:22, 34-63`; `SynchronizedScrubbingChart.kt:74, 100`; `feature/dashboard/.../CockpitViewModel.kt:44`; `CockpitChartCard.kt:51`.
  - **Confidence:** Confirmed (W10, V3).
  - **Root Cause & Execution Trace:** The step is fixed at `private const val ADJUSTMENT_STEP_MS = 60_000L` (`SliceControls.kt:22`). The window is never consulted, although the Cockpit passes a 24 h window (`CockpitViewModel.kt:44`) through `SynchronizedScrubbingChart` and `CockpitChartCard`. Every button target moves an edge by one minute.
  - **Failure / Exploit Scenario:** For TalkBack, keyboard and switch users these buttons are the only alternative to dragging. A 3 h slice takes about 175 activations, and each one commits a selection that restarts the analysis.
  - **Concrete Fix:** scale the step to the window (for example `max(60_000, window / 48)`, which is 30 min on 24 h). Alternatively, offer preset durations or a coarse/fine toggle.

- **[N-25] [LOW] Setting only some of the release-signing properties breaks configuration of every build, debug and tests included**
  - **Category:** Logic Bug (build configuration)
  - **Location:** `app/build.gradle.kts:21-31, 35`
  - **Confidence:** Confirmed from Gradle DSL semantics (W11, V1). Not executed; no keystore or properties file was read.
  - **Root Cause & Execution Trace:** When `INSPIRATION_RELEASE_STORE_FILE` is present, `signingConfigs.create("release") { … }` configures the config eagerly during script evaluation and calls `.get()` on the three other providers. A missing one throws `MissingValueException` while `:app` is being configured. `gradle.properties` does not enable configure-on-demand.
  - **Failure / Exploit Scenario:** A misspelled or missing password or alias property on a developer or CI machine fails `assembleDebug`, `testDebugUnitTest`, `lintDebug` and screenshot validation before any task runs.
  - **Concrete Fix:**
    ```kotlin
    val signing = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
        .associateWith { providers.gradleProperty("INSPIRATION_RELEASE_$it").orNull }
    if (signing.values.all { it != null }) signingConfigs.create("release") { /* use signing[...]!! */ }
    else if (signing.values.any { it != null }) logger.warn("Incomplete INSPIRATION_RELEASE_* signing properties; release stays unsigned")
    ```

- **[N-26] [LOW] The hidden-API exemption covers the whole app process, although one tolerant reflective call needs it**
  - **Category:** Security (hardening)
  - **Location:** `app/src/main/kotlin/com/akane/inspiration/InspirationApplication.kt:22-26` (`HiddenApiBypass.addHiddenApiExemptions("L")`); the only main-process consumer is `core/diagnostics/.../sensor/AndroidSensorCatalog.kt:30-43`.
  - **Confidence:** Confirmed (W11, V1). The other hidden-API users (`BatteryStatsClient`, `PowerStatsClient`, `DeviceIdleReader`) are built only inside `TelemetryUserService`. The manifest has no `android:process`.
  - **Root Cause & Execution Trace:** The `"L"` prefix exempts every restricted framework member for all app and library code in the main process. `Sensor.getHandle()` is `@UnsupportedAppUsage` with no `maxTargetSdk` (android12 and android15 sources), so it is unsupported/greylisted, and the catalog already handles `NoSuchMethodException`.
  - **Failure / Exploit Scenario:** There is no exploit, because hidden-API restrictions are not a security boundary. The cost is that blocked APIs used anywhere in the main process work silently until a platform update breaks them, and a native bypass library runs on every app start.
  - **Concrete Fix:** narrow the exemption to `addHiddenApiExemptions("Landroid/hardware/Sensor;->getHandle")`, or drop it if `getHandle` is reachable without it on minSdk 31–37.

- **[N-27] [LOW] The release APK ships about 85 unused locales and unshrunk resources, plus `.proto` sources and `DebugProbesKt.bin`**
  - **Category:** Performance (size)
  - **Location:** `app/build.gradle.kts:33-39` (`isMinifyEnabled = true`, but no `isShrinkResources`, locale filter or `packaging` excludes). Lint `NotShrinkingResources` flags `:36`.
  - **Confidence:** Confirmed by inspecting the existing release APK (0.2.3, built today, newer than all sources) with `aapt2 dump configurations` (build-tools 37.0.0) (W11, V3).
  - **Root Cause & Execution Trace:**
    - The APK lists 86 locale qualifiers, but the app has strings only in `values` and `values-tr`.
    - `resources.arsc` is 752,572 bytes. It must be stored uncompressed when targeting API 30 or later, so its size is the cost.
    - The APK also carries 15 `.proto` entries (4 project, 11 `google/protobuf`), `DebugProbesKt.bin` and `META-INF/*.version` files.
  - **Failure / Exploit Scenario:**
    - Install size is larger than needed.
    - On a non-English, non-Turkish device, Material3's built-in strings (sheets, dialogs, menus) appear in the device language while the app's own text is English.
  - **Concrete Fix:**
    ```kotlin
    android {
        androidResources { localeFilters += listOf("en", "tr") }
        buildTypes.release { isShrinkResources = true }
        packaging { resources.excludes += setOf("**/*.proto", "DebugProbesKt.bin", "META-INF/*.version") }
    }
    ```
    Verify with an R8 build, `aapt2 dump configurations`, and a release smoke test on redroid.

- **[N-28] [LOW] Missing tests: Android component glue, the pointer-gesture layer, and the manifest wiring of the backup rules**
  - **Category:** Test coverage
  - **Location:**
    - **Component glue:** `app/src/main/kotlin/com/akane/inspiration/status/StatusNotificationService.kt:52-87, 113-137`, `StatusNotificationController.kt:16-31`, `capture/ChargingCaptureJobService.kt:19-73`, `capture/BootCaptureReceiver.kt:17-33` and `MainActivity.kt:44`. The only test is `StatusNotificationControllerTest.kt:7-28`, a truth table of `shouldRun`.
    - **Gesture layer:** `core/designsystem/.../chart/scrub/ScrubGestures.kt:76, 88, 139-151`, `chart/draw/TimeAxis.kt:37-62`. Only the reducer is unit-tested.
    - **Backup rules:** `app/src/test/.../BackupRulesTest.kt:10-27` parses only `data_extraction_rules.xml`, never `AndroidManifest.xml:25-26`.
  - **Confidence:** Confirmed by test-source search (W6, W10, W11, V3).
    - There is no `androidTest` source set.
    - Screenshot previews exercise axis-label culling indirectly.
  - **Root Cause & Execution Trace:**
    - The decisions in these files are written inline in Android callbacks: `onStartCommand`/`onStartJob`/`onReceive`, the activity's `onStart`, and `pointerInput` lambdas.
    - JVM unit tests can't reach them without Robolectric or instrumentation, and the project has neither for these classes.
    - The backup test reads the XML resource directly instead of the manifest that references it.
  - **Failure / Exploit Scenario:** All 1509 tests would stay green after any of these regressions:
    - Work moved ahead of `startForeground`, causing `ForegroundServiceDidNotStartInTimeException`.
    - The `===` identity check in the job registry dropped, so `onStopJob` can no longer cancel the newer run.
    - The boot or package-replace trigger mapping changed (which is how N-14 went unnoticed).
    - The gesture driver's cancel or preview-clear ordering broken (N-21 is in this class).
    - `android:dataExtractionRules` removed. With targetSdk 36, `allowBackup="false"` alone does not stop device-to-device transfer of `inspiration.db`.
  - **Concrete Fix:**
    - Extract pure deciders and test them on the JVM: `BootCapturePlan.forAction(action): CaptureTrigger?`, a `JobRegistry` for put/cancel/remove-if-same, and a `StatusStartDecision`.
    - Add `createComposeRule` gesture tests (`performTouchInput { down(); advanceEventTime(longPressTimeout); moveBy(); up() }`) and a pure, tested label-placement function.
    - Assert both `<application>` attributes in `BackupRulesTest`, preferably against the merged release manifest.
    - Add an instrumented test for the foreground-service lifecycle.

## 3. Dead Code, Redundancy & Technical Debt Cleanup

Every item below was found by a worker and re-verified by validator V3 with `rg` over main, test and debug sources. Removing any of them is a recommendation only; audit mode changes nothing.

Items marked † also appear in the prior-report line that W10 saw by accident (§5). W10 reports finding the first two before that exposure, and W6 found the `arm` parameter with no exposure. Two further items W10 verified only *after* the exposure (`InspirationTheme(dynamicAccent)`/`withAccentFrom`, `LocalReducedMotion`) are deliberately left out of this independent list.

**Persistence**
- `SnapshotDao.deleteById` (`core/database/.../Daos.kt:211-212`): test-only.
- The single-row `SnapshotDao.insertAvailability` overload (`Daos.kt:19-20`): test-only. Production uses the list overload.
- **`TelemetryDao.hourly` (`Daos.kt:269-270`) and the `telemetry_hourly` table.**
  - The DAO method is read only by tests.
  - `MaintenanceDao.downsampleSamples` (`Daos.kt:362-387`) writes the table on every prune, but nothing in production reads it, and no statement ever deletes from it. It is a write-only table that grows by about 24 rows a day for ever.
  - Fix: either drop the downsample (and, in a migration, the table), or prune it with the rest of the retention.

**Telemetry and IPC**
- `AndroidCalibrationInputReader.readUncached()` (`source/AndroidCalibrationInputReader.kt:22-40`): its sysfs and sticky-intent reads are fully overwritten by `kernelReading()` (`AndroidTelemetrySource.kt:166-173`). This is wasted work, once per process.
- `DumpReader.readAll(InputStream)` and `readAll(ParcelFileDescriptor)` (`dump/DumpReader.kt:20-54`): test-only.
- `KernelUnits.uaToMa` (`live/KernelUnits.kt:17`) and `ChargeSegmentExtractor.fromHistory` (`capacity/CapacityEstimator.kt:46-47`): test-only.
- **Debug-only privileged surface that still ships in release:**
  - AIDL transactions 4 (`readPowerMonitors`) and 5 (`readDeviceIdle`), at `ITelemetryUserService.aidl:18-19`.
  - The service side `TelemetryUserService.kt:234-245`, plus `PowerStatsClient`, `DeviceIdleReader`/`DeviceIdleClient`/`PowerManagerClient`/`IdleFlags` and `BinderResult`.

  The only callers are in debug `ProbeReceiver.kt:191, 216`. Move them behind a debug source set, or delete them.
- `DumpTarget.POWER_PROTO` / `DEVICEIDLE_TEXT` (`DumpTarget.kt:5, 7`) and their service mappings (`TelemetryUserService.kt:463, 465`): no client requests them.
- `DozeRaw.AOSP_KEYS` (`toolkit/Toolkit.kt:149-190`): documented as test reference only. Keep it, or move it to test fixtures.
- The hidden-api-stub `BatteryConsumer` `POWER_COMPONENT_*`/`PROCESS_STATE_*` constants: unreferenced. Two carry API 36/37 values (`PROCESS_STATE_ANY = -1`, `POWER_COMPONENT_COUNT = 19`, against 0/18 on API 35), which is a trap if anyone starts using them.
- `PowerCalibration.cellCount`: read only by debug `ProbeReceiver.kt:583`.
- `powermanagerservice.proto` and several message fields (`parcel_version`, `cpu_frequency`, `job_completion`, `aggregated_wakelock`, `Cpu.by_process_state`, `pending_alarms`/`AlarmProto`): referenced only by `DumpParserTest`. They are kept alive by `-keep class * extends GeneratedMessageLite { *; }`.

**Toolkit and app**
- `ToolkitGateway.release()` (`:110`) has no references at all. `ToolkitActions.release()` (`:448`) is test-only, and its KDoc names a "container teardown" that does not exist.
- `ToolkitDependencies.holdBinding()` (`feature/toolkit/.../ToolkitDependencies.kt:31`): never called by the toolkit feature.
- † The public `ChargingCaptureScheduler.arm(context, reArmFromJob = false)` parameter (`app/.../capture/ChargingCaptureScheduler.kt:24-29`): never passed by its only caller. Passing `true` would skip the immediate-arm check, so remove it.
- `AppEntries.build(restricted = …)`, `AppEntry.restricted` and `AppEntry.launchable` (`app/.../di/AppEntries.kt:12-30`, `telemetry/app/AppFacingModels.kt:58-59`): production always passes `emptySet()` and never reads the fields.

**Diagnostics**
- The `BatteryStory` `endsNow = true` default (`BatteryStoryBuilder.kt:17`, `BatteryStory.kt:13`) is reached only from debug `ProbeReceiver.kt:335`. So the true branches in `AndroidStoryWriter.kt:60, 65` and `R.plurals.story_summary`/`story_summary_minutes` never run in release.
- `Explanation.category` / `action` (`DictionaryEntry.kt:31, 34`): read only by tests.
- The culprit and evidence computation (`DiagnosticRuleEngine.kt:95-122`, `CulpritScope`, `skippedInvalidUids`) is computed on every analysis but read only by the debug probe.

**Design system**
- † String `chart_a11y_cursor` (`res/values{,-tr}/strings_chart.xml:42`): no reference.
- † The `TimeSliceBanner(requested: String, covered: String, gapText: String?, …)` overload (`component/TimeSliceBanner.kt:99-113`): no caller.
- `niceTimeTicks` (`chart/math/TimeTicks.kt:58-62`): test-only. Production uses `niceTimeTickSet`.
- 17 orphaned screenshot reference PNGs (11 in `core/designsystem`: ScrubChart ×8, Toggle ×3; 6 in `feature/dashboard`), e.g. `CockpitDark_dark_aca7a85a` next to the rendered `_06dc3761`.

**Build**
- Version-catalog entries `protobuf-kotlin-lite` (`:44`) and `kotlin-android` (`:57`) are unused. The `kotlin-parcelize` alias (`:59`) is unused because `core/shizuku-ipc` applies the plugin by bare id.
- `implementation(libs.room.ktx)` (`core/database/build.gradle.kts:18`) is redundant: the 2.8.5 artifact contains only a version file, and `withTransaction` comes from room-runtime.
- `api(libs.shizuku.provider)` (`core/shizuku-ipc/build.gradle.kts:21`) should be `implementation`. Only the app manifest references `ShizukuProvider`, and the `api` leaks it onto every feature's compile classpath.
- The Stub/Proxy `{ *; }` keeps (`core/shizuku-ipc/consumer-rules.pro:21-22`) retain Proxy methods that are dead in release.

No stale `TODO`/`FIXME` traps were identified in the main sources reviewed.

## 4. Prioritized Remediation Roadmap

**Phase 1: Immediate hotfixes (isolated commits, each with a regression test)**
1. **N-03:** gate bg-dexopt by API level. Use `cancel-bg-dexopt-job` on 33+, and refuse the feature on 31/32.
2. **N-01:** emit the baseline screen and doze state for RESET (and post-OVERFLOW) records. Close an open charge at a RESET with `plugType 0` in `lastChargeSpan`. Treat RESET like START in `IntervalMetrics` for rows already stored.
3. **N-02:** reconcile history boundaries with capture anchors (slack plus the newer of the two). Add resolver tests with captures offset from history times.
4. **N-04:** reload the Cockpit and Health timelines when the coordinator's refresh tick is newer than the timeline's load.
5. **N-10:** derive "Charging" from status or current, not `plugged`.
6. **N-25:** make the signing-property handling all-or-nothing.

**Phase 2: Structural and concurrency hardening**
1. **N-07:** carry a "sent" bit through `ToolkitGateway`, and finalise unsent writes. This is the prerequisite for trustworthy reconcile and Reset all.
2. **N-06:** list unreadable overrides as "can't verify" and give `PROTECTION_UNKNOWN` its own refusal. Decide the policy for restore-direction writes on protected apps.
3. **N-09:** gate analysis subscriptions on visibility in Cockpit and Sleep Lab, or recompute on enter.
4. **N-05:** read BatteryManager current raw and at the same moment as sysfs, and calibrate over a window rather than one sample.
5. **N-11:** fence and rebind on unexpected service death.
6. **N-08:** check the binder first, and decide whether Sui is supported.
7. **N-12, N-13, N-14, N-15, N-16, N-17:** small state-machine and edge-case fixes.

**Phase 3: Architectural refactoring and test coverage**
1. Make one *time-anchor* abstraction the only way analysis code turns history events and snapshots into boundaries. This removes the class of bugs behind N-01, N-02 and N-13.
2. Replace argv-pinning toolkit tests with per-API behaviour tests, and add a device matrix (API 31, 33, 35) to the release checklist for privileged commands.
3. Add the N-28 tests: component glue deciders, Compose gesture tests, and manifest assertions for the backup rules.
4. Performance and hygiene:
   - Add a Compose stability config (N-19) and move Health shaping off Main (N-18).
   - Apply the APK resource, locale and packaging hygiene (N-27).
   - Narrow the hidden-API exemption (N-26).
   - Remove the dead code in §3, especially the write-only `telemetry_hourly` table and the debug-only privileged AIDL surface.
5. Accessibility and localisation pass on the charts: N-20, N-21, N-22, N-23, N-24.

## Coverage ledger

### Subsystems examined (one fresh read-only worker each)
| Worker | Scope |
|---|---|
| W1 | `:core:shizuku-ipc` service side: `TelemetryUserService`, AIDL and parcelables, toolkit executor, bg-dexopt controller, parsers and inspection, models. Also `:core:hidden-api-stub` |
| W2 | `:core:shizuku-ipc` client side: `ShizukuManagerImpl`, facade and state, `client/*` (BatteryHistory, BatteryStatsClient, usage budget) |
| W3 | `:core:telemetry-engine` source, capture, snapshot, dump, live, calibration, capacity, status and interval |
| W4 | `:core:telemetry-engine` diff and store, plus all of `:core:database` (entities, DAOs, migrations, schema JSON 1→5, `RoomSnapshotStore`) |
| W5 | Toolkit write path: `ToolkitActions`, `ToolkitGateway`, models and app wiring |
| W6 | `:app` manifest, Application, MainActivity, AppContainer, `capture/`, `di/`, `status/`, `shell/` and the debug source set |
| W7 | `:core:diagnostics` analysis, rules, dictionary, story and timeline |
| W8 | `:feature:dashboard`, `:feature:health`, `:feature:sleeplab` |
| W9 | `:feature:toolkit`, `:feature:xray` |
| W10 | `:core:designsystem` |
| W11 | Build config, build-logic, version catalog, manifests, R8/proguard, signing and release APK packaging |

Validators V1 (C1–C9), V2 (C10–C15) and V3 (C16–C29 plus the dead-code list) re-traced every candidate in fresh contexts.

### Tools actually run
- **Source-only recon:** `scripts/repo-recon.sh` → `evidence/recon.txt`.
- **Shared baseline:** `./gradlew assembleDebug testDebugUnitTest lintDebug validateDebugScreenshotTest --console=plain` exited 0 in 19 s → `evidence/baseline-gate.log`. It ran mostly from cache: 36 tasks executed, 635 up to date.
- **Forced test run:** `./gradlew testDebugUnitTest --rerun --console=plain` exited 0. It ran **1509 tests with 0 failures, 0 errors and 0 skipped** across 11 modules:

  | Module | Tests |
  |---|---|
  | app | 137 |
  | database | 60 |
  | designsystem | 128 |
  | diagnostics | 165 |
  | shizuku-ipc | 248 |
  | telemetry-engine | 390 |
  | dashboard | 51 |
  | health | 87 |
  | sleeplab | 76 |
  | toolkit | 78 |
  | xray | 89 |

- **`:app` lint:** 0 errors and 9 warnings:
  - `OldTargetApi`
  - `AndroidGradlePluginVersion` (Gradle 9.8.1)
  - `GradleDependency` (androidx.annotation 1.3.0)
  - 5× `NewerVersionAvailable` (Kotlin 2.4.21, hiddenapibypass 6.2, robolectric 4.17)
  - `NotShrinkingResources`, which matches N-27

  None indicates a logic defect.
- **Secret scan:** an `rg` secret-pattern scan over `app core feature build-logic gradle` and the root build files found nothing. No keystore, `.env`, `local.properties` or `google-services.json` file is tracked.
- **Worker-level evidence** (these were inspections, not builds):
  - `javap`/`dexdump` of the Shizuku api/provider 13.1.5 AARs, compose-ui 1.12.1, and the API 35 emulator image's `services.jar`/`framework.jar`
  - SDK sources for android-35/36/37
  - AOSP `frameworks/base` sources fetched read-only by the validators
  - `aapt2` and R8 mapping/seeds/usage inspection of the existing release build, built at 10:02 today with no source newer than it
- **Device: redroid** at `localhost:5555` (Android 15 / API 35, Shizuku 13.5), with the user's permission:
  1. A read-only `dumpsys batterystats --history` → `evidence/redroid-history-before.txt`.
  2. A root `sqlite3` read of the installed **release 0.2.3** database → `evidence/redroid-release-db-history.txt`.
  3. `adb uninstall` of the release build, then `installDebug` of `5c0b83d`.
  4. Debug probes `PROBE_CAPTURE`, `PROBE_INSIGHTS` and `PROBE_DIFF`, around a 40 s screen-off/on cycle → `evidence/redroid-w4-1-db.txt` and `evidence/redroid-w7-1-metrics.txt`.
  5. A 13-minute background then warm reopen of the Cockpit, with screenshots before and after a tab switch, plus a root `sqlite3` read of the snapshot rows → `evidence/redroid-w8-1-timeline.txt`, `evidence/redroid-w8-1-A-after-reopen.png`, `evidence/redroid-w8-1-B-after-tab-switch.png`.

  The device now has the debug build. The release install's data exists only as the evidence extract.

### Deviations, unavailable checks and gaps
- `bash .claude/kit/gradle-check.sh` was not used, because it writes logs under the guard-protected `.claude/kit/logs`. Raw `./gradlew … --console=plain` was used instead, with output in the scratchpad and the exit code captured (`*.rc`).
- No release/R8 build was run by the coordinator, because it needs signing properties that are deliberately unread. W11 inspected the existing release outputs instead.
- **Devices not available:**
  - No API 31/32/33 device or image. The N-03 premise rests on AOSP `android12`/`12L`/`13` source.
  - No Sui device (N-08).
  - No physical device with a real fuel gauge. The N-05 magnitudes are reproduced arithmetic only.
  - No reboot, shutdown or real charge cycle on a device. The N-01 RESET semantics come from AOSP source plus the redroid history. The claim about START's wall time was refuted from AOSP source and is not reported.
- **Reproduced on redroid:** N-01 (the screen-on stretch is counted as unknown), N-02 (a 10 ms "since screen off" window) and N-04 (the warm-return timeline is stale until a tab switch).
- **Not reproduced on a device:** N-11 (race), N-07 (unsent writes), N-06 (app-op persistence after a role/accessibility grant) and the cold-start variant of N-04.
- The project has no `androidTest` source set and no Compose UI or gesture tests, so component lifecycles and pointer handling are covered by code trace only.
- No dependency CVE scanner was available offline; only lint's version checks ran.
- Screenshot validation ran inside the cached gate and was not re-rendered.
- **Partially read:** many test bodies (listed per worker), `diagnostic_dictionary.json` entry bodies (ids, match types and actions were enumerated), screenshot-test sources, and protobuf field-level parity beyond the parsing fixtures.
- Scores are qualitative judgments, not measurements. Coverage is not exhaustive.

## 5. Audit isolation and delta

**Classification: `fresh workers; coordinator exposed`.** Several workers also had documented exposure incidents (listed below), so the blindness claim is limited accordingly. This is not a claim of absolute blindness.

### Source state and guard evidence
- HEAD `5c0b83d375dbf4360e8035a7cdc0c0de20d23326` on `main`. The application source matched HEAD. The uncommitted changes in the worktree were already present before the audit began and are all kit/config files: `.claude/settings.json`, `.codex/android-kit/{conflicts.json,manifest.json,manifest.txt,scaffold.py}` and an untracked `.codex/android-kit/resolutions.json`. The audit did not touch them, and the guard hashed them at `begin`.
- `audit-guard.py begin` created session `run-ax4ji1gb`. It archived the existing `AUDIT_REPORT.md` unread as `previous-report.md` (prior SHA-256 `99c33e531fb06af48e5632cb5c6c501942964376710c008c2fea2f333e34290f`) and hashed 811 protected paths.
- `audit-guard.py check`, run before this draft was written, printed "Protected files and original report unchanged" (rc 0).
- The audit wrote only `draft.md` and `evidence/` in the session directory, plus scratch files in the session scratchpad. On the redroid test device it replaced the user's release install with a debug build (see Coverage ledger).

### Input exclusions (deferred until after freeze)
Neither the coordinator nor any worker or validator opened the following:
- `AUDIT_REPORT.md` / `previous-report.md`
- `PROGRESS.md`, PROJECT/MAP files, `.claude/.codebase-info/*`
- `docs/plans/*`, `TEMPLATE.md`
- the Sidequest board, `git log`/`blame`/`show`, and any history- or churn-based prioritization

Exceptions are listed under the contamination ledger. Workers were told not to read keystores, `local.properties`, `.env*` or `~/.gradle/gradle.properties`.

### Fresh-context availability
Fresh contexts were available: all 11 subsystem workers (W1–W11) and 3 validators (V1–V3) ran as `audit-reviewer` agents with no inherited transcript. Each brief carried only the neutral protocol, the pinned revision, its bounded source paths, the shared tooling-baseline outcome and the Android/Kotlin/Shizuku checklist. Validators received the new candidate claims and raw source/device evidence only; author confidence and prior IDs were not passed.

### Contamination ledger
**Coordinator (this session):**
1. *At session start, from the harness:*
   - CLAUDE.md, which refers to PROGRESS.md "Baseline" counts.
   - The auto-memory index ("3 side issues from the 0.2.1 audit session", titles only).
   - Git-status commit subjects naming "audit remediation F-01..F-25 (0.2.3)", "F-18 follow-up – refuse a doze apply when the doze card is not loaded" and "F-01 follow-up – surface apps a 0.2.2 restore left at default".
2. *SessionStart hook, at session start and again after each context compaction:* injected `.claude/.codebase-info/INDEX.md`. It names earlier remediation themes (A-01–A-24, F-01–F-25), for example:
   - UID ownership proof and the ±2 s session predicate
   - the history horizon with a 2-minute grace
   - `AnalysisSession.recompute()`
   - spent-stub dead-binder recovery
   - chart handle slop and step-based tick labels
   - app-op allow writes and user-0 toolkit containment
3. *Source comments and test names* carrying earlier review codes: F-16..F-20, SQ-xx, H-xx, A-03.
4. *Worker reports relaying accidental leaks* (worker items 3–4 below). W10's report quoted one prior dead-code line in full; W9's report named only topic words (`modeOf`, `gate(pkg)`, `mayBeActive`, `isActive`).

The coordinator wrote worker and validator briefs from source topology and new candidate claims only, never from the items above.

**Workers and validators:**
1. *All workers:* the same harness context as coordinator item 1 (CLAUDE.md, memory index, commit subjects), plus source comments with earlier review codes.
2. *W5, W6, W8, W9:* a post-compaction SessionStart hook injected INDEX.md mid-investigation. All four declined to open map files and report that their findings came from source traces.
3. *W9:* an accidental repo-wide `rg`, caused by shell backtick expansion, previewed a few lines of `AUDIT_REPORT.md` (around lines 70, 160 and 179–180), `TEMPLATE.md` and `docs/plans/phase-2.md`. The full saved output was not opened. **W9-1, reported here as N-06, is therefore classified blindness-limited**, because the leaked lines touch the toolkit gate area.
4. *W10:* one `rg` printed `AUDIT_REPORT.md:347`, a prior dead-code list.
   - Items W10 verified only after that exposure (`dynamicAccent`/`withAccentFrom`, `LocalReducedMotion`) are **excluded** from this report.
   - Items in this report's dead-code list that also appear on that line are flagged there. W10 states it found `chart_a11y_cursor` and the String `TimeSliceBanner` overload before the exposure. W6 found the `ChargingCaptureScheduler.arm` parameter independently, with no exposure.
5. *Validators V1–V3:* none opened prior-assessment material. Their own exposure notes:
   - **V1:** the harness commit subjects (item 1) and source comments with review codes only.
   - **V2:** the same injected harness context and source comments only.
   - **V3:** one `find . -name '*stability*'` printed three file names under `.codex/android-kit/…` (`compose-stability.md`). The files were not opened. Separately, one repo-root `rg` had its output filtered to application paths before V3 read it.
6. *Coordinator, after the last context compaction:* the hook re-injected INDEX.md (coordinator item 2) once more. By then N-01–N-28, the dead-code list, the roadmap and the coverage ledger were already written. Only this section, the executive summary and the N-04 device result were added afterwards, all from evidence already on file.

### Freeze evidence
This section is written before `audit-guard.py freeze`; the guard appends the freeze hash. No post-freeze material is included above. The comparison with the earlier report appears only in the separately labeled **Audit Delta** that the guard appends at publication.


## Audit Delta (post-freeze comparison)

Everything in this section was written **after** the freeze. Nothing here changes the frozen findings N-01–N-28 above.

### Boundary, integrity and inputs
- **Independent report frozen before any prior material was opened.** Guard session `.git/audit-history/run-ax4ji1gb`. `freeze.json` records the SHA-256 of `independent.md` as **`79495114cd0766e4f97e4b8f3719e421fa1d6414c37f875061e22a357b1ebb97`**, and `cmp` shows it is byte-identical to `draft.md`. `audit-guard.py check` ran twice before the freeze, and both times printed "Protected files and original report unchanged".
- **Prior report:** `previous-report.md`, the guard's unread archive of `AUDIT_REPORT.md` (SHA-256 `99c33e53…2290f`).
  - It is the audit of `df8a909` with findings **F-01–F-25**, its own delta against the older A-01–A-24, and a remediation log marking all 25 F findings FIXED in release 0.2.3.
  - The A-series was classified in that report's delta and is not re-classified here.
  - ID namespaces: `N-xx` = this audit, `F-xx` = the previous report, `A-xx` = the one before it.
- **Post-freeze inputs and commands:**
  - Reading `previous-report.md`.
  - `git merge-base --is-ancestor` for all 30 remediation commits the prior log names. All are ancestors of HEAD `5c0b83d`.
  - `git log --oneline df8a909..HEAD` (36 commits) and `git diff --stat df8a909 HEAD -- app core feature build-logic gradle` (103 files, +8973/−3792).
  - Three `git log -S` pickaxes (dead-code provenance, below).
  - Scoped `rg` over `{app,core/*,feature/*}/src/main` at every prior fix site.
  - One redroid measurement for F-09: `dumpsys gfxinfo` → `evidence/redroid-delta-f09-gfxinfo.txt`, plus a screenshot.

  The following were not needed for the comparison and were not read: PROGRESS.md, `.claude/.codebase-info/*`, `docs/plans/*`, the board and transcripts. No application file, skill or kit file was changed.
- **Code changed since the frozen source:** none. HEAD `5c0b83d` is the audited revision. The last three commits after the remediation are a docs, version-bump and map refresh.

### Classification method
Findings are matched by root cause, not by ID. The statuses mean:
- **independently rediscovered:** a frozen N-finding has the same root cause as the prior finding. Here every such case is a *residual*: the prior fix is in source, but the same root survives in a sibling path, branch or feature that the fix did not reach.
- **fix present in source; verification limited:** the named repair is visible at the cited lines, and the shared 1509-test baseline passed. The old reproduction was not re-run.
- **verified resolved within stated checks:** a post-freeze check exercised the old failure and it did not occur, within the stated limits.

Absence from the frozen findings is never treated as proof of resolution.

### Per-finding comparison with F-01…F-25

| Prior finding | Classification | Current evidence and relation to new findings |
|---|---|---|
| **F-01** HIGH: appop "Allow/Restore/Reset all" writes `default` | fix present in source; verification limited | `ipc/toolkit/Toolkit.kt:42` `ALLOW(3, "allow", writable = true)`. `ToolkitParsers.kt:11,39` maps "No operations." to the op's default, `allow`. Not re-run through the Toolkit UI (the prior log notes the redroid picker lists only protected apps). Same "undo integrity" theme as new N-06/N-07, but a different root. |
| **F-02** HIGH: per-app attribution dropped across processes/package changes | fix present in source; verification limited | `SnapshotDiffer.kt:232-236`: `comparableScope = sameNamespace && covers(uid)` plus owner fingerprints. Redroid has no power model (every UID reads 0 mAh), so it was not observable there. |
| **F-03** MEDIUM: resolver self-pairs a snapshot | **independently rediscovered** (residual: N-12) | `ReferenceResolver.kt:145,151,154`: `closestReference` now filters with `precedes(end)`. The marker path at `:64-65` (`boundary.markerSnapshot ?: closestReference(...)`) bypasses `precedes`, so a marker selected right after "Mark now" still self-pairs (N-12). The prior report's own proposed fix was gated on `boundary.markerSnapshot == null`, so the narrowing was in the fix design. |
| **F-04** MEDIUM: time past the last ingest treated as known | fix present in source; verification limited (timeline half observed on device) | `IntervalMetrics.kt:51,62-64` (`knownStateEndWallMs`, `advanceUnknownTo`), `HistoryHorizon.kt:16-25`, `TimelineBuilder.kt:47-49`. The pre-freeze N-04 device run shows lanes closing at the horizon when the gap exceeds the 2 min grace, and extending within it (`evidence/redroid-w8-1-*`), as the fix intends. **Interaction with N-04:** with the horizon in place, a timeline loaded before the app-open ingest now shows *missing* data instead of extrapolated state. N-04 is the load-ordering defect behind that. |
| **F-05** MEDIUM: toolkit hard-wired to user 0 | fix present in source; verification limited | `ToolkitExecutor.kt:281` `if (userId != 0) return deny(ToolkitCode.UNSUPPORTED_USER)`, surfaced in `ToolkitText.kt:129` and `XRayText.kt:154`. No work-profile run. |
| **F-06** MEDIUM: custom doze original lost via Balanced→Standard | fix present in source; verification limited | `ToolkitActions.kt:1140-1143` (`mayBeActive = !sameState(…, chainOriginal)`), `:1173-1176` (`isActive = norm(current) == norm(afterRaw)`), as proposed. |
| **F-07** MEDIUM: Cockpit live gauge freezes when reads stop | **independently rediscovered** (residual in sibling feature: N-17) | Cockpit is fixed: `CockpitViewModel.kt:41,137` (`LIVE_STALE_MS`, staleness `delay`). Health has the same root: `liveActive = liveJob != null` with no freshness rule and no Shizuku state, so the "Live" badge and the growing charge time persist with no readings (N-17). N-17's fix is "the same rule Cockpit uses". |
| **F-08** MEDIUM: handle captured on touch, no slop | fix present in source; verification limited | `ScrubGestures.kt:49-51` (radius = `HandleHit / 2`, `touchSlop`), `:73` `PressedOnHandle`. There are no Compose gesture tests (N-28). Adjacent new N-21: an out-of-window selection's handles are drawn clamped but hit-tested unclamped. Same component, different root. |
| **F-09** MEDIUM: endless ACTIVE halo animation | **verified resolved within stated checks** | `ShizukuPulse.kt:31-39,84-86`: a bounded `Animatable` with `repeat(CYCLES = 3)` × 2400 ms. **Redroid:** idle Cockpit scrolled to top with "Binder active" shown, measured 15 s after the pulse window. `dumpsys gfxinfo` gave **0 frames rendered in 10 s** on a 15 Hz panel; an endless transition would give about 150. Limits: one device, 15 Hz, 10 s window, header visible. |
| **F-10** LOW: AnalysisSession can't be recomputed | **independently rediscovered** (residual of the same root: N-09) | `AnalysisSession.kt:101` adds `recompute()`. But the root the prior trace named ("Cockpit and Sleep Lab VMs collect for their whole lifetime") is unchanged. `recompute` is wired only to Retry and to `Debounced`/`Failed` *when `userWaiting && visible`*, so a return after a failed or debounced on-enter refresh still shows a frozen interval (N-09, which also pins it with `CockpitViewModelTest.kt:384-411`). |
| **F-11** LOW: refresh-failed effects outlive the visit | fix present in source; verification limited | `CockpitViewModel.kt:153` and `HealthViewModel.kt:100` drain with `tryReceive()`. `CockpitRoute.kt:50` and `HealthRoute.kt:72` use `repeatOnLifecycle(STARTED)`. |
| **F-12** LOW: timeline reload cancels a gesture | fix present in source; verification limited | `ScrubGestures.kt:25-48`: `pointerInput(state, inputs)` reads the window through `rememberUpdatedState` holders, and the `TimeScale` is built at Down. |
| **F-13** LOW: multi-day hypnogram ticks show dates only | fix present in source; verification limited | `SleepHypnogram.kt:307` `niceTimeTickSet(...)` picks the formatter from the step. Adjacent new N-22: the hypnogram's *accessibility* `clockFormatter` adds dates only at ≥ 48 h, so 24–48 h windows lose them. Same component and "threshold instead of content" theme, opposite window range. |
| **F-14** LOW: single-point series draws nothing | fix present in source; verification limited | `ScrubChartRenderer.kt:261` `if (points.size == 1)`. Screenshot validation passed (cached). |
| **F-15** LOW: timed-out force-stop reported FAILED | **independently rediscovered** (residual in sibling branch: N-15) | `ToolkitExecutor.kt:153` `if (!write.ok) return writeFailed(write, before)` fixes the timeout branch. The read-back branch at `:157`, `else -> return ToolkitResult.failed(ToolkitCode.PARSE, before = before)`, still breaks the `UNVERIFIED` contract after a successful stop (N-15). |
| **F-16** LOW: `awaitServiceUse` gives up on a racing Ready | fix present in source; verification limited | `ShizukuManagerImpl.kt:249-260`: a three-outcome `acquireServiceUse()` decided under the lock, then `mutableState.first { it !is NotRunning }`. |
| **F-17** LOW: dead binder kept after slow-shutdown rebind | fix present in source; verification limited | `ShizukuManagerImpl.kt:126,233` (`isBinderAlive` checks), `:266-271` (`linkToDeath` backstop). **Adjacent new N-11:** the *unexpected*-death path still lacks the unbind path's fence. This is the third generation of the same handover-fencing design: A-01 (callback path) → F-17 (timeout path) → N-11 (unexpected death). |
| **F-18** LOW: doze CAS uses an execute-time read | fix present in source; verification limited | `ToolkitActions.kt:77,116` carry `expectedBeforeRaw` to `gateway.applyDozeProfile`. Follow-up `729876f` refuses an apply when the card is not loaded. New N-16 (OpenApp drops a pending plan) is a different root in the same flow. |
| **F-19** LOW: active overrides computed twice per load | fix present in source; verification limited | `AppContainer.kt:245-255`: `installedApps()` no longer reads overrides (comment at `:253`). It left dead code behind (see the dead-code delta below). |
| **F-20** LOW: detail sheet stuck on its spinner | fix present in source; verification limited | `ToolkitViewModel.kt:213-226,246`: on failure the doze section and controls become `Unavailable(problem)`. |
| **F-21** LOW: `lastOfEachKindBefore` returns `wallMs = -1` rows | fix present in source; verification limited | `Daos.kt:246` `WHERE wallMs >= 0 AND wallMs < :beforeWallMs`. |
| **F-22** LOW: session matching exact in places, ±2 s elsewhere | **independently rediscovered** (residual at a sibling call site: N-13) | A shared `BatteryStatsSession.sameStart` (`BatteryStatsSession.kt:14-25`) is used by `SnapshotDiffer.kt:28,145,171-202` and `SnapshotCapturer.kt:130`. `RoomSnapshotStore.kt:163` `historySessions()` still does `.groupBy { it.statsStartMs }` exactly, so a backward clock step splits a session and the capacity estimator loses a charge segment (N-13). |
| **F-23** LOW: overlapping captures fail the identity proof | fix present in source; verification limited | `UidIdentityReader.kt:22-23,112-129`: a `Mutex` gate with `withTimeoutOrNull`, and captures take turns within their phase budgets. |
| **F-24** LOW: screen-time tile uses history while part is unknown | fix present in source; verification limited | `CockpitMappers.kt:121` (`takeIf { metrics.unknownMs == 0L && metrics.discontinuities.isEmpty() }`). **Interaction with N-01:** after a full-charge unplug RESET, the walker books the screen-on stretch as unknown (device-confirmed), so this guard then also hides the history fallback for that interval. |
| **F-25** LOW: "most prominent" compares mixed units | fix present in source; verification limited | `InsightOrder.kt:7-33`: a fixed `rulePriority` across rules, with magnitudes ranked only within a rule. |

The prior report's two **rejected candidates** (the "Deep sleep" tile pairing, and job re-arm ordering / main-thread `refresh()`) have no counterpart among N-01–N-28.

**Summary for the 25 prior findings:**
- **5 independently rediscovered** as same-root residuals: F-03→N-12, F-07→N-17, F-10→N-09, F-15→N-15, F-22→N-13.
- **1 verified resolved within stated checks:** F-09.
- **19 fix present in source; verification limited.** F-04 and F-17 have adjacent new findings (N-04, N-11), and F-08, F-13, F-18 and F-24 have related ones (N-21, N-22, N-16, N-01).
- **0 still reproducible as originally reported**, and **0 not assessed**.

### New in this audit (no prior counterpart)
- **HIGH:**
  - N-01: RESET semantics in the history walker, interval metrics, timeline and last-charge span.
  - N-02: history boundaries are not reconciled with capture anchors.
  - N-03: bg-dexopt cancel on API 31–33.
  - N-04: the timeline loads before the app-open ingest.
- **MEDIUM:** N-05 (live-current scaling), N-06, N-07, N-08 (Sui), N-10 ("Charging" while draining).
- **LOW:** N-14, N-16, N-18, N-19, N-20, N-23, N-24, N-25, N-26, N-27, N-28.
- N-11, N-21 and N-22 are adjacent to F-17, F-08 and F-13 (above), but have different roots.

The new HIGH findings sit in areas the previous report did not cover: the battery-stats RESET record, capture-time and history-time reconciliation, per-API privileged argv, and refresh/timeline ordering. Nothing in this comparison shows them to be regressions of fixed items. Since the last remediation commit `729876f` (F-18 follow-up), the only commits are `d28bf2e` (AUDIT_REPORT.md), `cf521e2` (version bump in `app/build.gradle.kts`) and `5c0b83d` (map refresh). Their introduction history was not traced with `git blame`.

### Dead-code delta (prior §3 vs frozen §3)
- **Independently rediscovered, still present:**
  - `TelemetryDao.hourly` and the write-only `telemetry_hourly` table
  - `SnapshotDao.deleteById`
  - the single-row `insertAvailability`
  - `DumpTarget.POWER_PROTO`/`DEVICEIDLE_TEXT`
  - the hidden-api-stub `POWER_COMPONENT_*`/`PROCESS_STATE_*` constants
  - `KernelUnits.uaToMa`
  - both `DumpReader.readAll` overloads
  - `PowerCalibration.cellCount`
  - `ToolkitDependencies.holdBinding()`
  - `ToolkitActions.release()`/`ToolkitGateway.release()`
  - † `chart_a11y_cursor`, † the String `TimeSliceBanner` overload, † the `ChargingCaptureScheduler.arm(…, reArmFromJob)` parameter. The † items are exposure-flagged in §5.
- **Still present (delta-only).** A post-freeze `rg` over main sources found no production reader for:
  - `SnapshotStore.byId` and `SnapshotStore.latestByTrigger`: only `RoomSnapshotStore`'s own implementations call the DAO (`RoomSnapshotStore.kt:91,133`).
  - `SnapshotStore.latest`.
  - `ShizukuManager.stop()`.
  - `SnapshotDiff.levelDropPct`.
  - `SliceInfo.gapMs`: set at `XRayMapper.kt:71` and declared at `XRayModels.kt:96`, but not read by any X-Ray UI file.
  - `HealthInputs.loadedAtMs`: written at `HealthViewModel.kt:202`, never read.
  - `InspirationTheme(dynamicAccent)`/`withAccentFrom`: no caller enables it.
  - `LocalReducedMotion`: never `provides`d.

  The last two were deliberately excluded from the frozen list because of W10's exposure.
- **Not assessed:** `SnapshotDiff.durationMs`, which the prior report called debug-only; its name is too common for a reliable `rg`. Also the prior "every analysis reloads the full retained history" performance note (`IntervalAnalyzer.kt:40`), which was not re-measured.
- **Left behind by the F-series remediation** (post-freeze `git log -S`):
  - `niceTimeTicks` became test-only when F-13's `75edef5` introduced `niceTimeTickSet`.
  - `AppEntries.build(restricted = emptySet())` and `AppEntry.restricted` came from F-19's `da2dbfd`.
  - The 17 orphaned screenshot references predate the F-series (`6e5ddee` A-16 and `acec85a` SQ-81).

### Pattern worth noting for remediation
The previous delta found 3 fixes applied at one call site instead of at the shared root: A-21, A-24 and A-01. This audit, without seeing that note, found **5 more same-root survivors of the F-series remediation**, plus a third-generation case in binder handover:

| Prior fix | Same root survives in |
|---|---|
| F-03 | the marker path |
| F-07 | Health |
| F-10 | the collector lifetime |
| F-15 | the read-back branch |
| F-22 | `historySessions()` |
| A-01 → F-17 | N-11 |

In two cases the narrowing was already in the prior report's *proposed fix*:
- F-03's fix was gated on `markerSnapshot == null`.
- F-10's fix bolted `recompute()` onto user-waiting paths instead of re-scoping the collector its own trace blamed.

For `/audit-remediate`, each fix ticket should:
- `rg` for sibling sites of the same predicate, branch or feature (for example every `groupBy { it.statsStartMs }`, every `liveActive`, every `ToolkitResult.failed` after a successful write);
- have the reviewer check that the *root* named in the trace is removed, not only the reported path.

### Scorecard and baseline comparison

| Area | Previous | This audit |
|---|---|---|
| Architecture | 8 | 7 |
| Correctness | 6 | 5 |
| Concurrency | 8 | 7 |
| Security | 9 | 8 |
| Performance | 7 | 7 |

Both are qualitative judgments by different audit runs. The lower scores reflect the new HIGH defects listed above and the same-root survivors, not a measured regression.

The previous remediation's final gate and this audit's baseline match:
- 1,509 unit tests, 0 failures, errors or skips (re-executed here with `--rerun`)
- the same 9 `:app` lint warnings, 0 errors
- screenshots validated (cached here)

### Post-freeze observation (not investigated)
During the F-09 check, the redroid Cockpit showed "Nothing to analyse yet" for **Since unplugged**. The pre-freeze `PROBE_INSIGHTS since_unplugged` had resolved a 6 s window. Redroid's emulated battery (level 0, `plug=none` with `+plugged` in history) makes unplug semantics unreliable there. This was not investigated and is not added to the findings.
