# Communication
*Last Updated: 2026-10-17*

## AIDL contract

[`ITelemetryUserService.aidl`](../../core/shizuku-ipc/src/main/aidl/com/akane/inspiration/ipc/ITelemetryUserService.aidl) is the stable app-to-user-service boundary:

| Method | Transaction code | Return |
|---|---:|---|
| `destroy()` | 16777114 | none |
| `readKernelBattery()` | 1 | `KernelBatterySnapshot` |
| `readCpuTimeInState()` | 2 | `CpuTimeSnapshot` |
| `readBatteryUsage(long maxAgeMs)` | 3 | `BatteryUsageSnapshot` |
| `readPowerMonitors()` | 4 | `PowerMonitorSnapshot` |
| `readDeviceIdle()` | 5 | `DeviceIdleSnapshot` |
| `openDump(int target)` | 6 | `DumpResult` containing a pipe FD when available |

Code 7 is explicitly reserved and must not be reused; typed Phase 5 actions start at 20. The generated Kotlin/Java stub is produced from the AIDL source and is not checked into the map.

## Parcelable data

The AIDL parcelables under [`core/shizuku-ipc/src/main/aidl/com/akane/inspiration/ipc/model`](../../core/shizuku-ipc/src/main/aidl/com/akane/inspiration/ipc/model) mirror the `@Parcelize` Kotlin models under [`core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/model`](../../core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/model). Snapshot DTOs carry `available: Boolean` and nullable `reason: String`; unavailable values are represented explicitly instead of by a null top-level response. The main DTOs are `KernelBatterySnapshot`, `BatteryUsageSnapshot`, `PowerMonitorSnapshot`, `DeviceIdleSnapshot`, `CpuTimeSnapshot`, and `DumpResult`.

## Dump targets and reasons

[`DumpTarget`](../../core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/model/DumpTarget.kt) is an integer allowlist:

| Constant | Code | Framework request |
|---|---:|---|
| `BATTERYSTATS_PROTO` | 1 | `batterystats --proto` |
| `POWER_PROTO` | 2 | `power --proto` |
| `ALARM_PROTO` | 3 | `alarm --proto` |
| `DEVICEIDLE_TEXT` | 4 | `deviceidle` with no args |

The same file defines machine reasons: `api`, `permission`, `remote`, `no_such_method`, `timeout`, `io`, `target`, and `caller`. These are diagnostic values, not user-facing prose.

## Authorization and failure behavior

`TelemetryUserService` checks the resolved application UID against `Binder.getCallingUid()` before every operation. It reads only the selected framework binders and sysfs paths, and `openDump` creates a pipe only for a valid target. A failed framework binder call is mapped by [`BinderResult.kt`](../../core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/BinderResult.kt) to a `Reasons` value; the service and DTO readers preserve availability and reason information across the boundary.
