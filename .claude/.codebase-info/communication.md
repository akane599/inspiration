# Communication
*Last Updated: 2026-10-19*

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

Code 7 is explicitly reserved and must not be reused; typed Phase 5 toolkit actions are txns 20–30 (`setAppOp`, `getAppOps`, `setStandbyBucket`, `getStandbyBucket`, `applyDozeProfile`, `getDozeState`, `forceStop`, `startBgDexopt`, `getBgDexoptStatus`, `cancelBgDexopt`, `restoreDozeRaw`) and `getApiVersion` is 31; only int codes cross the boundary (`ToolkitResult`, `BgDexoptStatus` parcelables).
- **API version:** `ToolkitApi.VERSION` is **3**. `ToolkitGateway` reports an older running service as STALE_SERVICE before any call.
- **Version 3 adds:** app-op mode `ALLOW` = 3 (mode 2 `DEFAULT` is readable but refused as a write) and refusal `ToolkitCode.UNSUPPORTED_USER` = 14.
- **Timed-out force-stop:** reports `UNVERIFIED`, not FAILED. The generated Kotlin/Java stub is produced from the AIDL source and is not checked into the map.

History reads are bounded by the service to at most 4,096 fixed-size events per page. A page reports `truncated` so the caller can request the next page; the capture path limits paging to eight pages, persists each page as soon as it is read, and advances a persisted cursor that only moves forward within a stats session. History ingests are serialised in-process by `SnapshotCapturer`'s `historyIngestLock`, so a slower ingest cannot move the cursor backwards. A stats-session reset reports `sessionChanged` and restarts the cursor conservatively. Each page re-runs `getBatteryUsageStats` with history and re-walks it from the start (known cost; a fix needs iterator state held in the service across calls).

## Parcelable data

The AIDL parcelables under [`core/shizuku-ipc/src/main/aidl/com/akane/inspiration/ipc/model`](../../core/shizuku-ipc/src/main/aidl/com/akane/inspiration/ipc/model) mirror the `@Parcelize` Kotlin models under [`core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/model`](../../core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/model). Snapshot DTOs carry `available: Boolean` and nullable `reason: String`; unavailable values are explicit rather than a null top-level response.

The main DTOs are `KernelBatterySnapshot`, `BatteryUsageSnapshot`, `BatteryHistorySnapshot`, `HistoryEvent`, `PowerMonitorSnapshot`, `DeviceIdleSnapshot`, `CpuTimeSnapshot`, and `DumpResult`. `BatteryUsageSnapshot` includes `statsDurationMs`, device aggregate components, and `truncatedUids`. [`UsageParcelBudget.kt`](../../core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/UsageParcelBudget.kt) keeps the serialized usage result under 512 KiB by dropping the lowest-total-mAh UIDs first. If either interval endpoint reports truncation, `SnapshotDiffer` invalidates all per-UID usage deltas; a device aggregate remains usable unless it was reconstructed from that incomplete UID sum.

UID ownership proof is app-side domain metadata, not a new AIDL transaction or parcelable field: [`UidIdentityReader.kt`](../../core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/source/UidIdentityReader.kt) performs bounded public PackageManager/UserManager reads around acquisition (one bulk `getInstalledPackages(0)` owner read, with a per-UID fallback in energy order); `FullRead.uidIdentity` becomes `BatterySnapshot.uidIdentityScope` and `UidSnapshot.ownerInstallFingerprint` through `SnapshotCapturer`, then Room v5 persists it. The reported package name remains only an attribution hint.

## Dump targets and reasons

[`DumpTarget`](../../core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/model/DumpTarget.kt) is an integer allowlist:

| Constant | Code | Framework request |
|---|---:|---|
| `BATTERYSTATS_PROTO` | 1 | `batterystats --proto` |
| `POWER_PROTO` | 2 | `power --proto` (still allowlisted by the service; the app no longer reads it) |
| `ALARM_PROTO` | 3 | `alarm --proto` |
| `DEVICEIDLE_TEXT` | 4 | `deviceidle` with no args |

The same file defines machine reasons: `api`, `permission`, `remote`, `no_such_method`, `timeout`, `io`, `target`, and `caller`. These are diagnostic values, not user-facing prose. Dump bytes are read by [`DumpReader.kt`](../../core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/dump/DumpReader.kt) with a 16 MiB cap and a 2 s read timeout (separate from the 5 s bind timeout), reporting why a read failed, and parsed by [`DumpParser.kt`](../../core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/dump/DumpParser.kt). A zero-byte alarm dump is unavailable, not an observed empty counter baseline; a nonempty valid proto with no alarm-stat rows remains a successful empty result.

## Authorization and failure behavior

`TelemetryUserService` checks the resolved application UID against `Binder.getCallingUid()` before every operation. It reads only selected framework binders and sysfs paths, and `openDump` creates a pipe only for a valid target. A failed framework binder call is mapped by [`BinderResult.kt`](../../core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/BinderResult.kt) to a `Reasons` value; service and DTO readers preserve availability and reason information across the boundary. `ShizukuManager.withTelemetryService()` bounds on-demand binding and returns unavailable/null on timeout or binder loss rather than keeping a resident shell process. Within its timeout it waits out `NotRunning` (a cold process receives the Shizuku binder some time after `start()`); any other non-Ready state returns null at once. A Shizuku server below API 13 is reported as `ShizukuState.UnsupportedServer(apiVersion)`, not Ready, because the service would reject every call. On the client side, `AndroidTelemetrySource.binderCall` lets a capture deadline end a blocking call. `kernelBatterySnapshot(timeoutMs)` applies one total deadline to binding plus the kernel transaction, shared by live readings and `AppContainer.kernelBattery()` (2 s for Health); expiry returns null, external cancellation propagates, and the abandoned native call may still occupy its IO thread. After service death, callback-path rebinding waits for Shizuku's post-callback wrapper cleanup through a queued Main dispatch (see [`architecture.md`](architecture.md)); the 2 s fallback does not provide the same fence. See "Deadlines that bind blocking calls" in [`patterns.md`](patterns.md).
