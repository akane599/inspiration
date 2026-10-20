audited: 2026-10-17 (SQ-3 plan-review Opus/medium, SQ-4 review-audit GPT-5.6 Terra/medium): both APPROVE WITH FIXES, fixes applied below.

# Phase 1 — Multi-module Gradle + Shizuku IPC core (story US-1)

Source spec: `TEMPLATE.md` §1, §2, §7 (Turkish). Recon evidence: SQ-1 (hidden APIs / Shizuku), SQ-2 (AGP 9 toolchain).

## Outcome
A multi-module build matching TEMPLATE §1 and a working privileged IPC core:
- Shizuku connection and permission state.
- A shell-UID `TelemetryUserService`.
- Version-gated clients for `batterystats`, `powerstats`, `deviceidle` and `power`.
- A sysfs battery reader and an allowlisted proto-dump channel.
- A unit/sign/dual-cell calibration engine.

It is proven by unit tests on the JVM plus one emulator smoke run with Shizuku on API 35, 36 and 37.

## Non-goals (owning phase)
- **Phase 2:**
  - Protobuf `.proto` schemas and parsing.
  - Snapshot diffing.
  - Room (dependency, plugin, entities). Room 3.0.3 is preferred and gets adopted only after a DB-open smoke test, with Room 2.8.5 as the fallback.
  - Coulomb counting.
  - Production BatteryManager reader for `CalibrationInput`.
  - `usagestats` / `IActivityManager` clients (TEMPLATE §2.A.5).
- **Phase 3:** rule engine and wakelock dictionary.
- **Phase 4:** design system and charts.
- **Phase 5:** screens, and the toolkit actions (appops, standby bucket, doze constants, bg-dexopt). These arrive as **typed, allowlisted** UserService transactions, never a generic command runner.
- No DI framework.

## Decisions (with evidence)
| # | Decision | Why |
|---|---|---|
| D1 | **Privileged binder calls run primarily inside the shell-UID `TelemetryUserService`.** Clients take an `IBinder` so the same code can also run in the app process via `ShizukuBinderWrapper` + HiddenApiBypass (secondary path). The T6 smoke measures both paths on API 35/36/37, and Phase 2 picks the snapshot path from that data. | SQ-1: shell has no non-SDK restrictions, holds BATTERY_STATS/DUMP/DEVICE_POWER and is the only one of the two that can read `sysfs_batteryinfo`. `ShizukuBinderWrapper.dump()` runs as the app UID, and API 37 blocklist status is unverified. SQ-4 agrees. SQ-3's contrarian view (in-process primary) is kept testable rather than dismissed. |
| D2 | Hidden interfaces come from a hand-written **compileOnly** stub module `:core:hidden-api-stub` (`com.android.library`, Shizuku-demo layout, never packaged, Java sources). It has no stubs for public SDK classes (e.g. `android.os.PowerMonitor`). | SQ-1 §2. `com.android.library` gives `android.jar` on the classpath without hand-wiring. |
| D3 | Power monitors: framework `android.os.IPowerStatsService` (stub) via binder in the UserService, **gated to API ≥35** (where `PowerMonitor` is public). The oneway `getPowerMonitorReadings` is called with a `ResultReceiver(null)` + latch + 2 s timeout; a timeout gives `available=false`. API 31–34 → `available=false, reason="api"`. The HAL methods named in TEMPLATE are **not used**. | SQ-1 §3/§5. SQ-3: the recon was inconsistent about 14 vs 16 as the floor, so gating to 35 avoids the hidden API-34 class. |
| D4 | Kernel wakelocks are not read from sysfs/debugfs. They come in Phase 2 from the batterystats proto dump. | SQ-1 §5 (no SELinux allow for wakeup sources). |
| D5 | **No generic command runner and no free-form dump.** `openDump(int target)` only accepts the closed `DumpTarget` set below. Arguments are fixed inside the service. Every UserService method checks that `Binder.getCallingUid()` equals the app's UID (resolved once at construction via `PackageManager.getPackageUid`) and throws `SecurityException` otherwise. The spec's `executePrivilegeCommand` is replaced by typed actions in Phase 5. | SQ-4 (high/high): argv-only still allows arbitrary executables/flags, and a leaked shell binder is a full shell capability. |
| D6 | Versions: shizuku api/provider 13.1.5, hiddenapibypass **6.1** (6.2 is <2 weeks old; follow-up after 2026-10-24), KSP 2.3.12 (catalog only), protobuf plugin 0.10.0 + javalite/kotlin-lite 4.36.2 (catalog only; applied in Phase 2), parcelize `org.jetbrains.kotlin.plugin.parcelize`. **Room is not added in Phase 1.** | SQ-2. SQ-3 + SQ-4 both: Room with no entities has no oracle. |
| D7 | Shared build config through a `build-logic` included build with convention plugins. The compose convention adds the Compose BOM + runtime. | SQ-2 §5 (including the BOM gotcha). |
| D8 | Manual DI (`AppContainer` in `:app`). | No feature needs Hilt yet. |
| D9 | `BatteryUsageStatsQuery`: `includeProcessStateData`/`includeVirtualUids` only on API ≥33; `includePowerModels` only on API ≤34; always `setMaxStatsAgeMs(0)`. API 31/32 → `byProcessState = null`. **Amended 2026-10-21 (audit B-22):** FULL captures read the batterystats dump first and query usage with a 5 s max age, reusing the dump's external-stats sync; the debug probe keeps 0. | SQ-1 §3. |
| D10 | UserService args: `processNameSuffix("telemetry")`, `daemon(false)`, `version(versionCode)` where versionCode = `PackageInfo.longVersionCode` read by `ShizukuManagerImpl` (library modules have no `BuildConfig.VERSION_CODE`), `tag("telemetry")`, `debuggable(ApplicationInfo.FLAG_DEBUGGABLE)`. AIDL declares `void destroy() = 16777114;`. The app rebinds on `onBinderReceived`. | SQ-1 §4; SQ-3 blocker. SQ-4 agrees with `daemon(false)` for zero residency. |
| D11 | **Latency contract (for Phase 2):** <150 ms is promised only for an already-bound service. Cold event capture is best-effort with a finite deadline, and misses are recorded, never retried in a loop. T6 measures cold bind and warm call latency. | SQ-4 D1 finding vs TEMPLATE §6. |

## Module layout
```
build-logic/convention/   inspiration.android.library, inspiration.android.library.compose, inspiration.android.feature
core/hidden-api-stub      compileOnly Java stubs
core/shizuku-ipc          AIDL + DTOs, ShizukuManager, binder clients, TelemetryUserService
core/telemetry-engine     PowerCalibrator (pure Kotlin)
core/diagnostics          empty skeleton (no sources besides namespace)
core/database             empty skeleton
feature/{dashboard,xray,sleeplab,health,toolkit}  empty skeletons; NO composables in Phase 1 (avoids @PreviewTest obligations)
app                       InspirationApplication (HiddenApiBypass, AppContainer), ShizukuProvider, debug ProbeReceiver
```
Dependency direction: `app → feature:* → core:*`; `core:telemetry-engine → core:shizuku-ipc`; `core:shizuku-ipc` has compileOnly on `core:hidden-api-stub`.

## Shared surface (pinned; cross-process ABI: transaction IDs are permanent, never renumbered or reused)
```kotlin
// :core:shizuku-ipc  package com.akane.inspiration.ipc
sealed interface ShizukuState {
    data object NotInstalled : ShizukuState          // Shizuku Manager package absent (needs <queries>)
    data object NotRunning : ShizukuState            // installed, no binder / binder dead
    data object PermissionRequired : ShizukuState
    data object PermissionDenied : ShizukuState
    data class Ready(val uid: Int, val apiVersion: Int) : ShizukuState
}
interface ShizukuManager {
    val state: StateFlow<ShizukuState>
    val telemetryService: StateFlow<ITelemetryUserService?>  // null until bound; null again on binder death / disconnect
    fun requestPermission()
    fun start()   // registers listeners, idempotent
    fun stop()    // unregisters, unbinds
}
// Binder calls are blocking: callers use Dispatchers.IO.
```
```aidl
// com.akane.inspiration.ipc.ITelemetryUserService
interface ITelemetryUserService {
    void destroy() = 16777114;
    KernelBatterySnapshot readKernelBattery() = 1;
    CpuTimeSnapshot readCpuTimeInState() = 2;
    BatteryUsageSnapshot readBatteryUsage(long maxAgeMs) = 3;
    PowerMonitorSnapshot readPowerMonitors() = 4;
    DeviceIdleSnapshot readDeviceIdle() = 5;
    DumpResult openDump(int target) = 6;
    // 7 reserved (was generic command runner; never reuse). Phase 5 typed actions start at 20.
}
```
Every result DTO is non-null and `@Parcelize`, in `com.akane.inspiration.ipc.model`, and carries `available: Boolean, reason: String?` (reason = short machine string such as `"api"`, `"permission"`, `"remote"`, `"no_such_method"`, `"timeout"`, `"io"`). Sysfs fields are nullable (absent node ≠ zero).
- `KernelBatterySnapshot(available, reason, currentNowRaw: Long?, voltageNowRaw: Long?, chargeCounterRaw: Long?, chargeFullRaw: Long?, chargeFullDesignRaw: Long?, cycleCount: Int?, temperatureDeciC: Int?, status: String?, capacityPercent: Int?, sourcePath: String?, readAtElapsedMs: Long)`
- `CpuTimeSnapshot(available, reason, entries: List<ClusterFreqTime>)`, `ClusterFreqTime(policy: Int, freqKhz: Long, timeMs: Long)`
- `BatteryUsageSnapshot(available, reason, statsStartMs: Long, statsEndMs: Long, dischargedPowerMah: Double, batteryTimeRemainingMs: Long, chargeTimeRemainingMs: Long, uids: List<UidUsage>)`; `UidUsage(uid: Int, packageName: String?, totalMah: Double, components: Map<String, Double>, byProcessState: Map<String, Double>?)`. Keys are the stable names from TEMPLATE §2.A.1 (`SCREEN`, `CPU`, …; `FOREGROUND`, `BACKGROUND`, `FOREGROUND_SERVICE`, `CACHED`).
- `PowerMonitorSnapshot(available, reason, monitors: List<PowerMonitorReading>)`; `PowerMonitorReading(name: String, type: Int, energyUws: Long, timestampMs: Long)`
- `DeviceIdleSnapshot(available, reason, deepIdle: Boolean, lightIdle: Boolean, powerSaveWhitelist: List<String>)` (idle flags from `IPowerManager.isDeviceIdleMode/isLightDeviceIdleMode`; whitelist from `IDeviceIdleController.getFullPowerWhitelist`)
- `DumpResult(available, reason, fd: ParcelFileDescriptor?)`. `DumpTarget` constants: `BATTERYSTATS_PROTO=1` (`batterystats --proto`), `POWER_PROTO=2` (`power --proto`), `ALARM_PROTO=3` (`alarm --proto`), `DEVICEIDLE_TEXT=4` (`deviceidle`). Any other value → `available=false, reason="target"`. The service writes the dump via `IBinder.dump(fd, args)` on a background thread into a `ParcelFileDescriptor.createPipe()` write end and returns the read end.

```kotlin
// :core:telemetry-engine  package com.akane.inspiration.telemetry.calibration
data class CalibrationInput(val sysfsCurrentRaw: Long?, val sysfsVoltageRaw: Long?,
    val bmCurrentNowUa: Int?, val bmVoltageMv: Int?, val isCharging: Boolean, val plugged: Boolean)
data class PowerCalibration(val currentScaleToMa: Double, val dischargeIsNegative: Boolean,
    val voltageScaleToMv: Double, val cellCount: Int, val confidence: Confidence)
enum class Confidence { HIGH, LOW, UNCALIBRATED }
class PowerCalibrator { fun calibrate(samples: List<CalibrationInput>): PowerCalibration
    fun toReading(raw: CalibrationInput, cal: PowerCalibration): PowerReading? }   // null when uncalibratable
data class PowerReading(val currentMa: Double, val voltageV: Double, val watts: Double) // + = charging, − = discharging
```

## Waves and tickets
Gradle: at most 2 concurrent verifying tickets (user override). Emulator work is orchestrator-only and sequential.

- **W1 · T1 build skeleton** (coding.normal): build-logic + 10 modules + catalog + settings. It is mechanical, so the >8-file size is accepted (stated). Verify: `bash .claude/kit/gradle-check.sh :app:assembleDebug :app:testDebugUnitTest :app:validateDebugScreenshotTest :core:hidden-api-stub:assembleDebug :core:telemetry-engine:assembleDebug`.
- **W2 · T2 hidden-api stubs** (coding.normal, after T1): IBatteryStats(+Stub), BatteryUsageStatsQuery(+Builder), BatteryUsageStats, BatteryConsumer, UidBatteryConsumer, IPowerStatsService(+Stub), IDeviceIdleController(+Stub), IPowerManager(+Stub), ServiceManager. Verify: `:core:hidden-api-stub:assembleDebug`.
- **W2 · T3 PowerCalibrator** (coding.normal, after T1). Verify: `:core:telemetry-engine:testDebugUnitTest`.
- **W2 · T4a IPC contract** (coding.normal, after T1): AIDL, DTOs, `DumpTarget`, Parcel round-trip-free unit tests of DTO defaults. Verify: `:core:shizuku-ipc:testDebugUnitTest`.
- **W3 · T4b Shizuku connection** (coding.normal, after T4a): `ShizukuManagerImpl` over a `ShizukuFacade` seam (fake in tests; covers binder received/dead, permission result, service connect/disconnect), `InspirationApplication`, `AppContainer`, manifest (`ShizukuProvider`: authorities `${applicationId}.shizuku`, exported=true, multiprocess=false, permission `android.permission.INTERACT_ACROSS_USERS_FULL`; `<queries><package android:name="moe.shizuku.privileged.api"/></queries>`). Verify: `:core:shizuku-ipc:testDebugUnitTest :app:assembleDebug`.
- **W3 · T5a binder clients** (coding.hard, after T2 + T4a): `BatteryStatsClient`, `PowerStatsClient`, `DeviceIdleClient`, `PowerManagerClient`. They take a `() -> IBinder?`, return the DTOs, and are gated per D3/D9. All of RemoteException, SecurityException, NoSuchMethodError, NoSuchFieldError, ReflectiveOperationException map to `available=false` + reason. Unit tests for gating decisions and mapping via fakes. Verify: `:core:shizuku-ipc:testDebugUnitTest`.
- **W4 · T5b TelemetryUserService** (coding.normal, after T4b + T5a): service impl (caller-UID check, sysfs discovery under `/sys/class/power_supply/*` with `type=Battery`, `time_in_state` reader, `openDump` per D5, `destroy()` → cleanup + `System.exit(0)`), binding in `ShizukuManagerImpl` per D10, R8 rules (`-dontwarn` for stubbed hidden classes, keep `TelemetryUserService` + both constructors). Unit tests: sysfs parsing from a temp dir, UID check, unknown dump target. Verify: `:core:shizuku-ipc:testDebugUnitTest :app:assembleDebug :app:minifyReleaseWithR8`.
- **W5 · T6 debug probe** (coding.normal, after T3 + T5b): `src/debug` manifest `ProbeReceiver` exported=true with `android:permission="android.permission.DUMP"`. It works off the main thread via `goAsync()` and logs one JSON line per source to tag `InspirationProbe`: Shizuku state, kernel node + calibration (BatteryManager read here), battery usage top 3 UIDs, power monitors, device idle, timings (cold bind ms, warm call ms, in-process path result per D1). Verify: `:app:assembleDebug`. Device evidence: the orchestrator installs Shizuku (RikkaApps/Shizuku GitHub release v13.6.0 APK), starts it via adb, and runs the probe on `pixel-api35`, `batstats-api36`, `screenoff-api37`.

## Review budget
- Done-oracles cover build wiring, calibration math, gating decisions and mappers.
- **Named untested seam:** real framework behavior (signatures, permissions, version gates) is covered by the T6 emulator run on API 35/36/37. API 31–34 have no AVD, so that is a **named untested risk**: gates come from AOSP source reading only (SQ-1).
- One `review-audit` (GPT) is bound to T5b's candidate, covering the privileged boundary: caller-UID enforcement, the closed dump set, no loops or resident work (TEMPLATE §6), and pipe/thread cleanup.

## Design-reopen evidence
- The API 37 emulator UserService can't call `getBatteryUsageStats` → re-plan D1 (dump-only path).
- The in-process path works on 35/36/37 with lower latency → Phase 2 may make it primary for binder reads (D1/D11).
- `IPowerStatsService` is absent or different on API 35 → gate D3 to 36.
- The Shizuku 13.1.5 aar fails under the AGP 9 manifest merge → vendor the API module.
