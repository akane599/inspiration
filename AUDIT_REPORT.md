# Inspiration — Whole-Repository Principal Audit

- **Revision audited:** `60fe3d5ac2e31451498e46eb8f6b1e1c1d92a780` (branch `Review-n-fix`). The working tree differs only in `.claude/settings.json` (the review-mode toggle); no application source is modified.
- **Date:** 2026-10-19
- **Mode:** standalone blind audit with Sidequest disabled, run by `/full-repo-audit` under the audit guard (session `run-884ftxmi`).
- **Evidence:**
  - Source tracing by five fresh reviewer contexts and three fresh validator contexts.
  - One shared tooling baseline (tests, lint, R8).
  - 26 reproduction test methods, run against a pristine `git archive` export of 60fe3d5.
  - Runtime checks on a redroid Android 15 device with Shizuku, driven through Mobile MCP and adb.
- **Scope of this frozen section:** independent findings only. The comparison with any earlier audit material is appended afterwards as a separately labelled delta.

---

## 1. Executive Summary & System Health Scorecard

The codebase is disciplined. The modules are cleanly layered, the AIDL surface is typed and checks the caller's UID, shell commands use fixed argv and never go through a shell, the privileged write path is audited, and the existing 1,083 unit tests pass. The main weaknesses are:
- **The Toolkit loses track of what it changed.** The write path's bookkeeping can lose or mis-target device changes in two specific conditions.
- **Capture and Shizuku failures are silent.** The lifecycle code assumes service availability and cooperative cancellation that the platform does not provide, so data collection fails without telling the user.

| Dimension | Score | Justification |
|---|---|---|
| Architecture & Modularity | **7/10** | Clean module graph, manual DI and a typed IPC contract. Package identity in X-Ray comes from a value that changes per snapshot, retention depends on the wall clock, and two feature ViewModels lack the failure boundary the others use. |
| Correctness & Resilience | **5/10** | Two HIGH Toolkit defects: a change that took effect is untracked (F-01), and a confirmed write can hit a different package (F-02). Shizuku state cannot recover within a process (F-03, confirmed on a device). Captures are silently lost (F-04, F-05), and a clock jump deletes history for good (F-06). |
| Concurrency & Resource Safety | **6/10** | Mutexes, `NonCancellable` miss recording and non-blocking dump reads are well done. Coroutine timeouts still wrap blocking binder calls (F-05), history ingests are not serialized (F-17), and uncaught exceptions in feature and supervisor scopes crash the process (F-08). |
| Security & Privilege Hygiene | **7/10** | UID-checked binder, fixed argv, a hard-deny gate and a fail-closed device-policy parser. However, a truncated protected-package list fails open (F-12), and a confirmed privileged write can target a package the dialog did not name (F-02). |
| Performance & Efficiency | **6/10** | Every gated Toolkit read spawns 8 shell processes with no cache, which takes 63–66 ms on the device. Override recompute grows with every target ever touched (F-07). Snapshot reads load more than they need (F-19), and every full capture reads an unused power dump. |

*Scores are qualitative judgments for this revision, not measured guarantees.*

**Top 3 systemic risks**
1. **Toolkit bookkeeping can lose track of device changes it made** (F-01, F-13). A write that lands on a third value is stored as `FAILED`, and a repeated write hides a live override. Either way the device stays modified, but the change is not listed, *Reset all* skips it and *Restore* is refused.
2. **The target of a privileged write comes from a per-snapshot value that changes.** X-Ray re-reads the package at confirm time, after a background analysis may have swapped it (F-02). The package it uses comes from batterystats' *process name* (F-09).
3. **Data collection silently stops or is destroyed.**
   - Shizuku permission state never recovers within a process (F-03).
   - Cold-start captures fail instead of waiting for the binder (F-04).
   - Capture deadlines cannot interrupt blocking binder calls and throw away late history (F-05).
   - One forward wall-clock jump permanently prunes the dataset (F-06).

---

## 2. Confirmed Findings (CRITICAL → HIGH → MEDIUM → LOW)

No CRITICAL findings.

### HIGH

- **[F-01] [HIGH] A Toolkit write that changes the device to a third value is stored as FAILED and drops out of override, reset and restore tracking** [FIXED in cb9e096]
  - **Category:** Logic Bug
  - **Location:**
    - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt`:
      - `settle` at 591-630
      - `decodeRestore` at 732-747
      - `overrideRows` at 801-806
      - `computeOverrides` at 810-834
      - reconcile Mismatch branch at 923-931
    - Service side: `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/ToolkitExecutor.kt:69-83` (bucket check at :81), `:40-56` (app-op check at :54), `:142-160` (doze restore at :156-157)
  - **Confidence:** Confirmed by call trace and reproduction (`AuditReproV1aTest`, 2 of 2 methods fail on 60fe3d5). Not reproducible on the redroid device, because app standby is disabled there (`mAppIdleEnabled=false`) and every bucket reads 5.
  - **Root Cause & Execution Trace:**
    1. The service carries both observed values when the read-back differs from the request:
       ```kotlin
       val after = readBucket(pkg.orEmpty()) ?: return unverified(before)
       if (after != bucket.rawValue.toString()) return ToolkitResult.failed(ToolkitCode.NOT_EFFECTIVE, before, after)
       ```
    2. `ToolkitActions.settle` sends every non-DONE status to FAILED, without checking whether the device moved:
       ```kotlin
       ToolkitStatus.UNAVAILABLE -> { audit.update(finished(pending, ToolkitActionStatus.FAILED, result, now)) … }
       else -> { audit.update(finished(pending, ToolkitActionStatus.FAILED, result, now)); ToolkitOutcome.Failed(result.code) }
       ```
    3. Override bookkeeping only sees DONE or RESTORED rows:
       ```kotlin
       .filter { (it.status == ToolkitActionStatus.DONE || it.status == ToolkitActionStatus.RESTORED) && it.kind in OVERRIDE_KINDS }
       ```
       So `computeOverrides`, `activeOverrides()` and `resetAll()` never see the row. `decodeRestore` refuses it: `if (record.status != ToolkitActionStatus.DONE || record.restoresId != null) return Decoded.No(NOT_RESTORABLE)`. `reconcile()` only looks at PENDING rows.
    4. The row also breaks any older chain. Suppose ACTIVE→RARE succeeded (DONE, after=40) and RESTRICTED is then requested and clamped to 20. The newest DONE row says 40, which no longer matches the device's 20, so `isActive` is false.
  - **Failure / Exploit Scenario:**
    - AOSP clamps `setAppStandbyBucket` to the app's minimum bucket. Apps holding `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`, wellbeing-role apps and apps with bound widgets cannot go below WORKING_SET.
    - A user restricts a messaging or calendar app. The device lands on WORKING_SET with reason "forced by user", so system prediction will not undo it.
    - The app shows "not effective" and stores a FAILED row. The changed app never appears in the override list, *Reset all* skips it and history *Restore* is refused. The app has silently changed the device and offers no way back.
    - Repro: the bucket moves ACTIVE(10)→FREQUENT(30), the stored row is FAILED, `activeOverrides()` is `[]`, and the restore is refused.
  - **Concrete Fix:** Store an observed change as part of its chain, while still reporting the outcome as not effective:
    ```kotlin
    // ToolkitActions.settle, before the status `when`:
    val movedElsewhere = result.code == ToolkitCode.NOT_EFFECTIVE && pending.kind in OVERRIDE_KINDS &&
        result.beforeRaw != null && result.afterRaw != null &&
        !sameState(pending.kind, result.afterRaw, result.beforeRaw)
    if (movedElsewhere) {
        // The device did change: keep the row in its chain so overrides, Reset all and Restore see it.
        audit.update(finished(pending, ToolkitActionStatus.DONE, result, now).copy(code = ToolkitClientCode.LANDED_ELSEWHERE))
        return ToolkitOutcome.Unavailable(ToolkitUnavailable.NOT_EFFECTIVE, result.code)
    }
    ```
    Apply the same rule in the reconcile Mismatch branch (923-931). The existing test `failedWriteIsRecordedWithServiceValues` (before == after) still passes. Add a test for before≠after.

- **[F-02] [HIGH] An X-Ray confirmation runs against the package re-read at confirm time, not the one the dialog named** [FIXED in c0b93f6]
  - **Category:** Logic Bug (privileged write targeting)
  - **Location:** `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayViewModel.kt`:
    - `pending` at 54
    - `showReady` detail swap at 164-166
    - `act` at 261-294 (package captured at 263)
    - `confirmPending` at 296-305

    `ToolkitActions.run` skips its warnings when `confirmed = true` (`ToolkitActions.kt:506-508`, `:519-521`).
  - **Confidence:** Confirmed by call trace and reproduction (`AuditReproV1dTest` fails: "user confirmed force-stopping "Label of com.example.suite.a" … force_stop was issued against com.example.suite.b"). Not attempted on the device, which has no X-Ray data.
  - **Root Cause & Execution Trace:**
    1. `act` plans for the package shown, `pkg = detail.app.packageName` (P1), but stores only the request: `pending = request`. The dialog shows P1's label.
    2. While the dialog is open, `onVisible`'s `collectLatest` keeps applying new `AnalysisState.Ready` values, for example the refresh started on Visible or any background capture. `showReady` swaps the open detail's app for the newest row with the same UID:
       ```kotlin
       detail = current.detail?.let { detail -> apps.firstOrNull { it.uid == detail.app.uid }?.let { detail.copy(app = it) } ?: detail },
       ```
       Nothing clears `pending` or `confirmation` when this happens.
    3. `confirmPending` re-reads the package and executes with `confirmed = true`:
       ```kotlin
       val pkg = detail.app.packageName ?: return
       viewModelScope.launch { execute(uid, request, pkg, confirmed = true) }
       ```
    4. One UID can change package between snapshots. `UidDelta.packageName` is batterystats' per-snapshot `packageWithHighestDrain`, and `packageChanged` is set only when both sides are non-null. So a null→P2 transition, with P1 having come from `packageForUid(uid).minOrNull()`, keeps the row and changes its package.
    5. A second symptom follows after the swap: the sheet still shows P1's controls (its jobs were filtered by the old package), but a no-confirmation tap in `act` writes to P2.
  - **Failure / Exploit Scenario:** A shared-UID suite of updated system apps passes the hard-deny gate. The user confirms "Force stop P1", and P2 is force-stopped (or restricted) with the warnings already bypassed. Likelihood is low: it needs a shared UID and an analysis finishing while the dialog is open. The impact, though, is a privileged write the user did not confirm. Toolkit is not affected, because it pins the target in `PreparedRequest` (`ToolkitViewModel.kt:56-61`).
  - **Concrete Fix:** Pin the target at plan time, as Toolkit does, and stop swapping the package of an open sheet:
    ```kotlin
    private data class PendingAction(val request: ActionRequest, val uid: Int, val pkg: String)
    private var pending: PendingAction? = null
    // act(): is ToolkitPlan.NeedsConfirmation -> { pending = PendingAction(request, uid, pkg); … }
    private fun confirmPending() {
        val p = pending ?: return
        pending = null
        _state.update { it.copy(confirmation = null) }
        updateDetail(p.uid) { it.copy(busy = true, notice = null) }
        viewModelScope.launch { execute(p.uid, p.request, p.pkg, confirmed = true) }
    }
    // showReady(): refresh the open row only while it still names the same package
    detail = current.detail?.let { d ->
        apps.firstOrNull { it.uid == d.app.uid && it.packageName == d.app.packageName }?.let { d.copy(app = it) } ?: d
    },
    ```

### MEDIUM

- **[F-03] [MEDIUM] Shizuku permission state never recovers within a process; "Grant access" becomes a dead button** [FIXED in 0fa2054]
  - **Category:** Logic Bug (state lifecycle)
  - **Location:** `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/ShizukuManagerImpl.kt`:
    - permission listener at 140-147
    - `requestPermission` at 149-158
    - `refreshState` at 293-327
    - `transportLostLocked` at 339-352

    Related: `ShizukuManager.kt:5-21` has no refresh API; `feature/dashboard/.../CockpitMappers.kt:56-57` sets `canGrant = true` for PermissionDenied; the toolkit string `toolkit_blocked_permission_denied` tells the user to allow the app in Shizuku and come back.
  - **Confidence:** Confirmed on the device and by reproduction (`AuditReproV1gTest` fails). The sub-claim that revocation while bound is permanent was rejected: the Shizuku server force-stops the client.
  - **Root Cause & Execution Trace:**
    1. Any non-grant result sets a terminal state without consulting the rationale, which `refreshState` does consult:
       ```kotlin
       if (grantResult == PackageManager.PERMISSION_GRANTED) updateState() else setUnavailableState(ShizukuState.PermissionDenied)
       ```
    2. A later request is refused unless the state is exactly `PermissionRequired`:
       ```kotlin
       if (!started || mutableState.value !is ShizukuState.PermissionRequired) return
       ```
    3. `refreshState` runs only from `start()`, the binder-received listener and a GRANTED result. A grant made in the Shizuku manager sends the client no callback, and `MainActivity.onStart` does not refresh.
  - **Failure / Exploit Scenario (device evidence: redroid, Android 15, Shizuku 13.5):**
    1. On a fresh install, Grant → Deny. Shizuku logs `dispatchPermissionConfirmationResult: uid=10088 … allowed=false, onetime=true`, so the deny was not persisted. Cockpit shows "Shizuku permission denied".
    2. A second tap on "Grant access" shows no dialog and leaves no Shizuku-side log.
    3. The user enables Inspiration in Shizuku → Application management, comes back to the same process (pid 2508) and switches tabs. The state still reads denied.
    4. The app's own DB records `capture_miss` rows at 23:14:00 (CHARGE_JOB), 23:14:14 and 23:14:52 (APP_OPEN), all `deadline_or_service_unavailable`, while access was in fact granted.
    5. Only force-stopping and relaunching recovered ("Binder active · UID 2000").
  - **Concrete Fix:**
    ```kotlin
    // ShizukuManager: add `fun refresh()`; MainActivity.onStart() calls container.shizukuManager.refresh().
    override fun refresh() { if (started) updateState() }

    override fun requestPermission() {
        if (!started) return
        if (mutableState.value !is ShizukuState.PermissionRequired) updateState() // picks up grants made in Shizuku
        if (mutableState.value !is ShizukuState.PermissionRequired) return
        try { facade.requestPermission(PERMISSION_REQUEST_CODE) } catch (_: RuntimeException) { synchronized(bindingLock) { transportLostLocked() } }
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, _ -> updateState() } // rationale decides Denied vs Required
    ```
    Update `deniedPermissionResultUpdatesStateWithoutBinding`, which pins PermissionDenied with rationale=false.

- **[F-04] [MEDIUM] Cold-process captures fail instead of waiting for the Shizuku binder, and Ready never re-runs the work that failed** [FIXED in e89d42b]
  - **Category:** Concurrency (startup race)
  - **Location:**
    - `ShizukuManagerImpl.kt`: initial state at :50 (NotRunning), `withTelemetryService` at 175-203, `acquireServiceUse` at 234-243
    - `app/src/main/kotlin/com/akane/inspiration/AppContainer.kt:275-282`
    - `app/.../capture/ChargingCaptureRunner.kt:43`
    - `ChargingCaptureJobService`
  - **Confidence:** Confirmed by call trace and reproduction (`AuditReproV2bTest` fails: "withTelemetryService(2000) returned null at once because the binder arrived 200 ms after the call"). **Not reproduced on the device:** on redroid the binder arrived during provider init (+2 ms), before `Application.onCreate`, so the race window did not open. The timing on real devices is unmeasured.
  - **Root Cause & Execution Trace:**
    1. Shizuku 13.1.5 delivers the binder asynchronously (`ShizukuProvider.onCreate` only initialises Sui; checked with javap).
    2. In a cold process `start()` sees `pingBinder() == false`, and the state becomes `NotRunning`.
    3. `withTelemetryService` returns at once and never uses its timeout:
       ```kotlin
       if (!acquireServiceUse()) return null   // acquireServiceUse(): false unless state is Ready
       ```
    4. App open goes `captureAppOpen` → `RefreshResult.Failed` → 12 s backoff. On Ready, `AppContainer.start` only does:
       ```kotlin
       reconcileOnEachReady(shizukuManager.state) { refreshCoordinator.clearFailureBackoff(); toolkit.reconcile() }
       ```
       Nothing re-runs the refresh.
    5. The charge job runs in a cold process, so `ingestHistory` gets null, a miss is recorded, and the next attempt is the 45-minute re-arm.
  - **Failure / Exploit Scenario:** The app is usually dead while charging with the screen off, so the charge job can miss most of its runs. The full captures and history ingest behind capacity and health estimates are then lost systematically, and the first Cockpit refresh shows a failure.
  - **Concrete Fix:**
    ```kotlin
    // withTelemetryService: wait for a transient NotRunning to become Ready, within the caller's budget.
    if (!acquireServiceUse()) {
        if (!started || mutableState.value.isTerminal()) return null  // NotInstalled / PermissionRequired / PermissionDenied
        val ready = withTimeoutOrNull(timeoutMs) { state.first { it is ShizukuState.Ready || it.isTerminal() } }
        if (ready !is ShizukuState.Ready || !acquireServiceUse()) return null
    }
    ```
    Also re-run a failed refresh on the Ready transition: `{ refreshCoordinator.clearFailureBackoff(); refreshCoordinator.refreshIfLastFailed(); toolkit.reconcile() }`.

- **[F-05] [MEDIUM] Capture deadlines cannot interrupt blocking binder reads; late history is discarded whole and the broadcast light fallback is lost** [FIXED in be01588]
  - **Category:** Concurrency / Resilience
  - **Location:**
    - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/capture/SnapshotCapturer.kt:32-81`
    - `core/telemetry-engine/.../source/AndroidTelemetrySource.kt`: `full` at 34-57, `readDump` at 118-127, `readHistory` at 129-150, `DEFAULT_SERVICE_TIMEOUT_MS` at 167
    - `ShizukuManagerImpl.kt:185,191`
    - `app/.../capture/CapturePolicy.kt:41-44,74-81`
    - `app/.../capture/BroadcastCaptureRunner.kt:17-19`
  - **Confidence:** Confirmed by reproduction (3 methods fail):
    - `app` `AuditReproV2aTest`: light row expected `[POWER_DISCONNECTED]` but was `[]`.
    - `telemetry-engine` `AuditReproV2aTest`: `ingestHistory(deadline 400 ms)` returned after 1239 ms; 8 pages were fetched, none persisted, and the cursor did not move.

    Device timing could not show it: on redroid `batterystats --proto` takes 18–20 ms with almost no history.
  - **Root Cause & Execution Trace:**
    1. `withTimeoutOrNull(deadlineMs) { source.full(...) }` wraps `withContext(io) { block(service) }`.
    2. Dump reads cancel cooperatively (`DumpReader` uses O_NONBLOCK with `ensureActive()`). The binder calls `readBatteryUsage(0)`, `readKernelBattery` and `readCpuTimeInState` never suspend, and neither does `readHistory`, which makes up to 8 pages in a plain `repeat`:
       ```kotlin
       repeat(MAX_HISTORY_PAGES) { val page = runCatching { service.readBatteryHistory(statsStartMs, afterHistoryTimeMs, HISTORY_PAGE_SIZE) } … }
       ```
    3. A late read returns null, and all pages are discarded: `read?.let { persistHistory(it.history) }`. The cursor never moves, so a large backlog never makes progress.
    4. For broadcast Full plans, a blocking read that outlives the 2000 + 1500 ms guard gets its miss recorded (under `NonCancellable`). The fallback then runs in a cancelled coroutine:
       ```kotlin
       .let { result -> if (result is CaptureResult.Missed) captureLight(plan.trigger) else result }
       ```
       So the light row the KDoc promises ("so the trigger still has a row") is never inserted.
    5. One 2000 ms `serviceTimeoutMs` bounds both the cold-bind wait and every dump read. Timeout and overflow both come back as `Unavailable("io")`, so they cannot be told apart.
  - **Failure / Exploit Scenario:**
    - When batterystats' external-stats sync is slow, a POWER_DISCONNECTED broadcast leaves no snapshot, so "since unplug" has no anchor.
    - After a long gap, every ingest refetches the same 8 pages, times out and discards them. History is never ingested, and binder work repeats on every refresh, marker and charge job.
  - **Concrete Fix:**
    ```kotlin
    // CapturePolicy.captureFor: the fallback must survive the guard that cancelled the full read.
    is CapturePlan.Full -> captureFull(plan.trigger, plan.deadlineMs, ingestHistory = false)
        .let { r -> if (r is CaptureResult.Missed) withContext(NonCancellable) { captureLight(plan.trigger) } else r }
    ```
    - Make `readHistory` a `suspend` function that calls `coroutineContext.ensureActive()` between pages and hands each page to a sink. `SnapshotCapturer` then persists the page and advances the cursor at once, independently of the whole-read deadline.
    - Run the remaining non-suspending binder calls through `async(io)` + `await` under the deadline, so that the caller is released; the abandoned binder thread finishes on IO.
    - Split `bindTimeoutMs` from `dumpTimeoutMs`, and return distinct reasons for timeout and overflow.

- **[F-06] [MEDIUM] Wall-clock time is used as elapsed time: a forward clock jump permanently prunes history (also the dexopt hold deadline and screen-on duration)** [FIXED in 3a9563a, df2bf4e, 5c199d5]
  - **Category:** Logic Bug (data loss)
  - **Location:**
    - Prune: `app/.../AppContainer.kt:127-131` and `app/.../capture/ChargingCaptureRunner.kt:63` call `store.prune(clocks.wallMs())`, which reaches `core/database/src/main/kotlin/com/akane/inspiration/database/Daos.kt:278-289`.
    - Dexopt hold deadline: `ToolkitActions.kt:1061,1081-1093`, with `nowMs = clocks::wallMs` at `AppContainer.kt:207`.
    - Screen-on segments: `core/telemetry-engine/.../status/UnplugSummary.kt:125-131`, a wall delta; the screen-off branch at :136-139 correctly uses `elapsedRealtime`.
  - **Confidence:** Confirmed by reproduction (3 methods fail):
    - `database` `AuditReproV2cTest`: a 2-day-old same-boot snapshot is deleted after a +365 d jump.
    - `telemetry-engine` `status/AuditReproV2cTest`: the hold of a RUNNING job is dropped after 1 min; screen-on shows 0.245 %/h where 12 %/h is correct.

    Not run on the device: redroid shares the host's kernel clock.
  - **Root Cause & Execution Trace:**
    ```kotlin
    suspend fun prune(nowWallMs: Long) {
        val cutoffWallMs = nowWallMs - RETENTION_MS
        … deleteUnmarkedSnapshotsBefore(cutoffWallMs); deleteHistoryBefore(cutoffWallMs); deleteMissesBefore(cutoffWallMs)
    }
    ```
    Every successful app-open capture and charge job calls it with the current wall time. Nothing checks that time against the monotonic clock.
  - **Failure / Exploit Scenario:**
    - A manual date change, a bad NITZ/NTP correction or a test-date setting moves the clock forward. The next refresh deletes every unmarked snapshot, all battery history and all misses. History cannot be re-ingested, because the cursor only moves forward and batterystats keeps a bounded history.
    - A jump in the middle of a dexopt job drops the binding hold; the idle unbind then kills the service process that started the job.
    - The status notification's screen-on rate is distorted by any clock change during screen-on.
  - **Concrete Fix:**
    ```kotlin
    // Before pruning (AppContainer.captureFull and ChargingCaptureRunner):
    val now = clocks.wallMs()
    val newest = store.newestClock()  // (wallMs, elapsedRealtimeMs, bootCount) of the newest snapshot
    val offsetDrift = newest?.takeIf { it.bootCount == clocks.bootCount() }
        ?.let { abs((now - clocks.elapsedRealtimeMs()) - (it.wallMs - it.elapsedRealtimeMs)) }
    if (offsetDrift == null || offsetDrift <= CLOCK_JUMP_TOLERANCE_MS) store.prune(now)  // skip and retry later after a jump
    ```
    Compute the dexopt deadline from an injected `elapsedRealtime` clock. In `UnplugSummary`, use the same-boot elapsed delta for screen-on segments, as the screen-off branch already does.

- **[F-07] [MEDIUM] Toolkit override recompute multiplies privileged reads under both locks: uncached 8-process gate × every historically touched target** [FIXED in 98a69ee]
  - **Category:** Performance
  - **Location:**
    - `ToolkitActions.kt`: `activeOverrides` at 214-219, `computeOverrides` at 810-834, `chainOriginal` at 775-783, `MAX_ROWS = Int.MAX_VALUE` at 1192
    - `core/database/.../Daos.kt:197-213` (`pruneTo` keeps DONE/RESTORED app-op, bucket and doze rows forever)
    - `ToolkitExecutor.kt`: `gate`/`hardDeny` at 197-220, `getAppOps` at 58-67, `getStandbyBucket` at 85-92
    - `core/shizuku-ipc/.../toolkit/Inspection.kt:72-92`
  - **Confidence:** Confirmed by reproduction (`AuditReproV1eTest`: "activeOverrides() issued 12 privileged reads for 6 packages whose chains are all closed") and device timing.
  - **Root Cause & Execution Trace:**
    1. Each gated service call, reads included, runs `hardDeny` → `inspector.protectedPackages()`. That is 4× `cmd role get-role-holders`, 3× `cmd settings get secure` and 1× `dumpsys device_policy`, with no cache, under `synchronized(writeLock)`.
    2. `getAppOps` therefore costs 8 + 3 spawns and `getStandbyBucket` costs 8 + 1.
    3. On the client, `computeOverrides` reads state for every target whose newest row is DONE before checking whether its chain is closed. A toggle back writes a plain DONE row with `restoresId == null`, so closed chains stay candidates forever under unbounded retention.
    4. The recompute runs on every `changes` bump and every collector start (Toolkit `follow` at `ToolkitViewModel.kt:139`, the X-Ray sheet at `XRayViewModel.kt:218`, `AppContainer.installedApps()` at each Toolkit load), all under the ToolkitActions mutex.
  - **Failure / Exploit Scenario:** On redroid, one set of the 8 protection commands took 66, 63 and 63 ms (three runs), against 12–13 ms for the single bucket read it gates. Every target ever touched adds 9–20 process spawns per recompute. Execute, plan and restore queue behind it, so Toolkit and X-Ray latency grows steadily over the install's lifetime, and is worse on slower OEM devices.
  - **Concrete Fix:**
    ```kotlin
    // ToolkitExecutor (guarded by writeLock): short-TTL cache; keep hardDeny on reads (ToolkitActions.prepare relies on it for ForceStop).
    private var protectedCache: Pair<Long, ProtectedPackages>? = null
    private fun protectedPackages(): ProtectedPackages? {
        val now = elapsedRealtime()
        protectedCache?.takeIf { now - it.first < PROTECTED_TTL_MS }?.let { return it.second }
        return inspector.protectedPackages()?.also { protectedCache = now to it }
    }
    // ToolkitActions.computeOverrides: drop candidates whose chain is provably closed before any read.
    val live = newest.filterNot { sameState(it.kind, it.afterRaw, chainOriginal(it, rows)) }
    ```
    Clear the cache on any write. Bound retention by pruning chains that are closed and older than N days.

- **[F-08] [MEDIUM] Exceptions escaping ToolkitActions are not contained: Toolkit and X-Ray launches and the dexopt supervisor crash the process, and the reconcile collector dies** [FIXED in 635d67c]
  - **Category:** Resilience
  - **Location:**
    - `feature/toolkit/.../ToolkitViewModel.kt`: 110-119 (history collect at 117), 136-142, 306-318, 368, 477-485, 494-502
    - `feature/xray/.../XRayViewModel.kt`: 213-222, 270-293, 304
    - `ToolkitActions.kt`: `run` at 585-589 (PENDING insert before the try), `defaultSupervisorScope()` at 1190, `superviseHold` at 1081-1093
    - `app/.../di/ReconcileOnReady.kt:21-27`
    - `app/.../di/ApplicationScope.kt:15-18`
  - **Confidence:** Confirmed by reproduction (`AuditReproV1fTest`: "SQLiteFullException … reached the thread's uncaught-exception handler"). The supervisor and collector paths are confirmed by call trace.
  - **Root Cause & Execution Trace:**
    1. Room errors are RuntimeExceptions: `SQLiteFullException`, `SQLiteDiskIOException`, and `IllegalStateException` on a closed database. They propagate unguarded from `audit.insert`/`update` in `run`, `settle` and `refuse`, and from `history()`/`activeOverrides()`.
    2. Unlike the other feature ViewModels, which use `attempt {}`, Toolkit and X-Ray call bare `viewModelScope.launch { … }`. An uncaught exception reaches the thread's default handler, which kills the process on Android, and `busy` stays true.
    3. `defaultSupervisorScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)` has no handler, and `AppContainer` passes no scope. A Room error in `refreshDexoptLocked` during a hold therefore crashes the process.
    4. `reconcileOnEachReady` ends in `.collect { reconcile() }`. The first exception ends the collector for the life of the process (the ApplicationScope handler only logs). No further Ready reconciles or backoff clears happen, and FORCE_STOP and dexopt PENDING rows never settle.
  - **Failure / Exploit Scenario:** On a nearly full disk, tapping any Toolkit action crashes the app. A dexopt hold running in the background crashes it with no user action at all.
  - **Concrete Fix:**
    ```kotlin
    // Toolkit/X-Ray: wrap every launch like Cockpit/Health/Sleep Lab do with their `attempt(block): T?` helper
    viewModelScope.launch {
        attempt { execute(prepared, confirmed = false) }
            ?: finish(prepared, ToolkitNotice.Rejected(Problem.Failed(code = null)))  // also clears busy
    }
    // ToolkitActions
    fun defaultSupervisorScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.w(TAG, "toolkit supervisor", e) })
    // (better: AppContainer passes applicationScope)
    // ReconcileOnReady
    .collect { try { reconcile() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "reconcile on Ready failed", e) } }
    ```

- **[F-09] [MEDIUM] A batterystats process name is used as the app's package identity in X-Ray and Sleep Lab** [FIXED in 627b424]
  - **Category:** Logic Bug
  - **Location:**
    - `core/shizuku-ipc/.../client/BatteryStatsClient.kt:268`
    - `core/telemetry-engine/.../diff/SnapshotDiffer.kt:208-218`
    - `feature/xray/.../XRayMapper.kt:52-58,92`
    - `XRayViewModel.kt:171-176`
    - `feature/sleeplab/.../SleepLabViewModel.kt:93-104`
    - `SleepLabMappers.kt:139,182`
    - `app/.../CachedAppLabels.kt:59-64`
  - **Confidence:** Confirmed by reproduction (`AuditReproV3fTest`: expected `com.example.messenger` but was `com.example.messenger:remote`). The framework producer was verified by disassembly: `CpuPowerCalculator` calls `setPackageWithHighestDrain(processName)`. On redroid every UID has 0.0 mAh (no battery), and `packageName` was null on all 104 stored rows, so this cannot be observed there.
  - **Root Cause & Execution Trace:**
    1. `UidBatteryConsumer.getPackageWithHighestDrain()` returns a process name. The differ stores it raw; its own comment says it "may carry a ':process' suffix".
    2. X-Ray asks PackageManager only when the name is null:
       ```kotlin
       val pkg = row.packageName ?: deps.appLabels.packageForUid(row.uid) ?: return@map row
       ```
    3. `CachedAppLabels.label` falls back to the raw string.
    4. `PackageNames.isValid` rejects `:` names, so every quick action is unavailable. Dotted process names such as `com.google.android.gms.persistent` pass the pattern but are not installed.
  - **Failure / Exploit Scenario:**
    - The top drainers on most phones (GMS's persistent process, messengers' `:push`/`:remote` processes) appear as raw process strings with no icon.
    - Their X-Ray quick actions are unavailable even though the app is valid.
    - Sleep Lab labels them the same way.
  - **Concrete Fix:**
    ```kotlin
    private suspend fun withIdentities(rows: List<AppRow>): List<AppRow> = rows.map { row ->
        val owners = deps.appLabels.packagesForUid(row.uid)          // new: expose AppLabelSource.loadPackagesForUid (CachedAppLabels.kt:20,37)
        val pkg = row.packageName?.substringBefore(':')?.takeIf { it in owners } ?: owners.minOrNull() ?: return@map row
        row.copy(packageName = pkg, label = deps.appLabels.label(pkg))
    }
    ```
    Use the same resolution in Sleep Lab's `withOwnerPackages`.

- **[F-10] [MEDIUM] The deep-sleep sentence and tiles have no minimum screen-off window: "Deep sleep ratio was 0.0%" after 20 seconds** [FIXED in cbd6298]
  - **Category:** Logic Bug (user-facing diagnosis)
  - **Location:**
    - `core/diagnostics/.../story/BatteryStoryBuilder.kt:22`
    - `core/telemetry-engine/.../diff/SnapshotDiffer.kt:381-385`
    - the same unfloored ratio in `feature/dashboard/.../CockpitMappers.kt:82-90` and `feature/sleeplab/.../SleepLabMappers.kt:247-251`
  - **Confidence:** Confirmed by reproduction (`AuditReproV3eTest`: 20 s of screen-off yields `[Summary(...), DeepSleep(ratio=0.0)]`).
  - **Root Cause & Execution Trace:** `sleep?.deepSleepRatio?.let { add(StorySentence.DeepSleep(it)) }` applies no floor. `deepSleepRatio` is computed whenever `screenOffRealtime > 0`. By contrast, the rate sentences have a 15-minute floor (`IntervalMetrics.kt:30,133-134`) and AwakeViolation needs 30 minutes.
  - **Failure / Exploit Scenario:** The default *Since unplugged* view minutes after unplugging, or a fresh marker, says the phone failed to sleep ("Derin uyku oranı %0,0") after a short screen-off spent awake. A misleading headline appears in the most common short-interval case.
  - **Concrete Fix:**
    ```kotlin
    sleep?.takeIf { (it.screenOffMs ?: 0L) >= MIN_SLEEP_WINDOW_MS }?.deepSleepRatio?.let { add(StorySentence.DeepSleep(it)) }
    // MIN_SLEEP_WINDOW_MS = 15 * 60_000L, shared with the Cockpit tile and Sleep Lab deepSleepUi
    ```

- **[F-11] [MEDIUM] The obscured-touch guard swallows every tap on privileged controls with no feedback** [FIXED in e7af3a2]
  - **Category:** Logic Bug (UX failure mode of a security control)
  - **Location:** `feature/toolkit/.../ToolkitTouchGuard.kt:15-24` and `feature/xray/.../XRayTouchGuard.kt:14-24`. It is applied to:
    - Doze profiles (`ToolkitSections.kt:93`), dexopt (`:189`), overrides and Reset all (`:251,259`)
    - Toolkit app controls (`ToolkitApps.kt:240`)
    - the dialog button rows (`ToolkitDialogs.kt:76`, `XRayDetail.kt:563`)
    - X-Ray quick actions (`XRayDetail.kt:282`)
  - **Confidence:** Confirmed by code trace and platform documentation:
    - `WindowManager.LayoutParams` (android-36): touches pass through a `TYPE_APPLICATION_OVERLAY` with alpha ≤ 0.8.
    - `MotionEvent`: the receiving window then gets `FLAG_WINDOW_IS_OBSCURED`.

    Not reproduced: it needs a device with a third-party overlay.
  - **Root Cause & Execution Trace:**
    ```kotlin
    val event = awaitPointerEvent(PointerEventPass.Initial)
    if (event.isObscured()) event.changes.forEach { it.consume() }
    ```
    Nothing is shown to the user. Cancel sits in the same guarded dialog row, so it is dead too.
  - **Failure / Exploit Scenario:** With a screen dimmer or blue-light filter running, which is common among this app's power-user audience, every Toolkit and X-Ray control looks broken and nothing explains why. Leaving a confirmation dialog needs back or a tap on the scrim.
  - **Concrete Fix:** Keep consuming the touch, but report it:
    ```kotlin
    internal fun Modifier.filterTouchesWhenObscured(onBlocked: () -> Unit): Modifier = pointerInput(onBlocked) {
        awaitPointerEventScope { while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.isObscured()) { event.changes.forEach { it.consume() }; if (event.type == PointerEventType.Press) onBlocked() }
        } }
    }
    ```
    Show a snackbar ("Another app is drawing over the screen; pause it to use these controls"), and move the non-destructive Cancel button outside the guard.

### LOW

- **[F-12] [LOW] The hard-deny protected-package list accepts truncated command output and fails open** [FIXED in e2a9232]
  - **Category:** Security
  - **Location:**
    - `core/shizuku-ipc/.../toolkit/CommandRunner.kt`: retention cap at :9, the `ok` definition at :19-28
    - `Inspection.kt`: `protectedPackages` at 72-92, `secureSetting` at 96-99
    - `ToolkitExecutor.kt:211-220`
  - **Confidence:** Confirmed by reproduction (`AuditReproV1cTest`, 2 of 2 fail: with 53 accessibility entries the read is cut at 4096 bytes, and `forceStop(com.example.screenreader)` returned DONE). Not reproducible on redroid: Android strips non-installed services from the setting, and `installedServiceCount=1`.
  - **Root Cause & Execution Trace:** `val ok get() = !timedOut && !failedToStart && exitCode == 0` ignores `truncated`. The role loop (`if (!out.ok) return null`) and `secureSetting` (`if (out.ok)`) parse truncated text. Only `deviceAdmins(policy.text, policy.truncated)` and `readAppOpState` honour truncation, which contradicts the comment at CommandRunner.kt:11-14, "truncation still fails closed".
  - **Failure / Exploit Scenario:** A user with about 48 or more enabled accessibility services can force-stop or restrict the services listed after the cut, for example a screen reader. Reaching that is rare.
  - **Concrete Fix:** `if (!out.ok || out.truncated) return null` in both the role loop and `secureSetting`. Optionally give the accessibility read a larger retention cap.

- **[F-13] [LOW] A same-value write over an existing override hides it** [FIXED in cb9e096]
  - **Category:** Logic Bug
  - **Location:** `ToolkitActions.kt`: `isActive` at 851-856, `activeOverride` at 837-841, `run`/`prepare` at 429-546
  - **Confidence:** Confirmed by reproduction (`AuditReproV1bTest`, 2 of 2 fail for bucket and app-op).
  - **Root Cause & Execution Trace:** `if (norm(current) != after || after == norm(row.beforeRaw)) return false` rejects the newest row (before == after) before `chainOriginal` is consulted. Nothing refuses a no-op write.
  - **Failure / Exploit Scenario:** UI guards (`ToolkitApps.kt:252`, `XRayDetail.kt:383`) block it, but stale controls (an outside change between reads, or the F-02 swap) let it through. The device stays RARE while the override disappears from the list and from *Reset all*.
  - **Concrete Fix:** For non-doze kinds, drop the `after == before` short-circuit and let `sameState(current, chainOriginal(row, rows))` decide.

- **[F-14] [LOW] Shizuku servers older than v13 are reported Ready, but the service rejects every call** [FIXED in 8ff10d9]
  - **Category:** Logic Bug
  - **Location:** `ShizukuManagerImpl.kt:299-313`; `core/shizuku-ipc/.../service/TelemetryUserService.kt:42-44,103-130` (the no-arg constructor sets `appUid = null`)
  - **Confidence:** Confirmed by reproduction (`AuditReproV1hTest`: "manager reports Ready(uid=2000, apiVersion=12)"). No device with a v11 or v12 server was available.
  - **Failure / Exploit Scenario:** The app shows Ready with no data. The Toolkit says "service out of date, restart Shizuku", but restarting doesn't help. The failure is closed, so nothing unsafe happens.
  - **Concrete Fix:** In `refreshState`, treat `facade.getVersion() < MIN_SERVER_VERSION (13)` as a distinct `UnsupportedServer` state with "Update Shizuku" text.

- **[F-15] [LOW] The battery story says "In the last X hours" for intervals that ended in the past** [FIXED in 8699898]
  - **Category:** Logic Bug
  - **Location:** `BatteryStoryBuilder.kt:9-18`, `values/strings.xml:4-12`, `values-tr/strings.xml:4-11`, `AndroidStoryWriter.kt:48-63`, `ReferenceResolver.kt:86-87,101,120-133`
  - **Confidence:** Confirmed by reproduction (`AuditReproV3bTest`: a slice that ended 19 h earlier renders "In the last 4 hours you used 20%…").
  - **Failure / Exploit Scenario:** The time framing of TimeSlice and SinceScreenOff stories is wrong. The slice banner partly offsets it.
  - **Concrete Fix:** Pass `endsNow` (taken from the reference kind) into the builder, and use a neutral "Over these %1$s hours…" / "Bu %1$s saatte…" sentence for past intervals.

- **[F-16] [LOW] `AnalysisState.Ready` does not identify its interval, so the previous interval's story or apps flash under a new selection** [FIXED in 8a4142d]
  - **Category:** Concurrency (state pairing)
  - **Location:** `core/diagnostics/.../AnalysisSession.kt:24-33,54-75`, `AnalysisSelection.kt:14-16`, `CockpitViewModel.kt:61-79`, `XRayViewModel.kt:143-151`, `SleepLabViewModel.kt:54,85-90`
  - **Confidence:** Confirmed by reproduction (`AuditReproV3dTest`: "With slice B selected the Cockpit still renders slice A's story"). It lasts one dispatcher hop.
  - **Concrete Fix:** Add `kind` to `IntervalAnalysis`. Consumers then map `Ready` whose `analysis.kind != selected` to `Loading`, which replaces the per-screen boundary checks.

- **[F-17] [LOW] Full captures with history ingest are not serialized, so the history cursor can move backwards** [FIXED in e788e1e]
  - **Category:** Concurrency
  - **Location:** `AppContainer.kt:182-186` (`createMarker`), `ChargingCaptureRunner.kt:43`, `SnapshotCapturer.kt:51,76-81`, `core/database/.../store/RoomSnapshotStore.kt:137-146`
  - **Confidence:** Confirmed by reproduction (`AuditReproV2dTest`: cursor expected `(50000, 200)` but was `(50000, 100)`).
  - **Failure / Exploit Scenario:** No data is lost, because the unique index plus `insertIgnore` absorb duplicates. The cost is redundant history paging and overlapping full dumps.
  - **Concrete Fix:** One history-ingest mutex shared by refresh, marker and the charge job, and/or `setHistoryCursor` that only moves forward within a `statsStartMs`.

- **[F-18] [LOW] Live calibration falls back to a sysfs current cached at process start** [FIXED in d7c4f36]
  - **Category:** Logic Bug
  - **Location:** `AndroidTelemetrySource.kt:98-100`, `AndroidCalibrationInputReader.kt:12,18-20` (`by lazy`), `PowerCalibrator.kt:77-78`
  - **Confidence:** Confirmed by reproduction (`AuditReproV2gTest`: the live current is -150 mA (cached) instead of the fresh -1200 mA). The narrow trigger needs a device.
  - **Root Cause:** `sysfsCurrentRaw = kernel?.currentNowRaw ?: calibrationInputReader.read().sysfsCurrentRaw`. A missing kernel value reuses the process-start value, which `PowerCalibrator` prefers over `bmCurrentNowUa`. The KDoc says live readings "always use the fresh" values. The stale current is persisted while charging and skews charge integration.
  - **Concrete Fix:** `sysfsCurrentRaw = kernel?.currentNowRaw`, `sysfsVoltageRaw = kernel?.voltageNowRaw`, with no cached fallback. Use the cached inputs only to work out scale and sign.

- **[F-19] [LOW] Snapshot reads load full snapshots only to read a timestamp or check existence; the status tick reads 30 days of rows** [FIXED in e63d323, ba2ccfd]
  - **Category:** Performance
  - **Location:** `RoomSnapshotStore.kt:105-114,241-298`; `ReferenceResolver.kt:46,50,82,91,136-137`; `ChargingCaptureRunner.kt:50-51`; `StatusNotificationService.kt:104,148`
  - **Confidence:** Confirmed by reproduction (`AuditReproV2eTest`: SinceUnplugged materializes 4 snapshots and Last24h 3, where 2 are needed). The status tick reads base rows only, not child tables.
  - **Concrete Fix:**
    - Reuse `end` when the boundary ends at now.
    - Add a `latestClockByTrigger(trigger)` query.
    - Bound the status query to the unplug window.
    - Add an index on `snapshot(trigger, wallMs)`; `latestByTrigger` currently scans.

- **[F-20] [LOW] Wakelock rows are upserted one statement at a time inside the insert transaction** [FIXED in f8d64c7]
  - **Category:** Performance
  - **Location:** `RoomSnapshotStore.kt:453-489`, `Daos.kt:42-60`
  - **Confidence:** Confirmed by code trace. The framework statement cache absorbs the re-prepare, so the remaining cost is binding, stepping and JNI per row.
  - **Concrete Fix:** Map names first (blank → "unnamed"), merge in memory, then use a single list `@Upsert`.

- **[F-21] [LOW] Timeline building and chart shaping run on the main thread** [FIXED in 42ad487]
  - **Category:** Performance
  - **Location:** `AppContainer.kt:156-162`, `RoomSnapshotStore.kt:122-131`, `CockpitViewModel.kt:141-167`, `SleepLabViewModel.kt:49-64,115-125`, `TimelineBuilder.kt:46-48,64,94`, `SleepLabMappers.kt:33-51`
  - **Confidence:** Confirmed by code trace. Sleep Lab intervals of several days (tens of thousands of events) plausibly cost tens to hundreds of ms of jank. Not profiled.
  - **Concrete Fix:** `withContext(Dispatchers.Default) { hypnogramData(deps.timeline(...)) }`, and make `maintenance` a set.

- **[F-22] [LOW] The monogram initial is uppercased without the locale (Turkish dotted i)** [FIXED in 4739668]
  - **Category:** Logic Bug (i18n)
  - **Location:** `feature/toolkit/.../ToolkitApps.kt:178`, `feature/xray/.../XRayScreen.kt:413`. `ToolkitText.kt:145-150` already does this correctly.
  - **Confidence:** Confirmed by code trace.
  - **Concrete Fix:** `label.firstOrNull { it.isLetterOrDigit() }?.toString()?.uppercase(currentLocale())`.

- **[F-23] [LOW] Clock times are always 24-hour, and the hypnogram formatter uses `Locale.getDefault()` instead of `LocalLocale`** [FIXED in 781d9de (locale part); 24-hour clock SKIPPED: `TelemetryFormat` documents one deliberate date-time style, so following the system hour cycle is a product decision that would also widen every time label and re-baseline every screenshot module]
  - **Category:** Logic Bug (i18n)
  - **Location:** `core/designsystem/.../TelemetryFormat.kt:31-32,122-130,149-151`, `SleepHypnogram.kt:448-449`
  - **Confidence:** Confirmed by code trace. The 24-hour style looks deliberate (documented as "the one date-time style"), and the locale mismatch has no realistic effect without per-app locales.
  - **Concrete Fix:** Derive the pattern from `DateFormat.is24HourFormat(context)`, and pass `LocalLocale.current.platformLocale` into the hypnogram.

### Candidates rejected during validation (not findings)
- **Charging time hides screen-on use in the screen-off-only rule gate:** rejected. Android's sensor, GPS, wakelock and alarm timers use on-battery time bases, verified by disassembling `BatteryStatsImpl`, so they are frozen during the plugged spans the app counts as `chargingMs`.
- **Insight ordering compares magnitudes in different units:** rejected as a defect. The behaviour is deliberate, documented in KDoc and pinned by `InsightOrderTest`. A design note remains: an explicit rule-priority list would make the intent clearer.
- **Rejected sub-claims:**
  - Shizuku revocation while bound ending NotRunning for good: the server force-stops the client.
  - Dump reads not being cancellable: `DumpReader` is cooperative.
  - The status tick loading child tables.
  - Per-row statement re-prepare cost.

---

## 3. Dead Code, Redundancy & Technical Debt Cleanup

Each item below was checked by call-site search across the source roots.

**Unused reads**
- `FullRead.power` / `PowerDump` / `parsePower`: the `dumpsys power --proto` read (`AndroidTelemetrySource.kt:39,112-113`) runs on every full capture, but `SnapshotCapturer.mergeFull` never consumes it. It took 11 ms per run on the device. Remove the read or use it. [FIXED in a9dfd84: read removed]
- Parsed dump fields that are never consumed: `UidDump.processStateMs`, `DumpSnapshot.cpuFrequenciesKhz`, `AlarmDump.pendingWakeups`, `totalFlightMs`, `DumpSnapshot.warnings`, `SnapshotDiff.durationMs`. [FIXED in 6382e3a; `SnapshotDiff.durationMs` kept because the debug probe reads it]

**Debug-only code that ships in release**
- Transactions 4 and 5 (`readPowerMonitors`/`readDeviceIdle`) with `PowerStatsClient`, `DeviceIdleReader`, `PowerManagerClient`, `IdleFlags` and the matching stubs.
- `DumpTarget.DEVICEIDLE_TEXT`, `DozeRaw.AOSP_KEYS` and the stub constants `BatteryConsumer.POWER_COMPONENT_BASE`/`COUNT`.
- Either gate them behind a debug source set or remove them. [SKIPPED: changes the AIDL contract the debug probe depends on; needs its own ticket]

**API with no production caller**
- `ShizukuManager.stop()`
- Test-only DAO API: `SnapshotDao.deleteById`, `insertAvailability` (single)
- `store.latest()` (debug only)
- [SKIPPED: all three are harmless API surface; removal is cleanup without a defect]

**Data written but never read in production**
- `telemetry_hourly` is written by prune's downsample and never read in production or pruned. It grows without bound, though slowly.
- `capture_miss` is write-only. The device run showed it is valuable diagnostic evidence, so surface it rather than delete it.
- [SKIPPED: both await a product decision (read, prune or drop `telemetry_hourly`; where to surface `capture_miss`)]

**Platform leftovers**
- `backup_rules.xml` / `android:fullBackupContent` do nothing on minSdk 31. [FIXED in 941dc78]
- The `SDK_INT >= P` check at `InspirationApplication.kt:22` is always true (lint ObsoleteSdkInt). [FIXED in 941dc78]
- No `shrinkResources` (lint NotShrinkingResources). [SKIPPED: needs a release-build smoke test on a device to prove no resource is stripped that reflection or the debug gallery needs]

**Duplication and traps**
- `ChargingCaptureRunner` is a trivial wrapper. [SKIPPED: harmless; F-06 and F-19 now route prune and clock reads through it]
- `attempt {}` is duplicated across three feature modules, and `currentLocale()` is duplicated too. [`attempt {}` FIXED in 635d67c (one shared `telemetry.app.attempt`); `currentLocale()` SKIPPED: not consolidated]
- `pruneTo` kind literals are duplicated. [FIXED in 98a69ee: `ToolkitAuditLog` constants]
- Enums are stored by `name`, so `valueOf` throws on a rename. [SKIPPED: no rename is planned; a tolerant decoder belongs with the next schema change]
- `toBareSnapshot` uses an empty availability map, a trap for future callers. [SKIPPED: no current caller is affected]
- `AndroidSensorCatalog` has a silent `catch (Throwable)`, which would classify every sensor as OTHER. [FIXED in 50cc552]
- `SleepLabViewModel.uidPackageCache` caches `null` forever, and `labelCache` is unbounded. [FIXED in 627b424: both removed; `AppLabels.packagesForUid` resolves instead]

**Hot-path inefficiencies**
- `SnapshotDiffer.kt:284-335` filters per-UID names per name, which is O(n²). [FIXED in 57e0554]
- Each `readBatteryHistory` page re-runs `getBatteryUsageStats` with history and re-walks it from the start (`BatteryStatsClient.kt:79-97`, `BatteryHistory.kt:59-82`). [SKIPPED: a fix needs iterator state held in the Shizuku service across AIDL calls]
- Formatters are allocated per call (`TelemetryFormat.kt:149-165`, and `ScrubChartMath.clockFormat` on every scrub frame). [FIXED in 10ec36c]

**Test-suite integrity** (Phase 3, failure domain 6)
- **The fakes don't model blocking.** They use `delay()` where production blocks in binder calls, so F-05 and F-04 cannot be seen in existing tests.
- **Untested components:** `StatusNotificationService`, `ChargingCaptureJobService`, the receivers, `MainActivity`'s permission prompt, both touch guards, and the Toolkit/X-Ray ViewModels with a throwing store.
- **Missing cases:**
  - a cold Shizuku binder, a one-time deny, a later grant, Shizuku below v13
  - NOT_EFFECTIVE with before≠after, a same-value write, recompute cost
  - wall-clock jumps
  - truncated command outputs
  - `chargingMs > 0` in rule fixtures
  - process-name packages
  - an analysis update during an X-Ray confirmation
  - `MigrationTestHelper` migration tests
- **Near-tautological test:** `ChargingCaptureRunnerTest` mostly checks its own fakes.
- **Repro tests are ready to adopt:** the 26 methods written for this audit (`AuditReproV1*`, `AuditReproV2*`, `AuditReproV3*`) fail on 60fe3d5 and are ready-made regression tests for remediation. They exist only in the audit evidence export, not in the repository.
- [Remediation: the repro methods were adopted into the regression tests of each finding's commit. Each one failed before its fix and passes after it. Migration coverage uses exported-schema tests (`InspirationDatabaseTest`, including a 3→4 index migration with an `EXPLAIN QUERY PLAN` check) instead of `MigrationTestHelper`. Blocking-aware fakes were added for F-04/F-05 (`AndroidTelemetrySourceDeadlineTest`, `ShizukuManagerReadyWaitTest`). The untested receivers/services remain uncovered.]

**Lint warnings to address in passing:** `PrivateApi` (`BatteryStatsClient.kt:291`), `UseRequiresApi` (`PowerStatsClient.kt:116`), `UseTomlInstead` (`core/shizuku-ipc/build.gradle.kts:22`), `PluralsCandidate` ×2 (`feature/toolkit` `strings.xml:13,88`), `UseKtx`, `AppBundleLocaleChanges`. Version-bump warnings are out of scope without a ticket. [FIXED in 8a4b75e, cd54565, 716c8e3: app lint warnings 17 → 9; the remaining 9 are 8 version-bump notices and `NotShrinkingResources`]

---

## 4. Prioritized Remediation Roadmap

**Phase 1: Immediate hotfixes**, each an isolated commit with its repro test promoted to a regression test:
1. **F-02:** pin the X-Ray confirmation target; keep the open sheet's package. (`AuditReproV1dTest`)
2. **F-01:** store before≠after NOT_EFFECTIVE as a DONE chain row; apply the same in reconcile Mismatch. (`AuditReproV1aTest`)
3. **F-03:** add `refresh()`, call it from `onStart`, let `requestPermission` re-read state, and let the listener defer to `updateState()`. (`AuditReproV1gTest`)
4. **F-08:** add `attempt {}` around the Toolkit and X-Ray launches, a handler on the supervisor scope, and a guarded reconcile collector. (`AuditReproV1fTest`)
5. **F-06 (prune):** add the clock-jump guard before `prune`. (database `AuditReproV2cTest`)
6. **F-12:** fail closed on truncated role and settings output. (`AuditReproV1cTest`)
7. **F-10:** add the 15-minute floor to the deep-sleep sentence and tiles. (`AuditReproV3eTest`)

**Phase 2: Structural and concurrency hardening**
1. **F-04:** wait for Ready inside `withTelemetryService`, and re-run a failed refresh on Ready. (`AuditReproV2bTest`)
2. **F-05:**
   - Use a `NonCancellable` light fallback.
   - Page history cooperatively, persisting each page.
   - Split the bind and dump timeouts and give them distinct reasons.
   - Remove the power dump.

   (both `AuditReproV2aTest`)
3. **F-07:** add the protected-package TTL cache, prune closed chains before reads, and bound audit retention. (`AuditReproV1eTest`)
4. **F-17:** add one history-ingest mutex and a cursor that only moves forward. (`AuditReproV2dTest`)
5. **F-06 (dexopt hold, UnplugSummary):** use monotonic clocks. (status `AuditReproV2cTest`)
6. **F-13, F-14, F-18:** small, isolated fixes with existing repros.
7. **F-11:** give the touch guard feedback, and move Cancel outside the guard.

**Phase 3: Architectural refactoring and test coverage**
1. **One identity resolver for the app ↔ UID ↔ package mapping**, shared by X-Ray, Sleep Lab and the Toolkit (F-09, and the root of F-02). Treat batterystats' process name as a hint, never an identity.
2. **Interval-tagged analysis results** (F-16), plus `endsNow` for the story (F-15).
3. **Off-main shaping and narrower store reads:** shaping off the main thread (F-21); clock-only queries, a `trigger` index and a batched wakelock upsert (F-19, F-20).
4. **Test infrastructure:** blocking binder fakes, a cold-Shizuku harness, a clock-jump fixture, `MigrationTestHelper` coverage, and ViewModel tests with a failing store.
5. **Debt cleanup:** the dead-code and debt list above, and the i18n fixes (F-22, F-23).

---

## Coverage Ledger

**Subsystems examined.** Every module was read by a dedicated fresh reviewer context, each also applying the Android/Kotlin/Shizuku checklist:

| Area | Modules | Reviewer |
|---|---|---|
| IPC | `:core:shizuku-ipc` (client, AIDL, UserService, toolkit executor, command runner, inspection), `:core:hidden-api-stub` | W1 |
| Telemetry engine | `:core:telemetry-engine` (sources, capture, diff, calibration, toolkit actions, status) | W2 |
| App | `:app` (DI/AppContainer, capture policy, receivers, job service, status notification, refresh coordinator, manifests, R8 rules) | W3 |
| Database and diagnostics | `:core:database` (Room schema, DAOs, migrations, store mapping) and `:core:diagnostics` (interval metrics, rule engine, story, analysis session) | W4 |
| Features and design system | `:feature:{dashboard, xray, sleeplab, health, toolkit}` and `:core:designsystem` | W5 |

Build logic was also covered (`build-logic/`, the version catalog, module build files) through recon and lint.

**Tools actually run**
- `scripts/repo-recon.sh`: source inventory.
- `./gradlew testDebugUnitTest lintDebug --continue --max-workers=4`: exit 0.
- `./gradlew testDebugUnitTest --rerun --continue --max-workers=4`: exit 0; 1,083 tests, 0 failures, 0 errors, 0 skipped. Per module:
  - app 107, database 30, designsystem 101, diagnostics 140, shizuku-ipc 205, telemetry-engine 244
  - dashboard 36, health 66, sleeplab 55, toolkit 51, xray 48
- Lint: 0 errors, 17 warnings, listed in §3 plus the dependency and target-version notices.
- `./gradlew :app:minifyReleaseWithR8 --max-workers=4`: exit 0. The mapping keeps `TelemetryUserService` and `ITelemetryUserService$Stub` and retains `TelemetryUserService.<init>(Context)`, so Shizuku's class-name instantiation survives R8.
- A secrets sweep of the source roots found only `gradleProperty()` lookups for release signing.
- 26 reproduction test methods in a pristine `git archive` export (`~/.cache/inspiration-audit/run-884ftxmi/repro`), all failing as predicted. The commands and outputs are in the evidence ledger.
- Device checks on redroid15_x86_64 (API 35) with Shizuku: UI-driven permission flow, cold launch, database inspection, command timing and settings experiments, via Mobile MCP and adb.

**Unavailable checks**
- No dependency CVE scanner is available offline (no OWASP or OSV tooling installed). Dependency versions were reviewed only through lint's version notices.
- Screenshot validation (`validateDebugScreenshotTest`) was not run.
- The redroid image has no battery (power model 0 mAh), app standby disabled, a single accessibility service, and a clock shared with the host. That made F-01, F-05, F-06, F-09 and F-12 unobservable at runtime; they rest on unit reproduction.
- No physical or OEM device, no API 31–34 device, and no Shizuku server below v13.

**Remaining gaps, unconfirmed and not reported as findings**
- **Dotted custom process names** (for example `com.google.process.gapps`) toggle `packageChanged` and may drop an app's row from X-Ray. This needs a device.
- **A successful broadcast full read can lose its insert:** if it finishes just under the 2 s budget, only 1.5 s of guard budget remains for the Room insert, which may be cancelled with no miss recorded. Not reproduced.
- **Device timings not measured:**
  - the cold-start binder push (F-04)
  - `readBatteryUsage(0)` blocking time (F-05)
  - main-thread shaping (F-21)
- **Overlay and touch-guard behaviour (F-11):** whether the guard blocks scrolling has not been checked.
- **Room migrations** were verified only by reading them against the exported schemas; there is no `MigrationTestHelper` run.
- **Scope of the repro runs:** each was run once; no flakiness study.

---

## 5. Audit Isolation

**Source revision and state:** see the header. Unmodified source was exported to `~/.cache/inspiration-audit/run-884ftxmi/{repro,target}` for repro tests and the device target build. The main checkout was not modified, and the audit guard `check` reported protected files and the original report unchanged before drafting.

**Input exclusions:** before freeze, the following were not read:
- `PROJECT.md`, `MAP.md`, `PROGRESS.md`, `docs/`
- the generated codebase map (beyond the hook injection noted below)
- git log, blame or show
- earlier reports (none existed on disk at `begin`)
- the Sidequest board

**Contamination ledger**
- **Coordinator exposure:**
  - SessionStart hooks auto-injected `.claude/.codebase-info/INDEX.md`, an architecture summary with no finding content.
  - The gitStatus snapshot included the last five commit subjects, among them "docs(map): refresh codebase map for B-series remediation" and two "delete report". So the coordinator knew an earlier audit or remediation cycle had happened, but not its findings.
  - Also auto-loaded: CLAUDE.md, the auto-memory index, live-rules, and scratch-script names from earlier sessions. The coordinator read only kit and config files (`settings.json`, `gradle-check.sh`, `map-guard.py`).
- **Reviewer and validator exposure:**
  - Hooks re-injected the map index and live-rules after compaction. One reviewer (W4) also received a compaction checkpoint of the main session's recent user messages: the audit command and a scheduling remark. None contained finding content.
  - Source comments and test names cite earlier ticket and decision tags (SQ-79, SQ-86, SQ-92, SQ-114, SQ-118, H3, H5, H7, H12, H13, H14, E3, D9) and describe earlier fixes. Each worker reported judging the code independently of them.
  - Validators received only neutral claim statements and locations, with no author severity or confidence.
- **Deviations from protocol:**
  - Raw `./gradlew` with logs in the scratchpad instead of `gradle-check.sh`, which writes into the protected `.claude/kit/logs`.
  - 5 concurrent reviewers and 3 concurrent validators, on the user's instruction to run read-only agents in parallel (default ≤3 and 1).
  - Repro exports placed on disk under `~/.cache`, because `/tmp` is RAM-backed and swap was full.
  - A redroid device appeared mid-audit (02:02 local), and Gradle was then limited to one invocation per the live rule.
  - The device check was added at the user's request.
- **Classification:** *fresh workers; coordinator exposed.* The coordinator's exposure is limited to an architecture summary and the knowledge that an earlier remediation cycle existed.

**Fresh-context availability:** all reviewers (W1–W5) and validators (V1–V3) were new agent contexts that did not inherit the coordinator's transcript.

**Freeze evidence:** this report is frozen with `audit-guard.py freeze` before any earlier audit material or deferred project context is opened. The guard records its SHA-256 in the session state (`.git/audit-history/run-884ftxmi`). Any comparison with earlier audit cycles appears below as a separately labelled, post-freeze **Audit Delta**. Post-freeze discoveries belong only there.


## Audit Delta (post-freeze comparison)

## Audit Delta (post-freeze; compared after `audit-guard.py freeze`)

**Prior material read after freeze:** there was no report on disk at `begin` (the guard's `prior_sha256` is None). The earlier reports were recovered from git history only after the independent report was frozen:
- **B-series:** `eefb558:AUDIT_REPORT.md`, 547 lines. Findings B01–B16, all marked FIXED on `audit-remediate-b`. It includes its own delta against the A-series.
- **A-series:** `bf0479e:AUDIT_REPORT.md`, findings A01–A12. 11 are FIXED and A10 is BLOCKED (kit tooling).
- **Deletion:** both were deleted from the tree. `2c8c7f7` deleted the A-series line, and the merge `89ca16d` dropped the B-series file.

Matching below is by root cause, not by ID. "Fix present" means the fix commit's change is visible in the 60fe3d5 source. "Regression test passes" means its test class exists and was among the 1,083 tests that passed in this audit's `testDebugUnitTest --rerun` baseline.

### Per-finding status of the B-series

| Prior | Root cause (short) | Status at 60fe3d5 | Evidence / relation to this audit |
|---|---|---|---|
| B16 | Stub `HistoryItem.batteryVoltage` type on API 34 | fix present in source; verification limited | `BatteryStatsClient.kt:210-213` reads the field by name via `WideningIntField`. API 34 was not re-run (the device here is API 35, where history ingest worked: cursor set, history rows stored). |
| B01 | Charges ending in a batterystats reset never become sessions | fix present in source; verification limited | Cross-session RESET handling commented at `CapacityEstimator.kt:51-67`. Not exercised here: redroid cannot produce a real reset. |
| B02 | `packageChanged` fired on null→name | fix present in source; verification limited | `SnapshotDiffer.kt:213` flags a change only when both names are known. **Interaction:** B02 recommended comparing against the UID's PackageManager package set for shared UIDs; that was not done. Keeping null→name rows now lets one UID change package between snapshots, which is the precondition of **F-02** (HIGH). The raw process name is still used as identity (**F-09**). |
| B03 | Uncertain toolkit writes leave the override chain | fix present for the *unknown-outcome* case; sibling case **independently rediscovered** as **F-01** (still reproducible) | `ToolkitCode.UNVERIFIED` keeps those rows PENDING, and `ToolkitActionsUncertainWriteTest` passes. But a *known* outcome that landed elsewhere (NOT_EFFECTIVE with before≠after, e.g. a bucket clamp) is still closed as FAILED, and the reconcile Mismatch branch still drops observed changes. `AuditReproV1aTest` fails on 60fe3d5. |
| B04 | Broadcast full captures: double deadline, lost misses, no light fallback | fix present (persist budget, `NonCancellable` miss, light fallback); residual **independently rediscovered** as **F-05** (still reproducible) | `CapturePolicy.kt:41-44,77-81`. The new light fallback runs in the coroutine the guard has already cancelled, so a *blocking* read that outlives the guard still leaves no row (app `AuditReproV2aTest`: expected `[POWER_DISCONNECTED]`, got `[]`). The B04 test models the slow read with `delay`, which is cancellable, so it cannot see this. |
| B05 | Each dexopt poll triggered a full override recompute | fix present in source; verification limited | Dexopt progress now bumps `dexoptEvents` rather than `changes` (`ToolkitActions.kt:331-332`). **Interaction:** B05 reduced how *often* the recompute runs. Its *cost* is the same 8-spawn uncached gate the B05 report itself described (about 20 spawns per package), and it grows with every target ever touched. That is **F-07**, still reproducible (`AuditReproV1eTest`; 63–66 ms per gate on the device). |
| B06 + B14 | Eager analysis; stale `Ready` overwriting `Loading` | fix present (single ordered `transformLatest` stream); B14 residual **independently rediscovered** as **F-16** (still reproducible) | `AnalysisSession.kt:60`. The race is gone, but B14's alternative fix ("tag `Ready` with its `ReferenceKind`") was not adopted. Consumers still pair the old `Ready` with the new selection for one dispatcher hop (`AuditReproV3dTest`). |
| B07 | Redundant full reads per charge job; 30-day history reloaded | fix present in source; verification limited | Not re-flagged. **F-19** is a different over-fetch, in snapshot materialization by `ReferenceResolver`. |
| B08 | X-Ray reloaded icons per emission | fix present in source; verification limited | `XRayViewModel.kt:174` comment and per-row loading. |
| B09 | `reconcileOnEachReady` lost Ready flips | verified resolved within stated checks | `.buffer(Channel.UNLIMITED)` at `ReconcileOnReady.kt:24`; `ReconcileOnReadyTest` passes. A *different* defect in the same collector (it dies on the first exception) is part of **F-08**. |
| B10 | `applicationScope` had no exception handler | verified resolved within stated checks; same root cause **independently rediscovered** in other scopes (**F-08**) | `ApplicationScope.kt:18` handler; `ApplicationScopeTest` passes. The same "uncontained long-lived scope" defect remains in the Toolkit and X-Ray `viewModelScope` launches and in `ToolkitActions.defaultSupervisorScope()` (`AuditReproV1fTest` fails). |
| B11 | Charge-job re-arm gaps | verified resolved within stated checks | Re-arm from `finally` (`ChargeJobRun.kt:8,31`); `ChargeJobRunTest` passes. **F-04** (cold-start capture failure) is a different cause in the same job. |
| B12 | `full()` blocking calls and merge on Main | fix present in source; verification limited | `full()` runs `withContext(ioDispatcher)` (`AndroidTelemetrySource.kt:34`). **F-05** is a different property of the same calls: they are *not cancellable* by the capture deadline. **F-21** is a different Main-thread site (timeline shaping). |
| B13 | Health axis label clamp threw | not assessed / insufficient evidence | Outside the validated candidate set; module tests pass. |
| B15 | Pull-to-refresh ignored during on-enter refresh | fix present in source; verification limited | `userWaiting` at `CockpitViewModel.kt:52,137`. |
| B "Rejected" | Charging time counted as screen-off-only | **independently re-rejected** | This audit's validator rejected the same candidate (DIAG-1) on the same on-battery time-base contract, verified by disassembling `BatteryStatsImpl`. The B report's suggested documenting comment and realistic-fixture test were not checked. |

### A-series (via the B-series delta, re-checked where this audit overlaps)
- **A01: wall-clock rollback reorders override chains.** Fix present (`overrideRows` sorts by id, `ToolkitActions.kt:806`). **Theme recurs:** **F-06** finds the wall clock still used as elapsed time elsewhere (prune cutoff, dexopt hold deadline, `UnplugSummary` screen-on), a different site with the same class of defect.
- **A03: status rates keep screen state across reboot.** Not re-flagged. **F-06** (C3) is a separate wall-delta defect in the same `UnplugSummary` area.
- **A04: dexopt start fencing.** Not re-flagged. **F-06** (C2) adds a wall-clock deadline issue in the dexopt *hold*.
- **A05: changed-package counters.** Protection still present, and B02 narrowed it. See the B02 row.
- **A02, A06, A07, A08, A11:** not re-flagged by the reviewers covering those files; not separately re-verified.
- **A09, A10, A12:** kit tooling, outside this audit's application scope; not assessed.

### Cross-audit patterns (same root cause found again)
1. **Toolkit chain bookkeeping is now a three-audit theme:**
   - A01: ordering.
   - B03: uncertain outcomes.
   - This audit: landed-elsewhere outcomes (F-01) and same-value writes (F-13).

   Each fix patched one outcome class. The structural fix is to derive chain membership from the *observed* before/after values the service already returns, not from the row status. That would close F-01 and F-13 together and make B03's special case a consequence rather than an exception.
2. **The capture deadline model:** B04 added a fallback inside the scope that the guard cancels, and B12 moved the calls to IO. Neither made blocking binder calls cancellable, which is F-05. The B04 and B12 tests use `delay`-based fakes, which hide this. Test infrastructure that models blocking is the missing piece.
3. **Failure containment:** B10 fixed one scope, and F-08 finds three more. A single containment rule (every long-lived scope gets a handler, and every feature launch uses `attempt {}`) would end the recurrence.
4. **Package identity:** B02's advice to resolve shared UIDs against PackageManager was not taken. F-02 and F-09 are its consequences.
5. **Recompute cost:** B05 cut the frequency, but F-07 shows the per-run cost is unbounded.

### Post-freeze discoveries
None beyond the classifications above. No new defects were found while reading the prior material.

### Baseline comparison
- **Unit tests:** the B-series final gate (`f067599`) had 1,083 tests and 0 failures, with per-module counts identical to this audit's `--rerun` baseline at 60fe3d5. No tests were added or lost since.
- **Lint:**
  - B gate: 0 errors / 10 warnings.
  - This audit: 0 errors / 17 warnings.
  - The difference is dependency and target-version notices (NewerVersionAvailable ×5, GradleDependency, AGP, OldTargetApi), likely because the lint data aged, not because of a source change.
- **Screenshots:** the B gate ran them; this audit did not (a disclosed gap).
- **R8:** both ran the R8 release build.
- **B residual open items still open:**
  - API 31–33 history iterator shape is unverified.
  - B01's API 36 re-insert question is unanswered.
  - History voltage 65535 normalisation is not done.
  - No CI and no dependency advisory scan; this audit also lacked a scanner.
  - `telemetry_hourly`/`capture_miss` still await a product decision. The device run showed `capture_miss` is useful evidence: it recorded the F-03 stuck-permission misses.

---

## Remediation log (`/audit-remediate`, branch `audit-remediate-c`)

Added after the audit; the sections above are the frozen audit except for the `[FIXED in …]` / `[SKIPPED …]` status tags.

- **Base:** `60fe3d5`. The branch adds 34 atomic commits (`781d9de` … `e788e1e`) plus this report commit.
- **Method:** 12 parallel workers in isolated worktrees, grouped by file ownership. Each finding commit carries regression tests, adopted from the audit's repro methods where one existed. Most workers also showed the tests failing with the fix absent (exit 1): F-01/F-02/F-03/F-04/F-05/F-06/F-07/F-08/F-09/F-12/F-13/F-16/F-17/F-18/F-19/F-20. The coordinator reviewed each worker branch before cherry-picking it.
- **Status:** 23/23 findings FIXED. F-23 is fixed only for the locale; the 24-hour clock part is SKIPPED as a product decision. §3 items are tagged in place.

**Full gate @ `e788e1e`** (`--max-workers=4`, run as four sequential stages, all exit 0):

| Check | Result | Audit baseline |
|---|---|---|
| `testDebugUnitTest` (all modules) | 1,238 tests, 0 failures | 1,083 |
| `validateDebugScreenshotTest` | 168 validated, 0 failures | not run |
| `lintDebug` | 0 errors, 9 warnings | 0 / 17 |
| `:app:minifyReleaseWithR8` | ok | ok |

**Device check** on redroid (Android 15, Shizuku 13.5), debug build @ `e788e1e`, driven with Mobile MCP:
- **F-03:**
  - With Shizuku permission cleared, the Cockpit showed "Shizuku permission needed".
  - Grant access → Deny → Grant access showed the Shizuku dialog again; before the fix this was a dead button.
  - After Deny again and a grant from Shizuku's app manager, returning to the app showed "Binder active · UID 2000" in the same process (pid 5691, no restart).

**Residuals and deviations:**
- **F-06:** the prune fix ages rows on the boot's monotonic clock rather than skipping a prune after a jump; skipping would only postpone the loss. The dexopt status parcel still carries only a wall-clock start; changing it is an IPC change.
- **F-17:** the forward-only cursor check holds within one stats session. A late write left over from an earlier session is excluded only by the in-process history mutex, and waiting on that mutex is not deadline-bound.
- **F-11:** the overlay scenario was not run on the device; it needs a third-party overlay app. Covered by unit and screenshot tests.
- **F-19:** an index on `snapshot(trigger, wallMs)` was added as DB v4 (`MIGRATION_3_4`).

**Incidents:**
- **Worktree base:** harness isolation worktrees were created from `main`, not `60fe3d5`. Workers rebased or created their own worktrees under `.claude/worktrees/`.
- **Formatting hook:** the format-on-edit hook (ktlint) rewrites whole files, so workers edited scratch copies to keep diffs minimal.
- **Gradle OOM:** the Gradle daemon was OOM-killed twice (kernel log; redroid plus one Gradle plus leftover workers and Kotlin daemons). After 7 orphaned Gradle workers and idle Kotlin daemons were stopped, the full gate was rerun in stages.
- **Shared Gradle slot:** because redroid was running, all Gradle runs shared one host-wide slot.
