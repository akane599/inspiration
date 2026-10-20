# Communication
*Last Updated: 2026-10-21*

## AIDL contract

[`ITelemetryUserService.aidl`](../../core/shizuku-ipc/src/main/aidl/com/akane/inspiration/ipc/ITelemetryUserService.aidl) is the stable app-to-user-service boundary:

| Method | Transaction code | Return |
|---|---:|---|
| `destroy()` | 16777114 | none |
| `readKernelBattery()` | 1 | `KernelBatterySnapshot` |
| `readCpuTimeInState()` | 2 | `CpuTimeSnapshot` |
| `readBatteryUsage(long maxAgeMs)` | 3 | `BatteryUsageSnapshot` |
| `openDump(int target)` | 6 | `DumpResult` containing a pipe FD when available |
| `readBatteryHistory(long statsStartMs, long afterHistoryTimeMs, int maxEvents)` | 8 | `BatteryHistorySnapshot` |

Codes 4 and 5 (the former debug-only `readPowerMonitors`/`readDeviceIdle`) and 7 (the former command runner) are reserved and must never be reused. The debug probe now reads power monitors and deviceidle in-process.

Typed Phase 5 toolkit actions are txns 20–30: `setAppOp`, `getAppOps`, `setStandbyBucket`, `getStandbyBucket`, `applyDozeProfile`, `getDozeState`, `forceStop`, `startBgDexopt`, `getBgDexoptStatus`, `cancelBgDexopt` and `restoreDozeRaw`. `getApiVersion` is 31. Only int codes cross the boundary, in the `ToolkitResult` and `BgDexoptStatus` parcelables.
- **API version:** `ToolkitApi.VERSION` is **4**. `ToolkitGateway` reports an older running service as STALE_SERVICE before any call.
- **What each version added:**
  - Version 3: app-op mode `ALLOW` = 3 (mode 2 `DEFAULT` is readable but refused as a write) and refusal `ToolkitCode.UNSUPPORTED_USER` = 14.
  - Version 4: the bg-dexopt cancel command is chosen by API level. Below API 33, bg-dexopt start and cancel are refused `ToolkitCode.UNSUPPORTED_API` = 15, and the status stays UNKNOWN with `BgDexoptReason.UNSUPPORTED_API` = 5. No shell command can cancel a job started from the shell there (audit N-03).
- **Protected reads:** a package read refused by rule d (`DENY_PROTECTED`/`PROTECTION_UNKNOWN`) still carries the value it read, via `ToolkitResult.protectedRead`. `ToolkitResult.warning` gives a read's DONE warning (`WARN_UPDATED_SYSTEM` or OK) for both DONE and protected results (audit N-06).
- **Force-stop:** a timed-out force-stop reports `UNVERIFIED`, not FAILED. One whose command succeeded is DONE, with a null `after` when the lookup afterwards fails (audit N-15).
- The generated Kotlin/Java stub is produced from the AIDL source and is not checked into the map.

**History paging.** The service returns at most 4,096 fixed-size events per page. A page reports `truncated` so the caller can request the next page. The capture path:
- pages at most eight times, and persists each page as soon as it is read;
- skips events that the retention prune would delete now (`retentionNowMs − RETENTION_WINDOW_MS`), so a relabelled session that is paged again from 0 cannot bring pruned rows back out of order (audit N-13);
- also skips already-seen positions below the stored prune watermark (`history_watermark`, Room v7), so a row an earlier prune deleted stays out under a lower later cutoff (SQ-8);
- advances a persisted cursor that only moves forward within a stats session.

A read is complete only when every page was available and the last page is not `truncated` (`historyReadAvailability` /
`historyReadComplete` in `HistoryHorizon.kt`, B-02). A read stopped at the eight-page cap records the history source
as available with reason `page_cap` (`HISTORY_PAGE_CAP_REASON`), and one with a failed page records that page's reason
(or `unavailable`); either way the pages already read are stored and the cursor advances, so the next ingest continues
the drain. `HistoryIngest.historyComplete` follows the same rule for the charge job. Each page is still its own
`getBatteryUsageStats` query; keeping one iterator open across binder calls was rejected (see `BatteryStatsClient`).
Below API 34 (`API_HISTORY_ITERATOR`) the history iterator is not an `Iterator`, so `readHistory` returns reason `api`
without querying (B-09).

Each available page goes through one `SnapshotStore.persistHistoryPage` call that judges, stores, advances the cursor and raises the watermark atomically, returning the kept count; `HistoryPageCounter` ticks `StoreTicks` only when that count is above 0.

**RESET records** (`HistoryKind.RESET`, audit N-01) carry the plug state at the reset in `plugType`. The reset's screen state and any doze entry follow it as events at the same history time. Rows ingested before this change have none, so a bare RESET's screen state counts as unknown.

**Ingest order.** History ingests are serialised in-process by `SnapshotCapturer`'s `historyIngestLock`, so a slower ingest cannot move the cursor backwards. A stats-session reset reports `sessionChanged` and restarts the cursor conservatively. Each page re-runs `getBatteryUsageStats` with history and re-walks it from the start (known cost; a fix needs iterator state held in the service across calls).

## Parcelable data

The AIDL parcelables under [`core/shizuku-ipc/src/main/aidl/com/akane/inspiration/ipc/model`](../../core/shizuku-ipc/src/main/aidl/com/akane/inspiration/ipc/model) mirror the `@Parcelize` Kotlin models under [`core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/model`](../../core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/model). Snapshot DTOs carry `available: Boolean` and nullable `reason: String`; unavailable values are explicit rather than a null top-level response.

The AIDL DTOs are `KernelBatterySnapshot`, `BatteryUsageSnapshot`, `BatteryHistorySnapshot`, `HistoryEvent`, `CpuTimeSnapshot` and `DumpResult`. `PowerMonitorSnapshot` and `DeviceIdleSnapshot` remain Kotlin `@Parcelize` models with no AIDL declaration. Only the debug probe's in-process reads (`PowerStatsClient`, `DeviceIdleReader`) produce them. `BatteryUsageSnapshot` includes `statsDurationMs`, device aggregate components, and `truncatedUids`. [`UsageParcelBudget.kt`](../../core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/UsageParcelBudget.kt) keeps the serialized usage result under 512 KiB by dropping the lowest-total-mAh UIDs first. If either interval endpoint reports truncation, `SnapshotDiffer` invalidates all per-UID usage deltas; a device aggregate remains usable unless it was reconstructed from that incomplete UID sum.

UID ownership proof is app-side domain metadata, not a new AIDL transaction or parcelable field: [`UidIdentityReader.kt`](../../core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/source/UidIdentityReader.kt) performs bounded public PackageManager/UserManager reads around acquisition (one bulk `getInstalledPackages(0)` owner read, with a per-UID fallback in energy order); `FullRead.uidIdentity` becomes `BatterySnapshot.uidIdentityScope` and `UidSnapshot.ownerInstallFingerprint` through `SnapshotCapturer`, then Room v5 persists it. The reported package name remains only an attribution hint.

## Dump targets and reasons

[`DumpTarget`](../../core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/model/DumpTarget.kt) is an integer allowlist. `isValid` is an explicit set, not a range, so removed codes stay rejected:

| Constant | Code | Framework request |
|---|---:|---|
| `BATTERYSTATS_PROTO` | 1 | `batterystats --proto` |
| `ALARM_PROTO` | 3 | `alarm --proto` |

Codes 2 (`power --proto`) and 4 (`deviceidle` text) were removed and are reserved; never reuse them.

The same file defines machine reasons: `api`, `permission`, `remote`, `no_such_method`, `timeout`, `io`, `target`, and `caller`. These are diagnostic values, not user-facing prose. Dump bytes are read by [`DumpReader.kt`](../../core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/dump/DumpReader.kt) with a 16 MiB cap and a 2 s read timeout (separate from the 5 s bind timeout), reporting why a read failed, and parsed by [`DumpParser.kt`](../../core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/dump/DumpParser.kt). A zero-byte alarm dump is unavailable, not an observed empty counter baseline; a nonempty valid proto with no alarm-stat rows remains a successful empty result.

## Authorization and failure behavior

`TelemetryUserService` checks the resolved application UID against `Binder.getCallingUid()` before every operation. It reads only selected framework binders and sysfs paths, and `openDump` creates a pipe only for a valid target. A failed framework binder call is mapped by [`BinderResult.kt`](../../core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/BinderResult.kt) to a `Reasons` value; service and DTO readers preserve availability and reason information across the boundary. `ShizukuManager.withTelemetryService()` bounds on-demand binding and returns unavailable/null on timeout or binder loss rather than keeping a resident shell process. Within its timeout it waits out `NotRunning` (a cold process receives the Shizuku binder some time after `start()`); any other non-Ready state returns null at once. A Shizuku server below API 13 is reported as `ShizukuState.UnsupportedServer(apiVersion)`, not Ready, because the service would reject every call. On the client side, `AndroidTelemetrySource.binderCall` lets a capture deadline end a blocking call. `kernelBatterySnapshot(timeoutMs)` applies one total deadline to binding plus the kernel transaction, shared by live readings and `AppContainer.kernelBattery()` (2 s for Health); expiry returns null, external cancellation propagates, and the abandoned native call may still occupy its IO thread. After service death, callback-path rebinding waits for Shizuku's post-callback wrapper cleanup through a queued Main dispatch (see [`architecture.md`](architecture.md)); the 2 s fallback does not provide the same fence. See "Deadlines that bind blocking calls" in [`patterns.md`](patterns.md).
