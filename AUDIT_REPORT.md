# Whole-repository audit — Inspiration

Audited 2026-10-18 at HEAD `bf0479e49e5b1e3b42989288213252f8a0432fc8`, branch `Review-n-fix`. Application source (`app/`, `core/`, `feature/`, `build-logic/`, `gradle/`, `*.kts`) was clean against HEAD for the whole run and was re-checked before publishing. The only dirty paths were the kit and agent tooling (`.claude/`, `.codex/`, `.agents/`, `android-kit-codex/`, `AGENTS.md`, `CLAUDE.md`), which this audit did not review as application code.

Mode: standalone review (Sidequest disabled, hooks untouched). No application code, test, screenshot reference, dependency or setting was modified. The prior report was archived **unread** before Phase 1 to `.git/audit-history/20261018T192546/AUDIT_REPORT.md` and read only after validation, for the Audit Delta (section 6).

Finding IDs use a new `B` series so they cannot be confused with the prior audit's `A01–A12`.

## 1. Executive Summary & System Health Scorecard

- **Architecture & Modularity: 8/10.** Clean feature/core boundaries, pinned dependency interfaces and a single privileged gateway. But history-boundary semantics (RESET/START/SHUTDOWN) are still re-implemented separately in each reducer, and one of them is wrong (B01).
- **Correctness & Resilience: 6/10.** The baseline is green and caller-UID, argv and pipe hygiene are solid. But battery history, the backbone of charge, capacity and interval features, is dead on Android 14 because of a compile-only stub type mismatch (B16, device-confirmed). Three medium logic defects also remain: measured capacity is lost when stats reset at unplug, apps disappear from diagnostics, and an uncertain toolkit write can later be re-applied.
- **Concurrency & Resource Safety: 8/10.** Structured scopes, `NonCancellable` audit sections, symmetric binding holds and bounded processes are all in place. What remains is narrow conflation/ordering races and a scope with no exception handler.
- **Security & Privilege Hygiene: 9/10.** Every AIDL transaction (1–8, 20–31, destroy) checks the caller UID. Privileged commands use a fixed absolute argv with validated packages and `--user 0`, no `sh -c`. Debug entry points are `DUMP`-guarded and absent from release (verified in the R8 mapping). All backup domains are excluded. No secrets in tracked sources.
- **Performance & Efficiency: 6/10.** Several paths do privileged or full-history work no screen asked for: a full analysis at every background process start, a redundant full batterystats dump per charge job, the 30-day history loaded repeatedly, an override recompute on every dexopt poll, and an icon LRU that gets zero hits. This works against the product's "zero observer effect" promise.

Scores are qualitative review judgments, not measured guarantees.

**Top 3 Systemic Risks**

1. **Each history consumer handles discontinuities its own way.** The capacity extractor treats every `RESET` as "discard the open charge". Android resets batterystats exactly when a deep or full charge is unplugged, so the most common charges never become charge sessions and measured capacity is rarely produced (B01). The prior audit fixed the same class of bug in the Timeline and Health reducers (A06/A07), but not here.
2. **A heuristic is used as UID identity.** `packageName` comes from `packageWithHighestDrain`, which is often null. The differ treats null→name as "package changed", and since the A05 fix the rule engine drops every package-changed UID. So an app idle at the reference point and active afterwards (the exact Night-Vampire profile) silently disappears from R1/R2/R4, X-Ray and Sleep Lab (B02).
3. **The toolkit audit log is the restore source of truth, but outcomes are modeled as binary.** A write that timed out or could not be read back is closed as FAILED. Lost replies stay PENDING until the next Shizuku Ready *edge*, and that edge can itself be lost (B09). Both drop the row out of the override chain, and a later manual change can then make Reset-all re-apply the restriction (B03).

**Totals:** 16 findings reported: 1 HIGH, 3 MEDIUM, 12 LOW, no CRITICAL. 13 are Confirmed (8 by throwaway reproduction tests, 3 by airtight trace, and B16 and B13 on a device) and 3 are Plausible (real mechanism, unproven or narrow trigger). B16 came from the device pass, which ran after the static review. One candidate was rejected after validation (section 2, "Rejected").

### Baseline and evidence

| Check | Command (from checkout root) | Result |
|---|---|---|
| Unit tests, all modules + app lint | `bash .claude/kit/gradle-check.sh testDebugUnitTest :app:lintDebug --continue --max-workers=4` | **PASS, exit 0** (log `.claude/kit/logs/gradle.Qy0wekz8.log`). **1034 tests, 0 failures, 0 skipped**: app 94, database 29, designsystem 102, diagnostics 136, shizuku-ipc 196, telemetry-engine 227, dashboard 35, health 63, sleeplab 54, toolkit 51, xray 47. Lint: 0 errors / 10 warnings, all housekeeping (OldTargetApi; newer Gradle, Kotlin, robolectric and hiddenapibypass; ObsoleteSdkInt `InspirationApplication.kt:22`; NotShrinkingResources `app/build.gradle.kts:36`; UseKtx `StatusNotificationSettings.kt:21`). |
| Release build with R8 | `bash .claude/kit/gradle-check.sh :app:assembleRelease --max-workers=4` | **PASS, exit 0** (log `.claude/kit/logs/gradle.l7kqqRc2.log`). Unsigned APK built. Mapping confirms `TelemetryUserService` and `ITelemetryUserService$Stub`/`$Proxy` keep their names (consumer rules) and that no `com.akane.inspiration.debug.*` class ships. |
| Secret scan | grep for key/token/password/private-key/AWS patterns across app/core/feature/build-logic/gradle/*.kts; `git ls-files` for keystore/env/google-services files | No secrets. The only hits are release-signing values read from user-level Gradle properties (`app/build.gradle.kts:26-28`). No sensitive files are tracked. Keystores and `local.properties` were not read. |
| Dependency review | `gradle/libs.versions.toml` read in full | Pinned, current versions. Lint lists minor updates available. **No advisory database scan was run** (see gaps). |
| Reproductions | 9 throwaway test classes in a detached scratch worktree at `bf0479e`, run one Gradle command at a time with `--max-workers=4` | `:core:diagnostics … --tests '*AuditReproC6*'` exit 0. `:core:shizuku-ipc`, `:core:telemetry-engine` and `:app` `--tests '*AuditRepro*'` exited 1 twice first (a missing import in the C2 test, then one wrong C5 expectation, which corrected the C5 mechanism). Final runs: `:app … '*AuditRepro*'` exit 0 (4 tests) and `:app … '*AuditReproC15*'` exit 0. The shizuku-ipc and telemetry-engine repros passed in the `--continue` run. Test sources are kept privately at `.git/audit-history/20261018T192546/repro/` (not in the working tree). |

Test IDs in the evidence directory follow the validator's candidate numbering (`C1–C16`). The mapping to report IDs is given on each finding.

### Phase 1 forensics (summary)

- **Inventory:** 12 Gradle modules, about 48k Kotlin lines, about 22k of them tests. Largest: telemetry-engine 8.9k, shizuku-ipc 7.3k, designsystem 6.3k, app 4.8k, toolkit 4.2k. 14 AIDL files; 3 manifests; no CI workflow (`.github/` absent).
- **Churn and fix-commit hotspots (last 500 commits, source only):** `ToolkitActions.kt` (10 changes, 9 in fix commits), `Daos.kt`, `RoomSnapshotStore.kt`, `AppContainer.kt`, `ToolkitViewModel.kt`, `BgDexoptController.kt`, `TelemetryUserService.kt`, `ShizukuManagerImpl.kt`, `SnapshotDiffer.kt`, `SnapshotCapturer.kt`, `HealthMappers.kt`, `ChargingCaptureRunner.kt`. Three of the medium findings sit in or next to these files.
- **Debt markers:** no TODO/FIXME/HACK. `@Suppress` only for deprecations or intentional constructor markers. The only `!!` (`ChargingCaptureRunner.kt:90-92`) is guarded by an earlier `currentMa == null` early return (verified).

## 2. Confirmed Findings (Ordered by Severity: CRITICAL -> HIGH -> MEDIUM -> LOW)

No CRITICAL findings. B16 (HIGH) was found during the device pass, after the static review, so it is numbered last but listed first.

---

### [B16] [HIGH] Battery history ingestion fails completely on API 34: the stub declares `HistoryItem.batteryVoltage` as `short`, but the framework field is `char`

- **Category:** Logic Bug (platform compatibility)
- **Location:** `core/hidden-api-stub/src/main/java/android/os/BatteryStats.java:15` (`public short batteryVoltage;`), read at `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/BatteryStatsClient.kt:192` (`voltageMv = batteryVoltage.toInt()`). The failure is mapped at `BinderResult.kt:24,42` (`LinkageError` → `no_such_method`). The class comment at `BatteryStatsClient.kt:149-153` asserts API 34+ compatibility from android-35/36/37 sources only.
- **Confidence:** Confirmed on device (redroid API 34, `localhost:5555`, app at `bf0479e`, Shizuku running as UID 2000).
  1. `PROBE` returns `"history":{"available":false,"reason":"no_such_method","count":0}`, while `dumpsys batterystats --history` shows 151 lines of records (RESET, level, plug, screen).
  2. `history_event` stays empty across PLUGGED/level/UNPLUGGED simulations, and `charge_session` stays empty.
  3. `dexdump` of the device's `/system/framework/framework.jar`: `BatteryStats$HistoryItem.batteryVoltage` has type `C` (char). Every other field the adapter reads matches the stub (`time J`, `currentTime J`, `cmd B`, `states I`, `states2 I`, `batteryLevel B`, `batteryPlugType B`, `batteryTemperature S`, `batteryChargeUah I`). `BatteryStatsHistoryIterator` implements `Iterator` and `AutoCloseable`, and `iterateBatteryStatsHistory()` exists, so the iterator shape is not the problem.
  4. A 15-line dex compiled against the project stub and run with `app_process` as UID 2000 prints `java.lang.NoSuchFieldError: No instance field batteryVoltage of type S in class Landroid/os/BatteryStats$HistoryItem;`. The same field read through `Field.getInt` succeeds (`type=char`).
- **Root Cause & Execution Trace:** ART links an instance-field access by name **and** type. `FrameworkBatteryHistory.records().hasNext()` → `toRecord()` reads `batteryVoltage` as `S`, so the first history item throws `NoSuchFieldError`. That is a `LinkageError`, which `withBinder` turns into `BatteryHistorySnapshot.unavailable("no_such_method")`. The whole page is discarded, not just the voltage. `SnapshotCapturer.ingestHistory` skips unavailable pages (`SnapshotCapturer.kt:63`), so nothing is stored and nothing is surfaced. PROGRESS.md's history evidence covers API 35/36/37 only, where the stub's `short` evidently matches.
- **Failure Scenario:** On every Android 14 device, with minSdk 31, the app records no `history_event` rows. Everything downstream degrades silently:
  - no charge sessions and no measured capacity (B01 can't even occur);
  - no cold-plug detection in the charge job;
  - no history-derived screen boundaries for intervals (`IntervalAnalyzer`, which API 35+ relies on per E5);
  - no history-based unplug reference (`ReferenceResolver`);
  - no Health charge curve or last-charge span.

  The UI shows "Nothing to analyse yet" / "Charges … show up here from battery history" forever. API 31–33 were not checked on a device. Their `HistoryItem` field types and iterator shape should be verified before claiming support.
- **Concrete Fix:** read the voltage without linking to a fixed type, resolving the `Field` once:
  ```kotlin
  // BatteryStatsClient.kt — FrameworkBatteryHistory
  private val voltageField: java.lang.reflect.Field? =
      runCatching { BatteryStats.HistoryItem::class.java.getField("batteryVoltage") }.getOrNull()

  voltageMv = voltageField?.getInt(this) ?: 0,   // Field.getInt widens both char (API ≤34) and short (35+)
  ```
  Drop `batteryVoltage` from the stub, so no compiled code can link to it by type again. Add a unit test that the record mapping doesn't touch the field directly, and record API 34 device evidence (`PROBE` history `available:true`, a non-empty `history_event`) in PROGRESS.md. Audit the remaining stub fields against API 31–33 framework jars, for example by running `dexdump` on emulator images, and either fix them or raise the effective floor for history.

---

### [B01] [MEDIUM] Charges that end in a batterystats reset never become charge sessions, so measured capacity is rarely produced

- **Category:** Logic Bug
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/capacity/CapacityEstimator.kt:44-64`. Related: `app/src/main/kotlin/com/akane/inspiration/capture/ChargingCaptureRunner.kt:50-56`, `core/database/src/main/kotlin/com/akane/inspiration/database/store/RoomSnapshotStore.kt:125-128` (`historySessions` grouped by `statsStartMs`), `core/database/.../Entities.kt:249` (dedup index: a row keeps the session it was first stored under), `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/BatteryHistory.kt:115-121` (RESET emission).
- **Confidence:** Confirmed. Reproduced (evidence `AuditReproC3Test` in both shizuku-ipc and telemetry-engine) plus device evidence that a 100 % unplug resets batterystats (`PROGRESS.md:29`).
- **Root Cause & Execution Trace:**
  1. `ChargingCaptureJobService` → `ChargingCaptureRunner.run` → `ChargeHealthPipeline`, the only producer of `charge_session`. It extracts segments **per stats session**:
     ```kotlin
     val segments = store.historySessions().flatMap { (epoch, events) ->
         ChargeSegmentExtractor.fromHistory(events).map { epoch to it }
     }
     ```
  2. Android resets batterystats on unplug after a full or deep charge. The history walker then emits either `[PLUGGED, LEVEL…, RESET(plugType=0), LEVEL]` (the reset record already carries the unplug, so there is **no** UNPLUGGED at all) or `[PLUGGED, LEVEL…, RESET(plugType=1), UNPLUGGED]` at the same timestamp. Both shapes were reproduced against the real walker.
  3. The extractor discards the open segment on any RESET:
     ```kotlin
     HistoryKind.RESET, HistoryKind.START, HistoryKind.SHUTDOWN -> active.clear()
     ```
  4. Separately, PLUGGED and the charging LEVEL rows stored during the charge (by the charge job or an app open) stay under the old `statsStartMs`, while RESET/UNPLUGGED land under the new one. Neither per-session group contains both ends.
  5. Reproduced: control charge → 1 segment. RESET+UNPLUGGED at the same timestamp → 0. Old and new sessions extracted separately → 0 each.
- **Failure Scenario:** A segment needs a rise of ≥ 40 points (`MINIMUM_SOC_RISE_PCT`), so typical qualifying charges (overnight to 100 %, or to 90 %+) end exactly at the reset point. Only charges that stop below the reset threshold are counted. `charge_session` stays sparse and `measuredMah` stays null, so the Health screen rarely reaches a measured capacity on a normal charging routine.
- **Concrete Fix:** treat a reset-at-unplug as the end of the charge, and extract over one ordered stream per boot rather than per stats session.
  ```kotlin
  // CapacityEstimator.kt — ChargeSegmentExtractor.fromHistory
  var resetWhilePluggedAt: Long? = null
  for (event in events.sortedBy(HistoryEvent::wallMs)) {           // callers pass one boot's events
      when (event.kind) {
          HistoryKind.START, HistoryKind.SHUTDOWN -> { active.clear(); resetWhilePluggedAt = null }
          HistoryKind.RESET -> when {
              active.isEmpty() -> Unit
              // The reset record carries the unplug: the charge ended here.
              event.plugType == 0 -> {
                  active += event
                  active.toChargeSegment()?.let(segments::add)
                  active = mutableListOf()
              }
              // Still plugged: only an UNPLUGGED at this same instant may close the segment.
              else -> resetWhilePluggedAt = event.wallMs
          }
          HistoryKind.PLUGGED -> { active = mutableListOf(event); resetWhilePluggedAt = null }
          HistoryKind.UNPLUGGED -> {
              if (active.isNotEmpty() && (resetWhilePluggedAt == null || resetWhilePluggedAt == event.wallMs)) {
                  active += event
                  active.toChargeSegment()?.let(segments::add)
              }
              active = mutableListOf(); resetWhilePluggedAt = null
          }
          else -> if (active.isNotEmpty()) {
              if (resetWhilePluggedAt != null) { active.clear(); resetWhilePluggedAt = null } // a mid-charge reset still discards
              else active += event
          }
      }
  }
  ```
  In `ChargingCaptureRunner`, flatten `historySessions()` into one list per `bootCount`, ordered by `wallMs`, run the extractor once per boot, and tag each segment with the `statsStartMs` of its start event. The `knownKeys` wall-time dedup already tolerates epoch changes. Narrow the existing test `charge sessions never span a lifecycle boundary` to START/SHUTDOWN and to mid-charge RESET. Add both walker shapes from step 2 as regressions.

---

### [B02] [MEDIUM] `packageChanged` fires on null→name, so apps that became active are silently dropped from every diagnostic

- **Category:** Logic Bug
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/SnapshotDiffer.kt:208-209`. Source of the field: `core/telemetry-engine/.../capture/SnapshotCapturer.kt:90` ← `core/shizuku-ipc/.../client/BatteryStatsClient.kt:245` (`packageWithHighestDrain`). Consumers that drop the UID: `core/diagnostics/.../rules/DiagnosticRuleEngine.kt:29,45`, `feature/xray/.../XRayMapper.kt:54`, `feature/sleeplab/.../SleepLabMappers.kt:134,174`.
- **Confidence:** Confirmed. Reproduced (evidence `AuditReproC4Test`: ref `packageName=null`, now `"com.example.app"` → `packageChanged=true` and the full lifetime counter `totalMah=50.0`, with the baseline discarded). Device evidence (redroid API 34, 9 real snapshots): 21 of 23 app UIDs went null→name between consecutive snapshots at least once (21 of 115 consecutive pairs; 0 name→null). So the trigger is the common case, not an edge case.
- **Root Cause & Execution Trace:**
  ```kotlin
  val packageChanged = uid >= FIRST_APPLICATION_UID && previous != null && previous.packageName != current.packageName
  val baseline = if (packageChanged) null else previous
  ```
  `packageName` is the framework's "package with highest drain" for the UID. It is null whenever nothing was attributed at capture time, a case the code itself acknowledges (`XRayViewModel.kt:172`: "batterystats often reports an app UID without a package"). `IntervalAnalyzer` → `SnapshotDiffer.diff` flags null→name (and name→null) as a change. Since the prior A05 fix, `DiagnosticRuleEngine` filters `it.valid && !it.packageChanged`, and X-Ray and Sleep Lab already excluded such rows. The UID shows up only as a number in `skippedInvalidUids`.
- **Failure Scenario:** An app idle when the user unplugs (null package at the reference) that then holds wakelocks or GPS overnight, the Night-Vampire / Sensor-Leak profile, is removed from R1, R2 culprits, R4, the X-Ray app list and Sleep Lab. The product's core diagnostic misses the very case it exists for, and nothing tells the user.
- **Concrete Fix:** only treat it as a change when both names are known and their packages differ:
  ```kotlin
  val prevPkg = previous?.packageName?.substringBefore(':')
  val curPkg = current.packageName?.substringBefore(':')
  val packageChanged = uid >= FIRST_APPLICATION_UID && prevPkg != null && curPkg != null && prevPkg != curPkg
  ```
  Real UID reuse after an uninstall still shows up as negative deltas, which `valid` already gates. For shared-UID apps, compare against the UID's PackageManager package set instead of one name. Regression: null→name, name→null and `pkg`→`pkg:proc` must not flag. Two different non-null packages must still flag, so A05 stays protected.

---

### [B03] [MEDIUM] Uncertain toolkit writes leave the override chain, so a later manual change makes Reset-all re-apply the restriction

- **Category:** Logic Bug (partial-failure atomicity, privileged write path)
- **Location:** `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/ToolkitExecutor.kt:51-53` (app-op), `:79-81` (bucket), `:118-125` (doze rollback). `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt:597-604` (`settle`), `:277-291` (`reconcile`, pending rows only), `:777-782` (`overrideRows`, DONE/RESTORED only). Reconcile trigger: `app/.../AppContainer.kt:275-282` → `app/.../di/ReconcileOnReady.kt:15-19`. `CommandRunner.kt:19-27,54-55` (`timedOut` ⇒ `!ok`, the process is killed after 10 s).
- **Confidence:** Confirmed. Reproduced with the toolkit fake backend (evidence `AuditReproC1Test`). Trigger frequency is low.
- **Root Cause & Execution Trace:**
  ```kotlin
  if (!write.ok) return ToolkitResult.failed(execCode(write), before = before)          // timeout after cmd may have committed
  val after = readBucket(pkg.orEmpty()) ?: return ToolkitResult.failed(ToolkitCode.PARSE, before = before) // write committed, read-back failed
  ```
  `ToolkitActions.settle` closes both cases as final **FAILED**. For doze, a failed rollback leaves the profile applied but is reported `UNAVAILABLE/NOT_EFFECTIVE`, which is also stored as FAILED. `overrideRows` keeps only DONE/RESTORED rows, so the committed change is invisible to `activeOverrides`, `resetAll` and X-Ray's restricted set (`AppContainer.kt:222`). Separately, a reply lost after the write leaves the row PENDING. It is re-checked only on the next transition into Shizuku Ready, which can be days away while the status FGS keeps the process alive, and B09 can swallow that transition. When it is finally checked, `verify` compares against the *current* state, so if the user changed the target meanwhile the row becomes FAILED (Mismatch).
- **Failure Scenario (reproduced):** SetBucket(P, RESTRICTED). The service commits 45 but the read-back fails, so row A is FAILED with before=10. No override is listed, yet the app is restricted. The user sets ACTIVE manually through the toolkit: row B, before=45, after=10, DONE. `computeOverrides` picks B, `chainOriginal(B)` cannot walk through A, so B's original is 45. B is listed as an override ("was restricted"), and **Reset all writes RESTRICTED back**. That re-applies a privileged restriction the user removed, against the documented invariant in `ToolkitActions.kt:250-253`.
- **Concrete Fix:**
  1. Service: report "write issued, outcome unknown" distinctly:
     ```kotlin
     // ToolkitExecutor.setAppOp / setStandbyBucket (add ToolkitCode.UNVERIFIED next to TIMEOUT/PARSE)
     if (!write.ok) return if (write.timedOut) ToolkitResult.unavailable(ToolkitCode.UNVERIFIED, before = before)
                           else ToolkitResult.failed(execCode(write), before = before)
     val after = readBucket(pkg.orEmpty()) ?: return ToolkitResult.unavailable(ToolkitCode.UNVERIFIED, before = before)
     // applyDoze: after the rollback write
     if (rolledBack?.value != current.value) return ToolkitResult.unavailable(ToolkitCode.UNVERIFIED, before = current.value, after = rolledBack?.value)
     ```
  2. Client `settle`: for `UNAVAILABLE` + `UNVERIFIED`, leave the row PENDING (as with `GatewayReply.Unavailable`) and return an `Unavailable` outcome whose wording the UI owns (add a `ToolkitUnavailable` value with en/tr strings).
  3. Client `run()` and `computeOverrides()`: under the existing mutex, first `reconcileRow` every PENDING row with the same `targetKey`, so an uncertain row joins the chain before a new change is written on top of it.
  4. Regressions: read-back failure → execute ACTIVE → `resetAll()` is empty and the bucket stays 10. Timeout after commit is treated the same way. Doze rollback failure leaves a PENDING row that `reconcile` settles.

---

### [B04] [LOW] Broadcast full captures run under two identical 2 s deadlines: a near-deadline snapshot is cancelled mid-insert, misses are never recorded, and there is no light fallback

- **Category:** Logic Bug
- **Location:** `app/src/main/kotlin/com/akane/inspiration/capture/RuntimeCaptureReceiver.kt:22-33`, `app/.../capture/BroadcastCaptureRunner.kt:12-19`, `core/telemetry-engine/.../capture/SnapshotCapturer.kt:36-37,57-60`. Consumers of the trigger rows: `telemetry/status/UnplugSummary.kt` (the POWER_DISCONNECTED anchor), `ChargingCaptureJobService.kt:40` (`latestByTrigger(POWER_CONNECTED)`).
- **Confidence:** Confirmed. Reproduced (evidence `AuditReproC5Test`); the mechanism was corrected during validation.
- **Root Cause & Execution Trace:**
  ```kotlin
  // BroadcastCaptureRunner.start
  run(capture = { withTimeoutOrNull(deadlineMs) { capture() } })               // outer: plan.deadlineMs (2000)
  // SnapshotCapturer.captureFull
  val read = withTimeoutOrNull(deadlineMs) { source.full(trigger, cursor) }    // inner: same 2000
  if (read == null) return missed(trigger, "deadline_or_service_unavailable")  // suspends in an already-cancelled scope
  ```
  For POWER_CONNECTED/DISCONNECTED and BATTERY_LOW/OKAY with a warm service, both timers start microseconds apart. If the read exceeds 2 s, kotlinx lets the inner `withTimeoutOrNull` return null, but the enclosing scope is already cancelled, so the suspending `recordMiss` throws and no `capture_miss` row is written. If the read finishes at about 1990 ms, the outer timer cancels `store.insert` (reproduced with a 50 ms insert). No path falls back to `captureLight`.
- **Failure Scenario:** On a slow device a plug/unplug event stores nothing. The "since unplug" anchor falls back to an older snapshot, and the charge job's POWER_CONNECTED check sees a stale row. `capture_miss` has no production reader, so the missing miss row only hurts diagnosability.
- **Concrete Fix:**
  ```kotlin
  // RuntimeCaptureReceiver: give the outer guard room to persist (still well inside the 10 s receiver budget)
  deadlineMs = (plan as? CapturePlan.Full)?.let { it.deadlineMs + PERSIST_BUDGET_MS } ?: CapturePolicy.BROADCAST_FULL_DEADLINE_MS,
  // capture = { … is CapturePlan.Full -> capturer.captureFull(…).let { if (it is CaptureResult.Missed) capturer.captureLight(plan.trigger) } }
  // SnapshotCapturer.missed
  withContext(NonCancellable) { store.recordMiss(clocks.wallMs(), trigger, reason) }
  ```
  with `PERSIST_BUDGET_MS = 1_500L`. Regression: fake source taking 2.1 s yields a miss row plus a LIGHT snapshot for the trigger; a source taking 1.99 s yields a FULL snapshot.

---

### [B05] [LOW] Each 5 s dexopt status poll triggers a full privileged override recompute, which also delays Cancel

- **Category:** Performance
- **Location:** `core/telemetry-engine/.../toolkit/ToolkitActions.kt:937-956` (bump at `:955`), `:214`, `:346-353`. Collectors: `feature/toolkit/.../ToolkitViewModel.kt:139-140`, `feature/xray/.../XRayViewModel.kt:218`. Service cost: `core/shizuku-ipc/.../toolkit/Inspection.kt:72-92`, `ToolkitExecutor.kt:58-92,167`.
- **Confidence:** Confirmed. Reproduced (evidence `AuditReproC2Test`: 60 s of RUNNING polls, nothing changed → 12 override recomputes, 24 privileged reads).
- **Root Cause & Execution Trace:** The app's own dexopt row stays PENDING while the job runs (`keepPendingOnDone`), so `refreshDexoptLocked` always takes the `pending.isNotEmpty()` branch:
  ```kotlin
  is BgDexoptStatus.Running, BgDexoptStatus.Unknown -> Unit
  }
  changes.value += 1          // bumps even though nothing changed
  ```
  `activeOverrides()` is `changes.map { mutex.withLock { computeOverrides() } }`. For each overridden package that re-runs the gate (8 role/settings/`dumpsys device_policy` spawns) plus the app-op/bucket reads, about 20 process spawns, all under the service `writeLock`.
- **Failure Scenario:** With the Toolkit screen open during a dexopt job (up to 60 min), the app spawns about 20×N privileged processes every 5 s on a CPU that dexopt is already saturating. With several overrides the recompute holds the client mutex and the service `writeLock` almost constantly, so the user's Cancel waits behind it.
- **Concrete Fix:** make `finalizeDexoptRows`/`supersedeOtherDexoptRows` return the number of rows updated, and only bump `changes` when it is greater than 0:
  ```kotlin
  val updated = when (status) { is Finished -> finalizeDexoptRows(…); is Cancelled -> finalizeDexoptRows(…); Idle -> finalizeDexoptRows(…); else -> 0 }
  if (updated > 0) changes.value += 1
  ```
  Regression: counting fake backend, 30 s of RUNNING polls → `getAppOps`/`getStandbyBucket` call counts do not grow.

---

### [B06] [LOW] `AnalysisSession` runs a full analysis at every process start with no subscriber

- **Category:** Performance
- **Location:** `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/AnalysisSession.kt:49-62`. Eager construction at `app/src/main/kotlin/com/akane/inspiration/AppContainer.kt:141-147` (built in `InspirationApplication.onCreate`).
- **Confidence:** Confirmed by trace.
- **Root Cause & Execution Trace:** `init { combine(selection.selected, refreshTicks.onStart { emit(INITIAL_TICK) }) … .mapLatest { compute(it) } … .launchIn(scope) }` runs in `applicationScope` as soon as the container exists. One compute means: reference resolution, `historyBetween(0, now)` (the 30-day history), snapshot diff, metrics, rules (including the reflective sensor-catalog build) and the story. Processes started without UI also pay this: `BootCaptureReceiver` (BOOT_COMPLETED, MY_PACKAGE_REPLACED), `ChargingCaptureJobService` (every 45 min while charging), `StatusNotificationService` restarts, and the Shizuku provider.
- **Failure Scenario:** Background CPU and IO with zero consumers, repeated on every background process start. This contradicts H13 ("visibility drives work").
- **Concrete Fix:** run the pipeline only while someone collects:
  ```kotlin
  init {
      var shown: ReferenceKind? = null
      _state.subscriptionCount.map { it > 0 }.distinctUntilChanged()
          .flatMapLatest { active -> if (!active) emptyFlow() else pipeline { shown } }
          .launchIn(scope)
  }
  ```
  Here `pipeline` is the existing `combine…mapLatest` chain, unchanged. It keeps the last published state when subscribers leave, so a returning screen still sees data immediately and refreshes on the next tick.

---

### [B07] [LOW] Redundant full reads: a second batterystats dump per charge job, and the 30-day history loaded repeatedly

- **Category:** Performance
- **Location:** `app/src/main/kotlin/com/akane/inspiration/capture/ChargingCaptureJobService.kt:30-38,52-58`. `core/telemetry-engine/.../capture/SnapshotCapturer.kt:44-49` (`ingestHistory` = full `source.full`). `core/telemetry-engine/.../diff/ReferenceResolver.kt:40` and `core/diagnostics/.../analysis/IntervalAnalyzer.kt:41` (each calls `historyBetween(0, …)` in one analysis). `core/database/.../store/RoomSnapshotStore.kt:125-128` (`historySessions`), `:270-277` (per-UID `filter` of every child table, O(U×rows)).
- **Confidence:** Confirmed by trace.
- **Root Cause & Execution Trace:** Every charge-job run calls `capturer.ingestHistory()`, which performs a complete `source.full` (usage stats, the multi-MB batterystats proto, power, alarm, kernel, CPU, history) only to keep the history. When the policy then says to capture, `captureFull(ingestHistory = true)` repeats the whole read. The job then loads the entire history to find one PLUGGED row. One `IntervalAnalyzer.analyze` loads the 30-day history twice.
- **Failure Scenario:** Two batterystats dumps every 45 min while charging, plus repeated whole-table loads on every analysis. This is wasted CPU, IO and memory in exactly the background paths the product promises to keep cheap.
- **Concrete Fix:** add a history-only source call (or reuse the first `FullRead` for the capture), add `HistoryDao.lastOfKind(kind)` (`ORDER BY wallMs DESC LIMIT 1`) for the PLUGGED lookup, pass the events `ReferenceResolver` already loaded into `IntervalAnalyzer`, and in `readSnapshot` replace the per-UID filters with one `groupBy { it.uid }` per child table.

---

### [B08] [LOW] X-Ray reloads every app icon on every analysis emission; above 96 rows the LRU gets zero hits

- **Category:** Performance
- **Location:** `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayViewModel.kt:155,172-176`. `app/src/main/kotlin/com/akane/inspiration/di/CachedAppLabels.kt:52,101` (`DEFAULT_MAX_ICONS = 96`, access-ordered LRU).
- **Confidence:** Confirmed. Reproduced (evidence `AuditReproC15Test`: 120 packages → second pass 120/120 reloads, 0 hits).
- **Root Cause & Execution Trace:** `showReady` → `withIdentities(XRayMapper.apps(diff, …))` calls `deps.appLabels.icon(pkg)` for **every** row (X-Ray lists every UID with mAh > 0, uncapped) on every Visible, refresh tick and selection change. A sequential scan longer than the cache size evicts each entry before it is requested again.
- **Failure Scenario:** With more than 96 drawing apps (plausible "since unplugged"), every emission repeats N × (`getApplicationIcon` + `toBitmap(128)`). That is roughly 1 s and about 10 MB of bitmaps per emission at N=150, and it also flushes the Toolkit picker's cached icons.
- **Concrete Fix:** stop loading icons in `withIdentities` (keep package and label resolution). Load icons per visible row in the composable, the way Toolkit's `AppIconLoader` does (`produceState(null, pkg) { value = appLabels.icon(pkg)?.asImageBitmap() }`). Alternatively, cap eager loading to the first `CachedAppLabels.DEFAULT_MAX_ICONS / 2` rows.

---

### [B09] [LOW] `reconcileOnEachReady` loses a Ready→down→Ready flip that happens while `reconcile()` is running

- **Category:** Concurrency
- **Location:** `app/src/main/kotlin/com/akane/inspiration/di/ReconcileOnReady.kt:15-19`, `app/.../AppContainer.kt:275-282`.
- **Confidence:** Confirmed. Reproduced (evidence `AuditReproC11Test`: Ready → NotRunning → Ready while `reconcile` is suspended → runs = 1).
- **Root Cause & Execution Trace:**
  ```kotlin
  state.map { it is ShizukuState.Ready }.distinctUntilChanged().filter { it }.collect { reconcile() }
  ```
  The collector suspends inside `reconcile()`, which makes several service calls that can each time out. `StateFlow` conflates, and `Ready(uid, api)` is an equal data class, so the collector next sees `true` again and `distinctUntilChanged` drops it.
- **Failure Scenario:** Rows that came back Unreachable during the outage stay PENDING until the next real Ready transition (this feeds B03). `clearFailureBackoff()` is skipped too, so a refresh right after Shizuku recovers can replay the cached failure for up to 12 s.
- **Concrete Fix:** observe the transitions in a separate coroutine:
  ```kotlin
  state.map { it is ShizukuState.Ready }
      .distinctUntilChanged()
      .buffer(Channel.UNLIMITED)   // upstream runs in its own coroutine and is not blocked by reconcile()
      .filter { it }
      .collect { reconcile() }
  ```
  The regression is the reproduction above with a gate held inside `reconcile`.

---

### [B10] [LOW] `applicationScope` has no exception handler: a Room error in `reconcile()`, or `arm()` throwing inside a catch block, kills the process

- **Category:** Error Handling
- **Location:** `app/src/main/kotlin/com/akane/inspiration/AppContainer.kt:84` (`CoroutineScope(SupervisorJob() + Dispatchers.IO)`), `:275-282`. `app/.../capture/ChargingCaptureJobService.kt:73-82` (`ChargingCaptureScheduler.arm` called from both catch blocks).
- **Confidence:** Plausible. The mechanism is certain; the triggers (`SQLiteFullException`, a corrupt DB, a `JobScheduler.schedule` limit or `IllegalStateException`) were not reproduced.
- **Root Cause:** `SupervisorJob` isolates siblings but still sends uncaught exceptions to the thread's default handler, which crashes the app. `reconcile()` failures in `start()` and an exception thrown by `arm()` inside `catch` both escape.
- **Failure Scenario:** On a full or corrupt disk the app crashes on every Shizuku Ready, and the persisted charge job may never re-arm.
- **Concrete Fix:**
  ```kotlin
  val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
      CoroutineExceptionHandler { _, e -> Log.e(TAG, "uncaught in applicationScope", e) })
  // ChargingCaptureJobService: wrap the catch-path re-arm
  runCatching { ChargingCaptureScheduler.arm(this@ChargingCaptureJobService, reArmFromJob = true) }
      .onFailure { Log.w(TAG, "re-arm failed", it) }
  ```

---

### [B11] [LOW] The persisted charge job can miss its re-arm, and a boot arm can be replaced by a 45-minute delay

- **Category:** Logic Bug
- **Location:** `app/src/main/kotlin/com/akane/inspiration/capture/ChargingCaptureJobService.kt:68-76`, `app/.../capture/BootCaptureReceiver.kt:25`.
- **Confidence:** Plausible. The windows are narrow.
- **Root Cause:** `if (isActive) { jobFinished(...); arm(...) }`: if `onStopJob` cancels after the coroutine's last suspension point has resumed, no `CancellationException` is thrown and `isActive` is false, so neither branch re-arms (and `onStopJob` returned false). Separately, BOOT_COMPLETED or MY_PACKAGE_REPLACED arriving while the job runs makes the same-ID `schedule` stop the job. Its cancel path then calls `arm(reArmFromJob = true)`, replacing the immediate boot arm with the 45-minute-latency one.
- **Failure Scenario:** The charge job disappears until the next app open (`ensureScheduled`) or boot, or a post-boot capture is delayed by 45 minutes.
- **Concrete Fix:** track `var reArmed = false`, set it wherever `arm` runs, and in `finally` call `if (!reArmed) runCatching { arm(…, reArmFromJob = true) }`. In the cancel path, skip the re-arm when `getPendingJob(JOB_ID)` already exists with zero minimum latency.

---

### [B12] [LOW] `full()` makes blocking binder calls and the O(U²) merge runs on Main for UI-initiated refreshes

- **Category:** Concurrency / Main-thread blocking
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/source/AndroidTelemetrySource.kt:33-34` (calls `light()`, lines 58-86, on the caller's dispatcher) vs `kernelReading` (line 92, already wrapped in `withContext(ioDispatcher)`). `core/telemetry-engine/.../capture/SnapshotCapturer.kt:69-135` (`mergeFull`, `firstOrNull` per UID). Callers: `feature/dashboard/.../CockpitViewModel.kt:132-136,194` and `feature/xray/.../XRayViewModel.kt:125` (`viewModelScope`, Main) → `RefreshCoordinator.refresh` (`NonCancellable` keeps the dispatcher).
- **Confidence:** Confirmed by trace. Impact is jank-level (a few ms), not ANR. The service reads themselves already run on IO (`ShizukuManagerImpl.kt:191`).
- **Concrete Fix:** `override suspend fun full(...): FullRead? = withContext(ioDispatcher) { … }`. In `mergeFull`, build `val usageByUid = usage.uids.associateBy { it.uid }` once and look rows up from it.

---

### [B13] [LOW] The Health line chart throws `IllegalArgumentException` when an axis label is wider than the space left for it

- **Category:** Logic Bug (crash)
- **Location:** `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthCharts.kt:109,114`, reached from `HealthCards.kt:395-428` (`WearBody`).
- **Confidence:** Confirmed on device, with a narrow trigger. On redroid API 34, with 6 seeded wear samples, the app crashed in the draw pass at font scale 3.0 and a 400 px / 560 dpi window (about 114 dp): `IllegalArgumentException: Cannot coerce value to an empty range: maximum -215.0 is less than minimum 303.0` at `HealthCharts.kt:114`. It did **not** crash at font 2.0 with 1080 px / 560 dpi (about 308 dp, the largest display size), or at 600 px / 420 dpi (about 228 dp). So realistic phone settings don't reach it. Only very narrow windows (freeform or split, cover screens) or a font scale above the platform maximum do. LOW stands.
- **Root Cause:**
  ```kotlin
  val left = (toX(label.value) - layout.size.width / 2f).coerceIn(plotLeft, size.width - layout.size.width.toFloat())
  ```
  `Float.coerceIn(min, max)` throws when `max < min`. Other draw code was checked and is safe: `TimeAxis.kt:66` uses `maxOf`, scrub and hypnogram plot sizes are clamped.
- **Failure Scenario:** On a small screen or freeform window with the largest font, opening Health during the first two days of wear history crashes the app in the draw pass.
- **Concrete Fix:**
  ```kotlin
  val top = (y - layout.size.height / 2f).coerceIn(0f, maxOf(0f, size.height - layout.size.height))
  val left = (toX(label.value) - layout.size.width / 2f).coerceIn(plotLeft, maxOf(plotLeft, size.width - layout.size.width))
  ```
  Add a `WearCard` screenshot preview at fontScale 2, widthDp 280, with a wear span under 2 days.

---

### [B14] [LOW] A stale `Ready` for the old selection can overwrite `Loading` for the new one

- **Category:** Concurrency
- **Location:** `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/AnalysisSession.kt:53-61`. Consumers: `feature/xray/.../XRayViewModel.kt:140-150` (its guard covers TimeSlice only), `feature/sleeplab/.../SleepLabViewModel.kt:57`.
- **Confidence:** Plausible. The trace is sound; there is no deterministic reproduction and the window is microseconds.
- **Root Cause:** `Loading` is written in the upstream `onEach` (the `mapLatest` producer coroutine), while `Ready` is written in the downstream `onEach`, which drains `mapLatest`'s buffer in another coroutine on multi-threaded IO. If A's result is already buffered when the user selects B, `Ready(A)` can land after B's `Loading`. `IntervalAnalysis` carries no `ReferenceKind`, so consumers cannot detect the mismatch.
- **Failure Scenario:** For the duration of B's compute, screens show interval A's numbers under B's label.
- **Concrete Fix:**
  ```kotlin
  combine(selection.selected, refreshTicks.onStart { emit(INITIAL_TICK) }) { kind, _ -> kind }
      .transformLatest { kind ->
          if (kind != shown) { shown = kind; emit(AnalysisState.Loading) }
          emit(compute(kind))
      }
      .onEach { _state.value = it }
      .launchIn(scope)
  ```
  Both writes now go through one ordered stream; alternatively, tag `Ready` with its `ReferenceKind`.

---

### [B15] [LOW] Pull-to-refresh is ignored, and its failure hidden, while the automatic on-enter refresh is running

- **Category:** Logic Bug (UX)
- **Location:** `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitViewModel.kt:130-145`, `feature/health/.../HealthViewModel.kt:130-145`, `feature/sleeplab/.../SleepLabViewModel.kt:124-137`.
- **Confidence:** Confirmed by trace.
- **Root Cause:**
  ```kotlin
  private fun startRefresh(userInitiated: Boolean) {
      if (refreshJob?.isActive == true) return          // before userInitiated is considered
  ```
  `Visible` starts `startRefresh(false)` (a full capture with up to a 10 s deadline). A user pull during that time returns early: `refreshing` never turns true, and if the running job fails, no `RefreshFailed` effect is sent, because that job is not user-initiated. The Empty/Error cards' Refresh buttons take the same path. X-Ray already handles this correctly.
- **Concrete Fix:**
  ```kotlin
  @Volatile private var userWaiting = false
  private fun startRefresh(userInitiated: Boolean) {
      if (userInitiated) { userWaiting = true; _state.update { it.copy(refreshing = true) } }
      if (refreshJob?.isActive == true) return
      refreshJob = viewModelScope.launch {
          try { … is RefreshResult.Failed, null -> if (userWaiting) effectChannel.trySend(CockpitEffect.RefreshFailed) }
          finally { userWaiting = false; _state.update { it.copy(refreshing = false) } }
      }
  }
  ```
  Apply the same change in Health and Sleep Lab. Regression: Visible with `refresh()` suspended → Refresh intent → `refreshing == true`, then one `RefreshFailed` after a `Failed` result.

---

### Rejected after validation (not findings)

- **"Charging interval counts as screen-off-only, so R4 Sensor Leak / R2 SCREEN_OFF_ONLY fire falsely"** (`DiagnosticRuleEngine.kt:49-52` with `IntervalMetrics.kt:105-113`). The mechanism is real: `Walk.advanceTo` books every plugged span as `chargingMs`, so plugged screen-on time never reaches `screenOnMs`. The validator's unit test showed the rule firing, but only by injecting GPS time recorded during charging. In production, everything R2/R4 consume (per-UID sensor `totalMs`, partial wakelocks, wakeup alarms, `screenOffAwakeMs`) comes from batterystats timers on the **on-battery** time base, so plugged time contributes zero. When `screenOnMs == 0 && unknownMs == 0`, every on-battery instant in the interval was screen-off, and the culprits really are screen-off culprits. Residual risk: this correctness depends on a framework time-base contract nothing in the code states. A short comment in `isScreenOffOnly` and a rule test using realistic (on-battery-only) fixtures would document it.
- **Reviewed and discarded as mitigated:** command injection (typed argv, `matchEntire` package regex, `DozeRaw` grammar, no generic runner); unauthenticated binder transactions (every txn including `getApiVersion`/`destroy` checks `Binder.getCallingUid()`, no `onTransact` override); 64 KB pipe deadlock (streams drained, `waitFor` timeout + `destroyForcibly`); TransactionTooLarge (history/usage parcels bounded, dumps via FD); goAsync without `finish()` (`invokeOnCompletion`/`finally` on every path); CancellationException swallowing (all `runCatching` wrap non-suspend binder calls, and ViewModel `attempt` helpers rethrow); hold-binding leaks (synchronous `holdBinding()`, double-Visible guards, `onCleared` release, linger timer identity-checked); exported debug probe (`DUMP`-guarded, debug source set only, absent from the release mapping); backup leakage (all nine domains excluded in both transports and the legacy rules); FGS compliance (`specialUse` type, subtype property and permission declared; `ForegroundServiceStartNotAllowedException` caught); Room schema (v1 identity hash unchanged since v0.1.0, 2→3 data-only migration registered); prune deleting referenced rows (marker FK is RESTRICT; prune is `@Transaction`); R8 breaking the UserService (keep rules verified in the mapping).

## 3. Dead Code, Redundancy & Technical Debt Cleanup

Verified with repository-wide searches (production source sets only; test-only references noted).

| Location | Status |
|---|---|
| `app/.../capture/CapturePolicy.kt:47` `appOpenDebounced` | Only `CapturePolicyTest`. The runtime debounce lives in `RefreshCoordinator`. *(Also listed by the prior audit; still present.)* |
| `core/designsystem/.../chart/math/SeriesMath.kt:44` `nearestIndex` | Only `SeriesMathTest`. *(Prior audit; still present.)* |
| `core/designsystem/.../component/TimeSliceBanner.kt:101` String overload | Only screenshot sheets; production calls use the `TimeRange` overload. *(Prior audit; still present.)* |
| `core/shizuku-ipc/build.gradle.kts:22` `implementation(libs.hiddenapibypass)` | No `HiddenApiBypass` reference in shizuku-ipc sources; the app declares its own. *(Prior audit; still present.)* |
| `core/hidden-api-stub/.../IDeviceIdleController.java:9` `isPowerSaveWhitelistApp`, `BatteryUsageStats.java:38` `getDischargePercentage`, `UidBatteryConsumer.java:20` `getTimeInProcessStateMs` | No callers. Compile-only stubs, so removal only shrinks the declared surface. |
| `core/telemetry-engine/.../dump/DumpReader.kt:20` `readAll(InputStream)` | Only tests; production uses the FD overload at `:50`. |
| `core/shizuku-ipc/.../toolkit/ToolkitParsers.kt:27` `appOpMode` | No production caller (`appOpState` is used). |
| `core/database/.../Daos.kt:120` `deleteById`, `:22` list `insertAvailability` overload, `:171` `hourly` | No production callers. |
| `telemetry_hourly`, `capture_miss` tables | Written and pruned but never read in production. Either surface them (a capture-miss diagnostic would make B04 observable) or stop writing them. |
| `feature/toolkit/.../ToolkitDependencies.kt:28` `holdBinding()` | Never called by the feature (as its KDoc says). Removing it from the pinned interface needs a recorded decision. |

No stale TODO/FIXME traps exist.

Structural debt that needs design rather than deletion:
- **One shared discontinuity contract.** RESET/START/SHUTDOWN handling now lives in `TimelineBuilder`, `HealthMappers.lastChargeSpan`, `UnplugSummary`, `IntervalMetrics.Walk` and `ChargeSegmentExtractor`, each written separately (B01 is the latest divergence). A single tested boundary model in telemetry-engine would prevent the next one.
- **Toolkit outcome model.** Make "issued, unverified" a first-class state across service, gateway and audit (B03) instead of mapping it onto FAILED.
- **On-demand work.** `AnalysisSession`, the charge job and X-Ray icon loading should follow the H13 visibility principle that the live readers already respect (B05–B08).

## 4. Prioritized Remediation Roadmap

**Phase 1: Immediate Hotfixes (Stop the Bleeding).** Isolated commits, each test-first using the kept repro.
0. **B16** read `batteryVoltage` through a cached `Field.getInt` and drop it from the stub. This restores all history-derived features on Android 14, and B01's fix is unobservable there without it. Verify on the API 34 redroid with `PROBE` (history `available:true`).
1. **B03** toolkit uncertain writes: service `UNVERIFIED` code, client keeps PENDING, per-target reconcile before writing. This is the only finding that can make the app perform a privileged write the user did not intend.
2. **B02** `packageChanged` both-non-null rule. One line plus tests; it restores diagnostics coverage immediately. Re-run A05's regression tests in the same commit.
3. **B01** charge-segment extraction across reset-at-unplug and across stats sessions, plus the narrowed lifecycle test.
4. **B13** `maxOf` clamps (a two-line crash guard).

**Phase 2: Structural & Concurrency Hardening.**
1. **B09** buffered transition observation, then **B10** scope exception handler and guarded re-arm, then **B11** `reArmed`/`finally` re-arm.
2. **B04** single deadline with persist budget, `NonCancellable` miss write, light fallback.
3. **B14** `transformLatest` ordering, **B15** user-refresh escalation, **B12** `withContext(io)` in `full()`.

**Phase 3: Architectural Refactoring, Efficiency & Test Coverage.**
1. Efficiency: **B05** (bump only on change), **B06** (subscription-gated session), **B07** (history-only read, `lastOfKind`, pass-through events, `groupBy`), **B08** (lazy per-row icons).
2. Extract the shared history-discontinuity model described in section 3 and migrate all five reducers to it, with a common fixture set covering both reset walker shapes.
3. Test blind spots worth closing: `ChargingCaptureJobService` (finish/re-arm/cancel paths), `BootCaptureReceiver`, `StatusNotificationService`/`Controller.sync` (no tests today; Robolectric is already in the catalog); `ReconcileOnReady` with a suspending reconcile; `AnalysisSession` on a real multi-threaded dispatcher; `ToolkitViewModel` double-confirm, Hidden during a confirmed write, and throwing deps; `RuleFixtures.metricsOf` always uses `chargingMs = 0`; `ChargeHealthPipelineTest` fakes `historySessions`, so Room grouping plus dedup is never exercised end to end.
4. Add a CI workflow (none exists) running the gate from section 1, plus a resolved-transitive dependency advisory scan.

## 5. Coverage Ledger

| Subsystem | Reviewer context | Examined |
|---|---|---|
| IPC and toolkit write path | A (read-only) | All of `core/shizuku-ipc/src/main` (ShizukuManagerImpl/Facade, TelemetryUserService, AIDL and parcelables, `client/*`, `toolkit/*`), `core/hidden-api-stub`, `telemetry/toolkit/*` (ToolkitActions, Gateway, Models), toolkit DAO and store mapping; callers in AppContainer, ReconcileOnReady, Toolkit/X-Ray ViewModels, AndroidTelemetrySource, ProbeReceiver |
| Telemetry and persistence | B (read-only) | All of `core/telemetry-engine` main except toolkit (capture, source, dump/proto, diff, reference, interval, capacity, live, calibration, status, app, store, snapshot) and all of `core/database` (entities, DAOs, store, WAL, schemas 1–3 identity hashes via git at v0.1.0/v0.2.0/HEAD, prune/downsample) |
| App shell, capture, FGS, build | C (read-only) | Both manifests, backup/extraction rules, InspirationApplication, MainActivity, AppContainer, all `di/*`, `capture/*`, `status/*`, `shell/*`; debug ProbeReceiver and DesignGalleryActivity; app/core build files, build-logic, version catalog, R8 and consumer rules |
| Feature modules | D (read-only) | All five ViewModels and Routes, dependency interfaces, mappers, touch guards, Health charts; X-Ray detail, Sleep Lab mappers and Health screen partially; ToolkitViewModelTest list |
| Diagnostics and designsystem | E (read-only) | All of `core/diagnostics` main, the dictionary JSON (72 entries), en/tr strings, rule/story fixtures; designsystem chart math, models, scrub chart, hypnogram, TimeAxis, TelemetryFormat, StackedBar/MetricBar/TimeSliceBanner |
| Validation | Independent validator (separate context, scratch worktree) | Every candidate re-traced; 9 repro test classes executed (section 1) |
| Coordinator | This session | Recon, forensics, baseline, release/R8 mapping check, secret scan, manifest review, dead-code verification, cross-check that rejected the charging/screen-off candidate, quote and line verification, report |

Up to 3 reviewer contexts ran concurrently. Validation ran in a context that wrote none of the candidates.

**Tools actually run:** git forensics (churn, fix clusters, worktree, identity hashes); grep/ripgrep; `bash .claude/kit/gradle-check.sh` for `testDebugUnitTest :app:lintDebug` and `:app:assembleRelease`; R8 mapping inspection; the 9 scratch reproductions listed in section 1; read-only `adb` property and package queries.

**Unavailable or not run (not claimed to pass):**
- **Device evidence (added after the static pass).** The debug app was installed on redroid API 34 (`localhost:5555` = `emulator-5554`) with Shizuku running as UID 2000, and driven through adb and mobile-mcp. Results:
  - Probe: bind 358–379 ms, UserService usage read 19 ms. Kernel/sysfs, CPU and power monitors are unavailable on redroid.
  - Found **B16**: history is unavailable with `no_such_method`, shown by `dexdump` plus an `app_process` field probe.
  - Measured B02's trigger frequency, and reproduced B13's crash, only under extreme sizing.
  - **B01 could not be exercised:** because of B16, no history reaches the extractor on API 34, so B01 still relies on `PROGRESS.md:29`.
  - B04 was not triggered: reads were far below 2 s on this host.
  - Not exercised: Binder death, OEM sysfs, a real batterystats reset, and FGS behaviour.
  - A system_server hang (watchdog) and one container restart happened during the session. Both were recovered with `stop; start` and a Shizuku restart. Fake-battery state, display settings and the 6 seeded `battery_health_sample` rows were reverted afterwards.
- **Screenshot validation** (`validateDebugScreenshotTest`) was not run this pass; no UI change was made, and B13 has no screenshot repro yet.
- **Lint for non-app modules** (only `:app:lintDebug` ran).
- **Static analyzers:** detekt, ktlint, tokei and cloc are not installed. Line counts came from `find`/`wc`.
- **Dependency advisory scan** (no OSV/OWASP tooling run). Versions were reviewed by hand only.
- **Instrumented and Compose pointer-input tests** (none exist).

**Remaining gaps:** Shizuku per-class connection-stub semantics after a settle timeout (reviewer A's open question; needs a device). The 10 s `captureFull` deadline discarding already-read history pages on a large first catch-up (reviewer B's open question; needs device timing). Designsystem `LiveGauge`, `SegmentedToggle`, `ShizukuPulse` and theme files were only grep-checked for division and coercion. Screenshot images were not visually reviewed.

## 6. Audit Delta (vs. archived report at `3c6cf4a`, read only after validation)

- **Prior status:** 12 findings (A01–A12), 11 marked FIXED and A10 BLOCKED. Nothing in this pass indicates a fixed defect regressed:
  - A01 (override ordering): `overrideRows` now sorts by id.
  - A02 (calibration), A03 (status reboot), A06 (timeline START/RESET) and A07 (Health charge span): not re-flagged by the reviewers who covered those files.
  - A04 (dexopt start fencing): reviewer A found cancellation and shutdown sound.
  - A05 (changed-package filter): present at `DiagnosticRuleEngine.kt:29,45`.
  - A08 (backup domains): all nine excluded.
  - A11 (X-Ray wording): not re-flagged.
  - A09, A10 and A12 concern kit tooling, which is outside this pass's application scope (A10 was not re-checked).
- **New interaction with a prior fix:** **B02** is the other side of **A05**. A05 correctly stopped lifetime counters from being reported as interval usage by excluding `packageChanged` UIDs. But `packageChanged` itself is too broad (null→name), so A05's filter now silently removes legitimate active apps from R1/R2/R4. Fixing B02 keeps A05's protection for real identity changes.
- **Residual of prior structural debt:** the prior report's section 3 warned that "several independent history reducers implement RESET/START/SHUTDOWN differently". A06/A07 fixed two of them. **B01** is a third, independent reducer (`ChargeSegmentExtractor`) with the same class of defect, and this pass also found that per-session extraction hides it.
- **Restore-chain theme continues:** after A01 (clock ordering), **B03** is a second, independent way the toolkit restore chain can mis-pick its original, this time through non-binary write outcomes rather than ordering.
- **Prior-audit candidate revisited:** the prior report discarded "sensor increments solely during charging" on the AOSP on-battery time-base contract. This pass rejected the related charging/screen-off-only candidate on the same contract (section 2, "Rejected").
- **Still-open cleanup** from the prior section 3: `appOpenDebounced`, `nearestIndex`, the `TimeSliceBanner` String overload, the shizuku-ipc `hiddenapibypass` dependency and the unused stub methods remain.
- **New, device-only:** **B16**. Neither audit could catch it statically, because the stubs are compile-only and PROGRESS.md's history evidence starts at API 35. It is the first finding gated on an API level inside the supported range (minSdk 31).
- **New in this pass, with no prior counterpart:** B04–B15, mostly in efficiency, lifecycle edges and UI robustness. The prior pass scored Performance 8/10 and found no performance defect; this pass found four confirmed wasteful paths (B05–B08), so the score here is lower.
- **Baseline drift:** the prior final gate had 1,034 unit tests passing, matching this run's 1034. The prior pass ran screenshots (168 comparisons) but not a release/R8 build; this pass ran the R8 release build but not screenshots.
