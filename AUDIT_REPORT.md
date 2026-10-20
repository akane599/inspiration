# Inspiration — Whole-Repository Audit (independent findings)

*Audit date: 2026-10-20 · Source: `dbd0347f6139d98422c669cb3fdde3b6e1ee962b` (`main`) · Working tree: only `.claude/settings.json` (modified) and `.claude/kit/resource-policy.json` (untracked), both pre-existing and outside application code · Mode: standalone review mode (Sidequest disabled), read-only*

> Scores are qualitative judgments, not measurements. This audit does not claim exhaustive coverage: the redroid device has no real battery, sensors or overnight history. Several behaviors were therefore established by code tracing and framework bytecode inspection, not by on-device reproduction. Each finding states its verification level.

---

## 1. Executive summary and system health scorecard

| Dimension | Score | Justification |
|---|---|---|
| Architecture and modularity | **8/10** | Clean module graph, a narrow AIDL privilege boundary and deliberate validity modelling. The validity model is too strict in places and silently discards good data (R-01, R-02), and capture policy and rule gates disagree (R-03). |
| Correctness and resilience | **7/10** | No crash-prone core paths found and failure containment is consistent. There are three MEDIUM correctness gaps in what the analytics show (missing apps, a night interval that is never evaluable) and several UI state-machine outcome losses. |
| Concurrency and resource safety | **8/10** | Binder deadlines, use counts, NonCancellable write-ahead logging and cancellation rethrow are all done well. Residual issues: a Shizuku binder IPC on Main under a shared lock, an FGS start/stop race, and in-flight UI work not owned by a job. |
| Security and privilege hygiene | **8/10** | Every AIDL transaction checks the caller UID. Commands are a fixed argv with no shell, package names are regex-validated, writes are pinned to user 0, and protected packages are gated. Residual issues: the completeness signal of command output that feeds the protected-package gate, and tapjacking hardening. |
| Performance and efficiency | **8/10** | Polling is lifecycle-scoped, charts decimate data, and there are no infinite animations. Every analysis scans the full 30-day history table. |

**Top 3 systemic risks**
1. **"Proof at both endpoints" validity drops legitimate per-app data with no visible trace.**
   - Apps first seen after the reference (R-01) and apps whose AlarmManager stats were reset by a force-stop (R-02) are marked `valid = false`.
   - All consumers then filter them out of X-Ray, every rule and Sleep Lab.
   - `skippedInvalidUids` is never surfaced in the UI, so totals and shares are silently computed over a subset.
2. **Capture policy, interval resolution and rule gates are designed separately and contradict each other.**
   - The "since screen off" (night) interval is bounded by LIGHT screen captures, and its end anchor lies after SCREEN_ON. Its rule gates require FULL data with zero screen-on time, so it can never produce Sensor Leak, Night Vampire or Awake Violation results (R-03).
   - Cockpit's mAh/h divides whole-interval counters by partial history time (R-09).
   - None of these can be exercised on redroid, which has no battery or sensors, so the test fixtures encode the assumptions rather than the real shapes.
3. **UI-side ownership of in-flight work.**
   - Toolkit ties result delivery to a planning generation that a lifecycle stop bumps (R-04).
   - The Cockpit marker job is tracked only in a sheet object that the UI discards (R-12).
   - The status FGS toggle has no pending-start state (R-06).
   - The Shizuku bind happens synchronously on Main under a lock that IO threads hold across binder calls (R-08).

---

## 2. Confirmed findings (most severe first)

Severity and verdict come from a separate validation pass (fresh contexts) over each reviewer's candidates. "CONFIRMED" means the code path was re-traced independently. "PLAUSIBLE" means the code path was confirmed, but a framework or timing precondition could not be observed on the device.

### R-01 [MEDIUM] Application UIDs absent from the reference snapshot are always invalid and disappear from X-Ray, the rules and Sleep Lab
- **Category:** Logic Bug · **Verdict:** PLAUSIBLE (code path confirmed; completeness of Android 15 `BatteryUsageStats` per-UID output not compared on the device)
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/SnapshotDiffer.kt:224-235, 258-260, 302-304`
  - Capture side: `.../telemetry/capture/SnapshotCapturer.kt:136, 156-187`
  - Consumers: `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayMapper.kt:63`, `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/rules/DiagnosticRuleEngine.kt:30,49`, `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabMappers.kt:149,195`
  - Test locking it in: `core/telemetry-engine/src/test/.../diff/SnapshotDifferTest.kt:68-89`
- **Root cause and trace:**
  - `knownOwners = comparableScope && previousFingerprint != null && currentFingerprint != null`; `identityValid = !applicationUid || (knownOwners && previousFingerprint == currentFingerprint)`.
  - A UID with no row in `ref` has `previousFingerprint == null`, so `identityValid == false`, and line 304 sets `valid = identityValid && countersValid`, which is false.
  - The comments say this is deliberate ("Missing observations are not zero baselines", line 208). The premise is wrong for one important case:
    - The capturer stores a UID identity scope only when `usage.truncatedUids == 0 && scope.isKnown()` (SnapshotCapturer.kt:136).
    - The usage list is the unfiltered `uidBatteryConsumers` (`BatteryStatsClient.kt:257-261`, `BatteryUsageAdapter.kt:134`), cut only by the parcel budget, which sets `truncatedUids` (`UsageParcelBudget.kt:19-26`).
    - So a same-session FULL `ref` with a known scope and `truncatedUids == 0` does prove that a missing UID had no accrued counters. All of its usage lies inside the interval, regardless of which package owns the UID now.
  - Reachability:
    - `SinceUnplugged` uses the first FULL snapshot after the unplug as `ref` (`ReferenceResolver.kt:144-160`), and POWER_DISCONNECTED takes a FULL capture (`CapturePolicy.kt:31-35`).
    - After a stats reset only the UIDs active at that moment are present. On redroid the stored FULL snapshot held 30 application UIDs against 90 installed (`pm list packages -U`).
- **Failure scenario:** The user unplugs after an overnight charge. Every app that first draws power after the unplug (most apps) is hidden from X-Ray "Since unplugged", from Night Vampire, Sensor Leak, Modem Drain and Awake Violation, and from Sleep Lab culprits. The remaining apps' percentages are computed over a smaller total. Nothing in the UI says rows were skipped: `skippedInvalidUids` (`Insight.kt:91`) has no non-debug reader.
- **Concrete fix (`SnapshotDiffer.diffUids`):**
  ```kotlin
  val refMembershipComplete = ref.kind == SnapshotKind.FULL && ref.sourceAvailable("usage") &&
      ref.truncatedUids == 0 && ref.uidIdentityScope?.isKnown() == true
  val provenZeroBaseline = previous == null && current != null && comparableScope && refMembershipComplete
  val identityValid = !applicationUid ||
      (knownOwners && previousFingerprint == currentFingerprint) ||
      (provenZeroBaseline && currentFingerprint != null)
  ```
  Update `SnapshotDifferTest.kt:68` to expect the newly observed UID to be valid when the reference membership is complete, and keep it invalid when `truncatedUids > 0` or the scope is unknown. Separately, surface the skipped-row count in X-Ray ("N apps hidden: no baseline").

### R-02 [MEDIUM] A force-stop resets AlarmManager per-package stats, which invalidates the app's whole delta, energy included
- **Category:** Logic Bug · **Verdict:** CONFIRMED (code path); the AlarmManagerService reset on `ACTION_PACKAGE_RESTARTED` comes from AOSP source knowledge, because a force-stop on the device would change state
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/SnapshotDiffer.kt:37-39, 219, 293-304, 480-491`
  - Data source: `.../capture/SnapshotCapturer.kt:155, 263-272`
  - Trigger: `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/ToolkitExecutor.kt:153-171` (`am force-stop`), or a force-stop from Settings
- **Root cause and trace:**
  - `alarmDumpWakeups` holds AlarmManager's in-memory per-package `wakeupCount`.
  - `UninstallReceiver` removes a package's `BroadcastStats` on `ACTION_PACKAGE_RESTARTED`, which a force-stop sends.
  - `diffUidAlarms` therefore yields negative counts, `hasNegative` (line 490) includes `alarmDumpWakeups.any { it.count < 0 }`, and line 304 sets `valid = false` for the whole UID, discarding `totalMah`.
  - `alarmDumpValid` only checks boot, session and source presence, so it cannot detect a per-package reset.
- **Failure scenario:** The user sees an app draining in X-Ray "Since unplugged" and force-stops it (the app offers this action). On the next refresh the app vanishes from X-Ray and from every rule for any interval spanning the stop. The drain that prompted the action is erased from the record.
- **Concrete fix:**
  ```kotlin
  val dumpReset = delta.alarmDumpWakeups.any { it.count < 0 }
  val checked = if (dumpReset) delta.copy(alarmDumpWakeups = emptyList()) else delta
  if (dumpReset) problems += "uid_alarm_dump_reset:$uid"
  val countersValid = !hasNegative(checked)
  checked.copy(valid = identityValid && countersValid)
  ```
  Update the test "negative alarm dump wakeup delta invalidates only that uid delta" to expect energy to be kept and the alarm-dump detail to be dropped.

### R-03 [MEDIUM] The "since screen off" (night) interval can never be evaluated by Sensor Leak, Night Vampire or Awake Violation
- **Category:** Logic Bug / Architecture · **Verdict:** CONFIRMED (no real overnight data on the device)
- **Location:**
  - Capture policy: `app/src/main/kotlin/com/akane/inspiration/capture/CapturePolicy.kt:26-29`, locked by `app/src/test/.../capture/CapturePolicyTest.kt:13-20`
  - LIGHT shape: `core/telemetry-engine/.../capture/SnapshotCapturer.kt:239-254`
  - Resolution: `.../diff/ReferenceResolver.kt:60, 64, 83, 144-160, 218-236`
  - Session check: `.../diff/BatteryStatsSession.kt`
  - Gates: `core/diagnostics/.../rules/DiagnosticRuleEngine.kt:53-56, 59-67, 182-190`
- **Root cause and trace:**
  1. Screen broadcasts store LIGHT snapshots, with no `statsDurationMs` and no per-UID data. With no FULL snapshot overnight, `SinceScreenOff` resolves to LIGHT endpoints. `BatteryStatsSession.same()` is then false, the diff has `batteryStats = false`, and every rule reports `battery_stats_invalid` / `no_screen_off_time`.
  2. Even with FULL endpoints, the resolver deliberately ends the window at the first snapshot at or after the history SCREEN_ON (KDoc lines 213-214). That makes `screenOnMs > 0`, so `isScreenOffOnly` (which requires `screenOnMs == 0L && unknownMs == 0L`) fails with `not_screen_off_interval`.
  - The resolver and the rule gate contradict each other. The product spec (`TEMPLATE.md` §4 "Gece Uykusu", §5 Rule 4) makes the night interval the primary home of Sensor Leak.
- **Failure scenario:** Every morning the night view shows no Sensor Leak, Night Vampire or Awake Violation result and no mAh figures. Its headline diagnostic is unreachable in production and the user is not told why.
- **Concrete fix (needs a design decision):**
  - Take a FULL capture without history ingest on SCREEN_OFF and SCREEN_ON when the service is warm, as the power events already do (update `CapturePolicyTest`).
  - Let the screen-off gate tolerate the anchor lag: `metrics.screenOnMs <= CAPTURE_SLACK_MS && metrics.unknownMs == 0L`.
  - Or end `SinceScreenOff` at the last snapshot *before* the history SCREEN_ON.
  - Add a rule-engine fixture shaped like a real SinceScreenOff interval.
  - Note that on API 34+ context-registered broadcasts to a cached process are deferred, so FULL screen captures only help while the process isn't cached. The status FGS keeps it uncached when enabled.

### R-04 [LOW] Toolkit discards the outcome of an in-flight privileged write when the screen stops (rotation, screen-off, tab switch)
- **Category:** Logic Bug · **Verdict:** CONFIRMED
- **Location:** `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitViewModel.kt`
  - `:91-98` `invalidatePlanning` (unconditional `generation++`)
  - `:147-155` `onHidden`
  - `:639-646` `finish`
  - `:593-598`, `:609-612` `askAgain`
  - `:512-516` `ask`
  - Trigger: `ToolkitRoute.kt:44-47` (`LifecycleStartEffect` sends `Hidden`)
- **Root cause and trace:**
  1. `confirmPending` launches `executeContained`. `Hidden` doesn't cancel it (the write is NonCancellable in `ToolkitActions.run`), but `onHidden` → `invalidatePlanning` bumps `generation`.
  2. `finish` then runs `if (prepared.generation == generation) showNotice(...)`, which is false, so the Applied / Rejected / Unavailable notice is dropped.
  3. A STALE outcome goes to `askAgain`, which returns early, so the write is silently not performed.
  - X-Ray handles the same case correctly by matching late results on uid and package (`XRayViewModel.kt:326-341`).
- **Failure scenario:** The user confirms Force stop and locks the phone or rotates during the service call. If the result was `Unavailable(UNVERIFIED)`, `Failed` or `Refused`, nothing is shown. Force stop has no read-back state to contradict the silence. A STALE doze Apply is silently not written. Only the history card (re-collected on Visible) records the outcome.
- **Concrete fix:** keep `generation` for planning and confirmation only, and match results on their target:
  ```kotlin
  private fun onHidden() {
      visible = false; visibleJob?.cancel(); detailJob?.cancel()
      if (planning || pending != null) invalidatePlanning()   // a dispatched write keeps its generation
      _state.update { it.copy(confirmation = null) }
  }
  // finish(): if (stillShown(prepared)) showNotice(prepared.request, notice) — compare target package, as X-Ray does
  // askAgain()/ask() when !visible: showNotice(request, ToolkitNotice.Rejected(Problem.Refused(ToolkitRefusal.STALE)))
  ```
  Add a ViewModel test: a confirmed ForceStop suspended in the gateway, then `Hidden` → `Visible`, completed with `Failed`, asserting that the notice is shown.

### R-05 [LOW] A privileged write that went through, but whose audit update then failed, is reported as "The command failed."
- **Category:** Logic Bug · **Verdict:** CONFIRMED
- **Location:**
  - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt:700-720` (NonCancellable block: only `write` has try/catch; `settle` at :714, RESTORED update at :716), plus `:799, 807, 816, 821, 826, 845` (`audit.update`)
  - `feature/toolkit/.../ToolkitViewModel.kt:42, 548-553`
  - `feature/xray/.../XRayViewModel.kt:40, 426-441`
- **Root cause and trace:**
  - `ToolkitGateway.call` (`ToolkitGateway.kt:133-156`) catches `RuntimeException` and maps `RemoteException` to `Unavailable`, so the only exceptions left after dispatch are Room/SQLite errors from `audit.update`.
  - These reach `attempt {}`, which returns null, which becomes `CALL_FAILED = Rejected(Problem.Failed(null))`, which renders as "The command failed.".
  - `changes.value += 1` (:718) is also skipped, so the history and override flows don't tick.
- **Failure scenario:** Under disk-full or another SQLite error, the user force-stops an app or runs Reset all. The device is changed, but the UI says it failed. For a reset-all failing on item k, items 1..k-1 were restored and are reported as failed too. The row stays PENDING and later reconciles as Done.
- **Concrete fix:**
  ```kotlin
  val outcome = try { settle(...) }
      catch (e: CancellationException) { throw e }
      catch (e: Exception) { changes.value += 1; return@withContext ToolkitOutcome.Unavailable(ToolkitUnavailable.UNVERIFIED) }
  ```
  In both ViewModels, map a post-dispatch exception to the existing "sent, but couldn't be confirmed" notice (`Problem.Unavailable(UNVERIFIED)`). Keep `CALL_FAILED` for planning failures. Update the existing test "a write whose audit row cannot be stored says it failed" only for the pre-dispatch case.

### R-06 [LOW] Turning the status-notification switch on then off before `startForeground` runs crashes the app process
- **Category:** Concurrency · **Verdict:** PLAUSIBLE (crash path confirmed in the device's `services.jar`; no live reproduction, which would need UI input)
- **Location:**
  - `app/src/main/kotlin/com/akane/inspiration/status/StatusNotificationController.kt:19-30` (start :25, stop :26)
  - `.../status/StatusStartDecision.kt:56-67`
  - `.../status/StatusNotificationService.kt:52-71`
  - `app/src/main/kotlin/com/akane/inspiration/AppContainer.kt:305-308`
  - `feature/toolkit/.../ToolkitViewModel.kt:119`, `ToolkitStatusNotification.kt` (`toggleable`, no debounce or busy state)
- **Root cause and trace:**
  - ON calls `ContextCompat.startForegroundService`; OFF calls `appContext.stopService(intent)` immediately. Nothing records that a foreground start is pending.
  - In `ActiveServices.bringDownServiceLocked` (dexdump of the device's API 35 `services.jar`), a service with `fgRequired` still set logs "Bringing down service while still waiting for start foreground". It then posts `SERVICE_FOREGROUND_CRASH_MSG` (69), which crashes the app with "Context.startForegroundService() did not then call Service.startForeground()".
  - This is an async remote exception on Main, so the `IllegalStateException` catch in `StatusStartDecision.sync` cannot intercept it.
  - `CREATE_SERVICE` / `SERVICE_ARGS` wait behind the toggle's frame barrier, while input is dispatched first. A second toggle during main-thread jank (see R-08), or from accessibility or automation, gets there first.
- **Failure scenario:** A rapid double toggle while Main is busy kills the process.
- **Concrete fix (all on Main, so no extra synchronization):**
  ```kotlin
  @Volatile internal var startPending = false
  start = { startPending = true; ContextCompat.startForegroundService(appContext, intent) }
  stop  = { if (!startPending) appContext.stopService(intent) } // the service re-reads shouldRun after startForeground and stops itself
  // StatusNotificationService.onStartCommand, right after enterForeground():
  StatusNotificationController.startPending = false
  ```

### R-07 [LOW] Command output still undrained when the 1 s reader join times out is reported as complete, which weakens the protected-package gate
- **Category:** Security · **Verdict:** PLAUSIBLE (code path confirmed; needs more than 1 s of reader-thread starvation, not reproduced). Note that the two original reviewers disagreed on reachability; the validator confirmed that data written before exit can remain in the pipe after `waitFor` returns.
- **Location:**
  - `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/CommandRunner.kt:124-145` (`awaitExit` / `output`), `:41` (`complete`), `:186` (`READER_JOIN_MS = 1_000L`)
  - Consumers: `toolkit/Inspection.kt:72-100`, `toolkit/ToolkitParsers.kt:110`, `toolkit/ToolkitExecutor.kt:325-329, 351-358` (30 s `PROTECTED_TTL_MS` cache)
- **Root cause and trace:**
  - After the child exits, `awaitExit` joins the reader for at most 1 s and never checks `reader.isAlive`.
  - `output()` sets `truncated = overflow`, which is only set at the size cap, so a cut-off read is `complete`.
  - Each consumer then fails open:
    - `roleHolders("")` returns an empty set.
    - `settingValue("")` returns null, so the setting counts as unset.
    - `deviceAdmins` accepts a section cut mid-way when `truncated == false`.
  - `ToolkitExecutor.gate` then treats the shortened protected set as authoritative and allows `setAppOp(IGNORE)`, `setStandbyBucket(RESTRICTED)` and `forceStop` on a home app, IME, accessibility service or device admin, for up to the 30 s cache lifetime.
- **Failure scenario:** Under severe memory pressure (zram thrash), the reader thread is descheduled for more than 1 s after `cmd role get-role-holders` exits. The home app or IME is then missing from the protected set, and a user restriction that should be refused is allowed.
- **Concrete fix:**
  ```kotlin
  @Volatile private var readerUnfinished = false
  // awaitExit(), after reader.join(READER_JOIN_MS):
  if (reader.isAlive) readerUnfinished = true
  // output():
  truncated = overflow || readerUnfinished
  ```
  Add a `ProcessCommandRunnerTest` case with a fake process whose stdout never reaches EOF after `waitFor` returns true, asserting `!output.complete`.

### R-08 [LOW] The Shizuku UserService bind runs as a two-way binder transaction on Main under `bindingLock`, which IO threads also hold during their own transactions
- **Category:** Performance / Concurrency · **Verdict:** CONFIRMED (two-way transaction codes 12/13 verified by javap on the Shizuku 13.1.5 jars; latency not measured)
- **Location:** `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/ShizukuManagerImpl.kt`
  - `:299-305` `holdBinding`
  - `:484-506` `bindIfRequiredLocked` → `Shizuku.bindUserService` (`addUserService`)
  - `:357-374, 508-537` idle unbind on `applicationScope` (IO) → `removeUserService`, under the same lock
  - `:405-452` `refreshState`
  - Main-thread callers: `feature/dashboard/.../CockpitViewModel.kt:132-135`, `feature/health/.../HealthViewModel.kt:278-281`, `app/.../MainActivity.kt:36`
- **Failure scenario:** When the Shizuku server is slow (system_server under load), opening Cockpit or Health while the 3 s idle unbind fires makes Main wait through two serialized two-way transactions. The result is jank on screen entry, and an ANR if the server stalls. `onServiceConnected`/`onServiceDisconnected` also take the lock on Main. No deadlock was found: Shizuku's callbacks only post to Main.
- **Concrete fix:** decide under the lock, transact outside it, and re-check `serviceGeneration` afterwards. At minimum:
  ```kotlin
  override fun holdBinding(): AutoCloseable {
      synchronized(bindingLock) { bindingHolders += 1 }
      scope.launch(ioDispatcher) { synchronized(bindingLock) { bindIfRequiredLocked() } }
      return BindingHolder()
  }
  ```
  Cache `applicationInfoProvider.load()` with `by lazy`, and move `MainActivity.onStart`'s `refresh()` onto `applicationScope`.

### R-09 [LOW] Cockpit mAh/h divides whole-interval battery-stats counters by history time that may cover only part of the interval
- **Category:** Logic Bug · **Verdict:** CONFIRMED (the reachable window is narrower and more transient than the original worked example)
- **Location:**
  - `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitMappers.kt:120-148` (`mahPerHour` at :145)
  - `core/telemetry-engine/.../interval/IntervalMetrics.kt:58-63, 157-160`
  - `.../store/HistoryHorizon.kt:22-25`
  - `core/diagnostics/.../analysis/IntervalAnalyzer.kt:50-53`
  - `app/.../capture/CapturePolicy.kt:84`
- **Root cause and trace:**
  - The numerators (`dischargeMah`, `dischargeScreenOffMah`) are counter deltas over the whole interval.
  - The denominators (`metrics.screenOnMs/screenOffMs`) count only attributed history time. Time past the horizon goes to `unknownMs`.
  - A broadcast FULL capture (plug or unplug) ingests no history and doesn't tick the store, yet it can be the `now` endpoint on a later recompute. The mapper already hides the screen-time tile when `unknownMs != 0 || discontinuities`, but mAh/h has no such guard.
- **Failure scenario:** With 30 min on and 1.5 h off attributed, 2 h unknown, and 140 mAh screen-off, the Idle tile shows 93 mAh/h instead of 40 mAh/h until a history-ingesting capture lands.
- **Concrete fix:** use the battery-stats durations, which exist under the same validity as the numerators and count on-battery time only (`SnapshotDiffer.kt:446-456`):
  ```kotlin
  activeMahPerHour = mahPerHour(screenOn, sleep?.screenOnMs ?: 0),
  idleMahPerHour  = mahPerHour(screenOff, sleep?.screenOffMs ?: 0),
  ```
  Add a `CockpitMappersTest` case with `unknownMs > 0`.

### R-10 [LOW] Tapjacking guard: partially covered windows pass, and API 31+ overlay hiding is not used
- **Category:** Security (hardening) · **Verdict:** PLAUSIBLE (API semantics; not exercised with a real overlay app)
- **Location:**
  - `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitTouchGuard.kt:96-103, 119`
  - `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayTouchGuard.kt:96-103, 119`
  - Guarded containers: `ToolkitDialogs.kt:84`, `ToolkitSections.kt:105,208,275,283`, `ToolkitApps.kt:305`, `XRayDetail.kt:341,675`
  - `app/src/main/kotlin/com/akane/inspiration/MainActivity.kt`, `app/src/main/AndroidManifest.xml` (no `HIDE_OVERLAY_WINDOWS`)
- **Root cause:** the guard checks only `FLAG_WINDOW_IS_OBSCURED`, which is set only when the touch point itself is under another window. Android 12+ already blocks touches that pass through untrusted overlays at opacity ≥ 0.8. An opaque overlay that covers the confirmation text but leaves the confirm button exposed still passes.
- **Failure scenario:** An app with "Display over other apps" rewrites the "Force stop <app>?" dialog text and leaves the red confirm button visible. The user's tap performs a force stop, restriction or doze change they didn't intend. Impact is bounded: actions are reversible, and protected/system packages are hard-denied.
- **Concrete fix:** declare `<uses-permission android:name="android.permission.HIDE_OVERLAY_WINDOWS"/>` (a normal permission, minSdk 31), and call `window.setHideOverlayWindows(true)` in `MainActivity.onCreate`, or at least while a privileged confirmation is shown. Blocking on `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` is not recommended, because it would also block screen dimmers and chat heads.

### R-11 [LOW] A handle drag ending near the window edge moves the edge the user didn't drag
- **Category:** Logic Bug · **Verdict:** CONFIRMED (walked through with exact values)
- **Location:** `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/math/ScrubGestureReducer.kt:280-293`, `.../chart/math/SeriesMath.kt:69-76`
  - Commit path: `chart/scrub/ScrubGestures.kt:209-221` → `feature/dashboard/.../CockpitChartCard.kt:47` → `CockpitViewModel.kt:270-276`
- **Root cause:**
  - `dragged = other ± minSelectionMs` ignores the window bounds.
  - `normalizeSelection` clamps, then rebuilds the range as `[widenedEnd - min, widenedEnd]`, which drops the fixed edge. This contradicts the comment at line 281.
- **Failure scenario:** Window [0, 60], min 5, selection [50, 58]. Dragging START to 59 gives [55, 60] instead of keeping 58 fixed. The mirror case is [2, 10] with END dragged to 1, giving [0, 5]. The app-wide time slice is analysed for a range the user did not pick.
- **Concrete fix:**
  ```kotlin
  val w = scale.window
  val dragged = when {
      abs(state.currentMs - other) >= minSelectionMs -> state.currentMs
      state.currentMs >= other -> if (other + minSelectionMs <= w.endMs) other + minSelectionMs else other - minSelectionMs
      else -> if (other - minSelectionMs >= w.startMs) other - minSelectionMs else other + minSelectionMs
  }
  ```
  Add reducer tests with `other` within `minSelectionMs` of each window bound.

### R-12 [LOW] Cockpit marker creation is not tracked as a job, so a dismissed sheet's completion lands on a reopened sheet
- **Category:** Concurrency · **Verdict:** CONFIRMED
- **Location:** `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitViewModel.kt:110, 123, 278-282, 301-316`; `CockpitState.kt:114-121`; `app/.../capture/MarkerCapture.kt:10-21`; `AppContainer.kt:228-233`
- **Root cause:**
  - The in-flight state exists only as `markerSheet.creating`.
  - `DismissMarkers` sets the sheet to null without cancelling the capture, and `MarkerSheetUi.loading()` recreates it with `creating = false`.
  - The first job's completion then writes `creating = false` or `failed = true` onto the new sheet.
- **Failure scenario:** The user taps "Mark now", swipes the sheet away, reopens it and taps again. Two FULL captures are serialized. The first one's completion re-enables the button while the second is still running, or shows "couldn't create" for a request the new sheet never made.
- **Concrete fix:**
  ```kotlin
  private var markJob: Job? = null
  // markNow: if (markJob?.isActive == true) return; markJob = viewModelScope.launch { ... }
  // openMarkers: MarkerSheetUi.loading(selected).copy(creating = markJob?.isActive == true)
  ```

### R-13 [LOW] `forSelection` re-labels only a stale `Ready`, so the previous selection's `Empty`/`Failed` briefly shows for a new selection
- **Category:** Concurrency · **Verdict:** PLAUSIBLE (logic certain; whether a frame renders the stale state was not observed)
- **Location:** `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/AnalysisSession.kt:37-45, 70-78`
  - Consumers: `CockpitViewModel.kt:91-97`, `XRayViewModel.kt:169-180`, `SleepLabViewModel.kt:109-120`
  - Test locking it in: `AnalysisSessionTest.kt:92`
- **Root cause:** `Empty` and `Failed` carry no kind. The selection flow updates on Main while the session's `Loading` arrives one IO→Main hop later.
- **Failure scenario:** After clearing a slice that had no data, the "no data" or error card (with Retry) flashes for the new selection. Sleep Lab also resets the hypnogram.
- **Concrete fix:** `data class Empty(val kind: ReferenceKind)` and `data class Failed(val message: String, val kind: ReferenceKind)`, then map any non-Loading state whose kind differs to `Loading`. Replace the test at line 92.

### R-14 [LOW] The status notification can miss the redraw after a capture: it subscribes to ticks only after its first redraw
- **Category:** Logic Bug · **Verdict:** PLAUSIBLE (ordering confirmed; impact small)
- **Location:** `app/src/main/kotlin/com/akane/inspiration/status/StatusNotificationService.kt:94-99`; `.../status/StatusTicks.kt:14-27` (`MutableSharedFlow(extraBufferCapacity = 1, DROP_OLDEST)`, no replay); `app/.../capture/BootCaptureReceiver.kt:12-29` (no `emitAfter`)
- **Failure scenario:** The app-open FULL capture finishes while the service is doing its first redraw, so the tick is dropped. The notification shows pre-capture figures until the next screen or charger event. The missing BOOT tick has negligible content impact.
- **Concrete fix:**
  ```kotlin
  container.statusTicks.onSubscription { emit(Unit) }.collect { redraw(container, source) }
  ```
  Optionally wrap the boot capture in `statusTicker.emitAfter { … }`.

### R-15 [LOW] Every analysis loads and maps the entire retained battery history (up to 30 days), whatever the interval
- **Category:** Performance · **Verdict:** CONFIRMED (row count on real devices not measured; redroid has 11 rows)
- **Location:** `core/diagnostics/.../analysis/IntervalAnalyzer.kt:41` → `core/telemetry-engine/.../diff/ReferenceResolver.kt:88-92` (`store.historyBetween(0, nowWallMs).filter{…}.sortedBy{…}`) → `core/database/.../store/RoomSnapshotStore.kt:154-157`, `Daos.kt:230-231`; retention is 30 days (`RetentionPrune.kt:6`)
- **Root cause:** `historyBetween(0, now)` runs on every selection change, store tick, `recompute()` and resubscribe. The interval walk only needs the last state of each kind before `from`, which the timeline path already gets through `lastHistoryOfEachKindBefore` plus a windowed query (`AppContainer.kt:197-203`).
- **Failure scenario:** After weeks of use, every app open and tab switch scans and allocates tens of thousands of rows before the story appears. This is avoidable CPU and GC work in an app that promises "zero observer effect".
- **Concrete fix:** seed the metrics with `store.lastHistoryOfEachKindBefore(from) + store.historyBetween(from, now)`, and add indexed newest-of-kind queries (UNPLUGGED, SCREEN_ON, the off→on pair) for the resolver anchors.

### R-16 [LOW] Test gap: touch-guard modifier wiring and single-target writes across Hidden → Visible are untested
- **Category:** Test Gap · **Verdict:** PLAUSIBLE
- **Location:** `feature/toolkit/.../ToolkitTouchGuard.kt:78-119` and `feature/xray/.../XRayTouchGuard.kt:78-119` (near-identical copies); `*/src/test/.../ObscuredTouchGateTest.kt` covers only the pure gate; `ToolkitViewModelTest.kt:1104-1119` covers only ResetAll across Hidden.
- **Risk:** reading `motionEvent.flags`, consuming at `PointerEventPass.Initial`, and children then not firing is the security-relevant part. A regression there leaves every test green. R-04 shipped for the same reason.
- **Fix:** add a Robolectric Compose UI test that dispatches `MotionEvent.obtain(...).apply { setFlags(FLAG_WINDOW_IS_OBSCURED) }` to a guarded clickable and asserts no click plus one notice, and that a click without the flag goes through. Move the duplicated guard into `:core:designsystem` so one test covers both. Add the R-04 ViewModel test.

### R-17 [LOW] Test gap: no automated release (R8) gate, and the service class name is a string literal
- **Category:** Test Gap · **Verdict:** PLAUSIBLE (keep rules are complete today)
- **Location:** `app/build.gradle.kts:45-51` (minify + shrink); `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/ShizukuManagerImpl.kt:496, 579-580`; `core/shizuku-ipc/consumer-rules.pro`; `core/telemetry-engine/consumer-rules.pro`
- **Evidence:** `:app:assembleRelease` passes at this revision. The mapping keeps `TelemetryUserService` (both constructors in `seeds.txt`) and `ITelemetryUserService$Stub` unrenamed, and protobuf-lite, HiddenApiBypass and Shizuku rules are all present in `configuration.txt`. The validator found no reflection target without a keep rule. But no build-logic task or documented command builds or smoke-tests the minified APK, and nothing ties the string `"com.akane.inspiration.ipc.service.TelemetryUserService"` to the class.
- **Failure scenario:** a rename or move of the service, or a new reflective path, breaks only the release build, at the moment Shizuku binds.
- **Fix:** use `TelemetryUserService::class.java.name` (same module) or pin the string with a unit test. Add `:app:assembleRelease` (or `minifyReleaseWithR8`) to the documented gate, plus an optional release smoke probe on redroid.

### R-18 [LOW] Test gap: the toolkit chain logic is never run against the real retention prune SQL
- **Category:** Test Gap · **Verdict:** PLAUSIBLE (the predicates agree today; checked case by case)
- **Location:** `core/telemetry-engine/src/test/.../toolkit/ToolkitActionsFakes.kt:39-69` (FakeAudit never prunes); `core/database/src/main/kotlin/com/akane/inspiration/database/Daos.kt:299-318, 336-338` (hand-written SQL); `core/telemetry-engine/.../toolkit/ToolkitActions.kt:1126-1143, 1924, 1947` (Kotlin predicates)
- **Risk:** the SQL is a second copy of `changedTarget`/`landedElsewhere`, and the status literals (`'pending'`, `'done'`, …) are hard-coded. Drift would silently delete chain links older than 500 rows, and Reset all would then restore an intermediate value.
- **Fix:** add a table-driven parity test in `:core:database` (status × kind × before/after, past 500 rows) asserting that the retained rows are a superset of `changedTarget(row)`. Build the SQL literals from `ToolkitActionStatus.*.code`.

### R-19 [LOW] Wall-clock deadline tests with about 230 ms of headroom on a memory-constrained host
- **Category:** Test Gap (flaky pattern) · **Verdict:** PLAUSIBLE (no flake observed)
- **Location:** `core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/source/AndroidTelemetrySourceDeadlineTest.kt:91-123, 157-180, 198-215, 217-232, 509, 854-862`
- **Evidence:** the baseline run recorded 0.867 s and 0.890 s against an 1100 ms bound (kernel), 0.441 s against 800 ms (capture), and 0.491 s against 950 ms (ingest). The timer includes dispatch to `Dispatchers.Default`. A second window exists: the test thread's `delay(600)` must finish before the production 800 ms deadline, or `transaction.entered.await(2s)` fails.
- **Fix:** bound each assertion by the failure it guards against: kernel `< ACQUISITION + DEADLINE` (1400) with `KERNEL_ACQUISITION_MS` around 300, and capture `< usageMs` (1500). Better still, drive `elapsedMs` from the `TestCoroutineScheduler`. Keep the `transaction.finished.count == 1` oracle.

### R-20 [LOW] `core/diagnostics` test gaps on the hidden-API failure path and a locked-in stale state
- **Category:** Test Gap · **Verdict:** PLAUSIBLE (2 of 4 sub-claims confirmed)
- **Location:** `core/diagnostics/src/test/.../sensor/AndroidSensorCatalogTest.kt` (no test for the `ReflectiveOperationException`/`SecurityException`/`ClassCastException` branches at `AndroidSensorCatalog.kt:40-48`); `AnalysisSessionTest.kt:92` (locks in R-13).
- **Fix:** add a Robolectric shadow whose `Sensor.getHandle` throws, and assert the OTHER classification and a single log line. Replace the R-13 test as described there.

### R-21 [LOW] `ChargingCaptureRunnerTest` contains an assertion that can never fail
- **Category:** Test Gap · **Verdict:** CONFIRMED
- **Location:** `app/src/test/kotlin/com/akane/inspiration/capture/ChargingCaptureRunnerTest.kt:11-35`; runner `app/src/main/kotlin/com/akane/inspiration/capture/ChargingCaptureRunner.kt:17-29`
- **Root cause:** `capture = { captureStarted = true; awaitCancellation(); savedTriggers += "CHARGE_JOB" }`. `awaitCancellation()` never returns normally, so `assertEquals(emptyList<String>(), savedTriggers)` cannot fail. The `pruned == false` assertion is still meaningful.
- **Fix:** delete the dead assertion. Add `analyze = { analyzed = true }` with `assertFalse(analyzed)`.

---

## 3. Dead code, redundancy and technical debt (verified with `rg` over `app/ core/ feature/`, excluding `build/`)

| Item | Evidence | Recommendation |
|---|---|---|
| Debug-only IPC clients `PowerStatsClient`, `DeviceIdleReader`/`DeviceIdleClient`, `PowerManagerClient`; `@Parcelize` models `PowerMonitorSnapshot`, `PowerMonitorReading`, `DeviceIdleSnapshot`; stubs `IPowerStatsService`, `IDeviceIdleController`, `IPowerManager` | The only non-test references are in `app/src/debug/.../ProbeReceiver.kt`. No AIDL declares the models (txns 4/5 are reserved). | Move to a debug source set or drop `@Parcelize`. |
| `DozeRaw.AOSP_KEYS` (`core/shizuku-ipc/.../toolkit/Toolkit.kt:168`) | No production reader; the KDoc says it is for tests and documentation. | Keep or move to test fixtures. |
| `BatteryState.status`, `currentMa`, `voltageMv`, `chargeCounterUah` persisted but never read | `rg "battery\.(status\|currentMa\|voltageMv\|chargeCounterUah)"` matches only `RoomSnapshotStore.kt:430-434`. FULL rows store sysfs "Charging" and LIGHT rows store "charging"; `currentMa` has no sign normalisation. | Normalise the values or stop storing them before anyone reads them. |
| `capture_miss` table is write-only in production | Only the insert (`RoomSnapshotStore.kt:295`) and the prune (`Daos.kt:404`). | Surface it in diagnostics or drop it. |
| `TelemetryDao.hourly`, `SnapshotStore.byId` | Test-only callers; the comments say this is deliberate. | None needed. |
| `Culprit`/`CulpritScope`, `ModemDrain.signalBinPct/scanningMs/radioActiveMs`, `DiagnosticReport.skippedInvalidUids`, `Explanation.category/action`; plurals `story_summary`/`story_summary_minutes` | Read only by the debug probe and tests. `IntervalAnalyzer.kt:55` always passes `endsNow = false`. | `skippedInvalidUids` should be surfaced (R-01). The others wait for the unbuilt culprit card. |
| `WearUi.NotEnough.sampleCount` (`feature/health/.../HealthState.kt:121`) | Written, never read by the UI. | Remove. |
| `BarRole.LIGHT_SLEEP/CRITICAL/DEEP_SLEEP/CHARGING`; `InspirationTheme(dynamicAccent)`/`withAccentFrom` | No feature or app use; the KDoc says they are kept on purpose. | Optional cleanup. |
| `testImplementation(libs.room.testing)` (`core/database/build.gradle.kts:21`) | No `MigrationTestHelper` or `androidx.room.testing` use. Migrations are tested by opening real v1–v4 databases. | Drop the dependency or adopt `MigrationTestHelper`. |
| Redundant `:app` dependencies: `lifecycle-runtime-compose`, `lifecycle-viewmodel-compose`, `kotlinx-collections-immutable` (`app/build.gradle.kts:206-213`) | 0 `androidx.lifecycle` imports in `app/src`. Features get them from `AndroidFeatureConventionPlugin.kt:29-31`. | Remove from `:app`. |
| Unused import `kotlinx.coroutines.withTimeoutOrNull` (`ScrubGestures.kt:18`) | The call resolves to the `AwaitPointerEventScope` member. | Remove. |
| Duplicated touch guard (`ToolkitTouchGuard.kt` and `XRayTouchGuard.kt`, near-identical) | Diff shows a KDoc word and a string resource. | Consolidate in `:core:designsystem` (see R-16). |
| Lint version staleness (`:app`): targetSdk below latest, Gradle 9.8.1, Kotlin plugins 2.4.21, `androidx.annotation` 1.11.0, `hiddenapibypass` 6.2, Robolectric 4.17 | `lintDebug` output. | Per the project rule, bump only in a ticket. |

No TODO, FIXME, HACK or XXX markers exist in application source (`rg` over `app core feature build-logic`). The only `!!` in main source (`app/.../capture/ChargingCaptureRunner.kt:176-178`) is guarded by the `currentMa == null` early return at `:162`. Raw SQL is limited to Room migrations (`InspirationDatabase.kt:70-106`, constant strings) and a debug-only `PRAGMA` in `ProbeReceiver.kt:531`.

---

## 4. Prioritized remediation roadmap

**Phase 1: immediate fixes (isolated commits)**
1. R-01: count a proven-zero baseline as valid, and surface skipped rows.
2. R-02: treat an alarm-dump reset as a per-source reset, not as invalid energy.
3. R-06: track a pending FGS start in the status controller (crash).
4. R-07: fail closed when the reader thread hasn't drained (protected-package gate).
5. R-05: report a post-dispatch audit failure as "unverified", not "failed".
6. R-04: deliver toolkit write outcomes across Hidden/Visible.

**Phase 2: structural and concurrency hardening**
1. R-03: capture-policy and rule-gate decision for the night interval. This needs a product decision; see the options above.
2. R-08: move Shizuku bind/unbind transactions off Main and outside `bindingLock`.
3. R-09: use battery-stats durations for mAh/h.
4. R-10: `HIDE_OVERLAY_WINDOWS` / `setHideOverlayWindows(true)`.
5. R-12, R-13, R-14: in-flight work owned by jobs, kind-tagged terminal states, subscribe before the first redraw.
6. R-11: scrub-reducer edge widening.
7. R-15: seeded, windowed history queries for analysis.

**Phase 3: architecture and test coverage**
1. R-16: Compose UI test of the touch guard, and consolidation of the duplicated guard.
2. R-17: a release R8 gate plus a class-literal service name.
3. R-18: a SQL/Kotlin retention parity test.
4. R-19: deterministic deadline tests on virtual time.
5. R-20, R-21: diagnostics failure-path tests; remove the dead assertion.
6. Realistic multi-day fixture snapshots (night interval, post-unplug UID appearance, force-stop) for the differ, resolver and rule engine. The current fixtures encode the assumptions that produced R-01 to R-03.
7. Dead-code cleanup from §3.

---

## 5. Coverage ledger

**Subsystems examined** (10 parallel read-only reviewers, each a fresh `audit-reviewer` context):
1. Privileged UserService, toolkit executor and AIDL (`core/shizuku-ipc` service/toolkit/model).
2. Shizuku client and binder lifecycle, plus hidden-API stubs.
3. Telemetry capture/source/diff/dump/interval/live/calibration/capacity (`core/telemetry-engine`).
4. Persistence and stateful services (`core/database`; telemetry-engine store/toolkit/status/app).
5. Diagnostics and analysis (`core/diagnostics`).
6. `:app` (manifests, AppContainer, capture receivers/jobs, DI, status FGS, shell, debug components).
7. `feature/dashboard`, `feature/health`, `feature/sleeplab`.
8. `feature/toolkit`, `feature/xray`.
9. `core/designsystem` and the build/release configuration (build-logic, catalogs, ProGuard).
10. Test-suite integrity across all modules.

Validation ran in 3 further fresh contexts, split by domain: analytics math (8 candidates), privileged/IPC/lifecycle (7), test integrity (6). No candidate was rejected; severities were re-assigned independently.

**Tools actually run**
- `testDebugUnitTest lintDebug validateDebugScreenshotTest --continue --max-workers=4` (all modules; another project's build held one host slot). Result: exit 0, 1,732 unit tests across 138 result files with 0 failures and 0 skips, 185 screenshot tests passing. Lint reported only `:app` version warnings (7) and 0 issues in the other 11 modules.
- `:app:assembleRelease --max-workers=4`: exit 0. The R8 mapping, seeds and configuration were inspected.
- Source-only recon (`repo-recon.sh`), plus an `rg` scan for secrets and signing config: no secrets are tracked, signing comes from Gradle properties, and no keystore or `.env` files are tracked.
- Read-only device checks on redroid (Android 15 / Shizuku 13.6.0):
  - `dumpsys` deviceidle, device_policy, battery, alarm, jobscheduler, activity services, package, sensorservice
  - `cmd appops get`, standby bucket, role holders, `settings get`
  - `pm list packages -U`, `service list`
  - read-only `sqlite3` SELECTs on the app database
- `dexdump` of the device's `framework.jar` and `services.jar`, `dexdump` of the Shizuku server APK, and `javap` of the Shizuku 13.1.5 API/AIDL jars.

**Unavailable or unverified checks**
- **No real battery data:** redroid has no battery, no sensors, 11 history rows and 3 snapshots. R-01, R-02, R-03, R-09 and R-15 could not be reproduced on real data.
- **Release runtime:** the release APK was not installed. Debug and release use different signing keys, and switching would need an uninstall, a state change. Bind and protobuf behaviour in release is unverified at runtime.
- **Other API levels:** no API 31–34 or 36–37 images. Hidden-API stub signatures were verified against API 35 only.
- **Destructive reproductions skipped:** force-stop, UI toggles, overlay apps and stats resets would mutate device state.
- **No online CVE lookup.** protobuf 4.36.2 is past CVE-2024-7254 by version knowledge.
- **Not examined in depth:** screenshot reference images, many test bodies (test names were reviewed), and `theme/{Color,TelemetryColors,Shape}.kt`.
- **`android-kit-codex/` is out of scope** (kit tooling, not application code).

---

## 6. Audit isolation (contamination ledger)

- **Classification: `fresh workers; coordinator exposed`.**
- **Coordinator exposure, all before investigation:**
  - The SessionStart hook injected `.claude/.codebase-info/INDEX.md` into the coordinator's context. It summarizes earlier audit remediations by ID range (A-01–A-24, F-01–F-25, N-01–N-28) with one-line fix themes.
  - Required instructions were also loaded: `CLAUDE.md` (no findings), auto-memory (git identity only), and live rules (which mention an earlier audit date and the map-hook contamination).
  - `gitStatus` showed five recent commit subjects (kit/docs chores, no finding content).
  - The coordinator did not pass prior conclusions or IDs to workers; scopes were derived from the module/source topology. However, the focus prompts in the worker briefs were written by an exposed coordinator, and some topics overlap themes named in INDEX.md (UID identity, history horizon, toolkit restore state, binder deadlines). Residual steering cannot be ruled out.
- **Worker exposure:**
  - All reviewers reported source and test comments citing earlier review IDs (e.g. `N-13`, `N-26`, `F-16`–`F-20`, `SQ-109/114/118`, "audit §3"). They recorded these and did not use them as evidence.
  - The test-integrity reviewer's context was compacted mid-run, and the SessionStart hook re-injected INDEX.md into it. It reports no reliance on it. Its findings are R-19 and R-21, and it rejected the R-07 path, which a separate validator then confirmed as reachable.
- **Excluded inputs (not opened before freeze):** `AUDIT_REPORT.md`, `PROGRESS.md`, `AGENTS.md`, `docs/**` (all plan docs contain audit/remediation references), `.claude/.codebase-info/**` (beyond the forced injection), `.git/audit-history/`, `android-kit-codex/`, and git log, blame or show.
- **Fresh-context availability:** yes. All 13 workers were `audit-reviewer` subagents with no inherited transcript; that agent type does not match the codebase-mapper SubagentStart hook.
- **Deviation from protocol:** validation used 3 domain-split validators instead of one, so each candidate got a deep trace. Each validator was independent of the candidate authors and received evidence only, without severity or confidence.
- **Integrity event (disclosed):**
  - The guard check before freeze failed because the kit's `gradle-check.sh` wrote two new logs under the protected `.claude/kit/logs/` during this audit's own baseline and release builds.
  - No pre-existing kit, skill or instruction file changed.
  - With the user's explicit approval, both logs were moved into `.git/audit-history/run-24sikb9_/evidence/` (`gradle-baseline.sSAlmC7e.log`, `gradle-release.TVOkAGv8.log`), and the guard check then passed.
- **Side effects:**
  - Build outputs under `*/build/`, including `app/build/outputs/apk/release/app-release-unsigned.apk` and the R8 mapping.
  - A read-only `sqlite3` open on the device updated the mtime of the app DB's `-wal`/`-shm` files; no data changed.
  - Framework jars were pulled into the session scratchpad.
  - No application, skill, kit or instruction files were modified.
- **Guard session:** `.git/audit-history/run-24sikb9_` (started 2026-10-20T12:51:21+03:00). Prior report archived unread as `previous-report.md`.


## Audit Delta (post-freeze comparison)

*Written after the independent findings above were frozen (guard `freeze`, `independent.md` sha256 `c3c533804193cdeb93f34ba09263aec7c3450e05f13d3eb78efdd8b6d52d211c`). Only after that freeze did this phase open the archived previous report (`.git/audit-history/run-24sikb9_/previous-report.md`, the 2026-10-19 N-series audit with its F-series delta and remediation log), git history, and `git log -S` traces. Nothing below changes the frozen findings.*

### Boundary and inputs
- **Previous report:** blind audit of `5c0b83d` (2026-10-19), N-01–N-28. It had a delta against F-01–F-25. Its remediation, release 0.2.4, marked all 28 FIXED (N-28 partial) and listed 16 open follow-ups.
- **Source compared:** `git log 5c0b83d..HEAD` holds 75 commits. 63 of them touch application code: 49 N-fix commits, 13 dead-code commits and the 0.2.4 bump `ec4a0d4`. The 12 after `ec4a0d4` touch only `.claude/`, `CLAUDE.md`, `PROGRESS.md` and `AUDIT_REPORT.md`. So this audit reviewed exactly the post-remediation 0.2.4 application source.
- **Commit check:** all 49 fix commits cited in the remediation table are ancestors of HEAD (`git merge-base --is-ancestor`).
- **Who did what:** a post-freeze mapping agent (not blind) checked each N-fix and follow-up against current source. The coordinator traced the commits that introduced the new MEDIUM findings with `git log -S`.

### Per-finding comparison with N-01…N-28

| Prior | Classification | Current evidence / relation to the frozen findings |
|---|---|---|
| N-01 RESET semantics | fix present in source; verification limited | `BatteryHistory.kt:124,167` (RESET restates screen and idle state); `HealthMappers.kt:141`; `IntervalMetrics.kt:120`. Prior device check passed. |
| N-02 history boundaries not reconciled with capture anchors | fix present in source; verification limited; **adjacent to new R-03** | `ReferenceResolver.kt:192-194, 224-230, 298` (`CAPTURE_SLACK_MS = 10_000`, end anchor at or after SCREEN_ON). The previous remediation's own device evidence for this fix recorded "`since_screen_off` 39.4 s off and **50 ms on**". That non-zero screen-on time is exactly what makes `isScreenOffOnly` fail (`DiagnosticRuleEngine.kt:53-56`), so the night interval can never reach the screen-off rules (R-03). The fix's documented deviation (slack plus horizon instead of `max(history, capture)`) moved the window end past SCREEN_ON on purpose, and the rule gate was never reconciled with it. |
| N-03 bg-dexopt cancel on API 31–33 | fix present in source; verification limited | `BgDexoptController.kt:30-35, 96`; `ToolkitExecutor.kt:254`. Not device-verifiable (redroid is API 35). |
| N-04 timeline loaded before the app-open ingest | fix present in source; verification limited; **adjacent to new R-14** | `CockpitViewModel.kt:163`, `HealthViewModel.kt:231`, `AppContainer.kt:131-142`, `RefreshCoordinator.kt:88-90`. The store-tick fix covers the screens. The status notification's separate `statusTicks` flow still subscribes after its first redraw (R-14), so it can miss the same tick. |
| N-05 live-current scaling | fix present in source; verification limited | `AndroidTelemetrySource.kt:169`, `PowerCalibrator.kt:218,231,379`, `SharedPreferencesCalibrationStore.kt:18,32`. |
| N-06 unreadable override dropped from the list | fix present in source; verification limited | `ToolkitActions.kt:269, 1190, 1205, 1230` (`unverified`). |
| N-07 never-dispatched writes kept PENDING | fix present in source; verification limited; **same theme as new R-05** | `ToolkitGateway.kt:47,142,155` (`sent`), `ToolkitActions.kt:794-798`. N-07 fixed the *pre*-dispatch side of write truth. R-05 is the *post*-dispatch side: a Room failure after the service replied DONE is still shown as "The command failed.". Different root, same "write outcome honesty" contract. |
| N-08 Sui reported NotInstalled | fix present in source; verification limited | `ShizukuManagerImpl.kt:417-418`. |
| N-09 analysis collected for the Activity's lifetime | fix present in source; verification limited | `CockpitViewModel.kt:140-143`, `SleepLabViewModel.kt:73,85`. |
| N-10 "Charging" while draining | fix present in source; verification limited | `CockpitMappers.kt:44,50,178-179,200`. Prior follow-up 4 (the status notification still says "Charging" from `plugged`) is still open. |
| N-11 death without bind fence | fix present in source; verification limited; **adjacent to new R-08** | `ShizukuManagerImpl.kt:127,143,291,383-384`. This is the fourth change to binding handover in this class (A-01 → F-17 → N-11). R-08 is not a fencing bug: the bind and unbind binder transactions themselves run under `bindingLock`, one of them on Main. The R-08 fix (transact outside the lock and re-check the generation) has to keep the N-11 fence intact. |
| N-12 marker self-pair | fix present in source; verification limited | `ReferenceResolver.kt:68`. New R-12 (marker job not tracked) is a different root in the same marker feature. |
| N-13 sessions grouped by exact `statsStartMs` | fix present in source; verification limited | `RoomSnapshotStore.kt:164-165`, `SnapshotCapturer.kt:126`. |
| N-14 MY_PACKAGE_REPLACED recorded as BOOT | fix present in source; verification limited | `BootCaptureReceiver.kt:40`, `UnplugSummary.kt:108`. |
| N-15 force-stop reported FAILED when the lookup fails | fix present in source; verification limited; **same theme as new R-05** | `ToolkitExecutor.kt:95,137,162`. That was the service side; R-05 is the client side of the same "a write that happened must not read as failed" contract. |
| N-16 app tap drops a pending plan | fix present in source; verification limited; **same flow as new R-04** | `ToolkitViewModel.kt:345`, `ToolkitApps.kt:69-72,154`. R-04 comes from `invalidatePlanning`, added in `6d5d028` ("preserve targets across asynchronous planning"). It bumps the planning generation on `Hidden` even while a dispatched write is running, so that write's notice is dropped. This is the same generation guard used for planning safety, applied to delivering results. |
| N-17 Health "Live" without readings | fix present in source; verification limited | `HealthViewModel.kt:36,191`. |
| N-18 Health shaping on Main | fix present in source; verification limited | `HealthViewModel.kt:49-66, 83, 380, 411`. |
| N-19 `ZoneId.systemDefault()` per recomposition | fix present in source; verification limited | `SystemZone.kt:29`; chart defaults use `rememberSystemZoneId()`. Prior follow-up 10's `ToolkitSections.kt:249,268,397` `toZoneId()` calls are still open. |
| N-20 picker collation | fix present in source; verification limited | `ToolkitViewModel.kt:293`. |
| N-21 out-of-window handles | fix present in source; verification limited; **adjacent to new R-11** | `ScrubChartRenderer.kt:384,392`. R-11 is a different root (the minimum-span widening in `ScrubGestureReducer.kt:280-293` drops the fixed edge at the window bounds) in the same handle-drag feature. |
| N-22 hypnogram a11y dates | fix present in source; verification limited | `SleepHypnogram.kt:485`. |
| N-23 axis labels localisation | fix present in source; verification limited | `SynchronizedScrubbingChart.kt:203`, `ScrubChartMath.kt:89`. |
| N-24 slice-control step | fix present in source; verification limited | `SliceControls.kt:32-33,59`. |
| N-25 partial signing properties | fix present in source; verification limited | `app/build.gradle.kts:34, 83-93`. This audit's secret and signing scan found nothing. |
| N-26 process-wide hidden-API exemption | **independently rediscovered as resolved (consistent)** | `InspirationApplication.kt:26` exempts only `Sensor.getHandle()`. The R-17 validator confirmed that the reflection targets are framework classes needing no keep rules. |
| N-27 release ships unused locales and sources | fix present in source; verification limited | `app/build.gradle.kts:49,56,62,77-78`. This audit's `:app:assembleRelease` passed. Prior follow-up 9 (descriptor `.proto.bin` and `version-control-info.textproto` still packaged) was not re-checked in the APK. |
| N-28 missing component, gesture and backup tests (partial) | **independently rediscovered (residual: R-16, R-06)** | Pure deciders were extracted and JVM-tested (`BootCapturePlan`, `StatusStartDecision`, `ScrubGestureDriverTest`, `BackupRulesTest:53-56`). The remediation recorded "no Compose-rule gesture tests … no instrumented FGS test". This audit found, without seeing that note: the touch-guard modifier wiring is untested (R-16), and an FGS start/stop race that a component-level test would cover (R-06). |

**Summary for N-01–N-28:**
- 0 still reproducible as originally reported.
- 1 independently rediscovered as resolved and consistent (N-26).
- 1 independently rediscovered as a partial residual (N-28 → R-16/R-06).
- 26 fix present in source, verification limited. Of these, 7 are adjacent to new findings: N-02→R-03, N-04→R-14, N-07/N-15→R-05, N-11→R-08, N-16→R-04, N-21→R-11.
- 0 not assessed.

### Prior follow-ups (2026-10-19 list, never fixed)
No application-code commit landed after `ec4a0d4`, so all 16 follow-ups remain as recorded. Current evidence comes from the mapping agent, spot-checked by the coordinator.

| # | Status at `dbd0347` | Relation to frozen findings |
|---|---|---|
| 1 "Since screen off" Empty when no capture brackets it | Open (`ReferenceResolver.kt:218-236` `else -> null`) | **Same root as R-03.** R-03 adds that even when a pair resolves, the LIGHT endpoints and the at-or-after-SCREEN_ON end make every screen-off rule non-evaluable. Fix both in one design change. |
| 2 Android 14+ broadcast deferral exposes late captures | Open | Noted in R-03's fix (FULL screen captures only help while the process isn't cached). |
| 3 `PROBE_INSIGHTS` without horizon or zero-length guard | Open (`ProbeReceiver.kt:353`) | Debug only. |
| 4 Status notification "Charging" from `plugged`; uncalibrated `light.currentMa` | Open (`UnplugSummary.kt:43`, `SnapshotCapturer.kt:234`) | Related to frozen §3 (persisted `currentMa` unnormalised, never read). |
| 5 Current unit while plugged | Open | — |
| 6 Charge-history rewind and prune watermark | Open | — |
| 7 Sui wording; shared-stub kill | Open | — |
| 8 bg-dexopt API 33 cancel versus 3 s destroy | Open | — |
| 9 `.proto.bin` / `version-control-info.textproto` in the APK; stale TEMPLATE/phase-3 text | Open | — |
| 10 Chart nits (2 dp sliver, Up after last Move, `toZoneId`, stale selection, date-rule mismatch) | Open (4 of 5 confirmed) | Separate from R-11. |
| 11 Rows not dimmed while busy; own lift before protected | Open | — |
| 12 Missed capture doesn't tick; `samplesBetween` unbounded | Partly open | Separate from R-14. |
| 13 Test fakes match `statsStartMs` exactly | Open (`SnapshotDifferTest.kt:1063`) | Same "fake encodes the assumption" pattern as R-18 and the roadmap's fixture item. |
| 14 Culprit card and gap banner unbuilt | Open | Explains the debug-only diagnostics fields in frozen §3. |
| 15 Kernel battery-node fallback can pick a HID peripheral | Open (`TelemetryUserService.kt:318-322`, no `scope=Device` exclusion) | Not in the frozen findings; the privileged-service reviewer did not flag it. **Still reproducible (delta-only), LOW.** |
| 16 BOOT_COMPLETED after a force-stop release labelled BOOT | Open (`BootCaptureReceiver.kt:39`) | — |

### Origins of the new MEDIUM findings (post-freeze `git log -S`)
- **R-01 is a regression introduced by an earlier remediation.**
  - `d2c06be` "fix(telemetry): resolve A-08 – prove capture-time UID continuity" (2026-10-19) changed a newly observed UID's delta from `valid = true` to `valid = identityValid`, which requires owner fingerprints at both endpoints.
  - The same commit added the test that locks it in ("a newly observed uid has no proven baseline").
  - Two later blind audits (F-series, then N-series, whose F-02 fix reworked the same comparison with namespace and fingerprints) did not flag the missing-from-a-complete-reference case.
  - The A-08 goal (don't attribute counters across a UID owner change) is preserved by R-01's proposed fix. A UID absent from a complete reference has no counters to mis-attribute.
- **R-02** comes from the original design: `negative_uid_alarm_dump` in `301a7ad` (SQ-68, alarm-dump persistence). It predates all audits.
- **R-03:** the rule gate comes from `03b5802` (SQ-43). The window end at or after SCREEN_ON comes from the N-02 fix `4886c58`/`3d2ac0c`. See N-02 above.

### Other prior material
- **R-15** (every analysis loads the whole history): the N-series delta listed "the prior 'every analysis reloads the full retained history' performance note (`IntervalAnalyzer.kt:40`)" as *not assessed*. B-series `0c5302a` ("one history load per analysis") reduced it to one load, but that load is still from wall time 0. **Independently rediscovered, still present.**
- **Dead code:**
  - Consistent with the prior "kept and documented" list: `TelemetryDao.hourly`, `SnapshotStore.byId`, culprit and explanation fields, `dynamicAccent`/`withAccentFrom`, `DozeRaw.AOSP_KEYS`.
  - Newly found in frozen §3: `room-testing` unused (the prior cleanup removed `room-ktx` but kept it), `WearUi.NotEnough.sampleCount`, write-only `capture_miss`, the unread persisted `BatteryState` fields, redundant `:app` lifecycle and collections dependencies, the unused `withTimeoutOrNull` import, and the debug-only power and deviceidle clients that stayed in `main` after the txn 4/5 removal (`9567090`).

### Pattern worth noting for remediation
- The prior delta warned that fixes applied at one call site leave same-root survivors. This audit adds a sharper version: **two of the three MEDIUM findings trace back to earlier audit fixes**.
  - R-01 was introduced by the A-08 fix.
  - R-03's window shape was set by the N-02 fix and never reconciled with the rule gates. The N-02 device evidence (50 ms "on") showed the symptom at the time.
- Both passed their own regression tests, because each test asserted the new invariant in isolation ("no proven baseline → invalid"; "end anchor at or after SCREEN_ON") and nothing tested the downstream consumer: X-Ray rows and rule evaluability.
- For `/audit-remediate`, each MEDIUM fix ticket should:
  - add a consumer-level test (a newly observed app appears in X-Ray "since unplug"; the night interval produces a Sensor Leak verdict on a realistic fixture);
  - review the earlier fix's intent (A-08: no cross-owner attribution; N-02: anchor reconciliation) so it is preserved.

### Scorecard and baseline comparison

| Dimension | Previous (N-series) | This audit |
|---|---|---|
| Architecture | 7 | 8 |
| Correctness | 5 | 7 |
| Concurrency | 7 | 8 |
| Security | 8 | 8 |
| Performance | 7 | 8 |

- The scores are qualitative and come from different runs.
- The rise reflects 4 HIGH findings fixed and no new HIGH or CRITICAL finding.
- Baseline match: the previous final gate at `ec4a0d4` recorded 1,732 unit tests, 7 lint warnings and 185 screenshots. This audit's baseline at `dbd0347` reproduces exactly 1,732 / 0 failures, 7 `:app` version warnings and 185 screenshots, and `:app:assembleRelease` passes.

### Post-freeze observations (not investigated)
- None beyond follow-up 15, re-confirmed in source and listed above as delta-only.
