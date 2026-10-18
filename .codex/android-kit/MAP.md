# Inspiration repository map

Mapped on 2026-10-18 at HEAD `483745fd03642542e8db2bebab497c71b8594634` (branch `Review-n-fix`). This is a focused source/build inspection, not an exhaustive audit. Update affected entries when their mapped paths change. Generated build output and secrets are excluded.

## Build and modules

`settings.gradle.kts` includes 12 modules. `gradle/libs.versions.toml` holds dependencies/plugins; `gradle/wrapper/gradle-wrapper.properties` pins Gradle. Shared Android, Compose and feature conventions live under `build-logic/convention/src/main/kotlin/com/akane/inspiration/convention/` (`AndroidLibraryConventionPlugin`, `AndroidLibraryComposeConventionPlugin`, `AndroidFeatureConventionPlugin`).

| Path | Responsibility and key symbols |
| --- | --- |
| `app/src/main/kotlin/com/akane/inspiration/` | Composition root and lifecycle: `InspirationApplication`, `MainActivity`, `AppContainer`. |
| `app/src/main/kotlin/com/akane/inspiration/shell/` | `AppShell`, destination selection, saved screen state, phone navigation bar and wide navigation rail. |
| `app/src/main/kotlin/com/akane/inspiration/di/` | Refresh coordination, timeline seeds, preloading, labels and installed app entries. |
| `app/src/main/kotlin/com/akane/inspiration/capture/` | Event receivers, capture policy, charge health pipeline, `ChargingCaptureScheduler`, `ChargingCaptureJobService`. |
| `app/src/main/kotlin/com/akane/inspiration/status/` | Status notification controller/settings/ticks and `StatusNotificationService`. |
| `core/hidden-api-stub/` | Compile-time framework API stubs; `shizuku-ipc` uses this module as `compileOnly`. |
| `core/shizuku-ipc/src/main/` | AIDL boundary `ITelemetryUserService`, `ShizukuManagerImpl`, `TelemetryUserService`, framework clients, parcelable models and toolkit execution. |
| `core/telemetry-engine/src/main/` | Domain telemetry, proto dump parsing, `AndroidTelemetrySource`, `SnapshotCapturer`, `LiveTelemetry`, diffs, calibration, capacity/interval calculations, store interfaces and toolkit actions. |
| `core/database/src/main/kotlin/com/akane/inspiration/database/` | Room entities/DAOs, `InspirationDatabase` (schema version 3), `store/RoomSnapshotStore`. Schema exports live in `core/database/schemas/`. |
| `core/diagnostics/src/main/` | `AnalysisSession`, `IntervalAnalyzer`, `TimelineBuilder`, diagnostic rules/dictionary, sensors and battery stories. |
| `core/designsystem/src/main/` | Theme/tokens, reusable components, chart math/models and gesture/scrub/hypnogram rendering. |
| `feature/dashboard/` | Cockpit dashboard; `CockpitViewModel`, dependencies, state, intents/effects and route. |
| `feature/xray/` | Per-app analysis; `XRayViewModel` and feature UI/dependencies. |
| `feature/sleeplab/` | Sleep diagnostics; `SleepLabViewModel` and feature UI/dependencies. |
| `feature/health/` | Battery health; `HealthViewModel` and feature UI/dependencies. |
| `feature/toolkit/` | User-triggered device actions; `ToolkitViewModel` and feature UI/dependencies. |

## Confirmed flows and boundaries

- `app/src/main/AndroidManifest.xml` registers `InspirationApplication`, launcher `MainActivity`, Shizuku provider, boot receiver, charging job service and status foreground service. The main activity is exported; the app's capture/status services and boot receiver are not.
- `InspirationApplication` creates `AppContainer`, starts Shizuku/container work and registers runtime capture receivers. `MainActivity` renders `AppShell` and supplies each route its feature-specific interface.
- Feature conventions depend on designsystem, telemetry-engine and diagnostics. `AppContainer` supplies all five dependency interfaces. Representative `CockpitViewModel` confirms StateFlow UI state, intent handling and an effects channel; live reads and binding ownership follow visibility.
- Telemetry-engine depends on shizuku-ipc. `AndroidTelemetrySource` combines ordinary battery APIs with service reads; `SnapshotCapturer` persists snapshots/history through `SnapshotStore`; `RoomSnapshotStore` implements that contract. Diagnostics depends on telemetry-engine and analyzes stored snapshots/intervals.
- `InspirationDatabase.create` opens `inspiration.db` using WAL. Database version is 3 with schema exports. MIGRATION_1_2 permits unknown cycleCount; MIGRATION_2_3 invalidates derived charge sessions/measured health once while preserving raw kernel health.
- `ChargingCaptureScheduler` uses a persisted, charging-constrained JobScheduler job (ID 29), rearmed from a job with a 45-minute minimum latency. Runtime events and app-open refresh also trigger capture. `StatusNotificationService` updates the persistent status notification from container state/ticks.
- `TelemetryUserService` is the privileged shell-UID boundary and declares caller checks. This map confirms the boundary's implementation exists; it does not certify its security or behavior on a device.

## Verification commands and evidence

Run from the checkout root:

- Discover tasks: `bash .codex/android-kit/gradle-check.sh tasks --all` (passed; log `.codex/android-kit/logs/gradle.LmqaC4Nx.log`).
- Baseline: `bash .codex/android-kit/gradle-check.sh :app:assembleDebug testDebugUnitTest validateDebugScreenshotTest` failed with exit 1 because the Gradle daemon disappeared. A fresh debug APK and 361 passing test results across three modules were produced; the complete unit/screenshot baseline is not verified. Full log: `.codex/android-kit/logs/gradle.9Rvo16ps.log`; board task #2 retains failure evidence. An incomplete NDK 28.2.13676358 installation was also reported; causation was not established. See PROJECT.md for details.
- Focused test: `bash .codex/android-kit/gradle-check.sh :feature:dashboard:testDebugUnitTest --tests 'com.akane.inspiration.dashboard.CockpitViewModelTest'` (available command, not separately executed in this map pass).
- UI validation: `bash .codex/android-kit/gradle-check.sh :<actual-ui-module>:validateDebugScreenshotTest`. Screenshot tasks exist for app, core:designsystem and all five features. Existing sources are under `src/screenshotTest/`; checked-in references are under `src/screenshotTestDebug/reference/`.
- Unit tests are under `src/test/` with additional app debug tests under `app/src/testDebug/`. Robolectric/database and diagnostic tests cover Android-dependent host behavior; this is separate from device verification.
- Lint task example: `bash .codex/android-kit/gradle-check.sh :app:lintDebug` (not run in this bootstrap). Kit health: `python3 .codex/android-kit/doctor.py`.

## Risks and limits

- Privileged Binder/framework APIs, dump formats, sysfs units and Shizuku lifecycle require device-specific evidence. No device/permission/IPC probe was performed here.
- Collection ownership, capture timing and visibility guards matter to battery impact; do not infer zero runtime overhead from architecture or host tests.
- Toolkit commands can change device settings; inspect their restore/persistence behavior and callers before changing the gateway or executor.
- Screenshot validation compares existing references; do not regenerate references merely to make a baseline pass. Passing comparisons do not replace visual review for future UI changes.
- Release minification/signing, store policy compliance, full dependency compatibility and all device/API combinations are outside this focused adoption pass. No network client dependency was observed in the inspected catalog/build files; that is not a full networking audit.

## Whole-repository audit refresh — 2026-10-18

Current audited HEAD: `eb3fe08185995a7925db4bc97d44c4da1cf1048c`. Earlier map/baseline entries describe the prior commit. All 12 modules were included in the audit; details and limitations are in root `AUDIT_REPORT.md`. Fresh `testDebugUnitTest --rerun --max-workers=2` passed: 991 tests, zero failures/errors/skips, 87 fresh XML reports. Log: `.codex/android-kit/logs/gradle.SlSFi8yK.log`. `lintDebug testDebugUnitTest --continue --max-workers=2` exited 1 with nine lint errors across five modules; log: `.codex/android-kit/logs/gradle.TNYcdNUC.log`. Release/device validation remains unperformed; subsequent screenshot evidence is recorded below.

- Subsequent audit screenshot validation: `bash .codex/android-kit/gradle-check.sh validateDebugScreenshotTest --continue --max-workers=1` passed; 167 results, zero failures/errors/skips, 76 fresh comparisons and 91 reused results. References unchanged. Log `.codex/android-kit/logs/gradle.apFs2oG1.log`.

## Remediation refresh — 2026-10-18

Source tip `f4365f0`, branch `fix/audit-remediation-20261018`. All 20 audit IDs have isolated verified commits; root AUDIT_REPORT.md contains status, paths, commands and limitations.

- Broadcast captures are asynchronous and deadline-bounded through BroadcastCaptureRunner.start; completion releases pending results even on cancellation. RefreshCoordinator converts operational capture failures into failed/backoff results and preserves cancellation.
- Shizuku bind/unbind loss clears transport state while owner handles release normally. Dexopt queued starts honor cancellation; supervision uses elapsedRealtime, with wall timestamps retained.
- Uncertain toolkit writes remain pending until readback; pending/successful restore-chain rows survive display-history pruning. Recovery reads are not capped at 500. A separate compact durable override store remains future architecture work.
- Room historySessions retains per-row epochs; charge extraction clears on RESET/START/SHUTDOWN. Database schema 3 invalidates older derived estimates once, preserving kernel fields.
- Diagnostics omit unsupported summaries/all-clear conclusions and retain charging baselines. Health merges history around foreground samples and preserves completed spans. Sleep Lab excludes package-changed lifetime counters. Toolkit labels carry app-op mode, retries follow visibility/access, and chart commits use the release coordinate.
- Final assemble/lint/fresh unit/screenshot gate passed: 1,023 unit tests, 167 screenshot comparisons, zero failures/errors/skips. Log `.codex/android-kit/logs/gradle.nK4SlGL9.log`; details in `.codex/android-kit/logs/remediation/final-results.json`.
- Six toolkit badge references were intentionally reviewed and updated; other references unchanged. Device/release validation remains unperformed.
