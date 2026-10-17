# Modules
*Last Updated: 2026-10-17*

The module graph is declared in [`settings.gradle.kts`](../../settings.gradle.kts). Shared Android configuration lives in [`build-logic/convention`](../../build-logic/convention).

## Application and build logic

| Module | Plugin / role | Status |
|---|---|---|
| `:app` | Android application; Compose shell, manifests, capture wiring, container, and debug probes | Implemented Phase 1 shell plus Phase 2 capture/persistence wiring |
| `build-logic:convention` | `inspiration.android.library`, `.library.compose`, and `.feature` convention plugins | Implemented shared configuration |

`:app` depends on all five feature modules, `:core:shizuku-ipc`, `:core:telemetry-engine`, and `:core:database`. Telemetry-engine is now a production implementation dependency rather than debug-only probe support.

## Core modules

| Module | Role | Status |
|---|---|---|
| `:core:hidden-api-stub` | Compile-only declarations for selected hidden Android framework interfaces | Implemented stubs used by IPC clients |
| `:core:shizuku-ipc` | Shizuku lifecycle/binding, AIDL, shell user service, framework readers, sysfs readers, DTOs, history, and allowlisted dumps | Implemented Phase 1 IPC plus Phase 2 history and usage-budget support |
| `:core:telemetry-engine` | Protobuf dump parser, snapshot models/capture, diff/reference resolution, capacity, live telemetry, calibration input, and store interface | Implemented Phase 2 domain pipeline |
| `:core:database` | Room schema v1, DAOs, WAL database, domain mappers, and retention/downsampling store | Implemented Phase 2 persistence |
| `:core:diagnostics` | Future deterministic insight/rule engine | Module exists; no source yet |

`core:telemetry-engine` exposes `core:shizuku-ipc`. `core:database` implements telemetry-engine's `SnapshotStore`. The feature modules receive telemetry-engine and diagnostics dependencies through `AndroidFeatureConventionPlugin`.

## Feature modules

| Module | Intended surface | Status |
|---|---|---|
| `:feature:dashboard` | Battery cockpit/dashboard | Module configuration only |
| `:feature:xray` | Component attribution and telemetry details | Module configuration only |
| `:feature:sleeplab` | Screen-off/deep-sleep analysis | Module configuration only |
| `:feature:health` | Battery health and aging | Module configuration only |
| `:feature:toolkit` | User actions and fixes | Module configuration only |

The only current screen implementation is `HomeRoute`/`HomeScreen` under `:app`; feature module names are reserved boundaries, not evidence that screens already exist.
