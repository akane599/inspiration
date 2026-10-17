# Modules
*Last Updated: 2026-10-17*

The module graph is declared in [`settings.gradle.kts`](../../settings.gradle.kts). Shared Android configuration lives in [`build-logic/convention`](../../build-logic/convention).

## Application and build logic

| Module | Plugin / role | Status |
|---|---|---|
| `:app` | Android application; Compose shell, manifests, launcher, container, and debug probe | Implemented Phase 1 shell and Home placeholder |
| `build-logic:convention` | `inspiration.android.library`, `.library.compose`, and `.feature` convention plugins | Implemented shared configuration |

`:app` depends on all five feature modules and `:core:shizuku-ipc`. It adds `:core:telemetry-engine` only to the debug configuration for the probe.

## Core modules

| Module | Role | Status |
|---|---|---|
| `:core:hidden-api-stub` | Compile-only declarations for selected hidden Android framework interfaces | Implemented stubs |
| `:core:shizuku-ipc` | Shizuku lifecycle, AIDL, shell user service, framework binder readers, sysfs readers, DTOs, and allowlisted dumps | Implemented Phase 1 IPC |
| `:core:telemetry-engine` | Calibration of kernel/BatteryManager readings via `PowerCalibrator` | Implemented calibration slice |
| `:core:diagnostics` | Future deterministic insight/rule engine | Module exists; no source yet |
| `:core:database` | Future Room/WAL/history storage | Module exists; no source yet |

`core:telemetry-engine` exposes the IPC module and the five feature modules receive telemetry-engine and diagnostics through `AndroidFeatureConventionPlugin`.

## Feature modules

| Module | Intended surface | Status |
|---|---|---|
| `:feature:dashboard` | Battery cockpit/dashboard | Module configuration only |
| `:feature:xray` | Component attribution and telemetry details | Module configuration only |
| `:feature:sleeplab` | Screen-off/deep-sleep analysis | Module configuration only |
| `:feature:health` | Battery health and aging | Module configuration only |
| `:feature:toolkit` | User actions and fixes | Module configuration only |

The only current screen implementation is `HomeRoute`/`HomeScreen` under `:app`; feature module names are reserved boundaries for later phase work, not evidence that screens already exist.
