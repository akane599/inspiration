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
| `readBatteryHistory(long statsStartMs, long afterHistoryTimeMs, int maxEvents)` | 8 | `BatteryHistorySnapshot` |

Code 7 is explicitly reserved and must not be reused; typed Phase 5 actions start at 20. The generated Kotlin/Java stub is produced from the AIDL source and is not checked into the map.

History reads are bounded by the service to at most 4,096 fixed-size events per page. A page reports `truncated` so the caller can request the next page; the capture path limits paging to eight pages and advances a persisted cursor. A stats-session reset reports `sessionChanged` and restarts the cursor conservatively.

## Parcelable data

The AIDL parcelables under [`core/shizuku-ipc/src/main/aidl/com/akane/inspiration/ipc/model`](../../core/shizuku-ipc/src/main/aidl/com/akane/inspiration/ipc/model) mirror the `@Parcelize` Kotlin models under [`core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/model`](../../core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/model). Snapshot DTOs carry `available: Boolean` and nullable `reason: String`; unavailable values are explicit rather than a null top-level response.

The main DTOs are `KernelBatterySnapshot`, `BatteryUsageSnapshot`, `BatteryHistorySnapshot`, `HistoryEvent`, `PowerMonitorSnapshot`, `DeviceIdleSnapshot`, `CpuTimeSnapshot`, and `DumpResult`. `BatteryUsageSnapshot` includes `statsDurationMs`, device aggregate components, and `truncatedUids`. [`UsageParcelBudget.kt`](../../core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/UsageParcelBudget.kt) keeps the serialized usage result under 512 KiB by dropping the lowest-total-mAh UIDs first.

## Dump targets and reasons

[`DumpTarget`](../../core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/model/DumpTarget.kt) is an integer allowlist:

| Constant | Code | Framework request |
|---|---:|---|
| `BATTERYSTATS_PROTO` | 1 | `batterystats --proto` |
| `POWER_PROTO` | 2 | `power --proto` |
| `ALARM_PROTO` | 3 | `alarm --proto` |
| `DEVICEIDLE_TEXT` | 4 | `deviceidle` with no args |

The same file defines machine reasons: `api`, `permission`, `remote`, `no_such_method`, `timeout`, `io`, `target`, and `caller`. These are diagnostic values, not user-facing prose. Dump bytes are read by [`DumpReader.kt`](../../core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/dump/DumpReader.kt) with a 16 MiB cap and parsed by [`DumpParser.kt`](../../core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/dump/DumpParser.kt).

## Authorization and failure behavior

`TelemetryUserService` checks the resolved application UID against `Binder.getCallingUid()` before every operation. It reads only selected framework binders and sysfs paths, and `openDump` creates a pipe only for a valid target. A failed framework binder call is mapped by [`BinderResult.kt`](../../core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/BinderResult.kt) to a `Reasons` value; service and DTO readers preserve availability and reason information across the boundary. `ShizukuManager.withTelemetryService()` bounds on-demand binding and returns unavailable/null on timeout or binder loss rather than keeping a resident shell process.
