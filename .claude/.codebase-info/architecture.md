# Architecture
*Last Updated: 2026-10-17*

## Runtime shape

The repository is a single Android application assembled from `:app`, convention-backed `:core:*` libraries, and convention-backed `:feature:*` libraries. Phase 2 adds a pull-first snapshot pipeline: light snapshots use local battery state and clocks; full snapshots combine BatteryUsageStats, parsed framework dumps, kernel battery values, CPU time-in-state, and optional battery history before persistence.

```
MainActivity.onStart → InspirationApplication.captureAppOpen()
                     └→ SnapshotCapturer.captureFull(APP_OPEN)
                          ├→ AndroidTelemetrySource
                          │    └→ ShizukuManager.withTelemetryService()
                          │         └→ TelemetryUserService (shell UID)
                          │              ├→ BatteryUsageStats + history
                          │              ├→ allowlisted batterystats/power/alarm dumps
                          │              └→ kernel/sysfs and CPU readers
                          └→ RoomSnapshotStore → InspirationDatabase (WAL)

RuntimeCaptureReceiver ──light/full policy──┘
BootCaptureReceiver ──light snapshot + arm JobScheduler
ChargingCaptureJobService ──full snapshot + history + prune
```

`MainActivity` triggers a debounced app-open capture. `InspirationApplication` owns the application scope, registers the non-exported runtime receiver, and wires `AppContainer`. `AppContainer` creates `AndroidTelemetrySource`, `SnapshotCapturer`, and the Room-backed `SnapshotStore`.

## Layers and responsibilities

- **Application shell — `:app`**: manifest, launcher activity, application/container wiring, capture policy, runtime and boot receivers, persisted charging `JobService`, backup exclusions, placeholder Home UI, and debug probes.
- **Feature layer — `:feature:dashboard`, `:feature:xray`, `:feature:sleeplab`, `:feature:health`, `:feature:toolkit`**: Compose library boundaries with shared telemetry/diagnostics dependencies. They still contain no implemented screens.
- **Telemetry layer — `:core:telemetry-engine`**: snapshot models, clock/source abstractions, trimmed protobuf parsing, full/light capture and merge, snapshot diff and reference resolution, capacity estimation, live telemetry, calibration input, and `SnapshotStore`.
- **Persistence layer — `:core:database`**: Room 2.8.5 schema v1, entities/DAOs, explicit WAL configuration, domain mappers, history cursor/deduplication, markers, charge sessions, capture misses, and 30-day pruning/downsampling.
- **IPC layer — `:core:shizuku-ipc`**: Shizuku lifecycle and ref-counted binding, AIDL contract, parcelable telemetry/history DTOs, framework binder readers, allowlisted dumps, battery history, and shell-service authorization.
- **Stub layer — `:core:hidden-api-stub`**: compile-time declarations for selected hidden Android interfaces used by IPC clients.
- **Reserved layer — `:core:diagnostics`**: included in the build but still has no source; rules and user-facing insights are later-phase work.
- **Build layer — `build-logic/convention`**: shared Android library, Compose library, and feature convention plugins.

## Capture and persistence flow

1. `CapturePolicy` selects a light or full plan. SCREEN_ON/OFF always write light snapshots; power and battery broadcasts use full capture only when the telemetry service is already warm, otherwise they fall back to light. Full broadcast work is bounded by a 2-second deadline.
2. Boot/package-replaced writes a light BOOT snapshot and arms the persisted charging job. The job ingests history, skips duplicate work for the current plug session, captures a full CHARGE_JOB snapshot when needed, prunes old data, and re-arms itself with its minimum latency.
3. `AndroidTelemetrySource.full()` reads usage, dumps, kernel values, CPU times, and paged history through `withTelemetryService`; `light()` reads sticky battery state, BatteryManager values, and clocks without Binder.
4. `SnapshotCapturer` merges partial sources, records source availability and session skew, persists a `BatterySnapshot`, and records misses instead of retrying in a loop. History pages advance the persisted cursor and deduplicate in Room.
5. `RoomSnapshotStore` writes the snapshot and child rows transactionally. `SnapshotDiffer`, `ReferenceResolver`, and capacity code consume stored snapshots/history for explicit validity, sleep, interval, and capacity calculations.

## Privileged data flow

1. `ShizukuManagerImpl` watches Shizuku state but binds the user service on demand through `withTelemetryService`; `holdBinding()` is the explicit UI keep-warm holder.
2. Shizuku launches `TelemetryUserService` under the shell UID. It resolves the app UID and rejects every AIDL call unless `Binder.getCallingUid()` matches it.
3. Authorized calls read framework services (`batterystats`, `powerstats`, `deviceidle`, `power`) through binder clients, selected sysfs nodes, CPU frequency paths, and `BatteryUsageStats` history.
4. Results cross the AIDL boundary as parcelable DTOs with `available` and machine-readable `reason` fields. Dumps use an allowlisted `DumpTarget` and a capped pipe; arbitrary command execution is not exposed.

The debug receiver exercises permission, IPC, capture, diff, direct readers, dumps, and timing. See [`entry-points.md`](entry-points.md) and [`onboarding.md`](onboarding.md).

## Product boundaries

Phase 2 deliberately leaves UI screens, charts, ViewModels, toolkit actions, and the Phase 3 rule engine out of the implemented path. No polling, resident/foreground service, WorkManager loop, or DI framework is introduced. Device-specific sysfs availability, hidden API signatures, synthetic emulator batteries, and history availability remain runtime assumptions recorded in [`PROGRESS.md`](../../PROGRESS.md). The selected Phase 2 decisions and reopen conditions are in [`docs/plans/phase-2.md`](../../docs/plans/phase-2.md).
