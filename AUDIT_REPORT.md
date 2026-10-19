# Inspiration — Whole-Repository Audit (independent findings)

- **Date:** 2026-10-19
- **Source state:** `df8a9098f092cd9d555ed0e7198feda9858674c5` on `main`; working tree clean except an unrelated `.claude/settings.json` edit (review-mode toggle). No application file changed during the audit.
- **Method:** source-only reconnaissance (`repo-recon.sh`), one shared tooling baseline, nine parallel read-only subsystem reviewers in fresh contexts, three fresh validator contexts (candidates deduplicated by root cause and split by area), one redroid device experiment. Coordinator wrote this report.
- **Isolation class:** **contaminated / not blind (partial)** — see §5. Findings were derived from and verified against current source; none was selected from prior-assessment material.

---

## 1. Executive Summary & System Health Scorecard

Scores are qualitative judgments, not measurements.

| Dimension | Score | Justification |
|---|---|---|
| Architecture & Modularity | **8/10** | Clean module graph (leaf designsystem, feature → core only, compileOnly hidden-API stubs, manual DI with per-feature interfaces); the weak spot is the shared `AnalysisSession` lifecycle that no feature can drive (F-10). |
| Correctness & Resilience | **6/10** | Arithmetic and failure containment are careful, but which snapshots get compared (F-02, F-03, F-04) and the toolkit's appop semantics (F-01) produce wrong or silently empty user-facing results in common situations. |
| Concurrency & Resource Safety | **8/10** | Binder deadlines, NonCancellable write-ahead, locked shared state, merged/drained process pipes with timeouts; residual issues are narrow races (F-16, F-17, F-23). |
| Security & Privilege Hygiene | **9/10** | Caller-UID check on every AIDL method, fixed argv with no shell, strict package regex, non-exported components, debug-only probe behind `DUMP`, no secrets in source; the user-0 hardwire (F-05) is a correctness rather than an escalation issue. |
| Performance & Efficiency | **7/10** | No polling, bounded parcels; but the Cockpit's always-on infinite animation (F-09) contradicts the zero-observer-effect goal on the main screen. |

**Top 3 systemic risks**
1. **The toolkit's "Allow / Restore / Reset all" for background appops writes `default`, which Android still enforces as restricted** — the app tells the user an undo succeeded while the restriction stays in force (F-01, device-confirmed).
2. **Interval selection frequently yields empty or misleading analyses with no explanation**: per-app attribution is dropped whenever endpoints come from different app processes or straddle any package update (F-02); "Since unplugged" pairs a snapshot with itself (F-03); time past the last history ingest is counted as known state (F-04).
3. **Recovery paths in the UI layer don't actually recover**: Retry can't recompute a failed analysis (F-10), the live gauge keeps showing stale values as live (F-07), and stale one-shot effects leak across visits (F-11).

---

## 2. Confirmed Findings

Severity is the coordinator's final call after validation. "Validator" notes the independent verdict. Paths are relative to the repository root; `…/telemetry/` = `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/`, `…/ipc/` = `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/`.

### HIGH

#### [F-01] HIGH [FIXED in 81c4c9e, c924c24] — Toolkit "Allow", Restore and Reset all write appop mode `default`, which leaves RUN_ANY_IN_BACKGROUND restricted
- **Category:** Logic Bug (privileged write path)
- **Location:** `…/ipc/toolkit/Toolkit.kt:23-26`; `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitViewModel.kt:346-347`; `…/telemetry/toolkit/ToolkitActions.kt:925-929` (restoreAction), `:999-1009` (sameState), `:1695-1701` (modeOf); `…/ipc/toolkit/ToolkitParsers.kt:10-12`; `…/ipc/toolkit/ToolkitExecutor.kt:70-74`; `feature/toolkit/.../ToolkitMapper.kt:26`.
- **Confidence:** Confirmed (source trace + redroid reproduction). Validator: CONFIRMED.
- **Root cause & trace:**
  ```kotlin
  enum class AppOpMode(val code: Int, val argv: String) { IGNORE(1, "ignore"), DEFAULT(2, "default"), }
  // ToolkitViewModel
  ToolkitAction.SetAppOp(it, request.op, if (request.ignored) AppOpMode.IGNORE else AppOpMode.DEFAULT)
  // ToolkitActions.modeOf
  AppOpMode.DEFAULT.argv, "allow" -> AppOpMode.DEFAULT
  ```
  1. Turning a background restriction off sends `SetAppOp(…, DEFAULT)` → executor runs `cmd appops set --user 0 <pkg> RUN_ANY_IN_BACKGROUND default`.
  2. Restore/Reset all decode the recorded original with `modeOf`, collapsing `allow` and "No operations" (parsed as `"default"`) into `DEFAULT`, so they also write `default`.
  3. Read-back sees `default` == `mode.argv` → row settles DONE; `sameState` treats allow≡default so the override disappears; `ToolkitMapper` shows `ignored = false` (allowed).
  4. Android's AppStateTracker treats any mode `!= MODE_ALLOWED` as forced-app-standby restricted; `MODE_DEFAULT` is stored, not pruned (the op's default is `allow`).
- **Device evidence** (redroid, Android 15, `com.android.music`; original mode restored afterwards):
  ```
  appops get … RUN_ANY_IN_BACKGROUND  → No operations. / Default mode: allow;  dumpsys alarm Restricted packages: (empty)
  set … ignore   → Restricted packages: u0a46 com.android.music
  set … default  → Restricted packages: u0a46 com.android.music   (no RUN_ANY_CHANGED event)
  set … allow    → Restricted packages: (empty)
  ```
- **Failure scenario:** User restricts app X in the background, then taps Reset all. The toolkit reports "Restored" and lists no override, but X's jobs and alarms remain blocked indefinitely. An untouched app toggled on and back off ends up restricted although it was originally unrestricted. RUN_IN_BACKGROUND is likely affected similarly for legacy-target apps (not device-verified).
- **Concrete fix:**
  ```kotlin
  enum class AppOpMode(val code: Int, val argv: String) {
      IGNORE(1, "ignore"),
      ALLOW(3, "allow"),          // permissive write target
      DEFAULT(2, "default"),      // only ever read back, never written by "allow"
  }
  // ToolkitViewModel
  if (request.ignored) AppOpMode.IGNORE else AppOpMode.ALLOW
  // ToolkitParsers: "No operations." -> "allow"
  // ToolkitActions.modeOf
  fun modeOf(raw: String): AppOpMode? = when (raw) {
      "ignore" -> AppOpMode.IGNORE
      "allow"  -> AppOpMode.ALLOW
      "default" -> AppOpMode.ALLOW   // legacy rows recorded "No operations" as "default": restore to allow
      else -> null
  }
  ```
  Stop treating `allow`/`default` as the same state in `sameState`; show a live `default` reading as *restricted* in `ToolkitMapper` so already-affected apps can be repaired; add `ALLOW` to the service-side allowlist and bump `ToolkitApi.VERSION` (mode codes cross AIDL). Add an executor test asserting the permissive argv is `allow`, and a parser test for "No operations." → `allow`.

#### [F-02] HIGH [FIXED in d60ec89, 05f48ef] — Per-app attribution is discarded whenever interval endpoints come from different app processes or straddle any package change
- **Category:** Logic Bug / Architecture
- **Location:** `…/telemetry/diff/SnapshotDiffer.kt:223-231`; `…/telemetry/source/UidIdentityReader.kt:94` (also epoch regeneration ~`:169`, `:185`); `…/telemetry/snapshot/UidIdentity.kt:8-17`; consumers `feature/xray/.../XRayMapper.kt:56`, `core/diagnostics/.../DiagnosticRuleEngine.kt:29`, `feature/sleeplab/.../SleepLabMappers.kt:149,195`; single full-capture source `app/src/main/kotlin/com/akane/inspiration/AppContainer.kt:95`.
- **Confidence:** Confirmed via call trace. Validator: CONFIRMED (HIGH).
- **Root cause & trace:**
  ```kotlin
  private val epoch = AtomicReference(UUID.randomUUID().toString())          // per process, never persisted
  val comparableScope = scope != null && scope.isKnown() && scope == now.uidIdentityScope && …
  val identityValid = !applicationUid || (knownOwners && previousFingerprint == currentFingerprint)
  ```
  1. Each full capture stores `UidIdentityScope(userId, userSerial, readerEpoch, packageChangeSequence)`.
  2. `diffUids` requires whole-scope data-class equality: the per-process random epoch *and* the device-wide `getChangedPackages` sequence must match.
  3. Otherwise every application UID becomes `uid_identity_unknown`; X-Ray rows, R1/R2/R4 culprits and Sleep Lab per-app wakelocks all filter on `valid`.
  4. `ReferenceResolver.closestReference` doesn't prefer scope-matching references, so nothing compensates.
- **Failure scenario:** User unplugs, Android kills the cached app process (routine), user opens the app hours later. Default "Since unplugged" X-Ray shows the generic "No app readings to report" card; R1 finds nothing; no explanation. Any Play Store update between endpoints has the same effect. Per-app data only survives intervals contained in one process lifetime with zero package changes — i.e. the headline "who drained my battery" feature is missing on most real intervals. The per-UID fingerprints (uid, user serial, sorted owner names, each owner's `firstInstallTime`) already detect reinstall/appId reuse/shared-UID changes; the extra scope equality adds strictness without protecting against a misattribution the fingerprints miss (only an uninstall/reinstall preserving `firstInstallTime` remains, and the negative-delta check mostly catches that).
- **Concrete fix:** keep epoch + sequence as the *intra-capture* open/close consistency proof (unchanged in `UidIdentityReader`), but compare across snapshots only what identifies the namespace:
  ```kotlin
  val r = ref.uidIdentityScope; val n = now.uidIdentityScope
  val comparableScope = r != null && n != null && r.isKnown() && n.isKnown() &&
      r.userId == n.userId && r.userSerial == n.userSerial && r.covers(uid) && n.covers(uid) &&
      ref.clock.bootCount >= 0 && sameBootWithIncreasingElapsed(ref, now)
  ```
  Add `SnapshotDifferTest` "same boot/user, equal fingerprints, different readerEpoch and sequence → app delta valid" and "fingerprint differs → uid_identity_changed". Update `XRayMapperTest:59` accordingly.

### MEDIUM

#### [F-03] MEDIUM [FIXED in bf55974] — "Since unplugged" / "Last 24h" can resolve a snapshot against itself and silently show an empty analysis
- **Category:** Logic Bug
- **Location:** `…/telemetry/diff/ReferenceResolver.kt:49-55,106-120`; `core/database/src/main/kotlin/com/akane/inspiration/database/Daos.kt:95-111`; `core/diagnostics/.../analysis/IntervalAnalyzer.kt:43`; `feature/xray/.../XRayMapper.kt:56,62-72`.
- **Confidence:** Confirmed via call trace. Validator: CONFIRMED (wider than described).
- **Root cause & trace:**
  ```kotlin
  val end = store.lastAtOrBefore(boundary.toWallMs, null) ?: return null
  val ref = boundary.markerSnapshot ?: closestReference(boundary.fromWallMs, end) ?: return null
  // closestReference: firstAtOrAfter(from, FULL, end.statsStart, end.bootCount)?.takeIf { it.clock.wallMs <= end.clock.wallMs } ?: …
  ```
  When `end` (the APP_OPEN capture just taken) is the only FULL snapshot after the boundary, `firstAtOrAfter` returns `end` itself; the FULL query also wins over an intervening LIGHT unplug anchor. With only LIGHT rows, the any-kind fallback pairs a LIGHT `end` with itself. `IntervalAnalyzer` only rejects `coveredTo < coveredFrom`.
- **Failure scenario:** Unplugging while the service is cold produces a LIGHT unplug capture; the first app open then analyses a zero-length interval on the default selection: all deltas 0, empty story, "0 m" screen tile, X-Ray generic empty card, no gap banner (it only exists for time slices).
- **Concrete fix:**
  ```kotlin
  val ref = boundary.markerSnapshot ?: closestReference(boundary.fromWallMs, end) ?: return null
  if (ref.clock.wallMs > end.clock.wallMs) return null
  if (boundary.markerSnapshot == null && (ref.id == end.id || ref.clock.elapsedMs == end.clock.elapsedMs)) {
      // fall back to the earliest snapshot of any kind after the boundary (per-UID unavailable), else report "needs a second snapshot"
      val anyRef = store.firstAtOrAfter(boundary.fromWallMs, null, null, end.clock.bootCount)
          ?.takeIf { it.id != end.id && it.clock.wallMs < end.clock.wallMs } ?: return null
      return build(anyRef, end, perUidAvailable = false)
  }
  ```
  Surface a null resolution as an explicit "not enough data yet" state. Add `ReferenceResolverTest` "only the end FULL after the boundary → not self-paired".

#### [F-04] MEDIUM [FIXED in fcaa4e2, 6b3c38d] — Interval metrics and Battery Story treat time after the last ingested history event as known state
- **Category:** Logic Bug
- **Location:** `core/diagnostics/.../analysis/IntervalAnalyzer.kt:40-49`; `…/telemetry/interval/IntervalMetrics.kt:41-47`; `core/diagnostics/.../analysis/TimelineBuilder.kt:176`; `core/diagnostics/.../BatteryStoryBuilder.kt:23-29`; `app/.../capture/CapturePolicy.kt:27-35,84`.
- **Confidence:** Confirmed via call trace (narrower trigger). Validator: CONFIRMED-NARROWER.
- **Root cause & trace:**
  ```kotlin
  val walk = Walk(fromWallMs)
  ordered.forEach(walk::apply)
  if (ordered.isNotEmpty()) walk.advanceTo(toWallMs)   // extends last screen/plug state to toWallMs
  ```
  Broadcast captures are LIGHT or `ingestHistory = false`; only app refresh, markers and the charge job ingest history. `end` can be a newer LIGHT/broadcast snapshot, so the tail between the last ingested event and `coveredTo` is attributed to the last known state with zero level drop; `unknownMs == 0` lets the story emit a Summary with diluted rates; timeline lanes stretch to `to`.
- **Failure scenario:** Shizuku drops (wireless debugging off) while the process lives; screen receivers keep storing LIGHT snapshots; the user opens the app and the refresh fails. "Over these 8 hours you used 12%… screen on 1.5%/h (normal)" while the real drop visible in the LIGHT snapshots is far larger. Also briefly on each cold start before the refresh tick.
- **Concrete fix:** persist the wall time of the last successful ingest next to `setHistoryCursor`, pass it as `historyHorizonWallMs`:
  ```kotlin
  if (ordered.isNotEmpty()) {
      val horizon = historyHorizonWallMs.coerceIn(fromWallMs, toWallMs)
      walk.advanceTo(maxOf(horizon, walk.cursorMs))
      walk.advanceUnknownTo(toWallMs)          // unknownMs += toWallMs - cursor
  }
  ```
  and close `TimelineBuilder` lanes at the same horizon. Test: history ending at T, LIGHT end at T+6h → no Summary, no extrapolated rate.

#### [F-05] MEDIUM [FIXED in 274353f] — Toolkit commands are hard-wired to user 0 while package inspection uses the app's own user
- **Category:** Logic Bug (privileged write path)
- **Location:** `…/ipc/toolkit/ToolkitExecutor.kt:14` (`private const val USER = "0"`, used at ~`:70,:106,:134,:326-351`); `…/ipc/toolkit/Inspection.kt:58-75,97`; `…/ipc/toolkit/CommandRunner.kt:25-26`; `…/ipc/service/TelemetryUserService.kt:117-128`; `app/.../AppContainer.kt:242-248`.
- **Confidence:** Confirmed via call trace + Shizuku APK disassembly (UserService context created with `createPackageContextAsUser(…, UserHandle.of(uid/100000))`). Device run in a work profile not performed. Validator: CONFIRMED-NARROWER.
- **Root cause & trace:** the picker lists the current user's apps; `AndroidPackageInspector.lookup` reads user N via `context.packageManager`; protected-package checks and every write/read-back argv pin `--user 0`. Nothing refuses toolkit use when `myUserId != 0`.
- **Failure scenario:** App installed in a work profile (Island/Shelter) with the same package in both users: restricting "com.foo" changes the personal-profile copy, read-back on user 0 passes → DONE, the work app is untouched; force-stop reports user N's stopped flag; IME/role protection is evaluated for the wrong user; profile-only packages fail with PARSE.
- **Concrete fix (minimal, safe):** pass `userId = appUid / 100_000` into `ToolkitExecutor` and refuse package actions when it is not 0:
  ```kotlin
  private fun gate(pkg: String?): Gate {
      if (userId != 0) return deny(ToolkitCode.UNSUPPORTED_USER)
      …
  ```
  (Full support: replace every literal `"0"` with `userId.toString()`, including role/secure-settings reads and `ACCESSIBILITY_SERVICES_ARGV`.) Unit test: executor with `userId = 10` never emits `"--user","0"` / refuses.

#### [F-06] MEDIUM [FIXED in 170ad81] — A custom doze value replaced via Balanced → Standard cannot be restored
- **Category:** Logic Bug
- **Location:** `…/telemetry/toolkit/ToolkitActions.kt:1087-1093` (mayBeActive), `:1122-1129` (isActive), `:551-556` (overCustom); `feature/toolkit/.../ToolkitViewModel.kt:338`; `feature/toolkit/src/main/res/values/strings.xml:5`.
- **Confidence:** Confirmed via call trace. Validator: CONFIRMED.
- **Root cause & trace:**
  ```kotlin
  if (row.kind == KIND_DOZE && norm(row.afterRaw) == null) return false   // mayBeActive
  return row.kind != KIND_DOZE || after != null                            // isActive
  ```
  Custom X → Balanced (no warning: current isn't "Standard over custom") → Standard (no warning: current is Balanced). Newest row is BAL→null, so no override is listed; restore is only accepted for listed overrides.
- **Failure scenario:** An OEM- or user-tuned `device_idle_constants` value is silently lost; the screen subtitle promises "Every change is listed below so you can undo it."
- **Concrete fix:** let an unset-doze row be active when its chain original is a non-null custom value (restore writes X via the existing CAS), and warn DOZE_OVER_CUSTOM when the chain original is custom, not only the current value:
  ```kotlin
  private fun mayBeActive(row, rows) = !sameState(row.kind, row.afterRaw, chainOriginal(row, rows))
  private fun isActive(row, current) = norm(current) == norm(row.afterRaw)
  ```
  (`activeOverride` already returns null when current equals the chain original, so unset-original chains still produce no override.) Test: `customOriginalThroughBalancedThenStandardIsAnOverrideRestoringTheCustomValue`.

#### [F-07] MEDIUM [FIXED in 6fb77bf] — Cockpit live gauge freezes on its last reading when Shizuku reads stop
- **Category:** Logic Bug
- **Location:** `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitViewModel.kt:112-116,136-141`; `…/telemetry/live/LiveTelemetry.kt:33-38`; `…/telemetry/source/AndroidTelemetrySource.kt:157-160`.
- **Confidence:** Confirmed via call trace. Validator: CONFIRMED.
- **Root cause & trace:**
  ```kotlin
  deps.liveReadings(LIVE_PERIOD_MS)
      .catch { /* A failed live read leaves the gauge on dashes; … */ }
      .collect { reading -> if (visible) onReading(reading) }
  ```
  `kernelReading()` returns null when the service is unavailable; `readings()` emits only non-null values; `onReading` is the only writer of `live` besides `onHidden`. `.catch` ends the flow and also leaves the last value, contrary to its comment.
- **Failure scenario:** Shizuku is killed while the Cockpit is open: the gauge keeps showing e.g. "−450 mA · 1.9 W · 9 h left" as live indefinitely.
- **Concrete fix:**
  ```kotlin
  .catch { _state.update { it.copy(live = null) } }
  .collectLatest { reading ->
      if (!visible) return@collectLatest
      onReading(reading)
      delay(LIVE_STALE_MS)                       // ≈ 3 × LIVE_PERIOD_MS + bind timeout
      _state.update { it.copy(live = null) }
  }
  ```
  plus clearing `live` when `shizukuState` leaves Ready. Test: a source that stops emitting returns the gauge to dashes.

#### [F-08] MEDIUM [FIXED in e197969] — Touching near a scrub-chart selection edge immediately captures and moves the edge; taps can't clear, vertical scrolls are swallowed
- **Category:** Logic Bug (input handling)
- **Location:** `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/math/ScrubGestureReducer.kt:82-99,117-118,151-164`; `…/chart/scrub/ScrubGestures.kt:43,58-59,88-90`; `…/chart/scrub/ScrubChartState.kt` (`HandleHit = 48.dp`); caller `feature/dashboard/.../CockpitChartCard.kt:51-57`.
- **Confidence:** Confirmed via call trace. Validator: CONFIRMED.
- **Root cause & trace:**
  ```kotlin
  return if (handle != null) ScrubGestureResult(handle, consume = true) else …   // Down: no slop
  if (minOf(startDistance, endDistance) > handleHitPx) return null              // 48 dp used as radius
  ```
  Down within 48 dp of an edge → `DraggingHandle`, consumed; every Move consumed with no vertical release; Up sets the edge to the absolute `timeAt(up.x)` and commits `SelectionCommit.Set`.
- **Failure scenario:** On a ~250-300 dp plot of a 24 h window, 48 dp ≈ 4 h: a tap inside a slice narrower than ~96 dp moves an edge by hours and re-runs the analysis instead of clearing; touching a handle without moving shifts it; a vertical page scroll starting near an edge is eaten and moves the edge.
- **Concrete fix:** hit radius `HandleHit / 2`; add `PressedOnHandle(edge, otherEdgeMs, edgeMs, downX, downY)` that waits for touch slop, releases on vertical-dominant movement, and treats Up-before-slop as a tap; make dragging relative: `currentMs = (edgeMs + scale.toTime(x) - scale.toTime(downX)).coerceIn(window)`. Test `tapNearHandleDoesNotMoveSelection`: `run(selection, Down(240f, 50f), Up(240f))` must not return `Set(…)`.

#### [F-09] MEDIUM [FIXED in 9dce621] — The Shizuku "ACTIVE" halo animates forever, requesting a frame every vsync on the Cockpit
- **Category:** Performance (battery / observer effect)
- **Location:** `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/component/ShizukuPulse.kt:58,77-86`; caller `feature/dashboard/.../CockpitHeader.kt:105` inside `verticalScroll` (`CockpitScreen.kt:42`); `CockpitMappers.kt:55` (Ready → ACTIVE).
- **Confidence:** Confirmed via call trace; frame-rate impact not measured on device. Validator: CONFIRMED.
- **Root cause & trace:**
  ```kotlin
  val pulsing = status == PulseStatus.ACTIVE && !rememberReducedMotion() && !LocalInspectionMode.current
  rememberInfiniteTransition(…).animateFloat(…, infiniteRepeatable(tween(PERIOD_MS, …), RepeatMode.Restart))
  ```
  ACTIVE is the steady state; the header stays composed when scrolled off; reading in `graphicsLayer` avoids recomposition but not per-vsync frames.
- **Failure scenario:** CPU/RenderThread/GPU never idle on the main screen of a battery-analytics app, inflating the very current draw shown by the live gauge and possibly pinning a high refresh rate on LTPO panels.
- **Concrete fix:** bounded pulse on transition to ACTIVE:
  ```kotlin
  val phase = remember { Animatable(0f) }
  LaunchedEffect(Unit) { repeat(3) { phase.snapTo(0f); phase.animateTo(1f, tween(PulseMetrics.PERIOD_MS, easing = LinearOutSlowInEasing)) } }
  ```
  (alpha is 0 at progress 1, so the halo disappears), or a static halo. Verify with `dumpsys gfxinfo` frame counts on an idle Cockpit.

### LOW

#### [F-10] LOW [FIXED in 4e398fc] — The shared AnalysisSession cannot be recomputed by Retry or on becoming visible
- **Category:** Logic Bug / Lifecycle
- **Location:** `core/diagnostics/.../analysis/AnalysisSession.kt:63-84`; `app/.../AppContainer.kt:155-162`; `app/.../di/RefreshCoordinator.kt:65-66,83`; `feature/dashboard/.../CockpitViewModel.kt:69-90,151-159`, `CockpitCards.kt:54,61`; `feature/xray/.../XRayScreen.kt:218-223`, `XRayViewModel.kt:139-152`; `feature/sleeplab/.../SleepLabScreen.kt:113-116,160-164`, `SleepLabViewModel.kt:58-75,170-197`.
- **Confidence:** Confirmed. Validator: CONFIRMED-NARROWER (two reviewers found it independently).
- **Trace:** recompute only on selection change or `refreshTicks` (= `lastRefreshWallMs.filterNotNull()`, changes only on `Refreshed`); restart-on-resubscribe never happens because Cockpit and Sleep Lab VMs collect for their whole lifetime; Retry CTAs call `refresh()`, which returns `Debounced` for 60 s after a success (ignored) or `Failed` while Shizuku is down (snackbar only).
- **Failure scenario:** a transient Room/analyzer exception leaves the Error card with a Retry that does nothing for 60 s (indefinitely while Shizuku is down, although the analysis only reads Room). The hypnogram's Failed state recovers on tab re-entry, but its Retry is dead.
- **Fix:** `AnalysisSession.recompute()` backed by a `MutableSharedFlow<Unit>(extraBufferCapacity = 1, DROP_OLDEST)` merged into the tick flow; expose via the Cockpit/X-Ray/Sleep Lab dependency interfaces; call from Retry when the state is Failed and on `Debounced`/`Failed`/`null` refresh results.

#### [F-11] LOW [FIXED in be42593] — Cockpit and Health "Refresh failed" effects outlive the visit
- **Category:** Lifecycle
- **Location:** `feature/health/.../HealthViewModel.kt:86-97,153-175`, `HealthRoute.kt:61-67`; `feature/dashboard/.../CockpitViewModel.kt:120-128,157-161`, `CockpitRoute.kt:43-49`.
- **Confidence:** Confirmed. Validator: CONFIRMED (found independently by two reviewers).
- **Trace:** `onHidden` keeps `userWaiting` and doesn't drain the `BUFFERED` effect channel; `AppShell` composes only the selected tab, so the collector is gone; a pulled refresh (≤10 s) failing after the user left buffers `RefreshFailed`, which the next visit's collector shows — possibly right after a successful refresh. Collectors lack `repeatOnLifecycle(STARTED)`. Sleep Lab and X-Ray already gate/drain correctly.
- **Fix:** in `onHidden`: `userWaiting = false; while (effectChannel.tryReceive().isSuccess) Unit`; send only `if (userWaiting && visible)`; wrap route collectors in `lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED)`.

#### [F-12] LOW [FIXED in 460c22f] — Cockpit timeline reload cancels a scrub/brush in progress
- **Category:** Logic Bug (input)
- **Location:** `core/designsystem/.../chart/scrub/ScrubGestures.kt:42` (`pointerInput(window)`); `feature/dashboard/.../CockpitViewModel.kt:150-177` (`to = clocks.wallMs()`), `CockpitMappers.kt:150-151`.
- **Confidence:** Confirmed. Validator: CONFIRMED-NARROWER (only the post-`Refreshed` reload a few seconds after entering).
- **Failure scenario:** a gesture started right after opening the tab is cleared without commit when the refresh lands; the still-pressed finger is ignored by the restarted `awaitFirstDown`.
- **Fix:** key `pointerInput(Unit)`, read the window through `rememberUpdatedState` in `ScrubGestureInputs`, build the `TimeScale` at Down.

#### [F-13] LOW [FIXED in 75edef5] — Multi-day hypnogram labels sub-day ticks with dates only
- **Category:** Logic Bug (presentation)
- **Location:** `core/designsystem/.../chart/hypnogram/SleepHypnogram.kt:290-294,454-455`; `…/chart/math/TimeTicks.kt:15-31`; producer `feature/sleeplab/.../SleepLabMappers.kt:48`.
- **Confidence:** Confirmed; reachable for SinceUnplugged/CustomMarker windows of 2–2.5 days. Validator: CONFIRMED-NARROWER.
- **Failure scenario:** 12 h ticks labelled "18 Oct, 18 Oct, 19 Oct, 19 Oct"; the selection a11y description loses time of day.
- **Fix:** have `niceTimeTicks` return the chosen step and pick the formatter from the step (`d MMM HH:mm` for sub-day steps), or add `dayAligned = window ≥ 2 days`.

#### [F-14] LOW [FIXED in e883122] — A chart series with a single point draws nothing
- **Category:** Logic Bug (presentation)
- **Location:** `core/designsystem/.../chart/scrub/ScrubChartRenderer.kt:228-229`; `core/diagnostics/.../TimelineBuilder.kt:74-76`; `…/ipc/client/BatteryHistory.kt:142`.
- **Confidence:** Confirmed. Validator: CONFIRMED.
- **Failure scenario:** 24 h window with one level change (e.g. charged to 100 % early) shows axes and lanes with no line and no empty-state text.
- **Fix:** seed a LEVEL point at `from` from the last LEVEL before the window (add LEVEL to `TimelineEvents.withSeeds`) and extend to `to`; minimally draw a dot when `points.size == 1`.

#### [F-15] LOW [FIXED in d848ec6] — A timed-out force-stop is reported FAILED instead of UNVERIFIED
- **Location:** `…/ipc/toolkit/ToolkitExecutor.kt:134-135` vs `writeFailed` (~`:374-378`).
- **Confidence:** Confirmed; impact cosmetic (reconcile still ends FAILED/UNVERIFIED). Validator: CONFIRMED-NARROWER.
- **Fix:** `if (!write.ok) return writeFailed(write, "stopped=${target.facts.stopped}")`.

#### [F-16] LOW [FIXED in c920fe7] — `awaitServiceUse` can give up when the state flips to Ready between a failed acquire and the state read
- **Location:** `…/ipc/ShizukuManagerImpl.kt:234-240` (state read outside `bindingLock`; set at ~`:355-358` on Main).
- **Confidence:** Confirmed (narrow window; traced only). Validator: CONFIRMED-NARROWER.
- **Failure scenario:** one cold-start capture returns unavailable although Shizuku became Ready microseconds later.
- **Fix:** decide inside the lock with three outcomes (acquired / wait while NotRunning / fail), or retry once when the observed state is Ready.

#### [F-17] LOW [FIXED in dc11437] — After a slow service shutdown, the timeout rebind can leave a dead telemetry binder in use while a screen holds the binding
- **Location:** `…/ipc/ShizukuManagerImpl.kt:73-79` (KDoc admits the handover is not fully fenced), `:84-128`, `:214-223`, `:314-320`, `:488` (`UNBIND_SETTLE_TIMEOUT_MS = 2_000L`); `…/ipc/toolkit/BgDexoptController.kt` (`SHUTDOWN_CANCEL_TIMEOUT_MS = 3_000L`).
- **Confidence:** Confirmed against disassembled Shizuku api 13.1.5 (`ShizukuServiceConnection.died()` fires once, clears connections). Validator: CONFIRMED-NARROWER (requires >2 s old-process death + holder in window + later death of the new process).
- **Failure scenario:** every live reading/toolkit call fails (RemoteException → null) until the user leaves Cockpit/Health.
- **Fix:** on `RemoteException` with `!service.asBinder().isBinderAlive`, invalidate the binding under `bindingLock` (same generation) and rebind; `linkToDeath` on the binder accepted in `onServiceConnected`; raise the settle timeout above the service's bounded shutdown (e.g. 5 s).

#### [F-18] LOW [FIXED in b645262, 729876f] — Doze compare-and-swap uses an execute-time read, so the "changed while you were deciding" re-ask never fires
- **Location:** `…/telemetry/toolkit/ToolkitActions.kt:551-555,629-633,682-683`; `feature/toolkit/.../ToolkitViewModel.kt:409-437`.
- **Confidence:** Confirmed. Validator: CONFIRMED-NARROWER (overwritten value is still recorded as `before`).
- **Fix:** carry the plan-time raw value in `ApplyDoze(profile, expectedBeforeRaw)` and refuse with STALE when it differs.

#### [F-19] LOW [FIXED in da2dbfd] — Each toolkit load recomputes active overrides twice under the global mutex
- **Location:** `app/.../AppContainer.kt:250-255`; `feature/toolkit/.../ToolkitViewModel.kt:144-146,207-209`; `ToolkitActions.kt:264-270`.
- **Confidence:** Confirmed. Validator: CONFIRMED.
- **Fix:** drop `activeOverrides().first()` from `installedApps()`; let the VM merge restricted flags from its own collector (`showOverrides` already does).

#### [F-20] LOW [FIXED in 0a7dd92] — Toolkit detail sheet can stay on its spinner after Hidden → Visible when the reload fails
- **Location:** `feature/toolkit/.../ToolkitViewModel.kt:126-134,185-194`; `ToolkitApps.kt:249`.
- **Confidence:** Confirmed. Validator: CONFIRMED-NARROWER (sheet can be dismissed).
- **Fix:** on the FAILED path set Loading controls to `Unavailable` (with retry) or re-run `loadDetail()`.

#### [F-21] LOW [FIXED in 245f142] — `lastOfEachKindBefore` returns history rows with unknown wall time (`wallMs = -1`)
- **Location:** `core/database/.../Daos.kt:228-233`; `…/ipc/client/BatteryHistory.kt:189`; `app/.../AppContainer.kt:177`; contrast `…/telemetry/store/SnapshotStore.kt:67-72`.
- **Confidence:** Confirmed; impact small (pruned soon after insert). Validator: CONFIRMED-NARROWER.
- **Fix:** `… WHERE wallMs >= 0 AND wallMs < :beforeWallMs GROUP BY kind`; optionally skip `wallMs < 0` events at insert.

#### [F-22] LOW [FIXED in 222ff2c] — Battery-stats session matching is exact in `dischargeDelta`/resolver SQL but ±2 s elsewhere
- **Location:** `…/telemetry/diff/SnapshotDiffer.kt:143-153` vs `:175-184`; `…/telemetry/capture/SnapshotCapturer.kt:129`; `ReferenceResolver.kt:110-114`; `Daos.kt:100`.
- **Confidence:** Confirmed inconsistency; `BatteryStatsImpl.noteCurrentTimeChangedLocked` does shift `statsStartMs` on wall-clock steps (Android 15 services.jar), sub-2 s shifts uncommon. Validator: CONFIRMED-NARROWER.
- **Fix:** one shared `sameBatteryStatsSession` predicate used by `dischargeDelta`; range comparison in the resolver SQL.

#### [F-23] LOW [FIXED in 9c2e9f7, 5d9e0af] — Overlapping full captures fail each other's UID identity proof
- **Location:** `…/telemetry/source/UidIdentityReader.kt:97-106,118,139-147,165-167`; `…/telemetry/capture/SnapshotCapturer.kt:47-51`; `app/.../capture/CapturePolicy.kt:84`.
- **Confidence:** Confirmed (narrow overlap of short phases). Validator: CONFIRMED-NARROWER. Becomes more relevant once F-02 is fixed.
- **Fix:** replace fail-fast `physicalCall` with a short timed wait (mutex with timeout, released in the executor `finally`), keeping a separate abandoned-call flag.

#### [F-24] LOW [FIXED in 56984db] — Cockpit screen-time tile falls back to history metrics even when part of the interval is unknown
- **Location:** `feature/dashboard/.../CockpitMappers.kt:96`.
- **Confidence:** Confirmed. Validator: CONFIRMED-NARROWER.
- **Fix:** `screenOnMs = sleep?.screenOnMs ?: metrics.screenOnMs.takeIf { metrics.unknownMs == 0L && metrics.discontinuities.isEmpty() }`.

#### [F-25] LOW [FIXED in 6c517ac] — "Most prominent" anomaly is chosen by comparing magnitudes in different units
- **Location:** `core/diagnostics/.../model/InsightOrder.kt:13-18`; `BatteryStoryBuilder.kt:34`; pinned by `InsightOrderTest.kt:38-39`.
- **Confidence:** Confirmed. Validator: CONFIRMED (GPS leak is the only RED leak).
- **Failure scenario:** a 5-minute RED GPS leak (300 000 ms) always outranks a RED Night Vampire (mAh) or a 90 % Awake Violation (ratio ≤ 1.0) in the story's "most notable" sentence.
- **Fix:** normalise each magnitude by its rule's RED/AMBER threshold (or use a fixed rule priority) for `topRule`; keep within-rule ordering; update the test.

### Rejected during validation
- **Cockpit "Deep sleep" tile pairs a screen-off ratio with whole-interval suspended time** — REFUTED: both values are on-battery time bases and differ only by suspended time while the screen is on (≈0). At most a tidy-up (`screenOffDeepSleepMs`).
- App/build reviewer candidates (job re-arm ordering vs `jobFinished`, main-thread `ShizukuManager.refresh()` in `onStart`) were not reproducible and are not reported as defects.

---

## 3. Dead Code, Redundancy & Technical Debt Cleanup
Verified with `rg` across `app core feature` as having no production reader (test/preview/debug-only use noted). Recommendations only — nothing was removed.
- **Persistence:** `TelemetryDao.hourly` (unread) while `telemetry_hourly` is written on every prune and never pruned — write-only table; `SnapshotDao.deleteById`, single-entity `insertAvailability` (tests only); `SnapshotStore.byId` and full-read `latestByTrigger` (tests/previews); `SnapshotStore.latest` (debug ProbeReceiver only).
- **IPC:** `DumpTarget.POWER_PROTO` and `DEVICEIDLE_TEXT` still accepted by `openDump` but never requested — dropping shrinks the privileged surface; hidden-API stub constants `POWER_COMPONENT_ANY/COUNT`, `PROCESS_STATE_ANY/COUNT`; `ShizukuManager.stop()` (tests only).
- **Telemetry:** `KernelUnits.uaToMa`; both `DumpReader.readAll` overloads (tests); `SnapshotDiff.levelDropPct` (tests/previews); `PowerCalibration.cellCount` (never read); `SnapshotDiff.durationMs` (debug only).
- **Toolkit:** `ToolkitDependencies.holdBinding()` unused by the feature; `ToolkitActions.release()` / `ToolkitGateway.release()` no production caller.
- **Features/design system:** `SliceInfo.gapMs` + `XRayMapper.GAP_BANNER_MIN_MS` (computed, never read); `HealthInputs.loadedAtMs` (write-only); string `chart_a11y_cursor` (en/tr) unreferenced; `InspirationTheme(dynamicAccent)`/`withAccentFrom` never enabled; `LocalReducedMotion` never provided; `TimeSliceBanner(String, String, …)` overload unused; `ChargingCaptureScheduler.arm(…, reArmFromJob)` parameter never passed.
- **Performance debt (not a defect):** every analysis loads and re-sorts the full retained history (`IntervalAnalyzer.kt:40`, up to 30 days).

---

## 4. Prioritized Remediation Roadmap

**Phase 1 — Immediate hotfixes (isolated commits)**
1. F-01 appop `allow` semantics + legacy-row repair + `ToolkitApi.VERSION` bump (device-verify on redroid with `dumpsys alarm`).
2. F-06 restorable custom doze original.
3. F-05 refuse toolkit outside user 0 (minimal) — full multi-user support later.
4. F-07 live gauge staleness.

**Phase 2 — Analysis correctness & lifecycle hardening**
1. F-02 cross-snapshot identity comparison by namespace + fingerprints (then F-23 timed gate).
2. F-03 self-paired reference fallback / explicit "not enough data".
3. F-04 history-ingest horizon → `unknownMs`.
4. F-10 explicit recompute trigger; F-11 visit-scoped effects; F-21, F-22, F-24, F-25.
5. F-16, F-17 binder-handover hardening; F-15, F-18, F-19, F-20 toolkit polish.

**Phase 3 — Interaction/perf and test coverage**
1. F-09 bounded pulse; F-08 handle slop/radius; F-12 window-stable gestures; F-13 tick/formatter coupling; F-14 seeded level line.
2. Tests to add (blind spots named by reviewers): real `allow/default` platform semantics (executor/parser); custom-original doze chains; resolver ref==end; history horizon < coveredTo; cross-process identity scopes; concurrent `UidIdentityReader`; AnalysisSession under a permanent collector with Debounced/Failed refresh; cross-visit effects; caller-UID rejection for `readBatteryUsage`/`readPowerMonitors`/`readDeviceIdle`; `openDump` success path; toolkit from a non-zero user; timed-out force-stop; `StatusNotificationService`/receivers/`ChargingCaptureJobService` lifecycle; minified-release UserService start.

---

## Coverage ledger
| Subsystem | Reviewer scope | Notes / gaps |
|---|---|---|
| Privileged IPC service (`core/shizuku-ipc` service/toolkit/models, AIDL, hidden-api-stub) | full main read | several test bodies read by name only; `BatteryUsageAdapter.kt` not read by this reviewer |
| IPC client + Shizuku manager | full main read; Shizuku api 13.1.5 disassembled | `ShizukuManagerImplTest` partially read |
| Telemetry engine (excl. toolkit) | full main read | most test bodies by name; `powermanagerservice.proto` not opened |
| Toolkit write path (telemetry toolkit + `:feature:toolkit`) | `ToolkitActions.kt` fully read | UI-only toolkit files partially read |
| Persistence (`:core:database`, store interface) | full read; schema JSONs diffed | some test bodies by name |
| `:app` + build config + manifests + R8 rules | full main read | `ProbeReceiver.kt` partially read; release R8 not executed |
| Diagnostics + Cockpit | full main read | dictionary text bodies not read |
| X-Ray, Sleep Lab, Health | main read with partial reads of large screen files | `XRayScreen`/`XRayDetail`/`HealthCards` partially; previews/screenshots not read |
| Design system | main logic read | several small components partially; screenshot sources/refs not inspected |
| Not audited | `android-kit-codex/` and `.claude/` kit tooling (out of scope, protected) | — |

**Tools actually run**
- `./gradlew testDebugUnitTest lintDebug validateDebugScreenshotTest --continue --max-workers=8` → exit 0 (tasks up-to-date).
- `./gradlew testDebugUnitTest --rerun --continue --max-workers=8` → exit 0; **1,399 tests, 0 failures, 0 errors, 0 skipped** across 11 modules; no hangs (slowest class 4.1 s).
- Lint: 0 issues in all library modules; `:app` 9 warnings, all version/config (OldTargetApi, Gradle 9.8.1 / Kotlin 2.4.21 / hiddenapibypass 6.2 / robolectric 4.17 / annotation 1.11.0 available, NotShrinkingResources).
- Secret scan (`rg` over source/build files): none; release signing reads user-level Gradle properties.
- Redroid (Android 15): appop experiment for F-01; read-only `sqlite3` SELECTs; Shizuku APK and services.jar pulled and disassembled read-only.

**Unavailable / not performed:** no dependency-vulnerability scanner configured (lint version checks only); no release/R8 build executed; no work-profile device run (F-05); no frame-rate measurement (F-09); no emulator runs.

---

## 5. Audit isolation
**Contamination ledger**
- **Coordinator (this session):** before investigation, the session-start hook auto-injected the generated codebase-map `INDEX.md`, which summarises a prior remediation's themes (capture-time UID ownership proof / schema v5, truncated/absent-source handling, atomic charging-job scheduling, recoverable toolkit restore/admission, measurement-scoped feature data, non-pointer interval controls). The git-status context showed recent commit subjects naming audit remediation and one finding ID. The auto-memory index mentions a previous audit session and follow-up tickets (no details). `CLAUDE.md` contains no findings. The prior report was copied by the guard and **not opened** before freeze; no `git log/blame/show` was run.
- **Subsystem reviewers (9 fresh contexts):** neutral brief with explicit exclusions, but a mandatory SubagentStart hook made each read one generated map document before the brief (database.md + INDEX.md, communication.md, patterns.md ×3, modules.md, entry-points.md, architecture.md). All reported not using it to select/validate findings.
- **Validators (3 fresh contexts):** candidates + raw source pointers only, no reviewer severity/confidence; INDEX.md injected by the same hook.
- **Source comments/tests:** 3 prior-audit-ID references in toolkit scope; Sidequest ticket IDs (SQ-1, SQ-114, SQ-118) in `:core:shizuku-ipc`. Not used for selection.
- **Classification:** **contaminated / not blind (partial)** — coordinator exposed to a thematic summary of prior remediation; workers fresh but exposed to generated map docs. The coordinator did not pass prior conclusions to workers.

**Protected-file integrity event:** the first guard session `run-fmvs0tor` (begun 08:43, before any project-summary reading) refused to freeze: between 08:50 and 08:52 a separate, user-initiated Codex session ran an android-kit merge that changed `.codex/android-kit/` (`scaffold.py`, `manifest.json/.txt`, `conflicts.json`, `resolutions.json`, a new backup directory, `runtime/board.sqlite3`, `runtime/verify-kit-merge.py`). This audit made no kit changes; application source, the Claude audit skill files and the existing `AUDIT_REPORT.md` were verified unchanged against the pinned revision. Nothing was restored. The user confirmed the merge was theirs and approved re-baselining.

**Freeze evidence:** re-baselined guard session `run-qsby37up` (begun after the integrity event, still before reading any prior report). The findings above are byte-identical to the draft written for `run-fmvs0tor`; only this isolation paragraph was added. Freeze hash recorded by `audit-guard.py freeze`.


## Audit Delta (post-freeze comparison)

### Boundary, integrity and inputs
- The independent findings (F-01…F-25) were **frozen before the previous report was opened**. Guard session `.git/audit-history/run-qsby37up/`; frozen independent SHA-256 **`fd303f93c2756392c2440e8bfdd2072e05d31af15e5661bed172c8faf6edfde2`**. The first session `run-fmvs0tor` refused freeze because of a concurrent user-initiated Codex android-kit merge (disclosed in §5); the user confirmed it and approved re-baselining. The prior report was byte-compared (`cmp`, not displayed) as unchanged before the new `begin`.
- Current source `df8a9098f092cd9d555ed0e7198feda9858674c5`; application/build source unchanged throughout.
- Prior material read after freeze: this session's `previous-report.md` only (audit at `bddeec0`, 24 findings A-01…A-24, with an embedded F-series delta and a remediation log marking all 24 FIXED). No git history, board, PROGRESS/PROJECT/MAP, transcripts or memory bodies were read for this comparison.
- Post-freeze checks: scoped `rg` over `app/src/main core/*/src/main feature/*/src/main` for the A-01, A-21 and A-24 fix sites. No new Gradle gate, device change or remediation.
- **Naming collision:** this report's finding IDs are `F-01…F-25`. The previous report's embedded *archived* F-series (an older audit) is unrelated; prior findings are referred to here only by their `A-xx` IDs.

### Classification method
Matching is by root cause, not ID. `fix present in source; verification limited` = the named repair is visible in current source and the shared baseline (1,399 tests, re-executed) passed; it does not re-run the old reproduction. Absence from the new findings is never treated as proof of resolution.

### Per-finding comparison with A-01…A-24

| Prior finding | Classification | Current evidence / relation to new findings |
|---|---|---|
| **A-01** HIGH — rebind inside Shizuku death callback loses the new connection | fix present in source; verification limited | `ShizukuManagerImpl.kt:100-111` now settles via `scope.launch(Dispatchers.Main)` with `awaitingUnboundDeath` re-checked under the lock; the IPC-client reviewer independently judged the callback path "properly fenced". **New F-17 is the residual the A-01 remediation explicitly left open** ("timeout-fallback wrapper reuse is unchanged"): independently rediscovered before freeze. |
| **A-21** HIGH — secondary-user X-Ray actions modify the owner installation | fix present in source; verification limited (X-Ray only) | `XRayViewModel.kt:50,243,419` contain `UnsupportedUser`. **New F-05 is a same-root sibling** independently found before freeze: the Toolkit screen (and the executor itself) still pins `--user 0` while inspecting the app's own user, with no equivalent guard (`rg` finds no user check in `feature/toolkit` or `ToolkitExecutor`). The A-21 containment was applied at one entry point, not at the shared executor boundary. |
| **A-24** LOW — hidden Sleep Lab refresh failure replayed on a new visit | fix present in source; verification limited (Sleep Lab only) | `SleepLabViewModel.kt:89` drains, `SleepLabRoute.kt:58` uses `repeatOnLifecycle`. **New F-11 is the same root in Cockpit and Health**, independently found by two reviewers before freeze; the A-24 fix pattern was not propagated to sibling features. |
| **A-08** MEDIUM — highest-drain package hints mistaken for shared-UID replacement | fix present in source; verification limited — **and its accepted trade-off is contested by new F-02** | The remediation introduced capture-time identity with a cross-snapshot scope of user/serial/**reader epoch**/**package-change sequence**, and the log states process restarts and package-sequence changes "reduce coverage conservatively". New F-02 (HIGH) argues from current source and the stored fingerprints that this conservatism drops per-app attribution on most real intervals (any process restart or package update between endpoints) without protecting against a misattribution the fingerprints miss. **This is a product/accuracy decision for the owner**, not a regression of the original A-08 bug. New F-23 (fail-fast identity gate under overlapping captures) is a related concurrency edge of the same mechanism. |
| **A-06** MEDIUM — accepted Doze original unrestorable after service recreation | fix present in source; verification limited | Distinct from new **F-06**, which loses a custom original through the Balanced→Standard override chain (`mayBeActive`/`isActive` null-after special case), not through service recreation. Same feature area, different root. |
| **A-03** MEDIUM — partially effective failed restore disappears from recovery | fix present in source; verification limited | The toolkit reviewer found write-ahead/restore chains sound. Note new **F-01**: restore of a background appop writes `default`, which Android still enforces — the restore "succeeds" by read-back while the restriction persists. Different root (wrong mode token), same undo-integrity theme; A-03's test suite models allow≡default and could not catch it. |
| **A-10** MEDIUM — sample-write error terminates live telemetry | fix present in source; verification limited | New **F-07** is downstream and distinct: when readings stop (null/exception), the Cockpit never clears the last value, and the `.catch` comment's "leaves the gauge on dashes" claim is false. |
| **A-23** LOW — historical fallback narrated as "the last" hours | fix present in source; verification limited | New **F-04** is a different temporal-truthfulness root: metrics extend the last known state past the last history ingest. |
| **A-14** MEDIUM — feature caches survive analysis change | fix present in source; verification limited | New **F-10** concerns the opposite direction (the shared session can't be made to recompute), not stale feature caches. |
| **A-13** MEDIUM — wall-clock ordering picks an obsolete trigger capture | fix present in source; verification limited | Persistence reviewer confirmed current-boot elapsed ordering. New **F-03** (self-paired reference) is a different resolver defect. |
| **A-16** MEDIUM — no non-pointer interval control | fix present in source; verification limited | Slice controls exist. New **F-08** (handle hit-zone/slop) and **F-12** (window-keyed gesture restart) are pointer-path defects in the same chart, not regressions of A-16. |
| **A-02** MEDIUM — kernel-only Binder reads outlive timeout/cancellation | fix present in source; verification limited | IPC-client reviewer: telemetry calls bounded through `binderCall`; abandoned native calls still hold an IO thread (documented limit). |
| **A-04 / A-05** MEDIUM — dexopt supervision / admission | fix present in source; verification limited | IPC-service and toolkit reviewers found the dexopt state machine and `dexoptStartsInFlight` admission race-free. |
| **A-12** MEDIUM — stale scheduler generation replaces a newer job | fix present in source; verification limited | App reviewer: generation check and submit under one lock. |
| **A-15 / A-18 / A-19 / A-20** MEDIUM — Health boundaries, failed-read wording, thermal labelling, hold provenance | fix present in source; verification limited | Features reviewer checked Health edge precedence and axis math as clean; the specific A-18/19/20 paths were not re-traced. |
| **A-17** MEDIUM — UID ownership cached as cosmetic | fix present in source; verification limited | `CachedAppLabels` path inspected only incidentally. |
| **A-07 / A-09 / A-11** MEDIUM — truncated UID usage, zero-byte alarm dump, transient design capacity | not assessed / insufficient evidence | Not specifically re-traced; the telemetry reviewer noted truncated-proto handling as currently unreachable but did not re-run A-07/A-09/A-11 scenarios. |
| **A-22** LOW — Sleep Lab discards valid cross-boot sleep data | not assessed / insufficient evidence | Not specifically re-traced. |

**Summary:** 24 prior findings compared — **3 with independently rediscovered same-root survivors in sibling scopes** (A-01→F-17, A-21→F-05, A-24→F-11), **1 whose accepted trade-off is contested** (A-08→F-02, plus F-23), **16 fix present in source; verification limited**, **4 not assessed** (A-07, A-09, A-11, A-22). No prior item is classified "verified resolved within stated checks"; the green 1,399-test baseline is not treated as proof.

### New-in-this-audit (no prior counterpart)
F-01 (appop `default` vs `allow`, device-confirmed), F-03, F-04, F-06, F-07, F-08, F-09, F-10, F-12–F-16, F-18–F-22, F-24, F-25. The previous report's dead-code section found nothing safe to delete; this audit lists `rg`-verified unreferenced declarations (§3) as recommendations only.

### Pattern worth noting for remediation
Three of the surviving issues are fixes applied at **one call site instead of the shared boundary** (A-21 in X-Ray but not the executor; A-24 in Sleep Lab but not Cockpit/Health; A-01's callback path but not the timeout path). Future remediation should search sibling entry points for the same root before closing a finding.

### Baseline comparison
Previous final gate: 1,399 unit tests / 177 screenshot validations, 9 lint warnings. This audit: 1,399 unit tests re-executed (0 failures/errors/skips), screenshot validation passed (up-to-date), same 9 lint warnings. No post-freeze discovery was added to the independent findings; frozen bytes are unchanged.

## Remediation (2026-10-19, branch `audit-remediate-e-20261019`, release 0.2.3)

All 25 confirmed findings are FIXED. Each fix is one commit with a regression test, written in an isolated worktree, verified with its module's narrow tests and lint, then cherry-picked or fast-forwarded. F-01, F-05, F-06, F-18 (toolkit) and F-02, F-23 (UID identity) also had an independent diff review; each follow-up commit below closes a gap that review or the user report raised.

| ID | Commit(s) | Status |
|---|---|---|
| F-01 | `81c4c9e`, follow-up `c924c24` (apps a 0.2.2 restore left at `default` are listed restricted, and Reset all writes `allow`) | FIXED |
| F-02 | `d60ec89`, follow-up `05f48ef` (one bulk `getInstalledPackages` read proves every app UID within the owner budget; per-UID fallback in energy order) | FIXED |
| F-03 | `bf55974` | FIXED |
| F-04 | `fcaa4e2`, follow-up `6b3c38d` (2-minute grace window; single-query horizon) | FIXED |
| F-05 | `274353f` | FIXED (containment only; no multi-user support) |
| F-06 | `170ad81` | FIXED |
| F-07 | `6fb77bf` | FIXED |
| F-08 | `e197969` | FIXED |
| F-09 | `9dce621` | FIXED (no frame-count measurement on a device) |
| F-10 | `4e398fc` | FIXED |
| F-11 | `be42593` | FIXED |
| F-12 | `460c22f` | FIXED (no Compose UI test harness; covered by review only) |
| F-13 | `75edef5` | FIXED (2 screenshot references intentionally updated) |
| F-14 | `e883122` | FIXED (new single-point screenshot reference) |
| F-15 | `d848ec6` | FIXED |
| F-16 | `c920fe7` | FIXED (race not deterministically testable; existing wait tests pass) |
| F-17 | `dc11437` | FIXED |
| F-18 | `b645262`, follow-up `729876f` (refuse a doze apply unless the card is loaded) | FIXED |
| F-19 | `da2dbfd` | FIXED |
| F-20 | `0a7dd92` | FIXED |
| F-21 | `245f142` | FIXED |
| F-22 | `222ff2c` | FIXED |
| F-23 | `9c2e9f7`, follow-up `5d9e0af` (gate released on any throwable; token-clear test) | FIXED |
| F-24 | `56984db` | FIXED |
| F-25 | `6c517ac` | FIXED (fixed rule priority, not a normalised score) |

**User report tied to F-02:** on a real phone, X-Ray "Apps" showed no apps or icons, only system UIDs. Root cause at `bf9b625`: app UIDs required equal reader epoch and package-change sequence across snapshots, so every app was `uid_identity_unknown` and `XRayMapper.apps` dropped it. Two other contributing risks were closed: the per-UID 400 ms owner budget, which cut off high (user-installed) UIDs, and the F-23 gate.

**Final gate** (alone, `--max-workers=8 --rerun-tasks`): `testDebugUnitTest lintDebug validateDebugScreenshotTest :app:assembleDebug :app:minifyReleaseWithR8 :app:assembleRelease`, exit 0.
- 1,509 unit tests, 0 failures/errors/skips (baseline 1,399).
- The same 9 lint warnings, 0 errors.
- Screenshots validated.
- Signed 0.2.3 release APK certificate SHA-256 `1d344dd2…f1f3de60`.

**Device (redroid, Android 15):**
- 0.2.3 was installed in place over 0.2.2 and runs with no crash.
- A capture from the new service process (new reader epoch, package-change sequence 39 → 40, bulk owner read) produced owner fingerprints identical to the 0.2.2 capture for all 27 provable app UIDs. The other two UIDs have no installed package. Under F-02 these compare valid; at `bf9b625` all 27 were withheld.

**Not verified on a device:**
- The X-Ray app list itself: redroid has no power model, so every UID reads 0 mAh.
- F-01 through the Toolkit UI: every redroid app the picker lists is protected.
- F-05 on a work profile.

**Interface notes for the decision log:**
- `ToolkitApi.VERSION` is now 3 (mode `allow`=3; a write of mode 2 is refused; refusal `UNSUPPORTED_USER`=14).
- `recomputeAnalysis()` was added to the Cockpit, X-Ray and Sleep Lab dependency interfaces.
- `ToolkitDependencies.installedApps()` now returns launchable apps only.
- `SnapshotStore.historyHorizonWallMs()` and `UidIdentityBackend.installedOwners()` were added.
- No Room schema change.
