# Whole-repository audit — Inspiration

Audited on 2026-10-18 at HEAD `3c6cf4a8ceda502c9c1bc3afa5e784eb494b4d3a`, branch `Review-n-fix`, including the existing untracked Android kit. This is a source audit of the complete application and its repository tooling. The original audit is retained below; current status headings and section 5 record remediation: **11 FIXED, A10 BLOCKED** by the instruction to preserve the Claude setup. During the original audit, application code, dependencies, screenshot references, the Claude setup and Sidequest state were not modified; subsequent authorized changes are recorded in section 5.

## 1. Executive Summary & System Health Scorecard

- **Architecture & Modularity: 8/10.** Twelve modules, explicit feature dependency interfaces and a shared analysis session provide useful boundaries; several independent history-state reducers still disagree on discontinuities.
- **Correctness & Resilience: 7/10.** The baseline passes, but deterministic probes expose incorrect restore ordering, power scaling and reboot/identity attribution.
- **Concurrency & Resource Safety: 7/10.** IO dispatching, bounded commands, symmetric ownership and cancellation containment are generally sound; dexopt start admission still races cancellation and shutdown.
- **Security & Privilege Hygiene: 7/10.** Typed privileged commands, caller checks and protected/debug-only entry points are present; backup-domain exclusions and scaffolder destination containment have confirmed gaps.
- **Performance & Efficiency: 8/10.** Live collectors follow visibility and command output/caches are bounded; no additional production-reachable performance defect was confirmed in this pass.

These scores are review judgments, not measured coverage percentages or a security certification.

**Top 3 Systemic Risks**

1. Restoration depends on wall-clock ordering: a time correction can hide the current device override and lose its original value during a restore.
2. Privileged work can start after cancellation or during service teardown, leaving dexopt without its charging/time supervisor.
3. Independent telemetry consumers interpret identity, units and reboot boundaries inconsistently, producing confident but incorrect battery diagnostics and charge history.

**12 findings confirmed: 10 MEDIUM, 2 LOW.** No CRITICAL or HIGH finding was confirmed. Medium findings include meaningful correctness/privacy failures with bounded or conditional triggers; low findings concern incorrect labels and a supported-tooling-version failure.

### Baseline and evidence

Executed from the checkout root:

```bash
bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug \
  testDebugUnitTest --rerun validateDebugScreenshotTest --rerun \
  --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g
```

**Gradle exited 0.** Debug assembly and lint passed; all 87 unit XML reports and seven screenshot result files were newer than the run start. There were **1,023 unit tests** and **167 screenshot comparisons**, zero failures/errors/skips. Screenshot references were unchanged. Retained quiet Gradle output (empty on this successful `-q` run): `.codex/android-kit/logs/gradle.lonwzH7L.log`; the wrapper records its PASS and log path in `.codex/android-kit/runtime/verify-6-1792329967792509489.log`.

| Module | Fresh unit tests | Fresh screenshot comparisons |
| --- | ---: | ---: |
| app | 93 | 8 |
| core:shizuku-ipc | 193 | — |
| core:telemetry-engine | 225 | — |
| core:database | 29 | — |
| core:diagnostics | 134 | — |
| core:designsystem | 102 | 55 |
| feature:dashboard | 35 | 13 |
| feature:xray | 46 | 23 |
| feature:sleeplab | 54 | 20 |
| feature:health | 61 | 12 |
| feature:toolkit | 51 | 36 |
| core:hidden-api-stub | No tests; compile-only framework declarations | — |

Lint emitted **17 warnings, zero errors**: app 10, IPC 4, diagnostics 1, toolkit 2. They include version/update notices, intentional private-API usage, API annotation advice and resource/style suggestions. No warning was promoted into a logic/security finding without an execution trace. Current reports are under each module's `build/reports/lint-results-debug.xml`. Lint reported newer versions for several pinned tools/libraries; this audit did not change versions or equate available updates with vulnerabilities.

Additional observed checks:

- Android detector confirmed all twelve modules, Java target 17, Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.20, compileSdk 37 / targetSdk 36 / minSdk 31. Runtime JDK is 21. Project doctor passed with 19 skills.
- Portable-kit suite: `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s android-kit-codex/tests -v`: **18 tests passed**. In-memory compilation / `bash -n` passed for 21 Python files and five Bash scripts in that tooling slice.
- Direct [OSV query-batch](https://google.github.io/osv.dev/api/#tag/vulnerability/operation/OSVQueryBatch) lookup returned no matched advisories for the seven selected catalog coordinates/versions: Shizuku API/provider 13.1.5, HiddenApiBypass 6.1, protobuf-javalite 4.36.2, kotlinx-coroutines-core 1.11.0, Room runtime 2.8.5, JUnit 4.13.2. This was a **limited direct-coordinate lookup**, not a complete resolved-transitive dependency/security scan.
- Secret-pattern scan of application/core/feature/build/tooling sources found no matches for the checked private-key, Google API key, GitHub token and AWS access-key forms. Sensitive local configuration/keystores were not read; this does not establish absence of all possible secrets.
- Standalone JVM probes executed actual compiled production classes with controlled inputs/schedulers. The shell classifier and scaffolder containment checks used isolated fake inputs/commands. Evidence is retained in `.codex/android-kit/logs/audit-20261018/`; these are host reproductions, not privileged device tests.

The first board verification rejected its repository fingerprint despite **Gradle exit 0**, because `.codex/skills/full-repo-audit/SKILL.md` changed externally during the run. No application source changed. That wrapper rejection is distinct from a failed build or test. The final board verification is run after the report/project-note updates so its current fingerprint can be recorded.

Not executed: release/minified build/signing, on-device Binder death/OEM API/SELinux/dump behavior, physical backup/transfer, actual privileged toolkit commands, or Python 3.9 runtime. No such check is claimed to pass. The host suite has no instrumented Android component or actual Compose pointer/lifecycle integration coverage.

### Repository coverage and forensics

The source inventory contains 201 main Kotlin/Java files across the twelve modules, plus debug entry points, 99 unit-test sources, 22 screenshot-test sources, AIDL declarations, protobuf definitions/fixtures, XML resources, Room schemas and build conventions. All production subsystems and their tests/resources were reviewed in focused slices, with caller/callee validation across boundaries:

| Slice | Reviewed behavior |
| --- | --- |
| app / DI / shell | Application/activity startup, dependency construction, refresh/backoff, timeline seeds, preloading, app labels, navigation/saved state and feature ownership |
| background / status | Runtime/boot receivers, broadcast deadlines, charging scheduler/job cancellation, health extraction, notification switch/permission/service/ticks |
| IPC / hidden stubs | Shizuku state/bind/unbind/death ownership, service authentication, AIDL/parcel budgets, framework clients, dump FDs, sysfs, typed command parsing, dexopt lifecycle |
| telemetry / database | Unit/sign calibration, live sampling, snapshots/history cursor, diff session/identity validity, reference resolution, interval metrics, capacity integration, migrations, pruning and restore chains |
| diagnostics / features | All five feature ViewModels/routes/mappers, rule gates, dictionary, stories, history state machines, action planning/readback and charge/health presentation |
| designsystem | All 39 production Kotlin files, chart math/series/gestures, scrub handoff, hypnogram rendering, formatting, theme/motion, ten unit-test and ten screenshot source files |
| build / portable tooling | Root/module Gradle files, convention plugins, catalog, wrapper checksum, manifests, R8 keep rules, installer/conflicts, board/fingerprints/timeouts, scaffold, detector/doctor and all emulator helpers |
| preserved sibling tooling | Nine Claude Python/Bash helpers and their probe producer/consumer were inspected read-only; no hooks, device commands, Claude configuration or Sidequest state were executed/modified |

Generated build trees, binary assets/archive history and external dependency implementations were not treated as handwritten application source. Framework contracts supporting findings were checked against official sources. Historical notes were treated as context rather than fresh test evidence. No prior audit report or archived report was read; `AUDIT_REPORT.md` was absent when this run began, and `.git/AUDIT_REPORT.prev.md` was unavailable for a report-to-report delta.

The full last-500-commit churn and fix-keyword scans were run. Source hotspots included `ToolkitActions.kt` (9 changes; 8 fix-cluster touches), Room store/DAO/tests, `ToolkitViewModel`, `AppContainer`, `SnapshotDiffer`, `ShizukuManagerImpl`, `TelemetryUserService` and dexopt execution. These files have substantial tests, but the specific temporal/identity/discontinuity cases below are missing or assert the incorrect behavior. Debt-marker scans found suppression/compatibility annotations rather than an untriaged TODO backlog.

False-positive checks explicitly discarded: command injection behind typed/validated argv; unrestricted debug probe IPC (it is debug-only and DUMP-protected); per-UID N+1 status snapshot loading (bare snapshot query); missing normal broadcast completion (bounded async runner); API-31 close errors already caught as LinkageError; duplicate regular user-cancel worker guard already fixed; off-window chart spans clipped by their producer; and sensor increments solely during charging (AOSP uses on-battery time bases). See [BatteryStatsImpl sensor timer construction](https://android.googlesource.com/platform/frameworks/base/+/712d5033ac384ea9090ecef525b3f6e575b8f3c0/services/core/java/com/android/server/power/stats/BatteryStatsImpl.java) for the last rejection.

## 2. Confirmed Findings (Ordered by Severity: CRITICAL -> HIGH -> MEDIUM -> LOW)

### [A01] [FIXED in 5cadb76] [MEDIUM] Wall-clock rollback reorders override chains, hiding restrictions and restoring the intermediate value

- **Category:** Logic Bug
- **Confidence:** Confirmed by cross-file call trace and compiled-class reproduction.

- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt:777-782`; related `:751-758`, `:786-802`, `:813-825`. DAO authoritative insertion order: `core/database/src/main/kotlin/com/akane/inspiration/database/Daos.kt:191-192` (newest query).

- **Root Cause & Execution Trace:** Toolkit/XRay UI actions call `ToolkitActions.execute` -> `run` -> `insertPending`, whose `startedAt` uses System.currentTimeMillis by default and whose Room id is monotonic -> service successfully writes bucket -> `settleDone` records DONE. `activeOverrides` -> `computeOverrides` -> `overrideRows` discards DAO newest-id order and sorts by wall-clock start; `putIfAbsent(targetKey)` selects the wrong row. `resetAll` uses the same wrong selection. `restore` -> `decodeRestore` -> `chainOriginal` walks the same reordered list.

- **Failure / Exploit Scenario:** R1 id=1/time=2000 changes bucket ACTIVE(10) to RARE(40); clock is corrected backwards; R2 id=2/time=1000 changes RARE(40) to RESTRICTED(45). Actual state is45. Ordering becomes [R1,R2], candidate R1.after40 fails `isActive(current45)`, so active overrides and Reset All omit the restriction. Restoring R2 starts its backward chain at the last element and returns40 instead of original10, preserving an unwanted restriction.

Offending snippet:

```kotlin
.sortedWith(compareByDescending<ToolkitActionRecord> { it.startedAt }.thenByDescending { it.id })
```

- **Concrete Fix:** use durable write order, leaving wall timestamps only for display.

```diff
-        .sortedWith(compareByDescending<ToolkitActionRecord> { it.startedAt }.thenByDescending { it.id })
+        .sortedByDescending { it.id }
```

Regression: execute two bucket writes with nowMs moving backwards; assert current row is listed and both per-item restore and resetAll return10.

### [A02] [FIXED in 202f496] [MEDIUM] Standard two-cell pack telemetry doubles measured current and power

- **Category:** Logic Bug
- **Confidence:** Confirmed by production-class reproduction, call trace, dimensional calculation and official kernel contract.

- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/calibration/PowerCalibrator.kt:51-62`, related `:73-88`.

- **Root Cause & Execution Trace:** `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/service/TelemetryUserService.kt:171-179` reads `current_now` and `voltage_now` from the same battery directory -> `AndroidTelemetrySource.kernelReading:97-104` combines them with fresh BatteryManager values -> `LiveTelemetry.toReading:49-65` recalibrates every reading -> `PowerCalibrator.calibrate` detects 7–9V, sets cellCount2, multiplies current conversion factor2 even when sysfs and BatteryManager currents agree. `toReading` retains the full pack voltage and computes watts. AppContainer:98 supplies this live flow; CockpitMappers:119-122 extrapolates chargeCounter/current (runtime estimate halved); dashboard power/current and HealthMappers:263-264 charging curves display doubled values. Charging readings are also stored by LiveTelemetry and used in ChargeHealthPipeline.withRecordedCurrent when charge counters are absent, biasing/rejecting derived capacity.

Official primary source: [Linux power-supply units](https://docs.kernel.org/power/power_supply_class.html). The power_supply interface exports current in microamps and voltage in microvolts. A series pack voltage is already the total pack voltage; its current does not double just because it has two series cells.

- **Failure / Exploit Scenario:** Concrete reproduction: sysfs=-400000µA, BatteryManager=-400000µA, sysfs=8400000µV -> expected -400mA and -3.36W; actual -800mA and -6.72W. The same happens during charging with positive current. Existing `PowerCalibratorTest` test `dual cell pack voltage selects two cells` incorrectly asserts the doubled current, so this is a real test-integrity blind spot rather than missing tests alone.

- **Concrete Fix:** keep cell-count metadata, but apply current correction only when independent comparison supplies actual factor-two evidence (the existing single-cell-voltage/half-BatteryManager-current branch).

```diff
         val cellCount = if (hasDualCellVoltage || hasHalfCurrentSingleCellVoltage) 2 else 1
-        val currentScale = baseCurrentScale * cellCount
+        val currentScale = baseCurrentScale * if (hasHalfCurrentSingleCellVoltage) 2 else 1
```

Regression: a full-pack8.4V sample with sysfs and BatteryManager both400mA must produce400mA/3.36W; retain the existing half-current, single-cell-voltage test for the evidence-based OEM correction. Update the wrong dual-cell expectation.

### [A03] [FIXED in dc09c39] [MEDIUM] Notification rates retain screen state through reboot

- **Category:** Logic Bug
- **Confidence:** Confirmed by call trace and compiled-class reproduction.

- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/status/UnplugSummary.kt:80-101`, related `:123-135`.

- **Root Cause & Execution Trace:** RuntimeCaptureReceiver + CapturePolicy store SCREEN_OFF while process is alive; BootCaptureReceiver stores BOOT using the new bootCount; StatusNotificationService.redraw:101-105 loads up to30days of snapshots and calls UnplugSummaryCalculator with a new APP_OPEN-shaped light snapshot. `tally` carries the last screen state across all snapshots; `screenAfter` handles only SCREEN_ON/OFF, so BOOT and bootCount changes never invalidate inference. `Tally.add` includes powered-off wall time in screen-off time and keeps attributing later new-boot energy to the stale old-boot screen state. BOOT_COMPLETED may launch the process after the boot screen-on broadcast has already happened, so no SCREEN_ON capture necessarily repairs the state until the next physical screen transition.

- **Failure / Exploit Scenario:** Concrete scenario: unplug90% at minute0; SCREEN_OFF at1; reboot BOOT at60 at85% (new boot, elapsed5min/uptime5min); keep screen on and app open until120 at75% (elapsed65min/uptime65min). Actual postboot60min are fully awake screen-on. Calculator reports screenOff7.563%/h, deepSleep0%, screenOn=null; the result remains wrong until a new captured screen event. If old state was ON and the rebooted phone later sleeps before any captured screen transition, suspend time is omitted instead. Existing reboot test explicitly assumes the old screen-off state persists and must be revised.

Offending inference persists the prior state through every other trigger: `CaptureTrigger.SCREEN_ON -> Screen.ON`, `CaptureTrigger.SCREEN_OFF -> Screen.OFF`, `else -> current`. No boot boundary is checked in the original `tally`.

- **Concrete Fix:** Replace `tally` as follows; total battery drop remains available while discontinuous attribution becomes unknown:

```kotlin
    private fun tally(
        points: List<BatterySnapshot>,
        anchor: Int,
    ): Tally {
        var screen: Screen? = null
        val tally = Tally()
        for (i in points.indices) {
            val point = points[i]
            if (point.trigger == CaptureTrigger.BOOT ||
                (i > 0 && points[i - 1].clock.bootCount != point.clock.bootCount)
            ) {
                screen = null
            }
            screen = screenAfter(screen, point)
            if (i >= anchor && i < points.lastIndex) {
                val next = points[i + 1]
                val comparableScreen = screen.takeIf { point.clock.bootCount == next.clock.bootCount }
                tally.add(point, next, comparableScreen)
            }
        }
        return tally
    }
```

Also remove Tally.add's crossboot screen-off branch or guard it so future callers cannot count powered-off time as observed screen-off. The replacement ensures this call never reaches it. Regression: changed bootCount with and without BOOT; rates/deepSleep remain unknown until a fresh SCREEN event, total level drop/elapsed remain available. General same-boot process-observer gaps need an explicit capture/observation generation if addressed beyond this isolated reboot fix; no existing persisted field proves continuity through process absence.

### [A04] [FIXED in 14208e1] [MEDIUM] Dexopt cancellation does not fence starts still probing or queued during shutdown

- **Category:** Concurrency
- **Location:** `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/BgDexoptController.kt:51-67`, `:79-96`, `:101-108`; related `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/ToolkitExecutor.kt:167-177`, `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/service/TelemetryUserService.kt:153-161`, and `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt:301-334`.
- **Confidence:** Confirmed by deterministic Java execution against actual compiled production `ToolkitExecutor`/`BgDexoptController` classes, with injected runners and scheduling; no privileged device action was performed.
- **Root Cause & Execution Trace:** Cancellation and shutdown do not invalidate the entire start lifetime. `tryStart` calls `plugged.isPlugged()` before recording RUNNING, then clears `userCancelRequested` and `cancelCompleted`. A cancel during that probe sees UNKNOWN/IDLE and cannot mark the pending start. Separately, `cancelIfRunning()` merely runs the system cancellation and never sets the flag that `runJob()` checks before spawning the job. `TelemetryUserService.destroy()` calls `toolkit.cancelOnShutdown()` and only afterward calls `exitProcess()`: an already-queued worker can run between those operations and start a new system job after the shutdown cancellation has completed. The existing IPC-2 fix correctly suppresses queued workers after a regular user cancel once RUNNING; these are distinct unguarded phases.

```kotlin
if (!plugged.isPlugged()) return ToolkitResult.refused(ToolkitCode.NOT_PLUGGED)
// ... later, after a concurrent successful cancel:
userCancelRequested = false
cancelCompleted = false

fun cancelIfRunning() {
    if (isRunning()) runner.run(BG_DEXOPT_CANCEL_ARGV, SHUTDOWN_CANCEL_TIMEOUT_MS)
}
```

- **Upstream validation:** `ToolkitActions.startBgDexopt()` deliberately calls its backend outside the client mutex and runs in NonCancellable, so a cancel from a replacement/second Toolkit ViewModel can overlap the probe. The same ViewModel blocks its own start/cancel buttons while busy; this limits same-screen reachability but does not protect the service shutdown path or shared backend. The `ToolkitExecutorTest` named `a slow plugged probe in startBgDexopt does not block a cancel on the write lock` already demonstrates a successful cancel while the probe is pending and then expects the stale start to succeed.
- **Reproduction:** `.codex/android-kit/logs/audit-20261018/ipc/DexoptAuditProbe.java`, `classpath.txt`, and `output.txt`. Both probe cases produced the command sequence `[/system/bin/cmd, package, bg-dexopt-job, --cancel]` followed by `[/system/bin/cmd, package, bg-dexopt-job]`. The shutdown case invokes actual `ToolkitExecutor.cancelOnShutdown()` after queueing the real worker, then permits that worker to run before the service's exit point. The probe case invokes actual `ToolkitExecutor.cancelBgDexopt()` from the injected plugged probe and runs the queued worker afterward. No sleeps or timing assumptions are used.
- **Failure / Exploit Scenario:** An acknowledged cancellation may be followed by newly started dexopt. During service shutdown the process exits immediately afterward, removing the unplug/60-minute supervisor while system-server dexopt continues. This breaks the application's cancellation/charging guard; it is not a caller-authentication bypass. The user-probe variant can restart work the user just cancelled.
- **Concrete Fix:** Fence the entire start lifetime with a cancellation generation, keep starts out while a cancellation command is in flight, and set a permanent shutdown flag before sending the shutdown cancel. Keep plugged probes and cancellation commands outside the controller lock; retain `runner.start` inside the existing locked pre-launch check. Replace the two user-cancellation hooks together with the caller below; do not merely set a flag after `runner.run`, which leaves the same race.

```kotlin
// BgDexoptController fields, all guarded by lock.
private var cancelGeneration = 0L
private var cancellationsInFlight = 0
private var shuttingDown = false

// At the beginning of tryStart, replace the initial isRunning check:
val generation = synchronized(lock) {
    if (shuttingDown || cancellationsInFlight != 0 ||
        state == BgDexoptState.RUNNING ||
        (state == BgDexoptState.UNKNOWN && reason != BgDexoptReason.NONE)
    ) return ToolkitResult.refused(ToolkitCode.BUSY)
    cancelGeneration
}
if (!plugged.isPlugged()) return ToolkitResult.refused(ToolkitCode.NOT_PLUGGED)
// Add to tryStart's existing synchronized compare-and-set condition:
if (shuttingDown || cancellationsInFlight != 0 || generation != cancelGeneration ||
    state == BgDexoptState.RUNNING ||
    (state == BgDexoptState.UNKNOWN && reason != BgDexoptReason.NONE)
) return ToolkitResult.refused(ToolkitCode.BUSY)
// Preserve the existing state/timestamp initialization, worker submission and result.

// Replace noteUserCancel and noteCancelCompleted:
fun beginUserCancel() {
    synchronized(lock) {
        cancelGeneration += 1
        cancellationsInFlight += 1
        if (state == BgDexoptState.RUNNING) userCancelRequested = true
    }
}

fun endUserCancel(success: Boolean) {
    synchronized(lock) {
        cancellationsInFlight -= 1
        if (success) {
            cancelCompleted = true
            if (state == BgDexoptState.UNKNOWN) state = BgDexoptState.IDLE
        }
    }
}

// Replace cancelIfRunning. Shutdown closes the controller even when no job is RUNNING:
fun cancelIfRunning() {
    val needsCancel = synchronized(lock) {
        shuttingDown = true
        cancelGeneration += 1
        userCancelRequested = true
        state == BgDexoptState.RUNNING
    }
    if (needsCancel) runner.run(BG_DEXOPT_CANCEL_ARGV, SHUTDOWN_CANCEL_TIMEOUT_MS)
}

// In runJob's existing locked pre-launch check:
if (shuttingDown || userCancelRequested) {
    finish(BgDexoptState.CANCELLED, exit = 0, why = BgDexoptReason.USER)
    return
}
```

```kotlin
// Replace ToolkitExecutor.cancelBgDexopt:
fun cancelBgDexopt(): ToolkitResult = synchronized(writeLock) {
    dexopt.beginUserCancel()
    var confirmed = false
    try {
        val out = runner.run(BG_DEXOPT_CANCEL_ARGV)
        confirmed = out.ok
        if (out.ok) ToolkitResult.done() else ToolkitResult.failed(execCode(out))
    } finally {
        dexopt.endUserCancel(confirmed)
    }
}
```

Update the existing direct cancellation-hook tests to bracket begin/end. Add deterministic regressions for: cancellation during a blocked probe; shutdown while worker queued; shutdown while probe blocked; no fresh start until an in-flight cancel completes; and no command after shutdown. The proposed fix is reviewable remediation, not an implemented or verified patch.

### [A05] [FIXED in 8ecb86c] [MEDIUM] Diagnostics treats changed-package lifetime counters as interval usage

- **Category:** Logic Bug
- **Location:** `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/rules/DiagnosticRuleEngine.kt:27-36`, related `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/SnapshotDiffer.kt:208-238` and `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/IntervalAnalyzer.kt:40-44`.
- **Confidence:** Confirmed by cross-file trace and JVM reproduction.
- **Root Cause & Execution Trace:** IntervalAnalyzer calls SnapshotDiffer. Its diffUids detects packageName changes, sets `val baseline = if (packageChanged) null else previous`, and subtracts zero instead of the preceding counters. Positive lifetime counters retain `valid=true`. DiagnosticRuleEngine accepts `val validUids = diff.uids.filter { it.valid }`; all three UID-consuming rules accept changed-package data. XRayMapper.apps and Sleep Lab's mapper already explicitly exclude packageChanged.
- **Failure / Exploit Scenario:** UID identity changes between FULL endpoints (including null/package metadata changes or UID reuse). Lifetime GPS/background activity predating the selected interval becomes a current red anomaly and enters the dashboard story. The same affected UID can be selected as an Awake Violation culprit.
- Reproduction: packageChanged=true, valid=true UID with 10 mAh total, 9 mAh background, zero foreground activity and six minutes GPS produces RED SensorLeak and NightVampire. See PACKAGE_CHANGED output.
- **Concrete Fix:**

```diff
- val validUids = diff.uids.filter { it.valid }
+ val validUids = diff.uids.filter { it.valid && !it.packageChanged }
...
- skippedInvalidUids = diff.uids.count { !it.valid },
+ skippedInvalidUids = diff.uids.count { !it.valid || it.packageChanged },
```

- Regression coverage: test changed-package inputs for Night Vampire, Sensor Leak and Awake Violation culprits. Existing RuleFixtures always initializes packageChanged=false.

### [A06] [FIXED in 36e88dd] [MEDIUM] Timeline carries screen and Doze state through a reboot without SHUTDOWN

- **Category:** Logic Bug
- **Location:** `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/TimelineBuilder.kt:146-148`, related `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/BatteryHistory.kt:120-121`, `app/src/main/kotlin/com/akane/inspiration/AppContainer.kt:156-161`, and `app/src/main/kotlin/com/akane/inspiration/di/TimelineEvents.kt`.
- **Confidence:** Confirmed by cross-file trace and JVM reproduction.
- **Root Cause & Execution Trace:** HistoryWalker emits START and resets its transition baseline. Captured rows remain in history across boots. AppContainer.timeline supplies those rows and prior state seeds through TimelineEvents.withSeeds. TimelineBuilder handles START/RESET only as `if (event.plugType != 0) plug.open(t) else plug.close(t)`, leaving screen, deep, light, and gaps lanes open. Dashboard and Sleep Lab render those lanes.
- **Failure / Exploit Scenario:** An abrupt device restart while deep idle or screen-on has no graceful SHUTDOWN record. The old state remains depicted through the next boot until another matching transition, potentially for hours. A fresh baseline cannot establish screen or idle state because HistoryEvent does not carry those fields.
- Reproduction: DEEP_IDLE_ON(1000), START(2000), LEVEL(3000), window 1000..4000 produces DEEP_IDLE(1000,4000). See START_WITHOUT_SHUTDOWN output.
- **Concrete Fix:** replace the RESET/START branch with conservative discontinuity handling:

```kotlin
HistoryKind.RESET, HistoryKind.START -> {
    // The framework walker establishes a fresh baseline here. HistoryEvent
    // carries no screen/idle baseline, so prior state cannot cross this boundary.
    listOf(screen, plug, deep, light, gaps).forEach { it.close(t) }
    if (event.plugType != 0) plug.open(t)
}
```

- Regression coverage: tests cover graceful SHUTDOWN followed by START, not START without SHUTDOWN or RESET with open lanes. Add screen, deep, light, charging and gap variants; retain the known plug baseline while withholding unknown screen/idle state.

### [A07] [FIXED in e1bda9a] [MEDIUM] Health charge reconstruction ignores authoritative boot/reset plug baselines

- **Category:** Logic Bug
- **Location:** `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthMappers.kt:122-136`, related `HealthMappers.kt:167-198`, `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthViewModel.kt:182-188`, and the history extractor and persistence call path.
- **Confidence:** Confirmed by cross-file trace and JVM reproduction.
- **Root Cause & Execution Trace:** HistoryWalker emits RESET/START with plugType and establishes its baseline without synthetic PLUGGED/UNPLUGGED. Persisted historyBetween supplies these events. lastChargeSpan only recognizes explicit PLUGGED/UNPLUGGED. chargeSession filters all LEVEL records through a stale or absent span, then curveUi/wearAnalysis consume that selection. HistoryEvent.toCurvePoint intentionally maps LEVEL regardless of plug state because the surrounding span is supposed to establish charging membership.
- **Failure / Exploit Scenario:** PLUGGED -> SHUTDOWN -> unplug while powered off -> START(plugType=0) -> declining LEVEL events. START already establishes unplugged state, so no postboot UNPLUGGED transition occurs. Health retains the preboot open charge and treats current discharge as charge history. Separately RESET(plugType=1) followed by charging LEVEL records has no span and loses the known history. These are UI reconstruction defects, independent of the intentionally invalidated capacity estimates.
- Reproduction: PLUGGED(1000), SHUTDOWN(2000), START(3000,plugType=0), unplugged LEVEL(4000,80), LEVEL(5000,79) yields ChargeSpan(1000,null), includes both postboot discharge points, and reports hot/high-voltage charging durations. See UNPLUGGED_POST_BOOT_SPAN output. RESET_CHARGE_SPAN separately demonstrates baseline loss.
The offending reducer handles only `HistoryKind.PLUGGED -> open = event.wallMs` and `HistoryKind.UNPLUGGED -> open?.let { ... }`; RESET, START and SHUTDOWN never update `open`.

- **Concrete Fix:** drop-in replacement retaining existing window-start inference:

```kotlin
internal fun lastChargeSpan(events: List<HistoryEvent>): ChargeSpan? {
    val sorted = events.sortedBy { it.wallMs }
    var open: Long? = null
    var last: ChargeSpan? = null
    var baselineKnown = false
    for (event in sorted) {
        when (event.kind) {
            HistoryKind.RESET, HistoryKind.START -> {
                open = event.wallMs.takeIf { event.plugType != 0 }
                baselineKnown = true
            }
            HistoryKind.SHUTDOWN -> {
                open?.let { start ->
                    if (event.wallMs > start) last = ChargeSpan(start, event.wallMs)
                }
                open = null
                baselineKnown = true
            }
            HistoryKind.PLUGGED -> {
                if (open == null) open = event.wallMs
                baselineKnown = true
            }
            HistoryKind.UNPLUGGED -> {
                val start = open ?: sorted.firstOrNull()?.wallMs
                    ?.takeIf { !baselineKnown }
                if (start != null && event.wallMs > start) {
                    last = ChargeSpan(start, event.wallMs)
                }
                open = null
                baselineKnown = true
            }
        }
    }
    return open?.let { ChargeSpan(it, null) } ?: last
}
```

- Regression coverage: tests cover explicit plug transitions and sample/history merging but contain no START, RESET or SHUTDOWN. Add both plugged and unplugged baselines and shutdown/restart sequences. Verify discharge records never enter the selected charge session.

### [A08] [FIXED in 166af9c] [MEDIUM] Root-only exclusions still export private data during some OEM device transfers

- **Category:** Security
- **Location:** `app/src/main/res/xml/data_extraction_rules.xml:3-8`; related `app/src/main/res/xml/backup_rules.xml:2-3`, `app/src/main/AndroidManifest.xml:23-27`, `core/database/src/main/kotlin/com/akane/inspiration/database/InspirationDatabase.kt:46-53,72`, `app/src/main/kotlin/com/akane/inspiration/status/StatusNotificationSettings.kt:48-51`.
- **Confidence:** Confirmed by application-to-framework call trace; no physical device-transfer test was performed.
- **Root Cause & Execution Trace:** AppContainer opens `inspiration.db` in Android's database domain and status settings use shared preferences. The only rule in each extraction section is `<exclude domain="root" path="." />`. Android's BackupAgent traverses root, database, files and preferences independently; its backup exclusion check compares exact canonical paths. Excluding the root traversal does not exclude those separate directory traversals. Android documents that some manufacturers permit device-to-device transfer despite `allowBackup=false`.
- **Failure / Exploit Scenario:** On those OEM transfers, package/UID telemetry, wakelock/history records, toolkit audit rows and preferences can leave the device despite the apparent opt-out. This is an export/privacy finding. Cloud backup is ordinarily disabled by the manifest, and restoration separately applies ancestor exclusions; successful restoration or execution of old-device toolkit records is **not** claimed. See [Android backup documentation](https://developer.android.com/identity/data/autobackup), [BackupAgent domain traversal/exclusion implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/backup/BackupAgent.java) and [BackupUtils restore filtering](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/backup/BackupUtils.java).
- **Concrete Fix:** Replace the sole exclusion inside **both** existing `cloud-backup` and `device-transfer` sections with the block below; mirror it inside legacy `full-backup-content`:

```xml
<exclude domain="root" path="." />
<exclude domain="file" path="." />
<exclude domain="database" path="." />
<exclude domain="sharedpref" path="." />
<exclude domain="external" path="." />
<exclude domain="device_root" path="." />
<exclude domain="device_file" path="." />
<exclude domain="device_database" path="." />
<exclude domain="device_sharedpref" path="." />
```

Verify transport output on a supported test device; merely checking restoration does not establish absence from the backup stream.

### [A09] [FIXED in c4ac135] [MEDIUM] Offline wrapper completion follows destination symlinks outside the project

- **Category:** Security
- **Location:** `.codex/android-kit/scaffold.py:107-110,135-140,166-173`; identical distribution implementation at `android-kit-codex/template/.codex/android-kit/scaffold.py:107-110,135-140,166-173`. Read-only sibling location: `.claude/kit/scaffold.py:107-110,135-140,166-173`.
- **Confidence:** Confirmed by a temporary-directory reproduction with fake downloads/Gradle generation; no real wrapper download or Gradle command was executed by the probe.
- **Root Cause & Execution Trace:** Android bootstrap documents offline scaffolding followed by `--wrapper-only`. That branch directly calls `gradle_wrapper()`, bypassing the normal scaffolding destination checks at lines 669-674. The wrapper function checks `if any((root / rel).exists() for rel in wrapper_files)` then uses `dst.parent.mkdir(...)` and `shutil.copy2(...)`. Existing symlinked directories are followed, and a dangling file symlink is invisible to `exists()`.
- **Failure / Exploit Scenario:** With `<project>/gradle -> <outside>/`, completing the wrapper writes `<outside>/wrapper/gradle-wrapper.jar` and `gradle-wrapper.properties`. A dangling `gradlew` link can also receive file contents at its outside target. The reproduction returned success and created both outside wrapper files. This is a local destination-containment failure, not a remotely reachable application exploit.
- **Concrete Fix:** Replace the initial `any(...exists())` check in the installed and distribution copies with:

```python
for rel in wrapper_files:
    dest = root / rel
    for part in (dest, *dest.parents):
        if part == root:
            break
        if part.is_symlink():
            raise ValueError(f"symlink in scaffold destination: {rel}")
    if dest.exists():
        raise ValueError(
            "wrapper files already exist; refusing to overwrite "
            "a complete or partial wrapper"
        )
```

Reject before network/subprocess execution. Add isolated ancestor-directory and dangling-file symlink cases asserting no outside writes. The sibling has the same defect, but the user's instruction forbids modifying the Claude setup; this audit made no such modification.

### [A10] [BLOCKED — protected Claude setup] [MEDIUM] The device-probe exit status certifies timeout/error as success

- **Category:** Logic Bug
- **Location:** `.claude/skills/device-probe/scripts/device-probe.sh:134-140`; producer `app/src/debug/kotlin/com/akane/inspiration/debug/ProbeReceiver.kt:69-79`.
- **Confidence:** Confirmed by cross-file trace and isolated execution of the current shell classifier; no device or hook was executed.
- **Root Cause & Execution Trace:** The device-probe skill uses the script's exit status as its verification oracle. ProbeReceiver emits `{"probe":"timeout"}` or `{"probe":"error",...}`, then unconditionally emits `{"probe":"done"}` in `finally`. The polling script accepts `(done|timeout)` and assigns `status=0` whenever `done` exists, ignoring earlier failures. Its offending assignment is `grep -q '"probe":"done"' "$out.tmp" && status=0`.
- **Failure / Exploit Scenario:** A timed-out or throwing debug probe is recorded as successful device verification when the normal poll contains both terminal records. Isolated classifier inputs `done`, `timeout + done`, and `error + done` all returned status **0**. `done` means receiver completion, not successful execution.
- **Concrete Fix:** The following replacement for shell lines 137-140 makes failures take precedence:

```bash
if grep -qE '"probe":"(error|timeout)"' "$out.tmp"; then
  status=3
  break
fi
if grep -q '"probe":"done"' "$out.tmp"; then
  status=0
  break
fi
```

Test all three classifier inputs; only `done` should return 0. A later structured result could carry an explicit success field. This sibling setup was inspected only; the user's preservation instruction prohibits applying this change here.

### [A11] [FIXED in ec96266] [LOW] X-Ray labels an allowed app-op override as restricted

- **Category:** Logic Bug
- **Location:** `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayViewModel.kt:243-249`, `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayText.kt:185-190`, `feature/xray/src/main/res/values/strings.xml:125-127`.
- **Confidence:** Confirmed by cross-file call trace; no separate Compose rendering reproduction claimed.
- **Root Cause & Execution Trace:** User switches off a preexisting external IGNORE restriction. ToolkitActions records before=ignore, after=default. ToolkitActions.activeOverride (813-816) correctly returns a change whose current mode differs from its original. XRayViewModel.overrideRow discards Override.currentRaw; XRayText.overrideText labels every app-op row as restricted regardless of actual mode. Toolkit's corresponding UI now preserves mode, but X-Ray does not.
- **Failure / Exploit Scenario:** X-Ray switch correctly shows off while the override text claims background activity is restricted. Restore actually re-enables the original block, opposite the implication of the label.
Offending selection: `row.appOp == AppOp.RUN_ANY_IN_BACKGROUND -> stringResource(R.string.xray_override_run_any_in_background)`; it chooses the restriction text without testing the actual mode.

- **Concrete Fix:** use neutral wording in both existing resource sets; no model migration is needed to make the statements accurate:

```xml
<!-- feature/xray/src/main/res/values/strings.xml -->
<string name="xray_override_run_any_in_background">Background activity setting changed</string>
<string name="xray_override_run_in_background">Background running setting changed</string>
<string name="xray_override_wake_lock">Wakelock setting changed</string>
```

```xml
<!-- feature/xray/src/main/res/values-tr/strings.xml -->
<string name="xray_override_run_any_in_background">Arka plan etkinliği ayarı değiştirildi</string>
<string name="xray_override_run_in_background">Arka planda çalışma ayarı değiştirildi</string>
<string name="xray_override_wake_lock">Uyandırma kilidi ayarı değiştirildi</string>
```

- Better structural remediation: carry actual mode in OverrideRow as Toolkit does, then render ignored/allowed/unknown values explicitly. Add preexisting-ignore -> default -> restore regression and screenshots for both modes.

### [A12] [FIXED in 9f9527f] [LOW] A documented QA command fails on supported Python 3.9

- **Category:** Logic Bug
- **Location:** `.codex/android-kit/emulator/skills/android-emulator-qa/scripts/ui_pick.py:8,22`; identical distribution template; caller `.codex/android-kit/emulator/scripts/android-qa.py:60-63`; advertised minimum `android-kit-codex/README.md:7`.
- **Confidence:** Confirmed by call trace and Python annotation semantics; Python 3.9 is unavailable here, so no Python 3.9 runtime pass/failure is claimed.
- **Root Cause & Execution Trace:** The installed `android-qa.py pick` command launches `ui_pick.py` with `sys.executable`. Its signatures `def clean_text(value: str | None) -> str:` and `def find_node(root: ET.Element, target: str) -> ET.Element | None:` eagerly evaluate unions without postponed annotations. On Python 3.9 these expressions raise TypeError while defining the functions, before main runs. Installer syntax compilation does not evaluate the annotations, so the supported-version installation check misses this runtime failure.
- **Failure / Exploit Scenario:** A developer using the advertised Python 3.9 minimum can install the kit but cannot use the coordinate picker.
- **Concrete Fix:** Immediately after the shebang, add the following in the installed picker and distribution template:

```python
from __future__ import annotations
```

Exercise the command on Python 3.9 in the compatibility matrix. Current interpreter-only compilation is insufficient to certify the advertised minimum.

## 3. Dead Code, Redundancy & Technical Debt Cleanup

The following optional cleanup opportunities were verified by repository-wide caller/import searches. They remain proposed, separate from the 12 confirmed finding IDs addressed in section 5.

| Location | Verified cleanup |
| --- | --- |
| `app/src/main/kotlin/com/akane/inspiration/capture/CapturePolicy.kt:47-50` | `appOpenDebounced` has only test callers; runtime debounce is implemented by RefreshCoordinator. Remove the obsolete helper/test while retaining the shared constant. |
| `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/math/SeriesMath.kt:44` | `nearestIndex` has only unit-test callers; the active scrub path uses its current series implementation. Safe repository-local removal includes the helper's dedicated tests. |
| `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/component/TimeSliceBanner.kt:101-115` | String-based overload has no callers; production and screenshots use the TimeRange overload. |
| `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/component/ReducedMotion.kt:26` and `chart/scrub/SynchronizedScrubbingChart.kt:346` | Duplicate animator-duration-scale reads can share one internal helper without behavior changes. |
| `core/shizuku-ipc/build.gradle.kts:22` | HiddenApiBypass has no imports/calls in this module; app declares and calls its own dependency. Remove the redundant IPC declaration without removing the app dependency. |
| `core/hidden-api-stub/src/main/java/android/os/{BatteryUsageStats,IDeviceIdleController,UidBatteryConsumer}.java` | `getDischargePercentage`, `isPowerSaveWhitelistApp` and `getTimeInProcessStateMs` have no code callers; remove only these unused compile-time declarations if shrinking the stub surface. |
| `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/ReferenceResolver.kt:36` | Initial `latest` full-snapshot lookup is unused except an early null check. The later end lookup already handles absence; remove the redundant hydration. |

Structural debt that requires design work rather than blind deletion:

- Toolkit history is also the durable original-value/restore-chain store. Pending rows and successful chains deliberately survive display pruning. Define safe chain compaction or a separate durable override record before bounding it; deleting old successful rows would recreate data loss.
- Several independent history reducers implement RESET/START/SHUTDOWN differently. Introduce shared, explicit boundary semantics, preserving unknown screen/idle state rather than inventing it. Do not infer screen continuity across periods when the process did not observe events.
- AndroidTelemetrySource parses a power dump that SnapshotCapturer does not consume. Decide whether to wire it into the product or remove the production read; parser/types still have tests/debug uses, so wholesale removal was not certified safe.
- Installed and distribution tooling intentionally duplicate helpers. Apply future Codex fixes to both copies and run portable-kit tests. The separately preserved Claude setup is outside authorized modifications.
- No unused entire module, proven unused large dependency set, stale TODO backlog, or additional confirmed production resource leak was identified. Compiler/lint style notices are not audit findings.

## 4. Prioritized Remediation Roadmap

**Phase 1: Immediate Hotfixes (Stop the Bleeding)**

1. Fix A01 restore ordering using durable action IDs; add backward-clock execute/restore/resetAll integration tests before shipping another override change.
2. Fence A04 start admission, in-flight cancellation and shutdown in one controller change. Prove that no start command occurs after cancellation/shutdown using deterministic scheduling, then verify real system-job cancellation on a test device.
3. Correct A08 backup exclusions and verify backup-stream contents, plus A09 scaffold destination checks with isolated symlink probes. A10's preserved probe script currently cannot establish success for timeout/error; record that limitation until the sibling owner authorizes remediation.
4. Correct A02 physical power/current calibration and the existing wrong unit-test expectation. Device comparisons should use independently observed pack current/voltage and retain evidence for OEM-specific correction factors.

**Phase 2: Structural & Concurrency Hardening**

1. Fix the independent A03 status and A06/A07 history-reconstruction paths; test graceful shutdown, abrupt START without SHUTDOWN, plugged/unplugged RESET, boot-count changes and unavailable state. Preserve intentionally conservative capacity invalidation.
2. Fix A05 changed-package diagnostics at the consumer boundary; assert lifetime counters never become selected-interval anomalies or awake culprits.
3. Correct A11 X-Ray override wording/model and A12 minimum-Python compatibility in their focused commits. Add screenshots for allowed and ignored overrides without regenerating unrelated references.

**Phase 3: Architectural Refactoring & Test Coverage**

1. Separate durable restore state from display history and define compaction/recovery invariants. Use monotonic/durable operation order for causality; retain wall time for presentation.
2. Share discontinuity and identity-validity contracts across metrics, timelines, health and stories. Explicitly represent unknown observation intervals; do not carry state merely because no contrary event exists.
3. Add high-value integration coverage for actual boot/runtime receivers, charging-job stop/rearm races, foreground notification permission/lifecycle, Shizuku reconnect/death, real Compose pointer/visibility handling, device transfer and minified release service construction. Existing host tests and screenshots do not cover those runtime boundaries.
4. Add supported-interpreter CI for portable helpers and a resolved-transitive dependency advisory scan. No `.github` CI workflow was found in this checkout.
5. After isolated regression tests, use the current full debug assembly/lint/unit/screenshot gate. Keep real device/release checks separate and report unavailable checks explicitly. Do not change SDK/toolchain/dependency versions or overwrite screenshot references merely to make verification pass.

**Audit Delta:** No previous working-tree or archived audit report was available for direct comparison. Prior remediation documentation was visible as project context, but old report findings were not imported as current findings. This audit independently traced the current implementation and records new/residual cases rather than claiming a report-to-report regression classification.

## 5. Remediation results — 2026-10-18

Branch: `fix/audit-remediation-20261018-pass2`, from `3c6cf4a`. Eleven authorized finding IDs have separate commits after successful focused verification; A10 is BLOCKED because AGENTS.md explicitly says “Do not modify the parallel Claude setup or Sidequest state.” The identical Claude scaffolder defect described under A09 also remains preserved. No confirmation is required for the completed authorized work.

The checkout already contained modified audit/project/map notes and untracked Codex/portable-kit files. Existing work was preserved. The four previously untracked helper files needed for A09/A12 are added in their fix commits; their installed and distribution contents match. Unrelated kit files, Claude files and Sidequest state were not staged or modified. Optional cleanup and architectural roadmap items in sections 3–4 remain future work.

### Per-finding verification and commits

Each Android command below compiled its module, ran Android lint and its complete unit suite. A07/A11 also validated their screenshot suites. Commands exited 0. A04 also passed an earlier gate (`gradle.vPtB2w0P.log`) before the exception-path test was added; the table records its final gate.

| ID | Commit | Files modified | Verification command run | Status / evidence |
| --- | --- | --- | --- | --- |
| A01 | `5cadb76` | `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt`<br>`core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActionsTest.kt` | `bash .codex/android-kit/gradle-check.sh :core:telemetry-engine:assembleDebug :core:telemetry-engine:lintDebug :core:telemetry-engine:testDebugUnitTest --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g` | FIXED — .codex/android-kit/logs/gradle.zf7lbIZN.log |
| A02 | `202f496` | `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/calibration/PowerCalibrator.kt`<br>`core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/calibration/PowerCalibratorTest.kt` | `bash .codex/android-kit/gradle-check.sh :core:telemetry-engine:assembleDebug :core:telemetry-engine:lintDebug :core:telemetry-engine:testDebugUnitTest --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g` | FIXED — .codex/android-kit/logs/gradle.hE2FuYvg.log |
| A03 | `dc09c39` | `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/status/UnplugSummary.kt`<br>`core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/status/UnplugSummaryCalculatorTest.kt` | `bash .codex/android-kit/gradle-check.sh :core:telemetry-engine:assembleDebug :core:telemetry-engine:lintDebug :core:telemetry-engine:testDebugUnitTest --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g` | FIXED — .codex/android-kit/logs/gradle.kp7h9gnF.log |
| A04 | `14208e1` | `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/BgDexoptController.kt`<br>`core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/ToolkitExecutor.kt`<br>`core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/toolkit/BgDexoptControllerTest.kt`<br>`core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/toolkit/ToolkitExecutorTest.kt` | `bash .codex/android-kit/gradle-check.sh :core:shizuku-ipc:assembleDebug :core:shizuku-ipc:lintDebug :core:shizuku-ipc:testDebugUnitTest --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g` | FIXED — .codex/android-kit/logs/gradle.8Nh4V9eH.log |
| A05 | `8ecb86c` | `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/rules/DiagnosticRuleEngine.kt`<br>`core/diagnostics/src/test/kotlin/com/akane/inspiration/diagnostics/rules/DiagnosticRuleEngineTest.kt` | `bash .codex/android-kit/gradle-check.sh :core:diagnostics:assembleDebug :core:diagnostics:lintDebug :core:diagnostics:testDebugUnitTest --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g` | FIXED — .codex/android-kit/logs/gradle.RO6mzy9O.log |
| A06 | `36e88dd` | `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/TimelineBuilder.kt`<br>`core/diagnostics/src/test/kotlin/com/akane/inspiration/diagnostics/analysis/TimelineBuilderTest.kt` | `bash .codex/android-kit/gradle-check.sh :core:diagnostics:assembleDebug :core:diagnostics:lintDebug :core:diagnostics:testDebugUnitTest --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g` | FIXED — .codex/android-kit/logs/gradle.NsYhYDMv.log |
| A07 | `e1bda9a` | `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthMappers.kt`<br>`feature/health/src/test/kotlin/com/akane/inspiration/health/HealthMappersTest.kt` | `bash .codex/android-kit/gradle-check.sh :feature:health:assembleDebug :feature:health:lintDebug :feature:health:testDebugUnitTest :feature:health:validateDebugScreenshotTest --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g` | FIXED — .codex/android-kit/logs/gradle.DbWA24JL.log |
| A08 | `166af9c` | `app/src/main/res/xml/backup_rules.xml`<br>`app/src/main/res/xml/data_extraction_rules.xml`<br>`app/src/test/kotlin/com/akane/inspiration/BackupRulesTest.kt` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug :app:lintDebug :app:testDebugUnitTest --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g` | FIXED — .codex/android-kit/logs/gradle.ihwjHHKf.log |
| A09 | `c4ac135` | `.codex/android-kit/scaffold.py`<br>`android-kit-codex/template/.codex/android-kit/scaffold.py`<br>`android-kit-codex/tests/test_scaffold_containment.py` | `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s android-kit-codex/tests -v` | FIXED — 20 tests passed; in-memory Python compilation and installed/template equality passed |
| A10 | `—` | `.claude/skills/device-probe/scripts/device-probe.sh (preserved)` | `Not run or modified` | BLOCKED — AGENTS.md: Do not modify the parallel Claude setup or Sidequest state. |
| A11 | `ec96266` | `feature/xray/src/main/res/values-tr/strings.xml`<br>`feature/xray/src/main/res/values/strings.xml`<br>`feature/xray/src/screenshotTest/kotlin/com/akane/inspiration/xray/XRayScreenshots.kt`<br>`feature/xray/src/screenshotTestDebug/reference/com/akane/inspiration/xray/XRayScreenshotsKt/XRayDetailAllowedOverride_detail-allowed-override_d5526eba_0.png`<br>`feature/xray/src/screenshotTestDebug/reference/com/akane/inspiration/xray/XRayScreenshotsKt/XRayDetailDark_detail-dark_c4881c2c_0.png`<br>`feature/xray/src/screenshotTestDebug/reference/com/akane/inspiration/xray/XRayScreenshotsKt/XRayDetailTurkish_detail-turkish_b1dcc5b3_0.png`<br>`feature/xray/src/test/kotlin/com/akane/inspiration/xray/XRayViewModelTest.kt` | `bash .codex/android-kit/gradle-check.sh :feature:xray:assembleDebug :feature:xray:lintDebug :feature:xray:testDebugUnitTest :feature:xray:validateDebugScreenshotTest --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g` | FIXED — .codex/android-kit/logs/gradle.T8AgqVSD.log |
| A12 | `9f9527f` | `.codex/android-kit/emulator/skills/android-emulator-qa/scripts/ui_pick.py`<br>`android-kit-codex/template/.codex/android-kit/emulator/skills/android-emulator-qa/scripts/ui_pick.py`<br>`android-kit-codex/tests/test_picker_compatibility.py` | `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s android-kit-codex/tests -v` | FIXED — 21 tests passed; in-memory Python compilation and installed/template equality passed |

### Regression and validation scope

- A01: backward wall-clock execute/list/restore/Reset All returns the original bucket using durable row IDs.
- A02: positive and negative 8.4 V pack readings retain measured current and correct watts; existing half-current OEM correction remains covered.
- A03: boot-count changes with and without BOOT invalidate inference until a fresh screen event; total drop/elapsed remain available. Earlier known screen-off segments retain their valid rates.
- A04: cancellation during a probe, queued-worker shutdown, probe-time shutdown, future starts after shutdown, in-flight cancel admission and exception cleanup are covered with controlled scheduling and a real concurrent executor cancel test.
- A05: changed-package data is excluded from Night Vampire, Sensor Leak and Awake Violation culprits; a comparable-data control proves all three paths otherwise fire.
- A06: START/RESET close screen/deep/light/charging/gap lanes, with plugged/unplugged baselines tested.
- A07: shutdown/reboot excludes subsequent discharge; plugged START/RESET establish a new span; known unplugged baselines suppress inferred charges.
- A08: XML regression checks all nine domains for both new transports and legacy backup; resource compilation/lint pass. Actual OEM backup-stream output has not been tested.
- A09: both helpers reject symlinked directories and dangling wrapper-file links before fetch/subprocess; outside paths remain untouched and partial wrappers survive unchanged.
- A11: English/Turkish labels now describe a setting change without asserting a restriction; external-ignore → default → confirmed restore is covered. Exactly two existing X-Ray references were intentionally updated and visually reviewed, and one allowed-override reference added and reviewed. Unrelated references are unchanged. Screenshot update command passed (`gradle.Li5GaES7.log`); validation passed separately as listed.
- A12: both helpers postpone annotations; Python 3.9 grammar and string-valued annotations are checked, and CLI coordinates run on Python 3.14.4. Python 3.9 and uv were not installed, so no Python 3.9 runtime pass is claimed.

Device privileged commands, physical backup/transfer, reboot/OEM integration, release signing/minification and Python 3.9 runtime validation were not executed. Same-boot observation gaps and long-term restore-chain compaction remain outside these isolated fixes.

### Final full gate

Executed after all eleven fix commits:

```bash
bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug \
  testDebugUnitTest --rerun validateDebugScreenshotTest --rerun \
  --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g
```

**PASS, Gradle exit 0**. Log: `.codex/android-kit/logs/gradle.8Cec1wim.log`. All **1,034 unit tests** (88 fresh XML reports) and **168 screenshot comparisons** (seven fresh XML reports) passed, zero failures/errors/skips. Lint: zero errors, 17 warnings. Full machine-readable totals: `.codex/android-kit/logs/remediation-pass2/final-results.json`; per-finding metadata: `findings.json` in the same directory.

Portable-kit suite: **21 passed** on Python 3.14.4, zero failures. Both modified helper pairs compile and match byte for byte. `git diff --check` passed, and the project doctor passed with 19 project skills. No SDK/toolchain/dependency changes or unrelated screenshot updates were made.

Board #7 verifies the final documented checkout with the same full debug task set (without forcing another fresh run); retained board evidence is under `.codex/android-kit/runtime/`. Existing logs from the original audit remain historical evidence.
