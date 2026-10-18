# Whole-repository audit — Inspiration

Original audit date: 2026-10-18. Audited commit: `eb3fe08185995a7925db4bc97d44c4da1cf1048c` (`Review-n-fix`). The repository was reviewed as a whole, not as a diff. During that original audit, application code, dependencies, golden images, Claude configuration and Sidequest state were not changed. The working tree already contained untracked Codex kit files and distribution artifacts; those local executable helpers were also reviewed and are explicitly distinguished from the shipped app.

## 1. Executive Summary & System Health Scorecard

### Remediation progress

All 20 confirmed findings were remediated on `fix/audit-remediation-20261018` in separate finding commits. Original audit evidence, scorecard, proposed fixes and roadmap below are historical; status entries and the final verification record supersede their unresolved-state wording.

| ID | Commit | Files modified | Verification | Status |
|---|---|---|---|---|
| LINT | `3dc4415` | `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/scrub/ScrubReadout.kt`<br>`core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/scrub/SynchronizedScrubbingChart.kt`<br>`core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/component/format/TelemetryFormat.kt`<br>`feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitCards.kt`<br>`feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitMarkerSheet.kt`<br>`feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabRoute.kt`<br>`feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitRoute.kt`<br>`feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitText.kt`<br>`feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayText.kt` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; screenshot gate `validateDebugScreenshotTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g` (.codex/android-kit/logs/gradle.2P4RLAiP.log); log `.codex/android-kit/logs/gradle.zXq6egx0.log` | FIXED |
| APP-1 | `efc24d9` | `app/src/main/kotlin/com/akane/inspiration/capture/BroadcastCaptureRunner.kt`<br>`app/src/main/kotlin/com/akane/inspiration/capture/RuntimeCaptureReceiver.kt`<br>`app/src/test/kotlin/com/akane/inspiration/capture/BroadcastCaptureRunnerTest.kt` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.6UKWXQYa.log` | FIXED |
| APP-2 | `f869731` | `app/src/main/kotlin/com/akane/inspiration/di/RefreshCoordinator.kt`<br>`app/src/main/kotlin/com/akane/inspiration/AppContainer.kt`<br>`app/src/test/kotlin/com/akane/inspiration/di/RefreshCoordinatorTest.kt` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.HkEGatf2.log` | FIXED |
| IPC-1 | `b58e3bf` | `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/ShizukuManagerImpl.kt`<br>`core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/ShizukuManagerImplTest.kt` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.clkRrYRu.log` | FIXED |
| T1 | `feedebd` | `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt`<br>`core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitGateway.kt`<br>`core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActionsFakes.kt`<br>`core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActionsRobustnessTest.kt`<br>`core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActionsTest.kt`<br>`core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActionsChainTest.kt` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.8gmBIHl2.log` | FIXED |
| T2 | `ffcef24` | `core/database/src/main/kotlin/com/akane/inspiration/database/Daos.kt`<br>`core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt`<br>`core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/store/SnapshotStore.kt`<br>`core/database/src/test/kotlin/com/akane/inspiration/database/store/RoomSnapshotStoreTest.kt`<br>`core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActionsRobustnessTest.kt` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.wAgWVBIS.log` | FIXED |
| DB-1 | `198bd27` | `app/src/main/kotlin/com/akane/inspiration/capture/ChargingCaptureRunner.kt`<br>`app/src/test/kotlin/com/akane/inspiration/capture/ChargeHealthPipelineTest.kt`<br>`core/database/src/main/kotlin/com/akane/inspiration/database/InspirationDatabase.kt`<br>`core/database/src/main/kotlin/com/akane/inspiration/database/store/RoomSnapshotStore.kt`<br>`core/database/src/test/kotlin/com/akane/inspiration/database/InspirationDatabaseTest.kt`<br>`core/database/schemas/com.akane.inspiration.database.InspirationDatabase/3.json`<br>`core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/capacity/CapacityEstimator.kt`<br>`core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/store/SnapshotStore.kt`<br>`core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/capacity/CapacityEstimatorTest.kt` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.sj1a12qv.log` | FIXED |
| DIAG-1 | `5cc15f7` | `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/story/BatteryStoryBuilder.kt`<br>`core/diagnostics/src/test/kotlin/com/akane/inspiration/diagnostics/story/BatteryStoryBuilderTest.kt`<br>`core/diagnostics/src/test/kotlin/com/akane/inspiration/diagnostics/story/StoryFixtures.kt` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.rqbRkxYh.log` | FIXED |
| DIAG-2 | `4a0ba18` | `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/TimelineBuilder.kt`<br>`app/src/main/kotlin/com/akane/inspiration/di/TimelineEvents.kt`<br>`app/src/test/kotlin/com/akane/inspiration/di/TimelineEventsTest.kt` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.cRhd5Ztk.log` | FIXED |
| FEAT-1 | `af861ff` | `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthMappers.kt`<br>`feature/health/src/test/kotlin/com/akane/inspiration/health/HealthMappersTest.kt` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.ZGQMOQHX.log` | FIXED |
| FEAT-2 | `29d1b09` | `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthMappers.kt`<br>`feature/health/src/test/kotlin/com/akane/inspiration/health/HealthMappersTest.kt` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.VL9xyZy0.log` | FIXED |
| FEAT-3 | `cf7f61d` | `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabMappers.kt`<br>`feature/sleeplab/src/test/kotlin/com/akane/inspiration/sleeplab/SleepLabMappersTest.kt` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.vlGpwPrJ.log` | FIXED |
| IPC-2 | `33fe04d` | `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/BgDexoptController.kt`<br>`core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/toolkit/BgDexoptControllerTest.kt` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.iE6F6KUW.log` | FIXED |
| IPC-3 | `b8bbb62` | `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/BgDexoptController.kt`<br>`core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/service/TelemetryUserService.kt`<br>`core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/toolkit/BgDexoptControllerTest.kt` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.ouujKhiP.log` | FIXED |
| T3 | `18149a7` | `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/status/UnplugSummary.kt`<br>`core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/status/UnplugSummaryCalculatorTest.kt` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.h2HOIR4r.log` | FIXED |
| TOOL-1 | `5ee78c3` | `.codex/android-kit/board.py`<br>`android-kit-codex/template/.codex/android-kit/board.py`<br>`android-kit-codex/tests/test_kit.py` | `python3 -m unittest discover -s android-kit-codex/tests -v; bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.v44NbucT.log`; 18 tests `.codex/android-kit/logs/remediation/TOOL-1-tests.log` | FIXED |
| TOOL-2 | `26de410` | `.claude/skills/device-probe/scripts/device-probe.sh` | `bash -n .claude/skills/device-probe/scripts/device-probe.sh; python3 .codex/android-kit/logs/remediation/check_probe.py; bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.kByso1rO.log`; isolated probe log `.codex/android-kit/logs/remediation/TOOL-2-final-tests.log` | FIXED |
| UI-1 | `995945f` | `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitMapper.kt`<br>`feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitModels.kt`<br>`feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitText.kt`<br>`feature/toolkit/src/main/res/values/strings.xml`<br>`feature/toolkit/src/main/res/values-tr/strings.xml`<br>`feature/toolkit/src/test/kotlin/com/akane/inspiration/toolkit/ToolkitViewModelTest.kt`<br>`feature/toolkit/src/screenshotTestDebug/reference` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.Z5dgBwGj.log`; screenshot update + validation `.codex/android-kit/logs/gradle.H7kdihxt.log` | FIXED |
| UI-2 | `a76f98b` | `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitViewModel.kt`<br>`feature/toolkit/src/test/kotlin/com/akane/inspiration/toolkit/ToolkitViewModelTest.kt` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.ttswBkxb.log` | FIXED |
| T4 | `a0275d9` | `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/ReferenceResolver.kt`<br>`core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/diff/SnapshotDifferTest.kt` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.4wWOMG5U.log` | FIXED |
| UI-3 | `f4365f0` | `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/math/ScrubGestureReducer.kt`<br>`core/designsystem/src/test/kotlin/com/akane/inspiration/designsystem/chart/math/ScrubGestureReducerTest.kt` | `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`; log `.codex/android-kit/logs/gradle.Fk78WC3m.log` | FIXED |


### Final remediation verification — 2026-10-18

All **20 confirmed IDs are FIXED**, with no skipped or blocked findings. The table above records the isolated commits, modified paths and per-finding gates. The lint prerequisite was committed separately. Source tip: `f4365f0` on `fix/audit-remediation-20261018`.

Final command:
```bash
bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --rerun validateDebugScreenshotTest --rerun --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g
```

**PASS**, exit 0: `.codex/android-kit/logs/gradle.nK4SlGL9.log`. Fresh results: **1,023 unit tests and 167 screenshot comparisons, zero failures/errors/skips**, all 87 unit XML reports and all 7 screenshot XML reports fresh. Machine-readable per-module totals: `.codex/android-kit/logs/remediation/final-results.json`. Local tooling: **18 isolated kit tests passed**; probe startup, immediate failure and timeout checks passed using fake commands, without running a real device probe. Their logs are listed in the finding table.

UI-1 intentionally updated six toolkit Ready golden images after reviewing narrow, Turkish and large-font renders. Pixel differences were confined to badge regions and image dimensions were unchanged. Its update/validation passed in `.codex/android-kit/logs/gradle.H7kdihxt.log`; the final all-module comparison also passed. No other reference images changed.

Implementation decisions and limits:

- **DB-1:** Schema **3**, with a tested 2→3 migration, clears uncertifiable derived charge sessions and measured-health estimates once. Raw kernel health fields remain. Charge sessions are recomputed from retained, epoch-partitioned history; older source history outside retention cannot be reconstructed.
- **T2:** Display-only history remains bounded; pending writes and successful override/restore chains are retained beyond 500 rows, and recovery reads no longer truncate them. This is the conservative retention fix, not the proposed separate durable override-table refactor. Chain compaction remains architectural debt. Baseline values already deleted by an older version cannot be recovered or invented and need manual verification.
- **IPC-2/3:** Host tests prove the queued-worker cancellation ordering and monotonic supervision invariants. They do not certify real ART/system-server cancellation behavior on every device.
- **TOOL-1:** Timeout cleanup terminates the verifier's process group. Independently detached daemons/system services remain outside that guarantee.
- **TOOL-2:** The user explicitly approved the single `.claude/skills/device-probe/scripts/device-probe.sh` change despite the general Claude-setup restriction. Claude settings, hooks, routing and Sidequest state were not modified.
- The initial concurrent screenshot attempt lost its Gradle daemon. Idle compiler/Gradle JVMs were stopped; all subsequent gates used one worker and a 2 GiB heap and completed. No unavailable check was converted into a pass.
- No release/minification/signing test or privileged device smoke test was performed. Section 3 cleanup opportunities and the remaining broader architectural/testing roadmap are recorded as debt, not additional unresolved confirmed IDs.

### Original audit assessment


Confirmed findings: **20 total — 0 critical, 5 high, 13 medium, 2 low.** These are verified call-chain defects or targeted reproductions, not a claim that every listed failure was reproduced on a device. Proposed code is reviewable remediation, not implemented or regression-tested fixes.

| Dimension | Score | Assessment |
|---|---:|---|
| Architecture & Modularity | 7/10 | Clear module boundaries and typed feature contracts, but recovery state is incorrectly derived from a disposable audit log. |
| Correctness & Resilience | 5/10 | Extensive unit tests coexist with missing error-boundary, history-provenance and measurement-availability guarantees. |
| Concurrency & Resource Safety | 5/10 | Structured scopes and bounded I/O are common, but main-thread blocking, Binder lifecycle exceptions and dexopt races remain reachable. |
| Security & Privilege Hygiene | 7/10 | Caller checks, argument allowlists, fixed command vectors and private persistence are present; privileged cancellation/recovery still needs hardening. |
| Performance & Efficiency | 7/10 | Visibility-controlled collection and retention limits help, while unused dumps and quadratic history work add avoidable cost. |

Scores are qualitative review judgments, not benchmark measurements or security certifications.

**Top 3 systemic risks**

1. **Privileged operations outlive reliable bookkeeping:** lost replies are incorrectly terminal failures, and log retention deletes the only restoration baseline (T1, T2). A setting may stay changed while Reset All cannot see it.
2. **Lifecycle failures escape their boundaries:** broadcast capture blocks the main thread, storage failures escape app-open launches, and Shizuku binding exceptions escape UI/service ownership paths (APP-1, APP-2, IPC-1).
3. **Diagnostic certainty exceeds evidence:** epoch/lifecycle boundaries can produce invented charge measurements; absent history can become a zero-use/all-clear story; sampled and historical charge sources disagree (DB-1, DIAG-1, FEAT-1/2).

### Phase 1 — Reconnaissance and git forensics

Recon ran before the deep subsystem review. Phase 2 baseline commands and security scans were started before dispatching subsystem reviewers; baseline execution continued while independent read-only reviews ran. The initial all-module lint/unit selector completed before findings were consolidated; fresh tests were then forced because the initial reports were reused.

- Twelve Android modules: app; core hidden-api-stub, shizuku-ipc, telemetry-engine, diagnostics, database, designsystem; feature dashboard, xray, sleeplab, health, toolkit. Three convention plugins live in the included build-logic build.
- Declared stack: Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.20, compileSdk 37, targetSdk 36, minSdk 31, Java target 17; runtime JDK 21. Compose, coroutines, manual AppContainer injection, Room schema **2** with explicit 1→2 migration, AIDL/Shizuku and shell-UID helpers. The saved map's schema-1 fact was corrected.
- `git ls-files`, `rg --files`, per-extension line counts, manifest/build inventory, `git log` history/churn and bug-keyword clusters were collected. Generated build/cache directories were excluded from manual source coverage. No CI workflow was present. Wrapper checksum and HTTPS repository configuration were inspected.
- Main entry points: Application and launcher Activity; runtime and boot broadcast receivers; charging JobService; notification foreground service; Shizuku provider and ITelemetryUserService; debug-only probe/gallery; operator-invoked kit scripts.
- Debt-marker scan found suppression annotations and a test name containing “unsafe”; no TODO/FIXME list was promoted into findings. Suppressions were reviewed in context, not treated as vulnerabilities.

Source counts below include tests and screenshot Kotlin, not generated files:

| Area | Kotlin files / lines | Other source |
|---|---:|---|
| app | 47 / 4,674 | 13 XML files; build script |
| core/shizuku-ipc | 48 / 7,108 | 14 AIDL files / 76 lines |
| core/hidden-api-stub | 0 | 12 Java files / 308 lines |
| core/telemetry-engine | 44 / 8,783 | 4 vendored proto definitions plus binary parser fixtures |
| core/database | 6 / 2,087 | 2 exported schemas, 18 tables each |
| core/diagnostics | 34 / 3,275 | Dictionary JSON and localized resources |
| core/designsystem | 59 / 6,265 | Fonts, localized chart resources and golden images |
| feature/dashboard | 16 / 2,455 | English/Turkish resources |
| feature/xray | 14 / 3,328 | English/Turkish resources |
| feature/sleeplab | 16 / 2,726 | English/Turkish resources |
| feature/health | 14 / 3,034 | English/Turkish resources |
| feature/toolkit | 16 / 4,206 | English/Turkish resources |
| build-logic | 3 / 98 | Convention build/settings scripts |
| tracked Claude helpers | — | 6 Python files / 972 lines; 3 shell files / 146 lines |

Top 25 source-history hotspots, using the last 500 commits (repository has 219 commits):

```text
      9 core/database/src/test/kotlin/com/akane/inspiration/database/store/RoomSnapshotStoreTest.kt
      8 core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/diff/SnapshotDifferTest.kt
      8 core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/capture/SnapshotCapturerTest.kt
      8 core/database/src/main/kotlin/com/akane/inspiration/database/Daos.kt
      7 feature/toolkit/src/test/kotlin/com/akane/inspiration/toolkit/ToolkitViewModelTest.kt
      7 core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt
      7 core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/snapshot/SnapshotModels.kt
      7 core/database/src/main/kotlin/com/akane/inspiration/database/store/RoomSnapshotStore.kt
      7 core/database/src/main/kotlin/com/akane/inspiration/database/Entities.kt
      7 app/src/main/kotlin/com/akane/inspiration/AppContainer.kt
      7 app/src/debug/kotlin/com/akane/inspiration/debug/ProbeReceiver.kt
      6 feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitViewModel.kt
      6 core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/SnapshotDiffer.kt
      6 core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/capture/SnapshotCapturer.kt
      6 core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/toolkit/ToolkitExecutorTest.kt
      6 core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/service/TelemetryUserService.kt
      6 core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/ShizukuManagerImpl.kt
      5 feature/toolkit/src/test/kotlin/com/akane/inspiration/toolkit/ToolkitFixtures.kt
      5 core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/ShizukuManagerImplTest.kt
      5 core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/ToolkitExecutor.kt
      5 core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/hypnogram/SleepHypnogram.kt
      5 app/src/main/kotlin/com/akane/inspiration/MainActivity.kt
      5 app/src/main/kotlin/com/akane/inspiration/InspirationApplication.kt
      4 feature/xray/src/test/kotlin/com/akane/inspiration/xray/XRayFixtures.kt
      4 core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitGatewayTest.kt
```

Bug-fix clusters (`fix|bug|crash|leak|race|deadlock|revert`) concentrate in ToolkitActions/ToolkitExecutor, ShizukuManagerImpl/TelemetryUserService, Room storage, snapshot capture/diff and chart gestures. The complete raw top-25 cluster list is retained in the evidence directory. These hotspots received caller/callee and test-path review; churn alone is not a finding.

### Phase 2 — Deterministic verification

| Check | Observed result | Evidence |
|---|---|---|
| Kit doctor and stack detector | Passed filesystem/tool availability checks; not an Android behavior pass | Commands `python3 .codex/android-kit/doctor.py`, `bash .codex/android-kit/detect-stack.sh` |
| `lintDebug testDebugUnitTest --continue --max-workers=2` | **Exit 1**, nine lint errors in five modules; initial tests reused outputs | `.codex/android-kit/logs/gradle.TNYcdNUC.log` |
| `testDebugUnitTest --rerun --max-workers=2` | **Passed: 991 tests, 0 failures, 0 errors, 0 skips; all 87 XML reports fresh** | `.codex/android-kit/logs/gradle.SlSFi8yK.log`; per-module `build/test-results/testDebugUnitTest` |
| Runtime dependency resolution | Passed `:app:dependencies --configuration debugRuntimeClasspath` | `.codex/android-kit/logs/gradle.6tCnSEzJ.log` |
| OSV API scans | No matches returned for 24 explicit catalog coordinates and 122 resolved runtime coordinates | Evidence `osv.json`, `osv-runtime.json`; https://api.osv.dev/v1/querybatch |
| Local kit tests | 17 tests passed in isolated temporary copies | `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s android-kit-codex/tests -v` |
| Script syntax | 27 Python files compiled in memory, 8 shell scripts passed `bash -n` | Tooling review evidence |
| Room migration SQL | Host SQLite replay preserved old cycle count and accepted NULL; foreign-key check empty | Database reviewer probe; this is separate from actual Room unit tests |
| Targeted logic/race probes | Reproduced diagnostics, charge-segment, status-anchor, toolkit label/gesture and dexopt failures using built classes; isolated tooling hang/orphan probes | Source/output retained under `.codex/android-kit/logs/full-repo-audit/` |
| Screenshot validation | **Passed**, 167 results with zero failures/errors/skips: 76 fresh comparisons in app/designsystem/dashboard, 91 reused results in other feature modules; references unchanged | `.codex/android-kit/logs/gradle.apFs2oG1.log`; per-module `build/test-results/validateDebugScreenshotTest` |

OSV results mean only that the queried package/version identifiers returned no listed advisories at audit time. They do not prove exploit absence, assess vendored code, or cover all build-plugin/test-only transitives. No installed gitleaks, semgrep, trivy or osv-scanner binary was found; targeted source scans, manifest review and OSV HTTP queries were used instead. Secret-bearing local configuration/keystore files were deliberately not read or printed. No app secret was identified in reviewed application source. Device enumeration found two adb targets, but **no privileged device action, real ANR injection, Shizuku smoke test, release/minification build, signing check or OEM behavior verification was performed**.

Fresh unit-test totals:

| Module | Tests |
|---|---:|
| app | 85 |
| core/database | 27 |
| core/designsystem | 100 |
| core/diagnostics | 132 |
| core/shizuku-ipc | 187 |
| core/telemetry-engine | 219 |
| feature/dashboard | 35 |
| feature/health | 58 |
| feature/sleeplab | 53 |
| feature/toolkit | 49 |
| feature/xray | 46 |

Hidden-api-stub and build conventions have no dedicated unit tests; stubs are compileOnly and have no shipped implementation. Screenshot fixtures are not lifecycle or device tests.

**Lint errors (tool output, not nine separately claimed runtime bugs):**

- `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/scrub/ScrubReadout.kt:42` — `NonObservableLocale`.
- `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/scrub/SynchronizedScrubbingChart.kt:161` — `NonObservableLocale`.
- `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/component/format/TelemetryFormat.kt:173` — `NonObservableLocale`.
- `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitText.kt:25` — `NonObservableLocale`.
- `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitRoute.kt:50` — `LocalContextGetResourceValueCall`.
- `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayText.kt:41` — `NonObservableLocale`.
- `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabRoute.kt:39` — `NonObservableLocale`.
- `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitCards.kt:94` — `NonObservableLocale`.
- `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitMarkerSheet.kt:102` — `LocalContextGetResourceValueCall`.

The locale diagnostics include fallback calls after an observable configuration read, so their runtime severity was not assumed from lint alone. They still fail the native lint gate. Resolve with the observable locale/resource APIs identified by lint and verify dynamic locale changes; do not add a blanket baseline suppression. Other warnings included PrivateApi (intentional hidden-framework integration), locale delivery, SDK/resource optimization and available dependency versions. Lint reported Gradle 9.8.1, Kotlin 2.4.21, HiddenApiBypass 6.2, Robolectric 4.17 and annotation 1.11.0 as newer; “newer” is not evidence that the pinned version is vulnerable. No versions were changed.

### Phase 3/4 — Six-domain coverage and false-positive filtering

Each row was reviewed across all six domains. “No new finding” means the reviewed paths did not establish a defect, not that the subsystem is proven safe. Findings span subsystem boundaries; each root cause is listed once.

| Subsystem | Architecture/lifecycle | Concurrency/resources | Security/trust | Errors/edge cases | Performance/power | Test integrity/blind spots |
|---|---|---|---|---|---|---|
| App, DI, shell, capture, status | Application scope, shared analysis, navigation, scheduler and notification ownership | APP-1 main blocking; scopes, job completion, receiver cleanup and cache locks traced | Exported component permissions, immutable PendingIntents, debug-only DUMP guard | APP-2 storage exceptions; T3 anchors; DB-1 charge provenance | 45-minute charging pacing; bounded label/icon caches; no status full-graph rehydration | Real coordinator/runner tests; missing Android receiver/JobService/storage-failure integration |
| Shizuku, AIDL, hidden stubs | Binder generation, binding holds, service destruction and compileOnly framework boundary | IPC-1/2/3; thread/process ownership, pipes, command deadlines and close paths | UID authorization, package allowlists, argv commands, fixed sysfs and validated settings strings | Dead Binder, stale service, null framework data, partial write and cancel outcomes | Bounded command output and idle unbind; no unconditional permanent telemetry loop | Existing manager/executor tests; daemon-death binding and queued-cancel/clock rollback gaps |
| Telemetry/source/parser/capture | Availability masks, cursor ownership, calibration, capacity, recovery state T1/T2 | Descriptor use, cooperative nonblocking read cancellation, mutex/hold supervision | Typed API and restore decoding traced to service validators | Parser version/malformed input, counter reset, source outage, T3 anchors | T4 suffix allocation; unused power dump; visibility-paced live reads | Real API35–37 fixtures; effect-then-reply-loss, retention and source-adapter gaps |
| Room persistence | Schema/entity mapping, version 2 migration, DB-1 epoch loss | Snapshot read/write/prune transactions, FK cascade/restrict, event-before-cursor replay | Bound DAO parameters and fixed migration SQL; private DB | T2 retention; partial rollback/multi-epoch risks; unknown enum provenance checked | Bulk hydration queries; per-UID rescan optimization deferred | 27 real Room tests; missing retention/recovery, concurrent mutation and fault injection |
| Diagnostics | Shared session/selection and availability DIAG-1; timeline DIAG-2 | mapLatest cancellation, immutable dictionary, reader use, sensor lazy state | Bundled regex patterns/IDs/actions checked; no dynamic executable sink | Reset/start/shutdown, missing/partial history, rule input guards | Repeated scans noted without unsupported runtime claims | Substantive rules/dictionary/story tests; real baseline-marker and empty-history integration missing |
| Dashboard/X-Ray/Sleep Lab/Health | Route/dependency/intent/visibility flows; FEAT-2 duplicated state | Scope cancellation, binding close, X-Ray stale-plan guards | Internal UID targets and typed actions; backend checks traced | FEAT-1/3, empty/unavailable state, null/nonfinite mappings | Chart/row limits and visibility collection; live buffer profiling debt | 192 fresh tests; source merge, identity-change and unplug transition gaps |
| Toolkit and design system | Generation-guarded plans; UI-1 presentation state; UI-2 retry ownership | Visibility and sibling jobs; pointer reducer/adapter UI-3 | Installed-app membership and guarded typed intents; touch guards reviewed statically | Unknown enum/formatting, chart bounds, stale confirmation outcomes | Decimation/cached paths/lazy lists; reduced motion readers | Real domain-backed VM fakes; no platform pointer/touch or in-flight retry coverage |
| Build, assets, docs, local tools | Module graph/conventions, wrapper pins, source/template copies, distribution setup | TOOL-1 subprocess descendants; TOOL-2 device connection wait | HTTPS/checksum, signing properties (values not read), traversal and argv validation | Script syntax, installer conflicts, scaffold staging and migration checks | One-shot scans; sparse local log retention not declared a leak | 17 isolated kit tests; timeout/emulator-start and Python compatibility gaps |

Rejected/downgraded candidates after tracing: main-thread PackageManager reads are dispatched to IO; Room status reads use bare snapshot rows; pipe reads already implement nonblocking cancellation; debug probes are DUMP-protected; history insert-before-cursor is replay-safe; X-Ray plan cancellation is guarded; unavailable dump arrays are removed before Sleep Lab maps them; malformed user SQL/command injection was not found in fixed/typed sinks. Sensor “screen-off only” charging suspicion was withheld because upstream platform counter semantics may invalidate the proposed scenario. Small/bounded quadratic UID hydration, long-lived application Context/Room ownership and sparse lifetime health records were not automatically labelled leaks. Exact source symbol searches and reviewer inventories are preserved with evidence.

## 2. Confirmed Findings (Ordered by Severity: CRITICAL → HIGH → MEDIUM → LOW)

No CRITICAL issue was confirmed. HIGH findings warrant prompt remediation. TOOL findings concern local development workflows, not the APK.

### [APP-1] [HIGH] Light broadcast capture blocks the Android main thread through database persistence

**Status: [FIXED in efc24d9]**

- **Category:** Concurrency / Performance
- **Location:** `app/src/main/kotlin/com/akane/inspiration/capture/RuntimeCaptureReceiver.kt:25-34`.
- **Confidence:** Confirmed by call trace; an actual Android ANR was not reproduced.
- **Trace:** `InspirationApplication.onCreate` lines 37-49 registers `RuntimeCaptureReceiver` with no scheduler, so callbacks execute on main. `CapturePolicy.forBroadcast` lines 28-32 selects Light for every screen-on/off regardless of service warmth (and for cold power/battery events). `onReceive` calls `runBlocking(Dispatchers.IO)`, which moves its body but blocks the calling main thread. `BroadcastCaptureRunner.run` awaits capture without a timeout. `SnapshotCapturer.captureLight` lines 51-54 reads Android battery services then awaits `store.insert`. `RoomSnapshotStore.insert` lines 50-80 opens a Room transaction and inserts the snapshot plus related rows. No upstream wrapper limits the wait. Room transaction contention, database initialization or slow storage therefore blocks input and lifecycle processing; sufficiently long waits cause ANR. This is on the ubiquitous screen-event path, not only an exceptional refresh.
- Offending snippet:
```kotlin
is CapturePlan.Light -> runBlocking(Dispatchers.IO) {
    runner.run(capture = {
        app.appContainer.statusTicker.emitAfter {
            app.appContainer.capturer.captureLight(plan.trigger)
        }
    })
}
```
- **Concrete fix:** remove `Dispatchers` and `runBlocking` imports and replace only the Light branch with this code (existing scope is IO). Match the Full branch's lifetime handling. Timeout bounds suspendable Room work; Android Binder reads themselves are not preemptible, so Android service stalls must still be separately tested.
```kotlin
is CapturePlan.Light -> {
    val pendingResult = goAsync()
    app.appContainer.applicationScope.launch {
        runner.run(
            capture = {
                app.appContainer.statusTicker.emitAfter {
                    withTimeoutOrNull(CapturePolicy.BROADCAST_FULL_DEADLINE_MS) {
                        app.appContainer.capturer.captureLight(plan.trigger)
                    }
                }
            },
            finish = { pendingResult.finish() },
        )
    }
}
```
- **Regression:** Android receiver test with delayed fake persistence must prove onReceive returns immediately and pending result finishes on success, timeout, failure and cancellation. Current `CapturePolicyTest` explicitly calls this a “synchronous light plan” and tests only enum routing; `BroadcastCaptureRunnerTest` covers cleanup/error propagation but not main-thread liveness.

### [APP-2] [HIGH] App-open capture storage failures escape a root launch and terminate the app

**Status: [FIXED in f869731]**

- **Category:** Logic Bug / resilience
- **Location:** `app/src/main/kotlin/com/akane/inspiration/AppContainer.kt:125-133`, related `InspirationApplication.kt:53-55`.
- **Confidence:** Confirmed via call trace; disk-full device failure was not injected.
- **Trace:** `MainActivity.onStart` lines 32-36 -> `InspirationApplication.captureAppOpen` -> `applicationScope.launch { appContainer.refresh() }`. Scope is `SupervisorJob() + Dispatchers.IO` (`AppContainer.kt:86`), without an exception handler. `RefreshCoordinator.refresh` lines 57-75 awaits `capture()` in NonCancellable, but catches no exception. `AppContainer.captureFull` only converts the returned Missed value; thrown failures bypass its when. `SnapshotCapturer.captureFull` lines 35-41 invokes `historyCursor`, `store.insert` and `persistHistory`; even a service-unavailable capture calls `recordMiss` at lines 57-59. All Room failures propagate. Successful capture subsequently invokes `store.prune` at AppContainer line 128, likewise unguarded. A full disk/SQLite IO failure thus escapes this root launch and reaches the Android uncaught exception handler, killing the process each time onStart retries. SupervisorJob isolates child cancellation; it does not consume exceptions. Feature callers' attempt wrappers do not protect the independent application launch. Charging and broadcast paths already handle exceptions, confirming inconsistent entry-point coverage.
- Offending snippets:
```kotlin
appContainer.applicationScope.launch { appContainer.refresh() }
```
```kotlin
private suspend fun captureFull(): RefreshResult =
    when (val result = capturer.captureFull(...)) {
        is CaptureResult.Saved -> {
            store.prune(clocks.wallMs())
            ...
        }
        is CaptureResult.Missed -> RefreshResult.Failed(result.reason)
    }
```
- **Concrete fix:** replace AppContainer.captureFull with below and add `android.util.Log` plus `kotlinx.coroutines.CancellationException` imports. This turns operational failures into the existing failure/backoff contract for every refresh caller, preserves cancellation, and logs without relying on another DB write when storage is broken.
```kotlin
private suspend fun captureFull(): RefreshResult = try {
    when (val result = capturer.captureFull(
        CaptureTrigger.APP_OPEN, FULL_CAPTURE_DEADLINE_MS, ingestHistory = true,
    )) {
        is CaptureResult.Saved -> {
            store.prune(clocks.wallMs())
            emitStatusTick()
            RefreshResult.Refreshed(clocks.wallMs())
        }
        is CaptureResult.Missed -> RefreshResult.Failed(result.reason)
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Log.w("AppContainer", "App refresh failed", error)
    RefreshResult.Failed("capture_failed")
}
```
- **Regression:** inject cursor/insert/history/prune failures through the real refresh adapter, verify Failed is returned and backoff starts without process-level uncaught exception; ensure cancellation remains cancellation. Existing coordinator tests test Failed values, not thrown capture failures; application entry point is not exercised.

### [IPC-1] [HIGH] Shizuku transport failure escapes binding lifecycle, crashes UI and strands ownership

**Status: [FIXED in b58e3bf]**

- **Category:** Concurrency / Logic Bug
- **Location:** `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/ShizukuManagerImpl.kt:341-343`, `346-369`; acquisition sites `206-211`, `230-238`, and `171-178`.
- **Confidence:** Confirmed by complete call trace and dependency bytecode.
- **Trace:** `CockpitViewModel.onVisible():95-99` -> `AppContainer.holdBinding():172` -> `ShizukuManagerImpl.holdBinding()` increments `bindingHolders` -> `bindIfRequiredLocked()` publishes `serviceBound=true` -> `RealShizukuFacade.bindUserService()` -> Shizuku 13.1.5 `requireService()/IShizukuService.addUserService()`. The actual cached dependency's bytecode shows that `RemoteException` is wrapped in **RuntimeException**, and `requireService()` throws IllegalStateException when the daemon disappears. No catch surrounds the main-thread `holdBinding()` caller. Separately, idle release -> `scheduleIdleUnbindLocked()` -> application-scope coroutine -> unbind transport call also escapes; `AppContainer.applicationScope` is SupervisorJob + IO without exception handler. SupervisorJob does not consume unhandled exceptions.
- Offending code:
  ```kotlin
  serviceArgs = args
  serviceBound = true
  facade.bindUserService(args, userServiceConnection)
  // ...
  if (serviceBound) {
      facade.unbindUserService(args, userServiceConnection, true)
      // Local cleanup is only reached if the remote call succeeds.
  }
  ```
- **Failure:** daemon death/permission revocation between the last Ready state and bind/unbind produces an app crash during navigation or idle cleanup. A caught bind failure at other callers still leaks `activeServiceUsers` because acquisition is before the try/finally; failed hold acquisition leaks `bindingHolders` because no handle is returned. `serviceBound` can remain true despite no service, suppressing subsequent binds. The death callback does not undo leaked owner counts.
- Dependency proof: `.codex/android-kit/logs/full-repo-audit/shizuku-bytecode.txt:804-826` (bind), `876-921` (unbind), `648-653` (requireService), `673-679` (wrapper).
- **Concrete fix:** contain exceptions at the facade lifecycle boundary and clear transport state before returning, so acquire/hold always reaches its matching release/handle. Do not put `runCatching` only around the block in `withTelemetryService`: acquisition happens before that block. The following helper and replacements repair the confirmed bind/unbind paths; apply the same transport normalization to state-refresh/permission RPCs in a follow-up boundary cleanup.
  ```kotlin
  private fun transportLostLocked() {
      serviceBound = false
      serviceArgs = null
      serviceGeneration += 1
      awaitingUnboundDeath = false
      unboundDeathOwed = false
      settleTimeout?.cancel()
      settleTimeout = null
      idleUnbind?.cancel()
      idleUnbind = null
      applicationInfo = null
      mutableTelemetryService.value = null
      mutableState.value = ShizukuState.NotRunning
  }
  ```
  ```diff
  @@ bindIfRequiredLocked
       serviceArgs = args
       serviceBound = true
  -    facade.bindUserService(args, userServiceConnection)
  +    try {
  +        facade.bindUserService(args, userServiceConnection)
  +    } catch (_: RuntimeException) {
  +        // Shizuku wraps RemoteException; death callback may arrive later.
  +        transportLostLocked()
  +    }
  @@ unbindTelemetryServiceLocked
       if (serviceBound) {
  -        facade.unbindUserService(args, userServiceConnection, true)
  +        try {
  +            facade.unbindUserService(args, userServiceConnection, true)
  +        } catch (_: RuntimeException) {
  +            transportLostLocked()
  +            return
  +        }
  ```
  This catch is intentionally at the external API boundary rather than around arbitrary user blocks. Preserve ownership counts: live callers/handles still release them normally. Add fake-facade tests throwing on bind, unbind, and daemon loss between ping and the next operation; assert no uncaught exception, no stale service and owner counts balance after callback/reconnect.

### [T1] [HIGH] Lost toolkit replies permanently erase recovery eligibility after a real privileged write

**Status: [FIXED in feedebd]**

- **Category:** Logic Bug / Architecture.
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt:581-588`, related `ToolkitGateway.kt:112-129`, `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/ShizukuManagerImpl.kt:188-199`, `ToolkitActions.kt:295-308,765-785,853-879`.
- **Confidence:** confirmed by call trace, not device reproduction.
- **Trigger and trace:** Toolkit screen maps a request to `ToolkitActions.execute` (`feature/toolkit/.../ToolkitViewModel.kt:331-334`); `run` records PENDING with the live before value, then calls backend; Gateway invokes Binder; `TelemetryUserService.setStandbyBucket` forwards to `ToolkitExecutor.setStandbyBucket` (`ToolkitExecutor.kt:69-83`), which executes the actual system command before readback/reply. If the shell service dies after the successful command but before reply, `ShizukuManagerImpl.withTelemetryService` catches `RemoteException` and returns null. It also returns null after a completed block when generation has changed. Gateway produces NO_SERVICE, and settlement executes:

```kotlin
audit.update(pending.copy(status = ToolkitActionStatus.FAILED, endedAt = now, code = code))
return ToolkitOutcome.Unavailable(unavailableFor(reply.reason))
```

`reconcile()` only loads `audit.pending()`. Overrides only include DONE/RESTORED rows. Thus a real `RESTRICTED` or `WAKE_LOCK=ignore` setting remains applied but is absent from active overrides, cannot be restored (`decodeRestore` rejects FAILED), and is never read back on reconnection. This is not mitigated by the existing write-ahead record: terminally marking it FAILED defeats its recovery purpose. `GatewayUnavailable`'s claim that nothing ran is false for this reachable post-dispatch loss.

- **Concrete remediation:** track whether dispatch began in Gateway, distinguish `NOT_DISPATCHED` and `OUTCOME_UNKNOWN`, and retain the latter as PENDING until readback settles it. The following conservative drop-in hotfix avoids data loss with the existing result types (may leave an actually unexecuted operation pending until reconnect, when existing verify will settle it):

```diff
--- a/core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt
+++ b/core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt
@@
-            audit.update(pending.copy(status = ToolkitActionStatus.FAILED, endedAt = now, code = code))
+            // A Binder failure can occur after the command committed. Only readback can settle it.
+            audit.update(pending.copy(status = ToolkitActionStatus.PENDING, endedAt = null, code = code))
             return ToolkitOutcome.Unavailable(unavailableFor(reply.reason))
```

Apply this companion diff to cover both `run` and `startBgDexopt` exception handlers (both already call `failPending`):

```diff
--- a/core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt
+++ b/core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt
@@
     private suspend fun failPending(pending: ToolkitActionRecord) {
-        audit.update(pending.copy(status = ToolkitActionStatus.FAILED, endedAt = nowMs(), code = ToolkitClientCode.WRITE_ERROR))
+        // An exception after dispatch does not prove that the privileged write failed.
+        // Retain before/param and let reconcile() read the actual state on reconnect.
+        audit.update(pending.copy(status = ToolkitActionStatus.PENDING, endedAt = null, code = ToolkitClientCode.WRITE_ERROR))
     }
```

For dexopt, retain the uncertain PENDING row as well: `refreshDexoptLocked` already acquires a hold only on an observed RUNNING state and finalizes observed terminal states. Do not invent a RUNNING state or an indefinite hold merely because start lost its reply. The Gateway's existing 30-second bridge, if already acquired, remains bounded. Reconnect reconciliation or the next status collection reads the real status and takes the existing supervised hold when appropriate. This fixes recovery eligibility; it cannot guarantee continuation of a job whose supervising service actually died. Update the existing exception test to expect PENDING followed by readback settlement, and add both pre-effect and post-effect failure scenarios. Immediate readback after uncertain errors, plus existing reconnect reconciliation, closes recovery without blindly repeating a privileged command. UI must label PENDING uncertain rather than claim rollback/success. Stronger protocol: per-operation IDs and service-side completed-operation receipt/readback for exact attribution.

- **Regression:** fake backend must change a bucket and *then* return Unavailable or throw; reconnect+reconcile must expose the restored DONE row and original before value. Current `aBackendExceptionFinalizesTheRowAsFailedAndIsRethrown` tests only pre-write exceptions and enshrines the unsafe generalization. Gateway tests cover missing/stale services, null replies and thrown errors, but do not test effect-then-reply-loss. Add generation-change-after-command case to manager integration coverage.

### [T2] [HIGH] Bounded audit history is also the only persistent restore state, so ordinary history pruning loses active restrictions

**Status: [FIXED in ffcef24]**

- **Category:** Architecture / Logic Bug.
- **Location:** `core/database/src/main/kotlin/com/akane/inspiration/database/Daos.kt:197-205`; `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt:696-711,744-753,765-785,1131`.
- **Confidence:** confirmed by query and complete call trace; persistence reviewer independently corroborated the query.
- **Trace:** every toolkit attempt, including refused attempts, calls `SnapshotStoreAudit.insert` -> `RoomSnapshotStore.insertToolkitAction` (`RoomSnapshotStore.kt:171-172`) -> DAO `insertAndPrune`. The exact query is:

```sql
DELETE FROM toolkit_action
WHERE id NOT IN (SELECT id FROM toolkit_action ORDER BY id DESC LIMIT :keep)
```

`keep=500`, irrespective of status or active effects. `activeOverrides`, `resetAll` and `decodeRestore` reconstruct current responsibility and the original before value solely from these retained audit rows; `MAX_ROWS=500` also limits reads. No separate durable override state exists.

- **Failure:** restrict app A or set a custom Doze profile, then generate 500 later actions/refusals against other apps. The only row proving A's override and original state is deleted. The device remains restricted, but active overrides and Reset All omit A. A multi-step chain can instead lose its earliest before value and restore an intermediate restricted bucket/profile rather than the user's original state. Pending actions can also be pruned before recovery. The bounded history is therefore corrupting still-live state, not merely trimming a log.

- Concrete architectural remediation (requires a schema migration and transaction tests, not a one-line larger cap): add a durable `toolkit_override` table keyed by `(kind,pkg,op)` with originalRaw, currentRaw, actionId, startedAt, status; represent pending operation records separately from bounded display history, or exclude them from pruning. On the *first* transition for a target, insert the original before state; on subsequent transitions update currentRaw/actionId while retaining originalRaw; on confirmed restore/external-state reconciliation remove the active record only when ownership ends. Persist these updates and audit settlement in a Room transaction. Query activeOverrides and resetAll from this table; bounded audit rows remain display-only. Migration must fold existing DONE/RESTORED chains in chronological order using the existing sameState/restore semantics, and conservatively preserve PENDING rows; already-pruned originals cannot be invented and must be marked unrestorable. Before that migration, a safe temporary hotfix is to disable pruning of pending/override-chain rows AND remove the 500-row limit from recovery queries; merely skipping deletion but leaving `MAX_ROWS` unchanged does not fix it. A production implementation must retain both current responsibility and original state independently of display-log size.

- Concrete interim hotfix while the durable-state migration is prepared: preserve every pending row and every override-kind chain row (including restore rows) in SQL; only prune terminal non-override history. Recovery must read all retained rows, while public history(limit) continues to use the explicit UI limit. The exact changes below address both destructive pruning and the client read cap:

```diff
--- a/core/database/src/main/kotlin/com/akane/inspiration/database/Daos.kt
+++ b/core/database/src/main/kotlin/com/akane/inspiration/database/Daos.kt
@@
-    /** Deletes everything but the newest [keep] rows. */
-    @Query("DELETE FROM toolkit_action WHERE id NOT IN (SELECT id FROM toolkit_action ORDER BY id DESC LIMIT :keep)")
+    /** Preserve recovery state and override chains until they have a separate durable store. */
+    @Query(
+        "DELETE FROM toolkit_action WHERE status != 'pending' " +
+            "AND kind NOT IN ('appop', 'bucket', 'doze') " +
+            "AND id NOT IN (SELECT id FROM toolkit_action ORDER BY id DESC LIMIT :keep)",
+    )
     suspend fun pruneTo(keep: Int): Int
--- a/core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt
+++ b/core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt
@@
-        const val MAX_ROWS = 500
+        // Recovery cannot truncate the retained override chains. history(limit) remains bounded.
+        const val MAX_ROWS = Int.MAX_VALUE
--- a/core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/store/SnapshotStore.kt
+++ b/core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/store/SnapshotStore.kt
@@
-    /** Inserts [record] (its id is ignored), prunes the table to the newest 500 rows and returns the new id. */
+    /** Inserts [record], pruning only old terminal non-override history; recovery rows remain durable. */
     suspend fun insertToolkitAction(record: ToolkitActionRecord): Long
```

Changing the single recovery constant covers every `audit.recent(MAX_ROWS)` call: `decodeRestore`, `overrideRows`, and original-row lookup in `reconcileRow`. It does not change DAO's display-history retention constant or `history(limit)`. This interim fix intentionally permits retained override chains to grow; it trades memory/storage growth for preventing irreversible loss of restore state. Ship the separate durable override table next to compact settled chains safely rather than reinstating a blind cap. Existing count-only Room test must be rewritten to assert bounded *eligible* history alongside preserved recovery rows.

- **Regression:** apply one override, insert >500 unrelated refused actions, assert activeOverride and Reset All still restore original; also retain a pending operation and a multi-step chain crossing the pruning boundary. Existing Room test `toolkit actions are pruned to the newest 500 rows on insert` asserts only the bound, not preserved active state. Toolkit fakes do not model this persisted pruning interaction.

### [DB-1] [MEDIUM] Charge analysis discards epoch/lifecycle boundaries and persists invented charging sessions

**Status: [FIXED in 198bd27]**

- **Category:** Logic Bug
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/capacity/CapacityEstimator.kt:49-59`; `app/src/main/kotlin/com/akane/inspiration/capture/ChargingCaptureRunner.kt:44-59`; `core/database/src/main/kotlin/com/akane/inspiration/database/store/RoomSnapshotStore.kt:122-123,510-519`.
- **Confidence:** Confirmed via complete call trace and reproduction against compiled repository classes.
- **Root cause & execution trace:**
  1. Framework history adapter produces RESET, START and SHUTDOWN events (`BatteryHistory.kt:120-122`) and exposes page statsStartMs (`:88`). These boundaries are real supported production input, not invented external DTO variants.
  2. `SnapshotCapturer.persistHistory` (`:62-65`) inserts events under their page epoch. `HistoryEventEntity.statsStartMs` stores that epoch, but `RoomSnapshotStore.historyBetween` maps all queried entities into `HistoryEvent`, whose `toDomain` drops epoch provenance (`:510-519`). Old events remain up to 30 days across battery-history resets.
  3. `ChargingCaptureJobService.kt:59-64` calls `ChargeHealthPipeline.run`; `evaluateSegments` queries *all* stored epochs and passes them together to `ChargeSegmentExtractor.fromHistory`. The extractor executes `for (event in events.sortedBy(HistoryEvent::historyTimeMs))`, while `else -> if (active.isNotEmpty()) active += event` treats RESET/START/SHUTDOWN as ordinary samples. Separate history-clock epochs can interleave; even a single monotonic history bridges explicit shutdown/start boundaries.
  4. Pipeline uses the **latest** `historyCursor()?.first` for every resulting session rather than each originating epoch. `store.upsertChargeSession` persists the invented segment; `CapacityEstimator.evaluate` checks SOC/charge/temperature/design bounds but does not reject lifecycle discontinuity. `recordHealth` combines accepted estimates into persisted measuredMah.
- **Concrete reproduction:** `.codex/android-kit/logs/full-repo-audit/ChargeEpochProbe.java` was compiled with javac and run against the current built-in Kotlin debug class directories for telemetry-engine and shizuku-ipc plus Android SDK API 37 stubs and Kotlin stdlib 2.4.20. No rewritten model of the algorithm was used.
  - Input A: old session PLUGGED(history=100, wall=10000,20%,1,000,000uAh), UNPLUGGED(200,10100,80%,4,000,000); new epoch PLUGGED(50,20000,20%,1,000,000), UNPLUGGED(150,20100,80%,3,700,000). Actual result is **one hybrid** old-plug→new-unplug session, not the two genuine sessions.
  - Input B: PLUGGED(100,10000,20%,1,000,000), SHUTDOWN(120,10020,30%,1,500,000), START(130,20000,40%,2,000,000), UNPLUGGED(200,20100,80%,3,700,000). Actual result again bridges shutdown/start instead of discarding incomplete observations.
  - Both produced `ChargeSegment(startHistoryTimeMs=100, startWallMs=10000, endWallMs=20100, startPct=20, endPct=80, deltaChargeUah=2700000, integratedMah=null, maxTemperatureDeciC=300)` and `SegmentVerdict(estimateMah=4500.0, independent=true, accepted=true, rejectReason=null)` against a 5000mAh kernel/design value.
- **Failure scenario:** Charging across a reboot/history reset can be counted as a complete independent measurement despite an unobserved discontinuity. Multiple retained epochs with overlapping history clocks can instead lose true sessions and manufacture hybrids. Incorrect accepted measurements permanently influence health EMA/confidence and retained session history. This is not a crash/security issue; it is silent diagnostic-data corruption. The overlapping-clock subcase depends on an actual reset of the framework history coordinate, while the explicitly emitted lifecycle-marker subcase does not.
- **Concrete fix:** Preserve stored epoch at the persistence boundary, extract per epoch, save the corresponding epoch and terminate any active segment on RESET/START/SHUTDOWN. Keep the existing wall-boundary duplicate check to avoid reaccepting physical events that framework re-reports. The following production-code patch addresses both demonstrated cases; fake stores that retain multi-epoch events should override the added API too.

```diff
--- a/core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/store/SnapshotStore.kt
+++ b/core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/store/SnapshotStore.kt
@@
     suspend fun historyBetween(fromWallMs: Long, toWallMs: Long): List<HistoryEvent>
+
+    /** Stored history partitioned by its original stats epoch. Default for single-epoch stores. */
+    suspend fun historySessions(): Map<Long, List<HistoryEvent>> =
+        mapOf((historyCursor()?.first ?: 0L) to historyBetween(0L, Long.MAX_VALUE))
--- a/core/database/src/main/kotlin/com/akane/inspiration/database/store/RoomSnapshotStore.kt
+++ b/core/database/src/main/kotlin/com/akane/inspiration/database/store/RoomSnapshotStore.kt
@@
     override suspend fun historyBetween(fromWallMs: Long, toWallMs: Long): List<HistoryEvent> =
         database.historyDao().between(fromWallMs, toWallMs).map { it.toDomain() }
+
+    override suspend fun historySessions(): Map<Long, List<HistoryEvent>> =
+        database.historyDao().between(0L, Long.MAX_VALUE)
+            .groupBy { it.statsStartMs }
+            .mapValues { (_, rows) -> rows.map { it.toDomain() } }
--- a/app/src/main/kotlin/com/akane/inspiration/capture/ChargingCaptureRunner.kt
+++ b/app/src/main/kotlin/com/akane/inspiration/capture/ChargingCaptureRunner.kt
@@
-        val statsStartMs = store.historyCursor()?.first ?: 0L
@@
-        val segments = ChargeSegmentExtractor.fromHistory(store.historyBetween(0L, Long.MAX_VALUE))
+        val segments = store.historySessions().flatMap { (epoch, events) ->
+            ChargeSegmentExtractor.fromHistory(events).map { epoch to it }
+        }
             // Wall times, not (statsStart, historyTime): a battery-stats reset changes the epoch key but not these.
-            .filter { (it.startWallMs to it.endWallMs) !in knownKeys }
-            .sortedBy { it.endWallMs }
-        for (segment in segments) {
+            .filter { (_, segment) -> (segment.startWallMs to segment.endWallMs) !in knownKeys }
+            .sortedBy { it.second.endWallMs }
+        for ((statsStartMs, segment) in segments) {
--- a/core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/capacity/CapacityEstimator.kt
+++ b/core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/capacity/CapacityEstimator.kt
@@
             when (event.kind) {
+                HistoryKind.RESET, HistoryKind.START, HistoryKind.SHUTDOWN -> active.clear()
                 HistoryKind.PLUGGED -> active = mutableListOf(event)
@@
         val start = first()
         val end = last()
+        if (start.wallMs < 0L || end.wallMs <= start.wallMs) return null
         if (end.levelPct - start.levelPct < MINIMUM_SOC_RISE_PCT) return null
```

Recovery: existing charge_session rows cannot be reliably certified because original historical rows were attributed to the then-current cursor epoch. In a migration/recovery release invalidate derived charge_session rows and measuredMah values in battery_health_sample, recompute charge sessions from retained source events, and preserve raw kernel health fields; document that sessions older than retained source history cannot be reconstructed. Do this atomically in a database-version migration rather than clearing on each app launch. Add Room-backed multi-epoch tests and RESET/START/SHUTDOWN extraction tests plus verify the two probe cases before rollout. Current tests cover only ordinary charge segments and do not enforce provenance barriers.

### [DIAG-1] [MEDIUM] Story turns missing history and unavailable diagnostics into a factual zero-use/all-clear narrative

**Status: [FIXED in 5cc15f7]**

- **Category:** Logic Bug
- **Location:** `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/story/BatteryStoryBuilder.kt:11-18`; related `analysis/IntervalAnalyzer.kt:40-44`, `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/interval/IntervalMetrics.kt:38-48,116-133`, `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitCards.kt:90-98`.
- **Confidence:** Confirmed by complete call trace and actual compiled-class reproduction.
- **Root cause / trace:**
  1. `RuntimeCaptureReceiver` receives POWER_DISCONNECTED and other events; when service is cold, `CapturePlan.Light` invokes `SnapshotCapturer.captureLight`, which stores battery levels but no history. This is normal supported behavior without Shizuku.
  2. On subsequent application startup, `AppContainer` creates `AnalysisSession`; its `refreshTicks.onStart { emit(INITIAL_TICK) }` resolves persisted snapshots before a successful privileged refresh is required.
  3. `ReferenceResolver.resolve` can resolve SinceUnplugged using `latestByTrigger(POWER_DISCONNECTED)` even with zero history; LIGHT endpoints are allowed and set perUidAvailable=false.
  4. `IntervalAnalyzer` passes the empty history into `IntervalMetricsCalculator.compute`. The returned object is non-null, with startLevel/endLevel null and levelUsedPct=0; all four diagnostic rules can be marked notEvaluable.
  5. Builder unconditionally executes `add(StorySentence.Summary(..., metrics.levelUsedPct))` and `add(StorySentence.Anomalies(report.insights.size, ...))`. It discards measurement/evaluation availability.
  6. `CockpitMappers.analysisUi` retains the story, `StoryCard` renders `StoryText`, and `AndroidStoryWriter` outputs “In the last 1 hour you used 0% of your battery. No anomalies detected.” The source contract explicitly says sentences with missing inputs are omitted.
- **Failure:** a stored pair with 90% then 70%, absent history, produces a zero-drain narrative; genuinely unperformed checks are represented by the same all-clear text as successfully evaluated checks. Partial/unknown history also understates usage as a whole-window fact.
- Concrete conservative fix (retains measured positive insights; omits unsupported summaries/negative conclusions):
```diff
--- a/core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/story/BatteryStoryBuilder.kt
+++ b/core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/story/BatteryStoryBuilder.kt
@@
             if (metrics != null) {
-                add(StorySentence.Summary((metrics.toWallMs - metrics.fromWallMs) / MS_PER_HOUR, metrics.levelUsedPct))
+                val durationMs = metrics.toWallMs - metrics.fromWallMs
+                val measuredMs = metrics.screenOnMs + metrics.screenOffMs + metrics.chargingMs
+                if (durationMs > 0 && metrics.startLevel != null && metrics.endLevel != null &&
+                    metrics.unknownMs == 0L && metrics.discontinuities.isEmpty() && measuredMs == durationMs
+                ) {
+                    add(StorySentence.Summary(durationMs / MS_PER_HOUR, metrics.levelUsedPct))
+                }
@@
-            if (report != null) {
+            if (report != null && (report.insights.isNotEmpty() || report.notEvaluable.isEmpty())) {
```
A richer follow-up can render explicit unavailable/partial-evaluation wording, but must not invent a zero total. Add tests using real `IntervalMetricsCalculator.compute(emptyList(), from, to)` and a rule report whose notEvaluable contains all rules, plus incomplete-history and complete known-zero controls.

### [DIAG-2] [MEDIUM] Timeline drops the charging baseline carried by RESET/START records

**Status: [FIXED in 4a0ba18]**

- **Category:** Logic Bug
- **Location:** `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/TimelineBuilder.kt:139-168`; related `app/src/main/kotlin/com/akane/inspiration/di/TimelineEvents.kt:31-36`.
- **Confidence:** Confirmed by complete call trace and actual compiled-class reproduction.
- **Root cause / trace:**
  1. `BatteryHistory.HistoryWalker.advance` (`core/shizuku-ipc/.../client/BatteryHistory.kt:119-127`) emits only RESET/START for those framework records and calls `setBaseline`. Its transitions function (130-144) emits PLUGGED only when the next record changes plug state; continuous charging after the baseline produces no PLUGGED event. `event` copies batteryPlugType into HistoryEvent.plugType.
  2. `AndroidTelemetrySource.readHistory` -> `SnapshotCapturer.persistHistory` -> `RoomSnapshotStore.insertHistory` stores these records unchanged. `RoomSnapshotStore.historyBetween` and `lastHistoryOfEachKindBefore` convert entities back without filling missing transitions.
  3. `AppContainer.timeline` (155-160) calls `TimelineEvents.withSeeds`; that helper drops RESET/START when they predate the requested window.
  4. Even when the baseline is inside the window, `TimelineBuilder.Spans.scan` recognizes only PLUGGED/UNPLUGGED and ignores RESET/START. The plug lane starts closed.
  5. `CockpitMappers` maps LaneKind.CHARGING to SegmentKind.CHARGING, so the dashboard omits the charging span. Independently, `IntervalMetricsCalculator` DOES seed `plugged = event.plugType != 0` on RESET/START (80-84), making the chart disagree with measured charging time.
- **Failure:** reboot/reset while connected, then remain connected for an hour: known one-hour charging interval is absent from the timeline. An old plug state can also survive a baseline that changed it, because scan ignores the new baseline.
- Concrete fix (both handling and pre-window retention are required):
```diff
--- a/core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/TimelineBuilder.kt
+++ b/core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/TimelineBuilder.kt
@@
                     HistoryKind.UNPLUGGED -> plug.close(t)
+                    HistoryKind.RESET, HistoryKind.START -> {
+                        if (event.plugType != 0) plug.open(t) else plug.close(t)
+                    }
--- a/app/src/main/kotlin/com/akane/inspiration/di/TimelineEvents.kt
+++ b/app/src/main/kotlin/com/akane/inspiration/di/TimelineEvents.kt
@@
             ordered.lastOrNull { it.kind == HistoryKind.SHUTDOWN },
+            ordered.lastOrNull { it.kind == HistoryKind.RESET },
+            ordered.lastOrNull { it.kind == HistoryKind.START },
```
Add builder tests RESET/START while plugged, then UNPLUGGED, and a withSeeds integration test where the baseline predates from. Preserve chronological ordering with the last explicit plug transitions.

### [FEAT-1] [MEDIUM] A short foreground observation discards the rest of the recorded charge history

**Status: [FIXED in af861ff]**

- **Category:** Logic Bug.
- **Location:** `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthMappers.kt:176-186`; related `HealthViewModel.kt:177-188`.
- **Confidence:** Confirmed (verified via call-trace).
- **Root cause and trace:** `HealthRoute` starts/stops `HealthViewModel` with lifecycle; its charging flow delegates through `AppContainer.liveReadings` (AppContainer.kt:177) to `LiveTelemetry.readings` (core/telemetry-engine/.../live/LiveTelemetry.kt:29-38), which persists charging readings. On a later visit, `HealthViewModel.load` reads both `AppContainer.samplesBetween` and `historyBetween`, and `render` passes both sources to `chargeSession`. `chargeSession` keeps history only before the FIRST persisted reading:
  ```kotlin
  val firstSample = sampleSession.firstOrNull()?.wallMs
  ...
  (firstSample == null || it.wallMs < firstSample)
  ```
  No upstream wrapper clips history to this period or guarantees persisted readings cover the remainder. The flow is intentionally stopped while hidden.
- **Concrete failure:** charge 0–60 minutes; open Health only at minutes 10–15, then close it and let charging finish. Reopen after unplug: history has level/temperature/voltage through minute 60, but the chart ends at minute 15 and `wearAnalysis` ignores all later hot/high-voltage observations. A second brief visit also loses history between the two observed runs.
- **Concrete fix:** retain history wherever a continuous sampled interval does not cover it; exact-timestamp samples win. Replace the `firstSample` declaration and `historySession` construction in `chargeSession` with:
  ```kotlin
  val historySession = if (effective == null) {
      emptyList()
  } else {
      history.filter { point ->
          if (point.wallMs < effective.startMs ||
              (effective.endMs != null && point.wallMs > effective.endMs)
          ) {
              false
          } else {
              val match = sampleSession.binarySearchBy(point.wallMs) { it.wallMs }
              if (match >= 0) {
                  false
              } else {
                  val next = -match - 1
                  val covered = next > 0 && next < sampleSession.size &&
                      sampleSession[next].wallMs - sampleSession[next - 1].wallMs <= SAMPLE_MAX_GAP_MS
                  !covered
              }
          }
      }
  }
  ```
  Keep the existing final sorted merge. Update KDoc from prefix-only semantics to history outside observed sample runs. Add tests with samples covering only the middle of a charge, and two sample runs separated by a historical hot interval.

### [FEAT-2] [MEDIUM] Unplugging converts the completed charge back into a sample-only open span

**Status: [FIXED in 29d1b09]**

- **Category:** Logic Bug / State lifecycle.
- **Location:** `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthViewModel.kt:183-184`; related `:91-97`, `HealthMappers.kt:149-154`.
- **Confidence:** Confirmed (verified via call-trace).
- **Root cause and trace:** `HealthRoute` receiver emits `PlugChanged(false)` on unplug. `HealthViewModel.onPlugChanged` stops the flow and loads completed history, but intentionally keeps its `live` sample buffer; only `onHidden` clears that buffer. `render` then passes `hasLive = live.isNotEmpty()` twice. `effectiveChargeSpan` interprets this as an ongoing new charge and replaces every closed history span with `ChargeSpan(lastRun(samples).first.wallMs, null)`. `chargeSession` drops the historical prefix before this replacement start, and `wearAnalysis` uses the last sampled time instead of the real unplug endpoint.
- **Concrete failure:** phone charged for an hour; user opens Health for its final five minutes and unplugs without leaving the screen. Once history reports the completed hour, the displayed session shrinks to the last five minutes and loses earlier heat/voltage coverage. The same data is displayed differently after leaving/re-entering the tab, because `onHidden` clears the buffer.
- **Concrete fix:** decide whether buffered samples actually extend beyond the completed history span. This preserves the correct fallback while history ingestion still describes the previous charge after unplug. Replace `effectiveChargeSpan` with:
  ```kotlin
  internal fun effectiveChargeSpan(
      span: ChargeSpan?,
      samples: List<CurvePoint>,
      hasLive: Boolean,
  ): ChargeSpan? {
      val newestSample = samples.maxOfOrNull { it.wallMs }
      val samplesAfterCompletedSpan = span?.endMs?.let { end ->
          newestSample != null && newestSample > end
      } ?: false
      return if (hasLive && (span == null || samplesAfterCompletedSpan)) {
          lastRun(samples).firstOrNull()?.let { ChargeSpan(it.wallMs, null) }
      } else {
          span
      }
  }
  ```
  Preserve buffered samples after unplug; do not solve by clearing them and racing persistence. Merely passing `charging && live.isNotEmpty()` would regress the interval before the new unplug event is ingested (the previous charge could reappear). Test completed history following Visible → PlugChanged(true) → readings → PlugChanged(false), asserting original start/end/prefix survive; also test the pending-ingestion interval still shows the latest buffered charge.

### [FEAT-3] [MEDIUM] Sleep Lab presents package-changed cumulative counters as interval deltas

**Status: [FIXED in cf7f61d]**

- **Category:** Logic Bug.
- **Location:** `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabMappers.kt:134-147,174-189`; related `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/SnapshotDiffer.kt:208-240`.
- **Confidence:** Confirmed (verified via call-trace).
- **Root cause and trace:** `SnapshotCapturer.mergeFull` obtains UID package identity from optional usage data (`SnapshotCapturer.kt:84-90`), while wakelocks/alarms are sourced independently from dumps. `IntervalAnalyzer.analyze` calls `SnapshotDiffer.diff` (`IntervalAnalyzer.kt:40`). When an application UID's package differs (including null → known), `diffUids` sets `packageChanged = true` and `baseline = null`; positive counters are therefore the current totals and still have `valid = true`. `AnalysisSession` emits the unmodified analysis, `AppContainer.analysis` exposes it, `SleepLabViewModel.showReady` calls `sleepLabAnalysis`, which calls both row mappers. Both only use `diff.uids.filter { it.valid }`; they do not honor `packageChanged`. `XRayMapper.apps:54` explicitly omits the same rows.
- **Concrete failure:** reference contains UID 10001 with a null usage package and 90 prior wakeups; later usage resolves that UID to its package with 100 wakeups. The differ marks identity changed and returns 100 (zero baseline), and Sleep Lab displays 100 wakeups for a period with only 10. Wakelock durations have the same error. These false rankings blame an app for activity outside the selected interval.
- **Concrete fix:**
  ```diff
  -    val rows = diff.uids.filter { it.valid }.flatMap { uid ->
  +    val rows = diff.uids.filter { it.valid && !it.packageChanged }.flatMap { uid ->
  ...
  -    val rows = diff.uids.filter { it.valid }.mapNotNull { uid ->
  +    val rows = diff.uids.filter { it.valid && !it.packageChanged }.mapNotNull { uid ->
  ```
  Add mapper tests using a positive, valid `UidDelta` with `packageChanged = true` in both tabs, ideally backed by an integration test using real `SnapshotDiffer` output.

### [IPC-2] [MEDIUM] A cancelled queued dexopt worker can start a new system job after Cancel succeeded

**Status: [FIXED in 33fe04d]**

- **Category:** Concurrency / Logic Bug
- **Location:** `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/BgDexoptController.kt:58-73`, `98-110`, `126-135`; related `ToolkitExecutor.kt:167-172`.
- **Confidence:** Confirmed by execution of actual compiled controller with deterministic fake process scheduling.
- **Trace:** `ToolkitViewModel.startDexopt():470-483` -> `ToolkitActions.startBgDexopt():301-324` -> `ToolkitGateway.startBgDexopt():94-97` -> service `startBgDexopt():287` -> executor -> controller. It sets RUNNING and schedules a worker, then returns success without the worker necessarily executing. UI can then enable Cancel once Running is observed. Cancel -> `ToolkitActions.cancelBgDexopt():328-340` -> gateway -> service -> executor: `noteUserCancel()`, successful system `--cancel`, `noteCancelCompleted()`. A delayed worker subsequently calls `runner.start(BG_DEXOPT_ARGV)` without checking the cancellation flag. After one wait it trusts old `cancelCompleted=true`, publishes CANCELLED and destroys only the cmd process, not the system-server job it has just launched.
- Offending code:
  ```kotlin
  private fun runJob() {
      val process = try { runner.start(BG_DEXOPT_ARGV) } ...
  }
  // supervise:
  val confirmed = exited || synchronized(lock) { cancelCompleted }
  ```
- Reproduction output:
  ```text
  CANCEL BEFORE WORKER: calls=[[/system/bin/cmd, package, bg-dexopt-job, --cancel], [/system/bin/cmd, package, bg-dexopt-job]] state=3 CANCELLED=3
  ```
  Harness delays only the worker supplied to `startThread`; invokes the exact note/command/note sequence from ToolkitExecutor; then executes the queued worker. The start command appears **after** cancellation and no later cancel command is issued. Fake running process never exits, but controller says CANCELLED.
- **Failure:** optimization continues after user cancels and the supervisory worker exits, removing future unplug/one-hour protection. Client also finalized the action CANCELLED and releases its hold. This is a temporal correctness defect, not an external-UID attack.
- Concrete minimal fix for this confirmed queued-worker ordering: atomically check cancellation and launch under the same controller lock used by `noteUserCancel`; this prevents a queued runnable from executing after its cancellation was acknowledged.
  ```diff
  @@ private fun runJob() {
       val process = try {
  -        runner.start(BG_DEXOPT_ARGV)
  +        synchronized(lock) {
  +            if (userCancelRequested) {
  +                finish(BgDexoptState.CANCELLED, exit = 0, why = BgDexoptReason.USER)
  +                return
  +            }
  +            runner.start(BG_DEXOPT_ARGV)
  +        }
       } catch (_: IOException) {
  ```
  Structural hardening should give each start a generation/token, reserve it before probing, and let cancellation invalidate that generation. Distinguish cancelled-before-launch from confirmed system completion; after a process has started, a successful `--cancel` alone can race process-to-system dispatch. The supervisor should retry cancellation while the client remains alive and return UNKNOWN on a bounded inconclusive wait, and the gateway/client should retain the pending row until this result is known. This broader handshake is an architectural remediation, not represented as already implemented by the minimal diff above.
- Blind spot: `BgDexoptControllerTest` queues runnables but never executes one after successful cancellation. `ToolkitExecutorTest:547-564` deliberately runs Cancel during the plugged probe and asserts both operations DONE; it verifies nonblocking behavior but not that cancelled work stays cancelled. UI's busy gate reduces the before-probe scenario on one screen, but does not prevent the queued worker race after Start has returned.

### [IPC-3] [MEDIUM] Wall-clock corrections disable dexopt unplug checks and extend its one-hour cap

**Status: [FIXED in b8bbb62]**

- **Category:** Logic Bug / Performance
- **Location:** `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/BgDexoptController.kt:114-141`; production clock injection `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/service/TelemetryUserService.kt:394-398`.
- **Confidence:** Confirmed by actual compiled-controller reproduction.
- **Trace:** Start flow above -> service `createToolkit()` supplies `clockMs = System::currentTimeMillis` -> controller records startedAtMs -> `supervise()` sets deadline and lastCheck using the wall clock -> compares wall clock deltas every wait slice. Manual or automatic backwards time correction causes `now-lastCheckMs` to remain negative until old wall time catches up. Unplug no longer gets checked every 30s; the deadline is extended by the clock correction. Forward corrections instead cancel prematurely.
- Snippet:
  ```kotlin
  val deadline = synchronized(lock) { startedAtMs } + capMs
  var lastCheckMs = clockMs()
  // ...
  val now = clockMs()
  when {
      now >= deadline -> cancelReason = BgDexoptReason.TIMEOUT
      now - lastCheckMs >= recheckMs -> { ... plugged.isPlugged() ... }
  }
  ```
- Reproduction: subtract one hour from fake wall time after the first 1s wait, with a physically unplugged probe after the initial successful check. Output `CLOCK BACKWARD: unplug seen after elapsed=3650000ms; expected <=30000ms; probes=2`. The fake waits add their requested duration and never sleep; the production controller is unchanged. This includes its cancellation wait overhead, and proves the next probe itself is postponed by approximately one hour.
- **Failure:** prolonged CPU/thermal/battery work while unplugged despite the explicit 30s safety guard, or early cancellation on a forward jump.
- Concrete fix (preserve wall timestamps, use monotonic elapsed clock for control; default maintains existing synthetic-clock test seams):
  ```diff
  @@ class BgDexoptController(
       private val capMs: Long = CAP_MS,
  +    private val elapsedMs: () -> Long = clockMs,
   ) {
  +    private var startedElapsedMs = 0L
  @@ tryStart
       startedAtMs = clockMs()
  +    startedElapsedMs = elapsedMs()
  @@ supervise
  -    val deadline = synchronized(lock) { startedAtMs } + capMs
  -    var lastCheckMs = clockMs()
  +    val deadline = synchronized(lock) { startedElapsedMs } + capMs
  +    var lastCheckMs = elapsedMs()
  @@
  -    if (process.awaitExit(minOf(WAIT_SLICE_MS, maxOf(deadline - clockMs(), 1L)))) {
  +    if (process.awaitExit(minOf(WAIT_SLICE_MS, maxOf(deadline - elapsedMs(), 1L)))) {
  @@
  -    val now = clockMs()
  +    val now = elapsedMs()
  ```
  In BOTH service constructor sites for `BgDexoptController`, add `elapsedMs = SystemClock::elapsedRealtime`. `SystemClock` is already imported. Add independent wall/elapsed clocks to tests; move wall clock in both directions while asserting elapsed-time unplug/cap invariants.

### [T3] [MEDIUM] A newer disconnect is ignored when any older plugged snapshot survives

**Status: [FIXED in 18149a7]**

- **Category:** Logic Bug.
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/status/UnplugSummary.kt:49-58`.
- **Confidence:** confirmed via call trace AND executable reproduction using the repository's built telemetry classes. Reproduction artifact `.codex/android-kit/logs/full-repo-audit/StatusRepro.java`; output `.codex/android-kit/logs/full-repo-audit/status-repro.txt`.
- **Trace:** `RuntimeCaptureReceiver` is registered at runtime; process absence or a failed capture can omit a POWER_CONNECTED event. A later POWER_DISCONNECTED still gets stored through `SnapshotCapturer.captureFull/captureLight`. `StatusNotificationService.redraw:102-105` loads the recent persisted snapshots and invokes `UnplugSummaryCalculator.compute`. The calculator finds both lastPlugged and lastDisconnect but only uses lastDisconnect if lastPlugged is negative:

```kotlin
if (lastPlugged < 0) {
    lastDisconnect.coerceAtLeast(0)
} else {
    (lastPlugged + 1 until points.size).firstOrNull { points[it].battery.plugged == false } ?: points.lastIndex
}
```

- Reproduced case (minutes): connected 0 at 100%, unplugged 10 at 100%, screen-on 40 at 90%, an unobserved charging period, unplugged 70 at 100%, now 100 at 95%. Actual anchor=10, drop=15, elapsed=90 minutes. Expected latest unplug anchor=70, drop=5, elapsed=30 minutes. Notification reports combined discharge sessions as one and corrupts rates.
- **Drop-in fix:** preserve the existing inferred unplug fallback, but let a later explicit disconnect supersede it.

```diff
--- a/core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/status/UnplugSummary.kt
+++ b/core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/status/UnplugSummary.kt
@@
-        val anchor =
+        val inferredAnchor =
             if (lastPlugged < 0) {
                 lastDisconnect.coerceAtLeast(0)
             } else {
                 (lastPlugged + 1 until points.size).firstOrNull { points[it].battery.plugged == false } ?: points.lastIndex
             }
+        val anchor = maxOf(inferredAnchor, lastDisconnect)
```

- **Test blind spot:** existing tests explicitly cover missed *disconnects* and disconnects with no stored plugged snapshot; they do not cover a missed *connect* with an older surviving plug record. Add the reproduced sequence.

### [TOOL-1] [MEDIUM] Board verification timeout leaves descendant processes running

**Status: [FIXED in 5ee78c3]**

- **Category:** Concurrency / Resource Leak.
- **Location:** `.codex/android-kit/board.py:65-68`; identical `android-kit-codex/template/.codex/android-kit/board.py:65-68`; caller `.agents/skills/android-board/SKILL.md:8`, documented workflow `android-kit-codex/README.md:51-58`; wrapper `.codex/android-kit/gradle-check.sh:7-8`.
- **Confidence:** Confirmed by isolated execution reproduction.
- **Root cause and trace:** skill/README instruct adding verification argv such as `bash .codex/android-kit/gradle-check.sh :app:testDebugUnitTest`; board `verify` executes `subprocess.run(..., timeout=args.timeout)`; wrapper launches `gradlew` at line 8. Python timeout kills/waits for its immediate process only. The wrapper's child and grandchildren survive. Board then fingerprints the tree and reports timeout although the old command still executes.
- **Failure:** local timed-out verification continues consuming resources/writing artifacts or changing device state while a later attempt starts; repository changes can occur after the supposedly completed timeout. A shell command `['bash','-c','(sleep 3; printf orphan > orphan.txt) & wait']` with timeout=1 demonstrated the delayed mutation. This is not a shipping Android runtime defect.
- **Concrete fix:** use a new session/process group and terminate/reap that group on timeout/cancellation. Linux/macOS are the toolkit's documented platforms. Apply to installed helper and source template:

```diff
-import argparse, hashlib, json, sqlite3, subprocess, sys, time
+import argparse, hashlib, json, os, signal, sqlite3, subprocess, sys, time
@@
-   try: result=subprocess.run(json.loads(row['command']),cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT,timeout=args.timeout); code=result.returncode
-   except subprocess.TimeoutExpired: code=124; stream.write(b'\nVerification timed out\n')
+   process = None
+   try:
+    process = subprocess.Popen(json.loads(row['command']), cwd=ROOT,
+        stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
+    code = process.wait(timeout=args.timeout)
+   except (subprocess.TimeoutExpired, KeyboardInterrupt) as exc:
+    if process is not None:
+     try: os.killpg(process.pid, signal.SIGKILL)
+     except ProcessLookupError: pass
+     process.wait()
+    if isinstance(exc, KeyboardInterrupt): raise
+    code = 124
+    stream.write(b'\nVerification timed out\n')
    except OSError as e: code=127; stream.write(str(e).encode())
```

Add regression proving a spawned grandchild cannot mutate a sentinel after timeout; existing tests only cover nonzero exit and source changes, not timeouts or child cleanup. Long-lived external Gradle daemons may need separate cancellation validation; process groups handle wrapper descendants but are not a universal daemon shutdown mechanism.

### [TOOL-2] [MEDIUM] Fresh-checkout emulator probe fails before boot and then waits indefinitely

**Status: [FIXED in 26de410]**

- **Category:** Logic Bug / Resource Management.
- **Location:** `.claude/skills/device-probe/scripts/device-probe.sh:43-47`, related output-directory creation line 104 (after device boot); caller `.claude/skills/device-probe/SKILL.md:13`; onboarding `.claude/.codebase-info/onboarding.md:79-84`.
- **Confidence:** Confirmed by isolated mock-command reproduction.
- **Root cause and trace:** documented skill invokes script on a checkout with no attached device; `adb get-state` fails; launch redirects stdout to `build/emulator-$avd.log` before creating `build/`. Shell redirection fails, so emulator never executes. Script then runs unbounded `adb wait-for-device`. Neither the later 180-iteration boot loop nor `--timeout` bounds this wait.
- **Failure:** developer's first device-evidence run hangs forever when no build directory exists, and also hangs indefinitely for invalid/unbootable AVDs. Actual reproduction printed the missing-directory error and never invoked the fake emulator.
- **Concrete fix:** create log parent before launch, remove unbounded wait-for-device and poll device readiness with deadline; check emulator startup exit to fail promptly. Replace the entire existing connection-start if block at lines 43-48 with:

```bash
if ! adb get-state >/dev/null 2>&1; then
  say "booting $avd"
  mkdir -p build || exit 2
  log=$(mktemp build/emulator.XXXXXXXX.log) || exit 2
  nohup emulator -avd "$avd" -no-window -no-audio -no-snapshot-save >"$log" 2>&1 &
  emulator_pid=$!
  deadline=$((SECONDS + 180))
  until adb get-state >/dev/null 2>&1; do
    if ! kill -0 "$emulator_pid" 2>/dev/null; then
      wait "$emulator_pid" || true
      say "emulator exited before connecting; log: $log"
      exit 2
    fi
    if (( SECONDS >= deadline )); then
      kill "$emulator_pid" 2>/dev/null || true
      wait "$emulator_pid" || true
      say "device connection timed out; log: $log"
      exit 2
    fi
    sleep 1
  done
fi
```

This change is proposed only; instruction forbids modifying parallel Claude setup. Add isolated shell tests for missing build directory, emulator immediate failure, and bounded connection timeout. ADB individual command stalls need an additional command timeout if they are to be covered as well.

### [UI-1] [MEDIUM] Active app-op overrides always claim the app is restricted, including an override that allowed it

**Status: [FIXED in 995945f]**

- **Category:** Logic Bug
- **Location:** `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitMapper.kt:33-40`; related `ToolkitModels.kt:133-134`, `ToolkitText.kt:173-179`, `ToolkitViewModel.kt:249-257`, `app/src/main/kotlin/com/akane/inspiration/AppContainer.kt:215-227`.
- **Confidence:** Confirmed via complete call trace.
- **Root cause:** the display model discards currentRaw and models every APP_OP override as AppOpIgnored:

```kotlin
OverrideKind.APP_OP -> OverrideWhat.AppOpIgnored(override.appOp)
```

- **Trace:** `MainActivity` destination Toolkit -> `ToolkitRoute` -> `ToolkitScreen` -> `AppDetailContent` -> `Controls` -> `OpSwitchRow` sends SetAppOp(op, false). `ToolkitViewModel.act/actionOf` constructs SetAppOp(package, op, DEFAULT); `ToolkitActions.plan/execute/run` persists and performs it through `GatewayBackend.setAppOp` -> `ToolkitGateway.setAppOp`. Start with an installed launchable app whose RUN_ANY_IN_BACKGROUND is already `ignore` (set in Android settings or another tool), with no Inspiration history. A successful write yields before=ignore, after=default. `ToolkitActions.activeOverrides` -> `computeOverrides` -> `activeOverride/isActive/chainOriginal` correctly retains this as a changed setting because default != ignore, and `overrideOf` preserves currentRaw=default. `ToolkitViewModel.showOverrides` -> `ToolkitMapper.overrideItem` loses that information. `OverridesCard/OverrideRow` -> `overrideText` renders `toolkit_op_blocked_line`, “Background activity restricted for …”. The app list also marks every override's pkg restricted. Restore is allowed and correctly returns it to ignore; the misleading row suggests the opposite current state.
- **Failure:** after successfully allowing background work, the main undo surface and picker claim it is restricted; users can reverse the wrong change based on false state. This is not a service privilege bypass.
- **Concrete fix:** retain mode in the UI model and render it; keep the existing constructor default for screenshot/test compatibility. Use a neutral badge label since the picker actually represents all modified apps (including ACTIVE bucket overrides), rather than mislabelling all modifications restrictions.

```diff
--- a/feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitModels.kt
+++ b/feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitModels.kt
@@
-    data class AppOpIgnored(val op: AppOp?) : OverrideWhat
+    data class AppOpIgnored(val op: AppOp?, val ignored: Boolean? = true) : OverrideWhat
--- a/feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitMapper.kt
+++ b/feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitMapper.kt
@@
-            OverrideKind.APP_OP -> OverrideWhat.AppOpIgnored(override.appOp)
+            OverrideKind.APP_OP -> OverrideWhat.AppOpIgnored(
+                override.appOp,
+                when (override.currentRaw?.trim()) {
+                    "ignore" -> true
+                    "default", "allow" -> false
+                    else -> null
+                },
+            )
--- a/feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitText.kt
+++ b/feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitText.kt
@@
-        is OverrideWhat.AppOpIgnored ->
-            what.op?.let { stringResource(R.string.toolkit_op_blocked_line, stringResource(opShortRes(it)), app) }
-                ?: stringResource(R.string.toolkit_override_op_other, app)
+        is OverrideWhat.AppOpIgnored -> if (what.op == null || what.ignored == null) {
+            stringResource(R.string.toolkit_override_op_other, app)
+        } else {
+            stringResource(
+                if (what.ignored) R.string.toolkit_op_blocked_line else R.string.toolkit_op_allowed_line,
+                stringResource(opShortRes(what.op)),
+                app,
+            )
+        }
--- a/feature/toolkit/src/main/res/values/strings.xml
+++ b/feature/toolkit/src/main/res/values/strings.xml
@@
-    <string name="toolkit_app_restricted">Restricted</string>
+    <string name="toolkit_app_restricted">Changed</string>
--- a/feature/toolkit/src/main/res/values-tr/strings.xml
+++ b/feature/toolkit/src/main/res/values-tr/strings.xml
@@
-    <string name="toolkit_app_restricted">Kısıtlı</string>
+    <string name="toolkit_app_restricted">Değiştirildi</string>
```

A subsequent cleanup can rename AppOpIgnored to AppOpSet and restricted to modified across fixtures and AppEntries. The above diff is deliberately source compatible. Add a ViewModel test with FakeDevice.app(CHATTY).ops[RUN_ANY_IN_BACKGROUND]="ignore" before Visible; turn the switch off and assert ignored=false on the override model and current device state default; restore should return ignore. Current tests only exercise allow -> ignore -> allow, which returns to the original and removes the override, hiding this bug.

### [UI-2] [MEDIUM] Retry loads can overwrite the Shizuku blocked state after access disappears

**Status: [FIXED in a76f98b]**

- **Category:** Concurrency
- **Location:** `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitViewModel.kt:167-183`; related lifecycle cancellation at 122-146 and `ToolkitScreen.kt:97-106`.
- **Confidence:** Confirmed via call trace/interleaving; no device reproduction claimed.
- Offending code:

```kotlin
private fun reload() {
    val status = _state.value.status
    if (status != ToolkitPhase.FAILED && status != ToolkitPhase.READY) return
    viewModelScope.launch { load() }
}
```

- **Trace:** `ErrorBlock`, `DozeCard` unavailable retry, or `appsSection` failed-list retry -> ToolkitIntent.Reload -> reload launches a sibling of visibleJob. load suspends in ToolkitActions.dozeState -> readDoze -> GatewayBackend.getDozeState -> ToolkitGateway.call -> ShizukuManager.withTelemetryService. Binder loss/permission revocation changes shizukuState; visibleJob's collectLatest -> follow -> block sets BLOCKED and clears controls. The unrelated retry coroutine is not cancelled. Its IPC then returns NO_SERVICE/STALE_SERVICE (Gateway converts absence to Unavailable), and load line 179 unconditionally overwrites BLOCKED with FAILED; a reply already available can instead set READY at 183. The route renders the retry error instead of the permission/NotRunning state; no further StateFlow emission is required while Shizuku stays unavailable. Hidden similarly cancels visibleJob but not retry. Calling Reload repeatedly while READY can create overlapping loads.
- **Failure:** access recovery UI becomes incorrect/stale after a real lifecycle transition, and unnecessary retry reads continue after leaving the screen.
- **Concrete fix:** route user reload through the same visibility collector restart used on entry; this cancels previous reads, reevaluates current Shizuku state and keeps retry work inside collectLatest. Cancel a pending detail read on Hidden as well.

```diff
--- a/feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitViewModel.kt
+++ b/feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitViewModel.kt
@@
     private fun onHidden() {
         visible = false
         visibleJob?.cancel()
+        detailJob?.cancel()
@@
     private fun reload() {
+        if (!visible) return
         val status = _state.value.status
         if (status != ToolkitPhase.FAILED && status != ToolkitPhase.READY) return
-        viewModelScope.launch { load() }
+        onVisible()
     }
```

Add regression: start with appsFailure=true, set FakeDevice.gate to deferred, send Reload, change deps.shizukuState to NotRunning and device.serviceUp=false, then release gate; final status must remain BLOCKED. Also repeat with Hidden and assert suspended reads cancel. Existing tests cover access loss with an already-open sheet, and a sequential reload recovery, but not an in-flight retry.

### [T4] [LOW] Completed-screen-off lookup repeatedly copies the entire history suffix

**Status: [FIXED in a0275d9]**

- **Category:** Performance.
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/ReferenceResolver.kt:110-117`.
- **Confidence:** confirmed by code and production call trace; no device benchmark claimed.
- **Trace:** app process-wide `AnalysisSession` -> `IntervalAnalyzer.analyze` -> `ReferenceResolver.resolve` loads all retained history and for SinceScreenOff calls completedScreenOff. For every SCREEN_OFF event it executes `events.drop(index + 1).firstOrNull { ... }`. Kotlin `List.drop` eagerly copies the entire suffix even if the next event is SCREEN_ON. With n events and k screen-off events this is O(n*k) copied references; for alternating screen transitions it is quadratic, repeated whenever that reference refreshes. It causes unnecessary allocation/GC and computation as retained history grows. It runs on a worker dispatcher, so no claim of direct main-thread blocking.
- Drop-in semantics-preserving linear replacement:

```kotlin
private fun completedScreenOff(events: List<HistoryEvent>): Boundary? {
    var candidate: Boundary? = null
    var screenOff: Long? = null
    for (event in events) {
        when (event.kind) {
            HistoryKind.SCREEN_OFF -> screenOff = event.wallMs
            HistoryKind.SCREEN_ON -> {
                screenOff?.let { candidate = Boundary(it, event.wallMs) }
                screenOff = null
            }
        }
    }
    return candidate
}
```

The latest SCREEN_OFF before the next SCREEN_ON is selected exactly as in the current implementation. Add large-history and consecutive OFF/ON fixtures; current resolver tests use only tiny histories.

### [UI-3] [LOW] Chart selection commits the last move position instead of the finger-up position

**Status: [FIXED in f4365f0]**

- **Category:** Logic Bug
- **Location:** `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/math/ScrubGestureReducer.kt:71` and `:133-163`; caller `chart/scrub/ScrubGestures.kt:72-86`.
- **Confidence:** Confirmed by deterministic reducer semantics/call trace.
- Offending code: `is ScrubGestureEvent.Up -> up(state, scale, selection)` discards `Up.x`, even though the pointer adapter passes the final change position.
- **Trace:** dashboard `ChartCard` -> SynchronizedScrubbingChart -> scrubGestures gets final PointerInputChange position and sends Up(lastX). Reducer ignores it and uses Brushing.currentMs / DraggingHandle.currentMs from the last Move. Driver.publishCommit -> ChartCard callback -> CockpitIntent.CommitBrush -> CockpitViewModel.commitBrush -> shared ReferenceSelection.select(TimeSlice) applies that stale range to diagnostics.
- Scenario: scale 0..100000 ms over 1000px, minimum 5000ms: Down(200,50), Move(400,50), Up(450) commits 20000..40000 instead of 20000..45000. With Move(240) then Up(260), the gesture crosses the minimum only on release and is wrongly discarded. On a 24-hour plot even a few final pixels can change minutes of selected diagnostics.
- Concrete fix, preserving tap/scroll decisions while updating established drags:

```diff
--- a/core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/math/ScrubGestureReducer.kt
+++ b/core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/math/ScrubGestureReducer.kt
@@
-        is ScrubGestureEvent.Up -> up(state, scale, selection)
+        is ScrubGestureEvent.Up -> up(
+            when (state) {
+                is ScrubGestureState.Brushing -> state.copy(currentMs = timeAt(event.x, scale))
+                is ScrubGestureState.DraggingHandle -> state.copy(currentMs = timeAt(event.x, scale))
+                else -> state
+            },
+            scale,
+            selection,
+        )
```

Tests use equal x values for final Move and Up throughout ScrubGestureReducerTest; add the concrete traces above and a handle-release equivalent.

## 3. Dead Code, Redundancy & Technical Debt Cleanup

These items were verified by repository-wide symbol searches. They are separate from correctness findings and were not removed during this audit.

- `CapturePolicy.appOpenDebounced`: production has no caller; only its own tests use it while RefreshCoordinator owns actual debounce. Remove the helper/test pair if no external contract is intended.
- `HealthInputs.loadedAtMs`: only declaration and construction remain; remove the unused field/argument.
- Design-system `nearestIndex`: only its dedicated test calls it; remove after confirming no public-library compatibility obligation. `chart_a11y_cursor` has no Kotlin consumer; either implement the intended accessibility behavior or remove the unused resource deliberately.
- `ToolkitDependencies.holdBinding`: feature never calls it; ToolkitActions owns the actual hold. Remove the unused contract/fake members if compatibility permits.
- `AndroidTelemetrySource.full` reads/parses a power dump into `FullRead.power`, but SnapshotCapturer and every other production consumer ignore the field. Remove the dump work/field and update constructors; retain parser/IPC APIs still used by debug probes. This avoids an unnecessary privileged read on every full capture.
- `ReferenceResolver.resolve` hydrates `latest` only as an existence check, then loads the endpoint again; use an existence query or the endpoint result. `LiveTelemetry.clocks` constructor parameter is unused.
- `TelemetryDao.hourly` has only test readers although production writes hourly aggregates. Clarify intended historical display before deleting a write-only dataset. `SnapshotDao.deleteById` is a useful test seam, not automatically dead code.
- `core/shizuku-ipc` redundantly declares HiddenApiBypass although only app code invokes it; app already declares its own dependency. Remove only the IPC declaration. Three unused compile-only stub methods (`BatteryUsageStats.getDischargePercentage`, `IDeviceIdleController.isPowerSaveWhitelistApp`, `UidBatteryConsumer.getTimeInProcessStateMs`) have no callers and can be pruned without deleting their used classes.
- Consolidate duplicate animator-duration-scale readers. Profile repeated history scans, per-UID child filtering and long foreground Health buffers before assigning performance severity. Do not confuse renderer point caps with bounded input accumulation.
- Source templates and installed kit helpers intentionally duplicate code; propagate fixes deliberately. The `project-files.py` settings-validation branch cannot match its three enumerated state filenames and can be removed. Claude compatibility no-op hooks may have external callers, so no global-removal claim is made.
- Stale schema-1 project-map entries were refreshed; stale sensor/persistence/shell-placeholder comments should be corrected when touching those files. Existing source suppressions were not equated with bugs.
- Tooling README advertises Python 3.9, while evaluated PEP 604 annotations in `ui_pick.py` need a newer interpreter or postponed annotations. No Python 3.9 runtime was available; record this as a compatibility follow-up rather than a reproduced runtime failure.

## 4. Prioritized Remediation Roadmap

### Phase 1: Immediate Hotfixes (Stop the Bleeding)

1. Preserve uncertain privileged writes and restoration baselines (T1/T2). Test a real effect followed by lost reply/generation change, pending rows and multi-step chains spanning >500 unrelated actions. Do not retry writes blindly or invent lost originals.
2. Remove synchronous broadcast waiting and contain operational refresh exceptions (APP-1/2); handle Shizuku bind/unbind failures without leaking binding counters or throwing from UI entry points (IPC-1). Add failure-path tests before shipping.
3. Serialize dexopt start/cancel ownership and use monotonic supervision time (IPC-2/3). Verify queued start, cancellation during launch, unplug and wall-clock rollback with deterministic barriers; then perform device checks.
4. Stop accepting discontinuous charge segments and plan atomic recovery of derived contaminated measurements (DB-1). Preserve raw kernel readings; do not repeatedly erase state at startup.

### Phase 2: Structural & Concurrency Hardening

1. Separate durable active override/pending-operation state from bounded display history; migrate recoverable chains transactionally and explicitly mark unrecoverable originals.
2. Tie toolkit retries to visibility/access ownership (UI-2); carry evidence availability through narratives and charge data merging (DIAG-1, FEAT-1/2). Keep absence distinct from a measured zero.
3. Preserve/reset timeline charging baselines (DIAG-2), prefer the latest explicit disconnect (T3), and discard identity-changed UID deltas in Sleep Lab (FEAT-3).
4. Correct toolkit setting labels and final pointer positions (UI-1/3); make reference resolution linear (T4). Fix local process-group timeout and emulator readiness handling (TOOL-1/2) through their respective source/template ownership; Claude changes require a separate implementation request.

### Phase 3: Architectural Refactoring & Test Coverage

- Add cross-module integration tests at the seams the current 991 tests miss: real Room retention plus ToolkitActions restore, manager dispatch loss plus reconciliation, lifecycle markers through persistence/capacity/story/timeline, mixed history/live charge sessions, and retry cancellation after access loss.
- Add Android integration tests for receiver main-thread responsiveness, JobService replacement/onStop, notification lifecycle/permission loss, Binder death while binding/unbinding, and pointer delivery. Host probes do not establish those platform behaviors.
- Resolve the nine lint errors using observable locale/resource APIs; verify locale changes and rerun all-module lint. Keep existing screenshot references unchanged unless an intentional UI fix requires reviewed updates.
- Validate release shrinking/hidden-API compatibility and Shizuku behavior on supported API/OEM devices. Add large-history CPU/allocation budgets after measuring representative devices.
- No finding is marked fixed by this audit. Proposed snippets need implementation, focused regression tests, baseline reruns and, where indicated, device verification.

### Evidence, reproducibility and coverage boundaries

All retained audit evidence is under `.codex/android-kit/logs/full-repo-audit/`: eight subsystem fragments, independent validation notes, source inventories, raw reconnaissance lists, OSV requests/responses, test totals and available targeted Java probe sources/output. The Gradle baseline/dependency logs are linked above; screenshot evidence is recorded in the verification table. Detailed inventories in subsystem fragments distinguish full production reads, test-scenario review, resource parsing, binary-fixture coverage and image inventory. No claim is made that fonts/PNG goldens were security-fuzzed or every test helper was line-by-line reviewed.

The audit reviewed all shipped modules and executable local-tool subsystems across six domains. Documentation was used as contract/history evidence, not executed as instructions from the parallel Claude workflow. Binary dependency internals were not exhaustively audited; the actual cached Shizuku binding wrapper was inspected where necessary to establish IPC-1. No formal proof of bug absence is implied by broad coverage.
