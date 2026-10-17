# Patterns
*Last Updated: 2026-10-17*

These patterns are established in Phase 1 and are worth preserving as new telemetry readers and features land.

## Explicit availability

Cross-process snapshots are `@Parcelize` data classes with `available` and nullable `reason` fields. Each model provides an `unavailable(reason)` factory, so missing permissions, framework APIs, sysfs nodes, and remote binders remain observable without exceptions crossing the AIDL boundary. Keep `Reasons` values machine-readable and put user-facing explanation in the UI/domain layer.

For direct framework binder clients, [`BinderResult<T>`](../../core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/BinderResult.kt) distinguishes `Ok(value)` from `Failed(reason)`. `withBinder` handles null binders and maps remote, permission, reflection, and linkage failures consistently.

## Version and capability gates

`ShizukuManagerImpl` gates readiness on manager presence, binder liveness, API compatibility (`isPreV11()`), and permission. The manager reports `ShizukuState` rather than exposing raw Shizuku calls to consumers. Capability-specific readers also return unavailable results when a framework service, hidden method, or sysfs node is absent. Do not infer support solely from the device API level.

## Fakes over mocks

IPC unit tests inject binder sources, service binders, file roots, clocks, pipe factories, and thread starters. [`core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/client/Fakes.kt`](../../core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/client/Fakes.kt) supplies recording and fake adapters; [`TelemetryUserServiceTest.kt`](../../core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/service/TelemetryUserServiceTest.kt) uses injected seams for authorization, filesystem, and pipe behavior. Prefer small deterministic fakes over a mocking framework when adding tests.

## Privileged boundary

Keep framework and sysfs access in the shell `TelemetryUserService`; the app process coordinates through `ShizukuManager` and consumes DTOs. Preserve the caller-UID check, the dump target allowlist, and pipe cleanup. Do not add a generic command runner or move privileged reads into Compose/ViewModel code.
