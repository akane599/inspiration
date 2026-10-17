# Phase 2 — Telemetry engine, proto parser, snapshot diff, Room, Coulomb (story US-2)

Source spec: `TEMPLATE.md` §3, §6, §7 Aşama 2 (Turkish). Recon evidence: SQ-17 (proto schemas; comments c_mvc2zkr0_a24852 and c_mvc3a5xn_96a018) and SQ-18 (capture and history APIs; comment c_mvc3cj5x_cc81c5). Phase 1 contract: `docs/plans/phase-1.md` (D1–D11 still hold).

## Outcome
The app can take a **BatterySnapshot** on demand and store it. A snapshot merges:
- `BatteryUsageStats` (per-UID mAh, process states);
- the parsed `batterystats` / `alarm` / `power` proto dumps (partial and kernel wakelocks, wakeup alarms, system timers);
- sysfs kernel battery values, calibrated;
- CPU `time_in_state`.

It records battery-history events (screen, plug, level, charge µAh, reset/boot) so reference points and charge sessions can be rebuilt after the fact. It computes Δ = S_now − S_ref between any two valid snapshots, with explicit validity rules, plus deep-sleep and awake-screen-off math. It estimates real battery capacity from charge sessions. Room (WAL) persists all of it, with 30-day downsampling and pruning. All of this runs without polling and without residency.

Proven by:
- JVM tests against 9 real emulator fixtures and synthetic data;
- a Room DB-open test on the JVM host;
- an orchestrator device run (`PROBE_CAPTURE`) on the API 35/36/37 emulators.

## Non-goals (owning phase)
- **Phase 3:** the diagnostic rules, wakelock dictionary and Battery Story text; the `usagestats` standby-bucket and `getHistoricalProcessExitReasons` clients, which Phase 3 rules consume (moved here from Phase 1's Phase 2 list).
- **Phase 4/5:** UI, charts, ViewModels, toolkit actions.
- No `aggregateSnapshots` (whole-session granularity, 100 KiB store; SQ-18 §3) and no `monotonicTimeRange` / `accumulated()` in Phase 2. Both are runtime-probeable later.
- No resident or foreground service, no periodic job, no WorkManager, no DI framework.

## Decisions (with evidence)
| # | Decision | Why |
|---|---|---|
| E1 | **Pull-first capture.** Snapshots are taken (a) on app process start or app open, (b) on a runtime-registered receiver for SCREEN_ON/OFF, POWER_CONNECTED/DISCONNECTED and BATTERY_LOW/OKAY in `InspirationApplication`, active only while some process is alive, (c) by one persisted JobScheduler **charging** job, re-armed on each run and on boot, (d) by a BOOT_COMPLETED / MY_PACKAGE_REPLACED manifest receiver, which records a boot marker and re-arms the job, (e) by a user **custom marker**. Past screen/plug boundaries come from **battery history** (E3), not from live events. Every capture has a finite deadline (receivers: `goAsync` + 8 s); a miss is recorded and never retried in a loop. | SQ-18 §1/§2: only BOOT/MY_PACKAGE_REPLACED reach a dead process. On API 35+ screen and power broadcasts are deferred and coalesced for cached processes. `shortService` can't be started from those broadcasts. A UserService `IIntentReceiver` is a no-op on 34+. TEMPLATE §6.2 as written is infeasible; this is the closest zero-observer design. |
| E2 | **Proto: trimmed, vendored AOSP schemas** (3 files: batterystats with the `BatteryStatsServiceDumpProto` wrapper, power, alarm). They keep the AOSP `package` and field numbers but set `option java_package = "com.akane.inspiration.telemetry.proto.*"`. External enums are declared as `int32`, `sint*` is kept, and the privacy imports/options are dropped. protobuf plugin 0.10.0 + protoc / javalite 4.36.2 lite codegen. The R8 rule `-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }` is a consumer rule. Licensing: Apache-2.0 headers, files marked "trimmed", plus a `NOTICE` entry. The pipe reader is capped at 16 MiB. The root sanity check is `hasBatterystats() && report_version in 35..36` (anything else is unavailable("format")). Absent field means default 0 / "". | SQ-17 §3–5 (verified on 9/9 fixtures; API 37 already emits an enum value that AOSP doesn't define; `android.os.BatteryStatsProto` already exists in framework.jar, so the class would be shadowed). |
| E3 | **Battery history via a new UserService method** `readBatteryHistory(long sinceWallMs, int maxEvents) = 8`. It runs `getBatteryUsageStats([query.includeBatteryHistory()])` in the shell and walks `iterateBatteryStatsHistory()`. It returns a compact `BatteryHistorySnapshot` of events: SCREEN_ON/OFF, PLUGGED/UNPLUGGED, LEVEL (with `chargeUah`, temperature, voltage), RESET, START, SHUTDOWN, DEEP_IDLE_ON/OFF. Each event carries a wall-clock time rebuilt from `currentTime` anchors (CMD_CURRENT_TIME/RESET/START) plus the deltas in the `time` field. If the event cap is hit, the result is truncated with `truncated = true`. A `LinkageError`/`NoSuchFieldError` gives unavailable(no_such_method). Code 7 stays reserved. | SQ-18 §3b: history exists on API 31–37, carries the screen/plug/level/charge-µAh fields, holds about 4 MiB and is wiped at each stats reset on A12–15. Shell avoids hidden-API blocks (D1). |
| E4 | **Snapshot validity tuple** stored with every snapshot: `bootCount` (Settings.Global.BOOT_COUNT), `elapsedRealtimeMs`, `uptimeMs`, `wallMs`, `statsStartMs`, `statsEndMs`. A batterystats-derived Δ is valid iff the two `statsStartMs` match (±2 s) and `statsEnd_now ≥ statsEnd_ref`. A kernel-counter Δ (cpu time_in_state, ODPM energy) is valid iff the `bootCount` matches and elapsed time is increasing. Any negative per-UID or per-component Δ makes that part invalid and it is reported (`DiffValidity`), never clamped. A UID missing from S_now has Δ = 0. Ordering uses (bootCount, elapsedRealtime); wall clock is for display only. | SQ-18 §4. |
| E5 | **References.** `ReferenceKind` = SINCE_UNPLUGGED, SINCE_SCREEN_OFF, SCREEN_ON_SESSION, LAST_24H, CUSTOM_MARKER(id), TIME_SLICE(fromWall, toWall). The boundary instant comes from history events, or from markers. The resolver picks the closest **valid** stored snapshot at or after the boundary as S_ref (S_now = the latest, or for TIME_SLICE the closest snapshot at or before `toWall`). It returns `coveredFromWall/coveredToWall` and a `gapMs` so the UI can say "the closest measurement is 12 min after the unplug". The device-level values for the exact interval (level drop, screen time, Δ charge µAh) come from history even when per-UID values are approximate. | SQ-18 §3 "what can be rebuilt". Honest data beats invented precision (TEMPLATE §0.1). |
| E6 | **Deep-sleep math** from the batterystats proto `system.battery`. Deep sleep = Δ(battery_realtime_ms − battery_uptime_ms). Screen-off deep sleep = Δ(screen_off_realtime_ms − screen_off_uptime_ms). Screen-off awake = Δ screen_off_uptime_ms. Deep-sleep ratio = screen-off deep sleep / Δ screen_off_realtime. Live fallback (same boot) = Δ elapsedRealtime − Δ uptimeMillis. | SQ-17 §1 (no dedicated field). TEMPLATE §3.2. |
| E7 | **Capacity (Coulomb)** from the fuel gauge's own integrator. For each charge segment found in history (or snapshots), with plugged = true and SoC rising ≥ 40 points, C = Δ`chargeUah` / (ΔSoC / 100). If no `chargeUah` exists but live current samples (≥ 1 per 5 s) cover the segment, use trapezoid ∫I dt. Outlier filter: drop segments with maxTemp > 40 °C, or that deviate > 15 % from the median of the last 10 accepted, or with C outside 0.5–1.2 × design capacity. Combine with an EMA, α = 0.3, on accepted segments. Output: `CapacityEstimate(measuredMah, healthPct vs design, kernelChargeFullMah, kernelHealthPct, cycleCount, sampleCount, confidence)`. | TEMPLATE §3.3. ∫I dt needs a live process during the whole charge; the gauge's coulomb counter already integrates, so this keeps zero observer effect. |
| E8 | **Room 3.0.3** (`androidx.room3`, KSP 2.3.12) with a **`BundledSQLiteDriver`** (`androidx.sqlite:sqlite-bundled`, at a version Room 3.0.3 requires and that is ≥ 2 weeks old), WAL (Room 3 default, asserted in a test), and the schema exported to `core/database/schemas/`. It is adopted only after the DB-open JVM test passes. If that fails, fall back to Room 2.8.5 + framework driver and record the reason. The bundled driver keeps the SQLite version identical on the JVM host and on the device. Its APK cost is ~1 MB/ABI, which is acceptable. Plain entities: no Parcelize on entities. | SQ-2 §2 (Room 3 fixture compiled; runtime unverified; ksp#3053). |
| E9 | **Schema v1** (below). Pruning/downsampling runs **only** inside a capture or the charging job (no timers). Raw `telemetry_sample` rows older than 30 days become `telemetry_hourly` rows and are then deleted. Snapshots older than 30 days are deleted unless a marker references them or they are the latest snapshot of their boot session; child rows cascade. `history_event` rows are deduplicated on `(bootCount, wallMs, kind)` and pruned after 30 days. | TEMPLATE §6.3. |
| E10 | **Live telemetry** is a cold `Flow<LiveReading>` that reads `readKernelBattery()` every 1.5 s **only while collected** (no `stateIn` with `SharingStarted.Eagerly`). It stops when the collector stops. Samples are persisted to `telemetry_sample` only while collected and charging (charge curve). | TEMPLATE §5 Ekran 1 + §6.1. |
| E11 | **Module boundaries.** Domain models, the parser, the diff engine, the capacity estimator, the `SnapshotCapturer` and the `SnapshotStore` interface all live in `:core:telemetry-engine` (depends on `:core:shizuku-ipc`). `:core:database` implements `SnapshotStore` (depends on telemetry-engine). Android components (receivers, JobService, boot receiver) and wiring live in `:app`. `AppContainer` adds `snapshotStore`, `snapshotCapturer` and `captureScheduler`. | Clean dependency direction. No DI framework (D8). |
| E12 | **Production `CalibrationInput` reader** (BatteryManager current/voltage/charging + sysfs) in telemetry-engine. The calibration result is cached in-process and re-derived per capture (cheap); it is not persisted in Phase 2. | Phase 1 non-goal carried over. |

## Schema v1 (`:core:database`, Room 3, all `@Entity` plain data classes)
```
snapshot(id PK autoinc, wallMs, elapsedRealtimeMs, uptimeMs, bootCount, statsStartMs, statsEndMs,
         trigger TEXT  -- APP_OPEN|SCREEN_OFF|SCREEN_ON|POWER_CONNECTED|POWER_DISCONNECTED|BATTERY_LOW|BATTERY_OKAY|CHARGE_JOB|BOOT|MARKER
         levelPct, plugged INT(bool), statusText, temperatureDeciC, voltageMv, currentMa, chargeCounterUah,
         dischargedMah, screenOnMs, screenOffRealtimeMs, screenOffUptimeMs, batteryRealtimeMs, batteryUptimeMs,
         deepDozeMs, lightDozeMs, availabilityMask INT)          INDEX(bootCount, elapsedRealtimeMs), INDEX(wallMs)
snapshot_uid(snapshotId FK cascade, uid, packageName?, totalMah, fgMah?, bgMah?, fgsMah?, cachedMah?,
         cpuUserMs, cpuSystemMs, fgActivityMs, fgServiceMs, partialWakelockMs, bgPartialWakelockMs, wakeupAlarmCount)  PK(snapshotId, uid)
snapshot_component(snapshotId FK cascade, uid /* -1 = device */, component TEXT, mah)                                  PK(snapshotId, uid, component)
snapshot_wakelock(snapshotId FK cascade, kind TEXT /* PARTIAL|KERNEL|WAKEUP_REASON */, uid /* -1 kernel */, name, durationMs, bgDurationMs, count)  PK(snapshotId, kind, uid, name)
snapshot_alarm(snapshotId FK cascade, uid, tag, count)                                                                  PK(snapshotId, uid, tag)
snapshot_cpu_freq(snapshotId FK cascade, policy, freqKhz, timeMs)                                                       PK(snapshotId, policy, freqKhz)
marker(id PK autoinc, snapshotId FK restrict, label, wallMs)
history_event(id PK autoinc, bootCount, wallMs, kind TEXT, levelPct?, chargeUah?, temperatureDeciC?, voltageMv?, plugType?)  UNIQUE(bootCount, wallMs, kind)
telemetry_sample(id PK autoinc, wallMs, elapsedRealtimeMs, bootCount, currentMa, voltageMv, temperatureDeciC, levelPct, plugged)  INDEX(wallMs)
telemetry_hourly(hourStartWallMs PK, sampleCount, avgCurrentMa, minCurrentMa, maxCurrentMa, avgVoltageMv, maxTemperatureDeciC, minLevelPct, maxLevelPct)
charge_session(id PK autoinc, startWallMs, endWallMs, startPct, endPct, deltaChargeUah?, integratedMah?, maxTemperatureDeciC?, estimateMah?, accepted INT, rejectReason?)  UNIQUE(startWallMs)
capture_miss(id PK autoinc, wallMs, trigger, reason)       -- D11 "misses are recorded"; pruned 30 d
```
Version 1, `exportSchema = true`. Any later change gets a migration and a `MigrationTestHelper` test (Phase 3+).

## Shared surface (pinned; package `com.akane.inspiration.telemetry` unless noted)
```kotlin
// dump/ (owner T1)
data class DumpSnapshot(val reportVersion: Int, val system: SystemTimers, val kernelWakelocks: List<KernelWakelock>,
    val wakeupReasons: List<KernelWakelock>, val uids: List<UidDump>, val cpuFrequenciesKhz: List<Long>)
data class SystemTimers(val totalRealtimeMs: Long, val totalUptimeMs: Long, val batteryRealtimeMs: Long, val batteryUptimeMs: Long,
    val screenOffRealtimeMs: Long, val screenOffUptimeMs: Long, val screenDozeMs: Long, val screenOnMs: Long,
    val interactiveMs: Long, val deepDozeMs: Long, val deepDozeCount: Long, val lightDozeMs: Long, val dischargeTotalMah: Double,
    val dischargeScreenOffMah: Double, val chargeTimeRemainingMs: Long?, val dischargeTimeRemainingMs: Long?)
data class KernelWakelock(val name: String, val durationMs: Long, val count: Long)
data class UidDump(val uid: Int, val partialWakelocks: List<UidWakelock>, val wakeupAlarms: List<UidAlarm> /* summed per tag */,
    val cpuUserMs: Long, val cpuSystemMs: Long, val fgActivityMs: Long, val fgServiceMs: Long, val processStateMs: Map<Int, Long>)
data class UidWakelock(val name: String, val durationMs: Long, val bgDurationMs: Long, val count: Long)
data class UidAlarm(val tag: String, val count: Long)
data class PowerDump(val wakefulness: Int, val heldWakeLocks: List<HeldWakeLock>, val lastWakeMs: Long, val lastSleepMs: Long)
data class HeldWakeLock(val tag: String, val uid: Int, val levelFlags: Int, val acquiredMs: Long)
data class AlarmDump(val filterStats: List<AlarmFilterStat>, val pendingWakeups: Int)
data class AlarmFilterStat(val uid: Int, val packageName: String, val tag: String, val count: Long, val wakeupCount: Long, val totalFlightMs: Long)
object DumpParser { fun parseBatteryStats(bytes: ByteArray): DumpParse<DumpSnapshot>; fun parsePower(bytes: ByteArray): DumpParse<PowerDump>; fun parseAlarm(bytes: ByteArray): DumpParse<AlarmDump> }
sealed interface DumpParse<out T> { data class Ok<T>(val value: T): DumpParse<T>; data class Unavailable(val reason: String): DumpParse<Nothing> }
object DumpReader { const val MAX_BYTES = 16 * 1024 * 1024; fun readAll(fd: ParcelFileDescriptor, maxBytes: Int = MAX_BYTES): ByteArray? /* null if over cap or IO error; always closes fd */ }

// ipc (owner T4): AIDL `BatteryHistorySnapshot readBatteryHistory(long sinceWallMs, int maxEvents) = 8;`
@Parcelize data class BatteryHistorySnapshot(val available: Boolean, val reason: String?, val events: List<HistoryEvent>, val truncated: Boolean)
@Parcelize data class HistoryEvent(val wallMs: Long, val kind: Int /* HistoryKind consts */, val levelPct: Int, val chargeUah: Long /* -1 unknown */,
    val temperatureDeciC: Int, val voltageMv: Int, val plugType: Int)
object HistoryKind { SCREEN_ON=1 SCREEN_OFF=2 PLUGGED=3 UNPLUGGED=4 LEVEL=5 RESET=6 START=7 SHUTDOWN=8 DEEP_IDLE_ON=9 DEEP_IDLE_OFF=10 }

// snapshot/ + diff/ (owner T2)
enum class CaptureTrigger { APP_OPEN, SCREEN_OFF, SCREEN_ON, POWER_CONNECTED, POWER_DISCONNECTED, BATTERY_LOW, BATTERY_OKAY, CHARGE_JOB, BOOT, MARKER }
data class SnapshotClock(val wallMs: Long, val elapsedRealtimeMs: Long, val uptimeMs: Long, val bootCount: Int, val statsStartMs: Long, val statsEndMs: Long)
data class BatterySnapshot(val id: Long /* 0 = unsaved */, val clock: SnapshotClock, val trigger: CaptureTrigger,
    val battery: BatteryState, val system: SystemTimers?, val uids: List<UidSnapshot>, val deviceComponentsMah: Map<String, Double>,
    val kernelWakelocks: List<KernelWakelock>, val wakeupReasons: List<KernelWakelock>, val cpuFreq: List<CpuFreqTime>, val availability: Availability)
data class BatteryState(val levelPct: Int?, val plugged: Boolean?, val status: String?, val temperatureDeciC: Int?, val voltageMv: Int?, val currentMa: Int?, val chargeCounterUah: Long?, val dischargedMah: Double?)
data class UidSnapshot(val uid: Int, val packageName: String?, val totalMah: Double, val fgMah: Double?, val bgMah: Double?, val fgsMah: Double?, val cachedMah: Double?,
    val componentsMah: Map<String, Double>, val cpuUserMs: Long, val cpuSystemMs: Long, val fgActivityMs: Long, val fgServiceMs: Long,
    val partialWakelocks: List<UidWakelock>, val wakeupAlarms: List<UidAlarm>)
data class CpuFreqTime(val policy: Int, val freqKhz: Long, val timeMs: Long)
data class Availability(val usage: Boolean, val dump: Boolean, val kernel: Boolean, val cpu: Boolean, val history: Boolean, val reasons: Map<String, String>)
data class SnapshotDiff(val ref: SnapshotClock, val now: SnapshotClock, val validity: DiffValidity, val durationMs: Long,
    val uids: List<UidDelta>, val deviceComponentsMah: Map<String, Double>, val kernelWakelocks: List<KernelWakelock>,
    val sleep: SleepStats?, val levelDropPct: Int?, val dischargedMah: Double?)
data class DiffValidity(val batteryStats: Boolean, val kernelCounters: Boolean, val problems: List<String>)
data class UidDelta(/* same fields as UidSnapshot, as deltas */ ...)   // T2 defines the exact fields; mirrors UidSnapshot 1:1
data class SleepStats(val deepSleepMs: Long, val screenOffDeepSleepMs: Long, val screenOffAwakeMs: Long, val screenOffMs: Long, val screenOnMs: Long, val deepSleepRatio: Double?)
object SnapshotDiffer { fun diff(ref: BatterySnapshot, now: BatterySnapshot): SnapshotDiff }
sealed interface ReferenceKind { SinceUnplugged; SinceScreenOff; ScreenOnSession; Last24h; data class CustomMarker(val markerId: Long); data class TimeSlice(val fromWallMs: Long, val toWallMs: Long) }
data class ResolvedReference(val ref: BatterySnapshot, val now: BatterySnapshot, val boundaryWallMs: Long, val coveredFromWallMs: Long, val coveredToWallMs: Long, val gapMs: Long)
class ReferenceResolver(private val store: SnapshotStore) { suspend fun resolve(kind: ReferenceKind, nowWallMs: Long): ResolvedReference? }

// capacity/ (owner T5)
data class ChargeSegment(val startWallMs: Long, val endWallMs: Long, val startPct: Int, val endPct: Int, val deltaChargeUah: Long?, val integratedMah: Double?, val maxTemperatureDeciC: Int?)
data class CapacityEstimate(val measuredMah: Double?, val healthPct: Double?, val kernelChargeFullMah: Double?, val kernelHealthPct: Double?, val designMah: Double?, val cycleCount: Int?, val sampleCount: Int, val confidence: Confidence)
object ChargeSegmentExtractor { fun fromHistory(events: List<HistoryEvent>): List<ChargeSegment> }
object CapacityEstimator { fun evaluate(segment: ChargeSegment, designMah: Double?, recentAccepted: List<Double>): SegmentVerdict; fun combine(accepted: List<Double>, kernel: KernelBatterySnapshot?, designMah: Double?): CapacityEstimate }
data class SegmentVerdict(val estimateMah: Double?, val accepted: Boolean, val rejectReason: String?)
object Integrator { fun trapezoidMah(samples: List<Pair<Long /*elapsedMs*/, Double /*mA*/>>): Double? /* null if any gap > 5 s */ }

// store (interface owner T2, impl owner T7)
interface SnapshotStore {
  suspend fun insert(snapshot: BatterySnapshot): Long
  suspend fun latest(): BatterySnapshot?
  suspend fun firstValidAtOrAfter(wallMs: Long, bootCount: Int?, statsStartMs: Long?): BatterySnapshot?
  suspend fun lastAtOrBefore(wallMs: Long): BatterySnapshot?
  suspend fun insertHistory(events: List<HistoryEvent>, bootCount: Int)   // dedup on unique key
  suspend fun historyBetween(fromWallMs: Long, toWallMs: Long): List<HistoryEvent>
  suspend fun lastHistoryWallMs(): Long?
  suspend fun addMarker(snapshotId: Long, label: String, wallMs: Long): Long
  suspend fun marker(id: Long): Pair<Long, BatterySnapshot>?
  suspend fun insertSamples(samples: List<LiveReading>)
  suspend fun upsertChargeSession(segment: ChargeSegment, verdict: SegmentVerdict)
  suspend fun acceptedEstimates(limit: Int): List<Double>
  suspend fun recordMiss(wallMs: Long, trigger: CaptureTrigger, reason: String)
  suspend fun prune(nowWallMs: Long)          // E9
}
data class LiveReading(val wallMs: Long, val elapsedRealtimeMs: Long, val bootCount: Int, val currentMa: Int?, val voltageMv: Int?, val watts: Double?, val temperatureDeciC: Int?, val levelPct: Int?, val plugged: Boolean?)

// capture/ (owner T6)
interface TelemetrySource { /* thin suspend wrapper over ITelemetryUserService + BatteryManager; fake in tests */ }
class SnapshotCapturer(source: TelemetrySource, store: SnapshotStore, clock: Clocks) { suspend fun capture(trigger: CaptureTrigger, deadlineMs: Long = 8_000): CaptureResult }
sealed interface CaptureResult { data class Saved(val id: Long, val availability: Availability): CaptureResult; data class Missed(val reason: String): CaptureResult }
class LiveTelemetry(source: TelemetrySource, calibrator: ..., clocks: Clocks) { fun readings(periodMs: Long = 1_500): Flow<LiveReading> }
```
Per-UID process-state mAh mapping: `fgMah` = PROCESS_STATE_FOREGROUND, `bgMah` = BACKGROUND, `fgsMah` = FOREGROUND_SERVICE, `cachedMah` = CACHED (keys as produced by T5a `BatteryUsageAdapter`; null when `byProcessState == null`, API 31/32).

## Tickets (waves are formed by dependencies; Gradle ≤ 2 concurrent)
| T | Title | Category | Files | Depends | Verify |
|---|---|---|---|---|---|
| T1 | Trimmed vendored protos + DumpParser + DumpReader + R8 + NOTICE | coding.normal | core/telemetry-engine/{build.gradle.kts, src/main/proto/**, src/main/kotlin/.../telemetry/dump/**, src/test/.../dump/**, consumer-rules.pro}, NOTICE | — | `:core:telemetry-engine:testDebugUnitTest :app:minifyReleaseWithR8`. Fixture assertions: batterystats api35/36/37 uids 66/70/70, kernel_wakelock 66/68/69, uid wakelocks 25/133/113, wakeup_alarm 3/5/4; power wake_locks 2/0/1; alarm stats 5. Also truncated, empty, invalid-UTF-8, unknown-enum (api37 value 14 kept) and over-cap cases |
| T2 | Domain snapshot model + SnapshotDiffer + SleepStats + ReferenceResolver + SnapshotStore interface | coding.normal | core/telemetry-engine/src/{main,test}/kotlin/.../telemetry/{snapshot,diff,store}/** | T1 (reuses the `dump/` types `SystemTimers`, `KernelWakelock`, `UidWakelock`, `UidAlarm`; never redeclares them) | `:core:telemetry-engine:testDebugUnitTest` with cases: same session, reset between (statsStart change → batteryStats invalid), reboot (bootCount change → kernel invalid), negative UID Δ, missing UID, TIME_SLICE gap reporting, deep-sleep math against hand-computed values |
| T3 | Room 3 + bundled driver DB, schema v1 entities/DAOs, WAL, export, prune/downsample SQL | coding.normal (DB-open test first; E8 fallback if it fails) | gradle/libs.versions.toml (room3, sqlite-bundled, ksp plugin use), build-logic only if a convention is needed, core/database/** | — | `:core:database:testDebugUnitTest` (JVM host DB-open via BundledSQLiteDriver; WAL asserted via `PRAGMA journal_mode`; cascade; unique dedup; prune keeps marker-referenced snapshots; 30-day downsample) `:app:assembleDebug` |
| T4 | readBatteryHistory (AIDL 8) + stubs (BatteryStats.HistoryItem, BatteryStatsHistoryIterator, Query.Builder.includeBatteryHistory, BatteryUsageStats.iterateBatteryStatsHistory) + UserService impl + DTOs | coding.hard | core/hidden-api-stub/**, core/shizuku-ipc/src/main/aidl/**, .../ipc/model/BatteryHistory*.kt, .../ipc/client/BatteryHistoryReader.kt, .../ipc/service/TelemetryUserService.kt, tests | — | `:core:shizuku-ipc:testDebugUnitTest :app:assembleDebug` (JVM: the mapping from a fake iterator → events, wall-time anchoring across CURRENT_TIME/RESET, cap/truncation, LinkageError → unavailable, caller-UID check first). Device evidence by the orchestrator |
| T5 | ChargeSegmentExtractor + CapacityEstimator + Integrator | coding.normal | core/telemetry-engine/src/{main,test}/kotlin/.../telemetry/capacity/** | T4 (HistoryEvent DTO) | `:core:telemetry-engine:testDebugUnitTest`: synthetic segments (≥40 % rule, temp reject, median-deviation reject, design-bounds reject, EMA), trapezoid with a gap |
| T6 | TelemetrySource (IPC + BatteryManager) + CalibrationInput reader + SnapshotCapturer (merge usage+dump+kernel+cpu+history, deadline, miss recording) + LiveTelemetry flow | coding.normal | core/telemetry-engine/src/{main,test}/kotlin/.../telemetry/{capture,source,live}/** | T1, T2, T4, T5 | `:core:telemetry-engine:testDebugUnitTest`: fake source; merge correctness (process-state map, component map, alarm summing), partial availability, deadline → Missed + recordMiss, LiveTelemetry stops on cancel (virtual time) |
| T7 | Room-backed SnapshotStore impl (mappers both ways, history dedup, markers, samples, charge sessions, misses, prune) | coding.normal | core/database/src/{main,test}/kotlin/.../database/store/**, core/database/build.gradle.kts | T2, T3, T5 | `:core:database:testDebugUnitTest`: round-trip BatterySnapshot equality, firstValidAtOrAfter honours statsStart/bootCount, prune |
| T8 | App wiring: runtime receiver (SCREEN/POWER/BATTERY), BOOT/MY_PACKAGE_REPLACED receiver, persisted charging JobService (re-arm), APP_OPEN capture, AppContainer, debug `PROBE_CAPTURE` / `PROBE_DIFF` actions | coding.normal | app/src/main/**, app/src/debug/**, app/build.gradle.kts (project deps only) | T6, T7 | `:app:assembleDebug :app:testDebugUnitTest :app:minifyReleaseWithR8` + orchestrator device run |

Review budget:
- T4 is the hidden-API and shell boundary (the caller-UID check stays first; no resident work): bind a GPT `review-audit` to its candidate.
- T3 and T7 touch the on-disk format. The schema is reviewed at plan stage (this audit); the oracle covers round-trips.
- T1, T2, T5 and T6 have deterministic oracles over real fixtures and synthetic data; no extra review.
- T8: the orchestrator's device run is the oracle.

## Design-reopen evidence
- The T3 JVM DB-open test fails with Room 3 + the bundled driver → fall back to Room 2.8.5 (E8) and re-pin T7.
- On the device, T4 history is unavailable or empty on API 35/36/37 → E5 boundaries fall back to stored snapshots plus the runtime-receiver triggers only. Record it.
- A real emulator `chargeUah` is constant (synthetic) → E7 is only unit-proven; mark it "unverified on hardware" in PROGRESS.
- Proto parse fails on any fixture → T1 drops to a hand-written decoder for the affected root (SQ-17 option 3).
- Capture p95 on a bound service is > 1 s → split the capture: usage + kernel first, dump and history later in the same `goAsync` window.
