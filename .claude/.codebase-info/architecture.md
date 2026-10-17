# Architecture
*Last Updated: 2026-10-17*

## Runtime shape

The repository is a single Android application assembled from `:app`, convention-backed `:core:*` libraries, and convention-backed `:feature:*` libraries. The app still presents the placeholder Home screen, but the Phase 1 privileged telemetry path is implemented in `:core:shizuku-ipc`.

```
MainActivity → HomeRoute/HomeViewModel → AppContainer
                                      └→ ShizukuManager
                                           └→ Shizuku user-service binding
                                                └→ TelemetryUserService (shell UID)
                                                     ├→ framework binders
                                                     └→ /sys/class/power_supply and CPU sysfs
```

`MainActivity` installs the Compose theme and `HomeRoute` (`app/src/main/kotlin/com/akane/inspiration/MainActivity.kt`). `InspirationApplication` enables Hidden API exemptions when possible and creates `AppContainer` (`app/src/main/kotlin/com/akane/inspiration/InspirationApplication.kt`, `AppContainer.kt`). The container owns the application-scoped `ShizukuManagerImpl`.

## Layers and responsibilities

- **Application shell — `:app`**: Android manifest, launcher activity, application/container wiring, placeholder Home UI, and the debug-only probe receiver.
- **Feature layer — `:feature:dashboard`, `:feature:xray`, `:feature:sleeplab`, `:feature:health`, `:feature:toolkit`**: Compose library modules with shared telemetry/diagnostics dependencies from the feature convention plugin. Their current source trees are empty; they are module boundaries, not implemented screens.
- **IPC layer — `:core:shizuku-ipc`**: Shizuku lifecycle state, user-service binding, AIDL contract, parcelable telemetry DTOs, framework binder readers, allowlisted dumps, and shell-service authorization.
- **Telemetry layer — `:core:telemetry-engine`**: Phase 1 calibration (`PowerCalibrator`) over kernel and `BatteryManager` readings. Snapshot diffing, persistence, and domain insights are not implemented yet.
- **Stub layer — `:core:hidden-api-stub`**: compile-time declarations for selected hidden Android interfaces used by IPC clients.
- **Reserved layers — `:core:diagnostics`, `:core:database`**: included in the build but currently contain only module configuration. Room and the diagnostic rule engine are deferred.
- **Build layer — `build-logic/convention`**: shared Android library, Compose library, and feature convention plugins.

## Privileged data flow

1. The app starts `ShizukuManagerImpl`, which registers Shizuku binder, death, and permission listeners.
2. If the Shizuku manager is installed, running, and permission is granted, the manager reports `ShizukuState.Ready` and binds `TelemetryUserService` through `Shizuku.UserServiceArgs`.
3. Shizuku launches the user service under the shell UID. `TelemetryUserService` resolves the app UID and rejects every AIDL call unless `Binder.getCallingUid()` matches it.
4. Authorized calls read framework services (`batterystats`, `powerstats`, `deviceidle`, `power`) through binder clients or read selected sysfs nodes under `/sys/class/power_supply` and CPU frequency policy paths.
5. Results cross the AIDL boundary as `@Parcelize` DTOs with `available` and machine-readable `reason` fields. Dumps use an allowlisted `DumpTarget` and a pipe; arbitrary command execution is not exposed.

The debug probe exercises this path, calibration, direct binder wrappers, dumps, and warm-call timing. See `app/src/debug/kotlin/com/akane/inspiration/debug/ProbeReceiver.kt` and [`onboarding.md`](onboarding.md).

## Product boundaries

Phase 1 deliberately keeps Room, diagnostics, screen architecture, and typed actions out of the implemented path. Device-specific sysfs availability, hidden API signatures, and SoC power monitors remain runtime assumptions recorded in [`PROGRESS.md`](../../PROGRESS.md). The Phase 1 decisions and rationale are in [`docs/plans/phase-1.md`](../../docs/plans/phase-1.md).
