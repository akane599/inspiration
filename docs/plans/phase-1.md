# Phase 1 — Multi-module Gradle + Shizuku IPC core (story US-1)

Source spec: `TEMPLATE.md` §1, §2, §7 (Turkish). Recon evidence: SQ-1 (hidden APIs / Shizuku), SQ-2 (AGP 9 toolchain).

## Outcome
A multi-module build matching TEMPLATE §1 and a working privileged IPC core: Shizuku connection/permission state, a shell-UID `TelemetryUserService`, version-gated clients for `batterystats`, `powerstats`, `deviceidle`, `power`, a sysfs battery reader, and a unit/sign/dual-cell calibration engine. Proven by unit tests on the JVM plus one emulator smoke run with Shizuku.

## Non-goals (later phases)
Protobuf parsing and `.proto` schemas, snapshot diffing, Room entities/DAOs, coulomb counting (Phase 2); rule engine and dictionary (Phase 3); design system and charts (Phase 4); real screens, toolkit actions UI (Phase 5). No DI framework.

## Decisions (with evidence)
| # | Decision | Why |
|---|---|---|
| D1 | **Privileged calls run primarily inside the shell-UID `TelemetryUserService`**. Binder clients are written once against an `IBinder` source so the same code also runs in the app process via `ShizukuBinderWrapper` + HiddenApiBypass (secondary path). | SQ-1: the shell process has no non-SDK restrictions, holds BATTERY_STATS/DUMP/DEVICE_POWER and can read `sysfs_batteryinfo`. The app process can't read sysfs, `ShizukuBinderWrapper.dump()` runs as the app UID (no DUMP), and on API 37 both the blocklist and HiddenApiBypass 6.1 are unverified. Spec tier A (`ShizukuBinderWrapper`) is kept as the secondary path, so neither tier is lost. |
| D2 | Hidden interfaces come from a hand-written **compileOnly stub module** `:core:hidden-api-stub` (`com.android.library`, never packaged). | SQ-1 §2: the runtime classes come from the device boot classpath. `dev.rikka.hidden:stub` lacks IBatteryStats/BatteryUsageStats/IPowerStatsService/IPowerManager. |
| D3 | `IPowerStatsService`: framework `android.os.IPowerStatsService` (API 34+) → `getSupportedPowerMonitors` / `getPowerMonitorReadings` (oneway, ResultReceiver). API 31–33 → `Unavailable`. The HAL methods named in TEMPLATE (`getEnergyMeterInfo`…) are **not used**. | SQ-1 §3/§5: the HAL is a `protected_service` and unreachable. Empty monitor lists are expected off Pixel. |
| D4 | Kernel wakelocks are **not** read from sysfs/debugfs. The UserService exposes `openDump(service, args): ParcelFileDescriptor` (runs as shell). Phase 2 parses `batterystats --proto` from it. | SQ-1 §5: shell has no SELinux allow for wakeup sources. |
| D5 | `executePrivilegeCommand(cmd: String)` becomes `runCommand(argv: String[], timeoutMs: Long): CommandResult`: no `sh -c`, argv only, timeout, stdout/stderr capped at 64 KiB. | This removes shell injection from package names and keeps it off any polling path (spec §6). |
| D6 | Versions: shizuku api/provider 13.1.5, hiddenapibypass **6.1** (6.2 is <2 weeks old → follow-up ticket after 2026-10-24), KSP 2.3.12, **Room 3.0.3** (`androidx.room3`) + bundled SQLite driver, protobuf plugin 0.10.0 + javalite/kotlin-lite 4.36.2, parcelize = `org.jetbrains.kotlin.plugin.parcelize`. | SQ-2 empirical fixture on AGP 9.4.1/Kotlin 2.4.20. Room 3 suits a new app (KSP-only, suspend/Flow DAOs, consistent bundled SQLite across OEM ROMs). Fallback is Room 2.8.5 if Phase 2 hits a blocker. |
| D7 | Shared build config through a `build-logic` included build with convention plugins. | SQ-2 §5 (NowInAndroid pattern, verified in a fixture). |
| D8 | Manual DI (`AppContainer` in `:app`) for Phase 1. | No feature needs Hilt yet, and it avoids a dependency plus Hilt/AGP 9 risk. Revisit in Phase 5. |
| D9 | `BatteryUsageStatsQuery` flags are version-gated: `includeProcessStateData`/`includeVirtualUids` only on API ≥33, `includePowerModels` not called on API ≥35 (no-op), always `setMaxStatsAgeMs(0)`. On API 31/32 the per-process-state breakdown is `null`. | SQ-1 §3. |
| D10 | UserService args: `processNameSuffix("telemetry")`, `daemon(false)`, `version(BuildConfig.VERSION_CODE)`, `tag("telemetry")`. AIDL declares `void destroy() = 16777114;`. The app rebinds on `onBinderReceived`. | SQ-1 §4. `daemon(false)` means no resident process after the app dies (zero-observer). Phase 2 can revisit for snapshot latency. |

## Module layout
```
build-logic/convention/   inspiration.android.library, inspiration.android.library.compose, inspiration.android.feature
core/hidden-api-stub      compileOnly stubs (Java)
core/shizuku-ipc          ShizukuManager, UserService (AIDL + impl), binder clients, DTOs (@Parcelize)
core/telemetry-engine     PowerCalibrator (pure Kotlin) [+ protobuf plugin wired, no protos yet]
core/diagnostics          empty skeleton
core/database             room3 + ksp plugins applied, schema dir, no entities yet
feature/{dashboard,xray,sleeplab,health,toolkit}  empty Compose skeletons
app                       Application (HiddenApiBypass init, AppContainer), Shizuku provider in manifest, debug probe receiver
```
Dependency direction: `app → feature:* → core:*`; `core:telemetry-engine → core:shizuku-ipc`; `core:shizuku-ipc` compileOnly `core:hidden-api-stub`.

## Shared surface (pinned)
```kotlin
// :core:shizuku-ipc  package com.akane.inspiration.ipc
sealed interface ShizukuState {
    data object NotInstalled : ShizukuState          // binder never received / pre-v11
    data object NotRunning : ShizukuState            // binder dead
    data object PermissionRequired : ShizukuState
    data object PermissionDenied : ShizukuState
    data class Ready(val uid: Int, val apiVersion: Int, val serviceConnected: Boolean) : ShizukuState
}
interface ShizukuManager {
    val state: StateFlow<ShizukuState>
    fun requestPermission()
    val telemetryService: StateFlow<ITelemetryUserService?>   // null until bound
}

// AIDL  com.akane.inspiration.ipc.ITelemetryUserService
interface ITelemetryUserService {
    void destroy() = 16777114;
    KernelBatterySnapshot readKernelBatteryNode() = 1;
    List<ClusterFreqTime> readCpuTimeInState() = 2;          // empty list when unreadable
    BatteryUsageSnapshot readBatteryUsage(long maxAgeMs) = 3; // null on failure
    PowerMonitorSnapshot readPowerMonitors() = 4;            // available=false on API<34 / no ODPM
    DeviceIdleSnapshot readDeviceIdle() = 5;
    ParcelFileDescriptor openDump(String service, in String[] args) = 6;
    CommandResult runCommand(in String[] argv, long timeoutMs) = 7;
}
```
DTOs are `@Parcelize` data classes in `com.akane.inspiration.ipc.model`, with every sysfs field nullable (absent node ≠ zero):
- `KernelBatterySnapshot(currentNowRaw: Long?, voltageNowRaw: Long?, chargeCounterRaw: Long?, chargeFullRaw: Long?, chargeFullDesignRaw: Long?, cycleCount: Int?, temperatureDeciC: Int?, status: String?, capacityPercent: Int?, sourcePath: String, readAtElapsedMs: Long)`
- `BatteryUsageSnapshot(statsStartMs, statsEndMs, dischargedPowerMah: Double, batteryTimeRemainingMs: Long, chargeTimeRemainingMs: Long, uids: List<UidUsage>)` and `UidUsage(uid, packageName: String?, totalMah, components: Map<String, Double>, byProcessState: Map<String, Double>?)`. Component and state keys use the stable names from TEMPLATE §2.A.1.
- `PowerMonitorSnapshot(available: Boolean, reason: String?, monitors: List<PowerMonitorReading>)` with `PowerMonitorReading(name: String, type: Int, energyUws: Long, timestampMs: Long)`
- `DeviceIdleSnapshot(deepIdle: Boolean, lightIdle: Boolean, powerSaveWhitelist: List<String>)`
- `ClusterFreqTime(policy: Int, freqKhz: Long, timeMs: Long)`, `CommandResult(exitCode: Int, stdout: String, stderr: String, timedOut: Boolean)`

```kotlin
// :core:telemetry-engine  package com.akane.inspiration.telemetry.calibration
data class CalibrationInput(val sysfsCurrentRaw: Long?, val sysfsVoltageRaw: Long?,
    val bmCurrentNowUa: Int?, val bmVoltageMv: Int?, val isCharging: Boolean, val plugged: Boolean)
data class PowerCalibration(val currentScaleToMa: Double, val dischargeIsNegative: Boolean,
    val voltageScaleToMv: Double, val cellCount: Int, val confidence: Confidence)
enum class Confidence { HIGH, LOW, UNCALIBRATED }
class PowerCalibrator { fun calibrate(samples: List<CalibrationInput>): PowerCalibration
    fun toReading(raw: CalibrationInput, cal: PowerCalibration): PowerReading }
data class PowerReading(val currentMa: Double, val voltageV: Double, val watts: Double) // + = charging, − = discharging
```

## Waves and tickets
- **W1 · T1 build skeleton** (coding.normal): build-logic + all modules + catalog + `settings.gradle.kts`; core/feature modules empty but compiling; `app` depends on features. Verify: `bash .claude/kit/gradle-check.sh :app:assembleDebug :app:testDebugUnitTest :core:database:assembleDebug :core:telemetry-engine:assembleDebug :app:validateDebugScreenshotTest`.
- **W2 · T2 hidden-api stubs** (coding.normal, depends T1): Java stubs for IBatteryStats(+Stub), BatteryUsageStatsQuery(+Builder), BatteryUsageStats, BatteryConsumer, UidBatteryConsumer, IPowerStatsService(+Stub), PowerMonitor, IDeviceIdleController(+Stub), IPowerManager(+Stub), ServiceManager. Signatures copied from AOSP tags noted in SQ-1. Verify: `:core:hidden-api-stub:assembleDebug`.
- **W2 · T3 PowerCalibrator** (coding.normal, depends T1): pure Kotlin + JUnit fixtures (µA vs mA, sign flip, dual-cell ~7.7–8.9 V, missing sysfs, BatteryManager-only, contradictory samples → LOW). Verify: `:core:telemetry-engine:testDebugUnitTest`.
- **W3 · T4 Shizuku connection** (coding.normal, depends T1): `ShizukuManager` impl over a `ShizukuFacade` seam (fake in tests), `InspirationApplication` (HiddenApiBypass `addHiddenApiExemptions("L")` in `attachBaseContext`, try/catch logged), manifest `ShizukuProvider`, AIDL file + DTOs, UserService binding/rebinding. Verify: `:core:shizuku-ipc:testDebugUnitTest :app:assembleDebug`.
- **W4 · T5 TelemetryUserService + binder clients** (coding.hard, depends T2, T4): `TelemetryUserService` (sysfs reader with `/sys/class/power_supply/{battery,bms,…}` discovery, time_in_state, openDump via `IBinder.dumpAsync`/pipe, runCommand with ProcessBuilder argv) + version-gated clients `BatteryStatsClient`, `PowerStatsClient`, `DeviceIdleClient`, `PowerManagerClient` taking an `IBinder` source; every call wrapped (RemoteException, SecurityException, NoSuchMethodError, ReflectiveOperationException → typed failure). Unit tests for the pure mappers (BatteryUsageStats → DTO via a fake adapter, sysfs parsing from a temp dir). Verify: `:core:shizuku-ipc:testDebugUnitTest :app:assembleDebug`.
- **W5 · T6 debug probe + emulator smoke** (coding.normal, depends T3, T5): debug-only `ProbeReceiver` (`adb shell am broadcast -a com.akane.inspiration.debug.PROBE -n …`) that logs one JSON line per source (Shizuku state, kernel node + calibration, battery usage top 3 UIDs, power monitors, device idle). The orchestrator then runs it on `batstats-api36` and `screenoff-api37` with Shizuku started via adb. Verify (ticket): `:app:assembleDebug`; device evidence is collected by the orchestrator.

## Review budget
The done-oracles cover build wiring, calibration math and mappers. **Named untested seam:** hidden-API behavior on the real framework (signatures, version gates, permission paths) cannot be observed on the JVM, so T6's emulator run on API 36 and 37 is its oracle. One `review-audit` (GPT) on T5's candidate covers error handling / zero-observer (no loops, no resident work) and argv safety of `runCommand`.

## Design-reopen evidence
- On the API 37 emulator, the UserService can't call `getBatteryUsageStats` → re-plan D1 (e.g. dump-only).
- Room 3 plugin breaks the build with no entities → move Room plugin application to Phase 2 (D6 fallback).
- Shizuku 13.1.5 aar fails under AGP 9 manifest merge → vendor the API module.
