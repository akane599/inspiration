audited: 2026-10-17. Reviewers: SQ-19 plan-review (Opus/medium): APPROVE WITH FIXES. SQ-20 review-audit (GPT-5.6 Terra/medium): agree on E1 and E2, fixes required for E3, E8 and E9. All fixes applied below (rev 2).

# Phase 2 — Telemetry engine, proto parser, snapshot diff, Room, Coulomb (story US-2)

Source spec: `TEMPLATE.md` §3, §6, §7 Aşama 2 (Turkish). Recon evidence: SQ-17 (proto schemas; comments c_mvc2zkr0_a24852 and c_mvc3a5xn_96a018) and SQ-18 (capture and history APIs; comment c_mvc3cj5x_cc81c5). Audit: SQ-19 (c_mvc3q676_df4b83) and SQ-20 (c_mvc3mif7_b0ed38). Phase 1 contract: `docs/plans/phase-1.md` (D1–D11 still hold).

## Outcome
The app can take a **BatterySnapshot** and store it. A **full** snapshot merges:
- `BatteryUsageStats` (per-UID mAh, process states, device aggregate);
- the parsed `batterystats` / `alarm` / `power` proto dumps;
- calibrated sysfs kernel battery values;
- CPU `time_in_state`.

A **light** snapshot needs no Binder call: sticky `BATTERY_CHANGED`, BatteryManager current, and clocks.

Battery-history events are ingested incrementally, so reference points and charge sessions survive stats resets. The app computes Δ = S_now − S_ref with explicit validity rules, deep-sleep and awake-screen-off math, and estimates capacity from charge sessions. Room (WAL) persists all of it, with 30-day downsampling. Nothing polls and nothing stays resident.

Proven by:
- JVM tests against 9 real emulator fixtures and synthetic data;
- Room DAO/repository tests under Robolectric;
- an orchestrator device run (`PROBE_CAPTURE`, `PROBE_DIFF`, DB/WAL check, job re-arm count) on API 35/36/37.

## Non-goals (owning phase)
- **Phase 3:** rules, wakelock dictionary, Battery Story; the `usagestats` standby-bucket and exit-reason clients.
- **Phase 4/5:** UI, charts, ViewModels, toolkit actions, the **in-app open-source notices screen** (Apache-2.0 §4d for the vendored protos).
- No `aggregateSnapshots`, `monotonicTimeRange` or `accumulated()`. No ODPM energy in snapshots; the debug probe only.
- No resident or foreground service, no periodic job, no WorkManager, no DI framework, no new lifecycle-process artifact.

## Decisions (with evidence)
| # | Decision | Why |
|---|---|---|
| E1 | **Pull-first capture, per trigger:** (a) **APP_OPEN**: full capture plus history ingest, from `MainActivity.onStart`, debounced 60 s. It is never triggered by process start, because a job or boot receiver also starts the process (SQ-19 M2). (b) **Runtime receiver** registered in `InspirationApplication.onCreate` (`RECEIVER_NOT_EXPORTED`): SCREEN_ON/OFF write a **light** snapshot only, synchronously, with no `goAsync` (ordered broadcast plus a held suspend blocker, SQ-19 M1). POWER_CONNECTED/DISCONNECTED and BATTERY_LOW/OKAY do a full capture **only if the telemetry service is already bound** (warm), under `goAsync` with a 2 s deadline; otherwise they write a light snapshot. (c) **One persisted JobScheduler charging job** (`setRequiresCharging(true)`, `setPersisted(true)`, `setMinimumLatency(45 min)` when re-armed from its own run). It is idempotent per plug session: it skips if a CHARGE_JOB snapshot exists after the last PLUGGED event (SQ-19 B1). Each run does a full capture plus history ingest plus prune. (d) **BOOT_COMPLETED / MY_PACKAGE_REPLACED** manifest receiver: writes a light BOOT snapshot and arms the job (no latency). (e) **Custom marker**: full capture. A miss is recorded in `capture_miss`, never retried in a loop. | SQ-18 §1/§2; SQ-19 B1/M1/M2. TEMPLATE §6.2 is infeasible as written (dead/cached processes don't get these broadcasts); this is the closest zero-observer design (SQ-20 agrees on E1). |
| E2 | **Proto: trimmed, vendored AOSP schemas** (batterystats with the `BatteryStatsServiceDumpProto` wrapper, power, alarm). They keep the AOSP `package` and field numbers but set `option java_package = "com.akane.inspiration.telemetry.proto"`. External enums are `int32`, `sint*` is kept, privacy imports/options are dropped, and every file has an Apache-2.0 header with a "trimmed" mark. protobuf plugin 0.10.0 + protoc / javalite 4.36.2 lite codegen. `consumer-rules.pro`: `-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }`. A repo `NOTICE` gets an AOSP attribution. `DumpReader` cap 16 MiB. Root check: `hasBatterystats() && report_version >= 35`; a value > 36 parses with a warning in `DumpSnapshot.warnings`. Absent field means default. | SQ-17 §3–5 (9/9 fixtures). SQ-20 agrees. |
| E3 | **History:** `BatteryHistorySnapshot readBatteryHistory(long statsStartMs, long afterHistoryTimeMs, int maxEvents) = 8`. The shell walks `iterateBatteryStatsHistory()` of `getBatteryUsageStats([includeBatteryHistory])` and closes the stats and iterator in `finally` (where Closeable). The result is **keep-oldest**: events with `historyTimeMs > afterHistoryTimeMs`, at most `min(maxEvents, 4096)`, with `truncated = true` and the caller paging on (bounded: ≤ 8 pages per capture). Every event is fixed-size primitives (~44 B), so 4096 events ≤ 180 KiB. If the current session's `statsStartMs` differs from the argument (a reset), the cursor restarts at 0 and `sessionChanged = true`. The history buffer survives a stats reset, so a session restart re-reads old events; `UNIQUE(historyTimeMs, kind, wallMs)` makes that harmless, and the first ingestion keeps the event's original `statsStartMs` (SQ-34, device evidence on API 36). Events: SCREEN_ON/OFF, PLUGGED/UNPLUGGED, **LEVEL only when batteryLevel changes** (carrying the chargeUah/temp/voltage at that point), RESET, START, SHUTDOWN, DEEP_IDLE_ON/OFF. Wall time is rebuilt from `currentTime` anchors. On API 36+ the shell requests the longest preferred history duration if the builder exposes one; T4 reports what exists. History is read **only** on APP_OPEN, CHARGE_JOB and marker captures, never from the runtime receiver. `LinkageError` → unavailable(no_such_method). | SQ-18 §3b; SQ-19 M3/M4; SQ-20 E3. |
| E3b | **Usage DTO byte budget:** the shell serialises `BatteryUsageSnapshot` into a temporary `Parcel` to measure it. Above 512 KiB it drops the lowest-`totalMah` UIDs until it fits and sets `truncatedUids = <count>`. The usage DTO gains `statsDurationMs` (where available, else end − start) and `deviceComponents` (the device-scope aggregate consumer). | SQ-20 E3 (unbounded maps); SQ-19 M5/M6. |
| E4 | **Validity tuple** per snapshot: `bootCount`, `elapsedRealtimeMs`, `uptimeMs`, `wallMs`, `statsStartMs`, `statsDurationMs`. A batterystats-derived Δ is valid iff the two `statsStartMs` match (±2 s) and `statsDuration_now ≥ statsDuration_ref`. A kernel/clock Δ (cpu time_in_state, elapsed − uptime) is valid iff `bootCount` matches and elapsed time increases. A negative per-UID or per-component Δ makes that part invalid and it is reported, never clamped; `UidDelta.valid` identifies invalid UID deltas and `DiffValidity.invalidDeviceComponents` and `invalidKernelWakelocks` identify invalid device-level entries. A missing UID in S_now gives Δ = 0. For UID ≥ 10000, a package-name change between ref and now counts the UID as new (ref = 0) and is flagged. **Session skew:** if the dump's `system.battery.start_clock_time_ms` differs from the usage `statsStartMs` by > 2 s, the dump part is unavailable("session_skew"). A wall-clock fix can rewrite statsStart and read as a false reset; this is conservative and documented. | SQ-18 §4; SQ-19 M5, m5. |
| E5 | **References** (semantics pinned): SINCE_UNPLUGGED = last UNPLUGGED event → now. SINCE_SCREEN_OFF = the last **completed** screen-off session (last SCREEN_OFF → the following SCREEN_ON); this is the "night" view. SCREEN_ON_SESSION = last SCREEN_ON → now. LAST_24H. CUSTOM_MARKER(id). TIME_SLICE(from, to). Boundaries come from the stored `history_event` rows, with markers and light snapshots as a fallback. S_ref = the closest valid snapshot at or after the boundary (for per-UID, a **full** snapshot). S_now = the latest, or the closest at or before `to`. The result reports covered from/to and `gapMs`. Device-level interval values (level drop, screen time, Δ chargeUah, deep sleep from light snapshots) come from history and light snapshots even when per-UID values aren't available. Known limitation: on API 35+, coalesced broadcasts make snapshots sparse, so TIME_SLICE per-UID values often carry large gaps, and the UI has to say so. | SQ-18 §3; SQ-19 B2, m10. |
| E6 | **Deep sleep** from the proto `system.battery`: deep sleep = Δ(battery_realtime − battery_uptime); screen-off deep sleep = Δ(screen_off_realtime − screen_off_uptime); screen-off awake = Δ screen_off_uptime; ratio = screen-off deep sleep / Δ screen_off_realtime. **Light-snapshot fallback** (same boot): Δ elapsedRealtime − Δ uptimeMillis between two light or full snapshots. | SQ-17 §1; TEMPLATE §3.2. |
| E7 | **Capacity:** segments come from **stored** `history_event` rows with plugged throughout and SoC rising ≥ 40 points: C = Δ chargeUah / (ΔSoC / 100). Fallback: trapezoid ∫I dt over live samples (any gap > 5 s → none). Rules: reject maxTemp > 45 °C; reject C outside 0.5–1.2 × design; the median-deviation rule (> 15 %) applies only once ≥ 3 segments are accepted, and resets after 3 consecutive rejections. EMA α = 0.3 over accepted segments in **chronological** order. If |measured − kernel charge_full| ≤ 1 %, `independent = false`: many gauges derive charge_counter from SoC × learned FCC. **Primary display = kernel FCC + SoH**; measured C is shown only when `independent`. `combine` takes calibrated mAh values (`KernelCapacity`), not raw sysfs. | TEMPLATE §3.3; SQ-19 M8 + contrarian 3. |
| E8 | **Room 2.8.5** (`androidx.room`, KSP 2.3.12, plugin `androidx.room` with `room { schemaDirectory("$projectDir/schemas") }`). Framework SQLite through SupportSQLite. **WAL** is set explicitly with `setJournalMode(WRITE_AHEAD_LOGGING)` and asserted. DAO/repository tests run under **Robolectric** (a test-only dependency; version pinned by T3, ≥ 2 weeks old, supporting SDK 36 via `@Config(sdk = [35 or 36])`). The device run checks `PRAGMA journal_mode` = wal. Plain entities, no Parcelize. **Room 3 is deferred** because neither host-test path works in an Android-library module (SQ-19 B4) and the bundled driver costs ~1 MB/ABI (SQ-20 E8). Both reviewers independently preferred Room 2.8.x. | SQ-2 §2; SQ-19 B4 + contrarian 2; SQ-20 E8. Supersedes Phase 1 D6's "Room 3 preferred". |
| E9 | **Schema v1** (below). It stays mutable until the first release; after that every change needs a migration plus a `MigrationTestHelper` test. Pruning runs only inside CHARGE_JOB and APP_OPEN captures. Samples older than 30 d go to `telemetry_hourly`, then are deleted. Snapshots older than 30 d are deleted unless a marker references them; children cascade. `history_event` and `capture_miss` are pruned after 30 d. **Privacy:** `android:allowBackup="false"` plus `dataExtractionRules` that exclude all domains for both `cloud-backup` and `device-transfer`, and `fullBackupContent` = exclude all (pre-31 attribute kept for lint). This lands **in T8, before** any production write. No export in Phase 2. | TEMPLATE §6.3; SQ-20 E9 (backup exfiltration of app inventory and wakelock names). |
| E10 | **Live telemetry:** a cold `Flow<LiveReading>` that reads `readKernelBattery()` every 1.5 s **only while collected**. Samples are persisted only while collected and charging. | TEMPLATE §5/§6.1. |
| E11 | **Module boundaries:** domain models, parser, diff, capacity, capturer and the `SnapshotStore` interface live in `:core:telemetry-engine` (→ `:core:shizuku-ipc`). `:core:database` implements `SnapshotStore` (→ telemetry-engine). Receivers, the JobService, the boot receiver and wiring live in `:app`, which **switches telemetry-engine from `debugImplementation` to `implementation`** and adds `:core:database`. | No DI framework (D8); SQ-19 M7/m7. |
| E12 | **Bind for capture:** `ShizukuManager.withTelemetryService(timeoutMs) { service -> ... }` is ref-counted. It binds if needed, waits ≤ timeout for the service, runs the block, and when the count returns to 0 and no `holdBinding()` holder exists (a UI holder), it unbinds with `remove = true`, so the shell app_process exits (daemon(false), D10). `start()` stays the permission/state watcher and **no longer binds by itself**. | SQ-19 B5 (`start()` keeps the shell process resident). |
| E13 | **Production `CalibrationInput` reader** (BatteryManager + sysfs) in telemetry-engine; the result is cached in-process. | Phase 1 carry-over. |

## Schema v1 (`:core:database`, Room 2.8.5, plain `@Entity` classes)
```
snapshot(id PK autoinc, kind TEXT /* FULL|LIGHT */, trigger TEXT /* CaptureTrigger */, wallMs, elapsedRealtimeMs, uptimeMs, bootCount,
         statsStartMs?, statsDurationMs?, levelPct?, plugged?, statusText?, temperatureDeciC?, voltageMv?, currentMa?, chargeCounterUah?,
         dischargedMah?, truncatedUids INT DEFAULT 0)
         INDEX(bootCount, elapsedRealtimeMs), INDEX(wallMs), INDEX(statsStartMs)
snapshot_system(snapshotId PK FK cascade, startClockTimeMs, totalRealtimeMs, totalUptimeMs, batteryRealtimeMs, batteryUptimeMs,
         screenOffRealtimeMs, screenOffUptimeMs, screenDozeMs, screenOnMs, interactiveMs, deepDozeMs, deepDozeCount, lightDozeMs,
         dischargeTotalMah, dischargeScreenOffMah, chargeTimeRemainingMs?, dischargeTimeRemainingMs?)        -- absent row == system null
snapshot_availability(snapshotId FK cascade, source TEXT /* usage|dump|kernel|cpu|history */, available INT, reason?)  PK(snapshotId, source)
snapshot_uid(snapshotId FK cascade, uid, packageName?, totalMah, fgMah?, bgMah?, fgsMah?, cachedMah?,
         cpuUserMs, cpuSystemMs, fgActivityMs, fgServiceMs)                                                 PK(snapshotId, uid)
snapshot_component(snapshotId FK cascade, uid /* -1 = device aggregate */, component TEXT, mah)              PK(snapshotId, uid, component)
snapshot_wakelock(snapshotId FK cascade, kind TEXT /* PARTIAL|KERNEL|WAKEUP_REASON */, uid /* -1 for kernel kinds */, name /* "" → "<unnamed>" */,
         durationMs, bgDurationMs, count)                                  PK(snapshotId, kind, uid, name)   -- duplicates merged (summed) before insert
snapshot_alarm(snapshotId FK cascade, uid, tag, count)                                                     PK(snapshotId, uid, tag)  -- summed per tag
snapshot_cpu_freq(snapshotId FK cascade, policy, freqKhz, timeMs)                                          PK(snapshotId, policy, freqKhz)
marker(id PK autoinc, snapshotId FK restrict, label, wallMs)                                               INDEX(snapshotId)
history_event(id PK autoinc, statsStartMs, historyTimeMs, wallMs, kind TEXT, levelPct?, chargeUah?, temperatureDeciC?, voltageMv?, plugType?)
         UNIQUE(historyTimeMs, kind, wallMs), INDEX(wallMs)   -- not session-keyed: history survives stats resets (SQ-34)
history_cursor(id PK = 1, statsStartMs, lastHistoryTimeMs)
telemetry_sample(id PK autoinc, wallMs, elapsedRealtimeMs, bootCount, currentMa?, voltageMv?, watts?, temperatureDeciC?, levelPct?, plugged?)  INDEX(wallMs)
telemetry_hourly(hourStartWallMs PK, sampleCount, avgCurrentMa, minCurrentMa, maxCurrentMa, avgVoltageMv, avgWatts, maxTemperatureDeciC, minLevelPct, maxLevelPct)
charge_session(id PK autoinc, statsStartMs, startHistoryTimeMs, startWallMs, endWallMs, startPct, endPct, deltaChargeUah?, integratedMah?,
         maxTemperatureDeciC?, estimateMah?, independent INT, accepted INT, rejectReason?)               UNIQUE(statsStartMs, startHistoryTimeMs)
capture_miss(id PK autoinc, wallMs, trigger, reason)                                                       INDEX(wallMs)
```
**T7 round-trip oracle:** `store.insert(s)` followed by reading it back by id equals `s` with `id` set, for full, light and partial-availability snapshots. Lists compare order-insensitively after the merge rules above.

## Shared surface (pinned; package `com.akane.inspiration.telemetry` unless noted)
```kotlin
// common/ (owner T2)
interface Clocks { fun wallMs(): Long; fun elapsedRealtimeMs(): Long; fun uptimeMs(): Long; fun bootCount(): Int }   // Android impl in T6
enum class Confidence  // reuse calibration.Confidence (existing)

// dump/ (owner T1)
data class DumpSnapshot(val reportVersion: Int, val system: SystemTimers, val kernelWakelocks: List<KernelWakelock>, val wakeupReasons: List<KernelWakelock>,
    val uids: List<UidDump>, val cpuFrequenciesKhz: List<Long>, val warnings: List<String>)
data class SystemTimers(val startClockTimeMs: Long, val totalRealtimeMs: Long, val totalUptimeMs: Long, val batteryRealtimeMs: Long, val batteryUptimeMs: Long,
    val screenOffRealtimeMs: Long, val screenOffUptimeMs: Long, val screenDozeMs: Long, val screenOnMs: Long, val interactiveMs: Long,
    val deepDozeMs: Long, val deepDozeCount: Long, val lightDozeMs: Long, val dischargeTotalMah: Double, val dischargeScreenOffMah: Double,
    val chargeTimeRemainingMs: Long?, val dischargeTimeRemainingMs: Long?)
data class KernelWakelock(val name: String, val durationMs: Long, val count: Long)            // merged by name; "" → "<unnamed>"
data class UidDump(val uid: Int, val partialWakelocks: List<UidWakelock>, val wakeupAlarms: List<UidAlarm>, val cpuUserMs: Long, val cpuSystemMs: Long,
    val fgActivityMs: Long, val fgServiceMs: Long, val processStateMs: Map<Int, Long>)
data class UidWakelock(val name: String, val durationMs: Long, val bgDurationMs: Long, val count: Long)
data class UidAlarm(val tag: String, val count: Long)                                         // summed per tag
data class PowerDump(val wakefulness: Int, val heldWakeLocks: List<HeldWakeLock>, val lastWakeMs: Long, val lastSleepMs: Long)
data class HeldWakeLock(val tag: String, val uid: Int, val levelFlags: Int, val acquiredMs: Long)
data class AlarmDump(val filterStats: List<AlarmFilterStat>, val pendingWakeups: Int)
data class AlarmFilterStat(val uid: Int, val packageName: String, val tag: String, val count: Long, val wakeupCount: Long, val totalFlightMs: Long)
sealed interface DumpParse<out T> { data class Ok<T>(val value: T) : DumpParse<T>; data class Unavailable(val reason: String) : DumpParse<Nothing> }
object DumpParser { fun parseBatteryStats(bytes: ByteArray): DumpParse<DumpSnapshot>; fun parsePower(bytes: ByteArray): DumpParse<PowerDump>; fun parseAlarm(bytes: ByteArray): DumpParse<AlarmDump> }
object DumpReader { const val MAX_BYTES = 16 * 1024 * 1024
    fun readAll(input: java.io.InputStream, maxBytes: Int = MAX_BYTES): ByteArray?          // null if over cap / IOException; closes input
    fun readAll(fd: android.os.ParcelFileDescriptor, maxBytes: Int = MAX_BYTES): ByteArray? } // thin AutoCloseInputStream wrapper

// ipc (owner T4) — AIDL `BatteryHistorySnapshot readBatteryHistory(long statsStartMs, long afterHistoryTimeMs, int maxEvents) = 8;`
@Parcelize data class BatteryHistorySnapshot(val available: Boolean, val reason: String?, val statsStartMs: Long, val sessionChanged: Boolean,
    val events: List<HistoryEvent>, val truncated: Boolean)
@Parcelize data class HistoryEvent(val historyTimeMs: Long, val wallMs: Long, val kind: Int, val levelPct: Int, val chargeUah: Long /* -1 */,
    val temperatureDeciC: Int, val voltageMv: Int, val plugType: Int)
object HistoryKind { const val SCREEN_ON = 1; SCREEN_OFF = 2; PLUGGED = 3; UNPLUGGED = 4; LEVEL = 5; RESET = 6; START = 7; SHUTDOWN = 8; DEEP_IDLE_ON = 9; DEEP_IDLE_OFF = 10 }
// BatteryUsageSnapshot gains: statsDurationMs: Long, deviceComponents: Map<String, Double>, truncatedUids: Int   (E3b)

// ipc (owner T9)
interface ShizukuManager { /* existing */ suspend fun <T> withTelemetryService(timeoutMs: Long, block: suspend (ITelemetryUserService) -> T): T?  /* null = unavailable/timeout */
    fun holdBinding(): AutoCloseable   /* UI keeps the service warm while visible */ }

// snapshot/ + diff/ + store/ (owner T2)
enum class CaptureTrigger { APP_OPEN, SCREEN_OFF, SCREEN_ON, POWER_CONNECTED, POWER_DISCONNECTED, BATTERY_LOW, BATTERY_OKAY, CHARGE_JOB, BOOT, MARKER }
enum class SnapshotKind { FULL, LIGHT }
data class SnapshotClock(val wallMs: Long, val elapsedRealtimeMs: Long, val uptimeMs: Long, val bootCount: Int, val statsStartMs: Long?, val statsDurationMs: Long?)
data class BatterySnapshot(val id: Long, val kind: SnapshotKind, val trigger: CaptureTrigger, val clock: SnapshotClock, val battery: BatteryState,
    val system: SystemTimers?, val uids: List<UidSnapshot>, val deviceComponentsMah: Map<String, Double>, val kernelWakelocks: List<KernelWakelock>,
    val wakeupReasons: List<KernelWakelock>, val cpuFreq: List<CpuFreqTime>, val availability: Map<String, SourceAvailability>, val truncatedUids: Int)
data class SourceAvailability(val available: Boolean, val reason: String?)
data class BatteryState(val levelPct: Int?, val plugged: Boolean?, val status: String?, val temperatureDeciC: Int?, val voltageMv: Int?, val currentMa: Int?, val chargeCounterUah: Long?, val dischargedMah: Double?)
data class UidSnapshot(val uid: Int, val packageName: String?, val totalMah: Double, val fgMah: Double?, val bgMah: Double?, val fgsMah: Double?, val cachedMah: Double?,
    val componentsMah: Map<String, Double>, val cpuUserMs: Long, val cpuSystemMs: Long, val fgActivityMs: Long, val fgServiceMs: Long,
    val partialWakelocks: List<UidWakelock>, val wakeupAlarms: List<UidAlarm>)
data class CpuFreqTime(val policy: Int, val freqKhz: Long, val timeMs: Long)
data class UidDelta(val uid: Int, val packageName: String?, val packageChanged: Boolean, val valid: Boolean, val totalMah: Double, val fgMah: Double?, val bgMah: Double?, val fgsMah: Double?,
    val cachedMah: Double?, val componentsMah: Map<String, Double>, val cpuUserMs: Long, val cpuSystemMs: Long, val fgActivityMs: Long, val fgServiceMs: Long,
    val partialWakelocks: List<UidWakelock>, val wakeupAlarms: List<UidAlarm>)
data class DiffValidity(val batteryStats: Boolean, val kernelCounters: Boolean, val problems: List<String>, val invalidDeviceComponents: Set<String> = emptySet(), val invalidKernelWakelocks: Set<String> = emptySet())
data class SleepStats(val deepSleepMs: Long, val screenOffDeepSleepMs: Long?, val screenOffAwakeMs: Long?, val screenOffMs: Long?, val screenOnMs: Long?, val deepSleepRatio: Double?)
data class SnapshotDiff(val ref: SnapshotClock, val now: SnapshotClock, val validity: DiffValidity, val durationMs: Long, val uids: List<UidDelta>,
    val deviceComponentsMah: Map<String, Double>, val kernelWakelocks: List<KernelWakelock>, val sleep: SleepStats?, val levelDropPct: Int?, val dischargedMah: Double?)
object SnapshotDiffer { fun diff(ref: BatterySnapshot, now: BatterySnapshot): SnapshotDiff }
sealed interface ReferenceKind { data object SinceUnplugged : ReferenceKind; data object SinceScreenOff : ReferenceKind; data object ScreenOnSession : ReferenceKind
    data object Last24h : ReferenceKind; data class CustomMarker(val markerId: Long) : ReferenceKind; data class TimeSlice(val fromWallMs: Long, val toWallMs: Long) : ReferenceKind }
data class ResolvedReference(val ref: BatterySnapshot, val now: BatterySnapshot, val boundaryFromWallMs: Long, val boundaryToWallMs: Long,
    val coveredFromWallMs: Long, val coveredToWallMs: Long, val gapMs: Long, val perUidAvailable: Boolean)
class ReferenceResolver(private val store: SnapshotStore) { suspend fun resolve(kind: ReferenceKind, nowWallMs: Long): ResolvedReference? }
data class LiveReading(val wallMs: Long, val elapsedRealtimeMs: Long, val bootCount: Int, val currentMa: Int?, val voltageMv: Int?, val watts: Double?,
    val temperatureDeciC: Int?, val levelPct: Int?, val plugged: Boolean?)
interface SnapshotStore {
  suspend fun insert(snapshot: BatterySnapshot): Long
  suspend fun byId(id: Long): BatterySnapshot?
  suspend fun latest(kind: SnapshotKind? = null): BatterySnapshot?
  suspend fun firstAtOrAfter(wallMs: Long, kind: SnapshotKind?, statsStartMs: Long?, bootCount: Int?): BatterySnapshot?
  suspend fun lastAtOrBefore(wallMs: Long, kind: SnapshotKind?): BatterySnapshot?
  suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot?
  suspend fun insertHistory(events: List<HistoryEvent>, statsStartMs: Long)          // dedup on UNIQUE(statsStartMs, historyTimeMs, kind)
  suspend fun historyBetween(fromWallMs: Long, toWallMs: Long): List<HistoryEvent>
  suspend fun historyCursor(): Pair<Long, Long>?                                    // (statsStartMs, lastHistoryTimeMs)
  suspend fun setHistoryCursor(statsStartMs: Long, lastHistoryTimeMs: Long)
  suspend fun addMarker(snapshotId: Long, label: String, wallMs: Long): Long
  suspend fun marker(id: Long): Pair<Long, BatterySnapshot>?
  suspend fun insertSamples(samples: List<LiveReading>)
  suspend fun upsertChargeSession(statsStartMs: Long, segment: ChargeSegment, verdict: SegmentVerdict)
  suspend fun acceptedEstimatesChronological(limit: Int): List<Double>
  suspend fun recordMiss(wallMs: Long, trigger: CaptureTrigger, reason: String)
  suspend fun prune(nowWallMs: Long)
}

// capacity/ (owner T5)
data class ChargeSegment(val startHistoryTimeMs: Long, val startWallMs: Long, val endWallMs: Long, val startPct: Int, val endPct: Int,
    val deltaChargeUah: Long?, val integratedMah: Double?, val maxTemperatureDeciC: Int?)
data class KernelCapacity(val chargeFullMah: Double?, val designMah: Double?, val cycleCount: Int?)
data class SegmentVerdict(val estimateMah: Double?, val independent: Boolean, val accepted: Boolean, val rejectReason: String?)
data class CapacityEstimate(val kernelChargeFullMah: Double?, val kernelHealthPct: Double?, val designMah: Double?, val cycleCount: Int?,
    val measuredMah: Double?, val measuredHealthPct: Double?, val independent: Boolean, val sampleCount: Int, val confidence: Confidence)
object ChargeSegmentExtractor { fun fromHistory(events: List<HistoryEvent>): List<ChargeSegment> }
object CapacityEstimator { fun evaluate(segment: ChargeSegment, kernel: KernelCapacity, recentAcceptedChronological: List<Double>, consecutiveRejections: Int): SegmentVerdict
    fun combine(acceptedChronological: List<Double>, kernel: KernelCapacity): CapacityEstimate }
object Integrator { fun trapezoidMah(samples: List<Pair<Long, Double>>): Double? }

// capture/ + source/ + live/ (owner T6)
interface TelemetrySource { suspend fun full(trigger: CaptureTrigger, historyCursor: Pair<Long, Long>?): FullRead?; fun light(): LightRead }  // T6 pins FullRead/LightRead
class SnapshotCapturer(source: TelemetrySource, store: SnapshotStore, clocks: Clocks) {
    suspend fun captureFull(trigger: CaptureTrigger, deadlineMs: Long, ingestHistory: Boolean): CaptureResult
    suspend fun captureLight(trigger: CaptureTrigger): CaptureResult }
sealed interface CaptureResult { data class Saved(val id: Long, val kind: SnapshotKind) : CaptureResult; data class Missed(val reason: String) : CaptureResult }
class LiveTelemetry(source: TelemetrySource, calibrator: com.akane.inspiration.telemetry.calibration.PowerCalibrator, clocks: Clocks) { fun readings(periodMs: Long = 1_500): Flow<LiveReading> }

// app (owner T8): pure, unit-tested policy
object CapturePolicy { fun forBroadcast(action: String, serviceWarm: Boolean): Plan  /* LIGHT | FULL(deadline) */; fun chargeJobShouldRun(lastPluggedWallMs: Long?, lastChargeJobWallMs: Long?): Boolean
    fun appOpenDebounced(lastAppOpenWallMs: Long?, nowWallMs: Long): Boolean }
```
Per-UID process-state mapping: `fgMah` = foreground, `bgMah` = background, `fgsMah` = foreground_service, `cachedMah` = cached. The keys are as produced by T5a `BatteryUsageAdapter`. They are null when `byProcessState == null` (API 31/32). `deviceComponentsMah` comes from the device aggregate consumer (E3b); if that is unavailable, it is the sum of the UID components with `availability["usage"].reason = "device_from_uid_sum"`.

## Tickets (Gradle ≤ 2 concurrent; dispatch order T1+T4 → T3+T9 → T2+T5 → T6+T7 → T8)
| T | Title | Category | Files | Depends | Verify |
|---|---|---|---|---|---|
| T1 | Trimmed vendored protos + DumpParser + DumpReader + consumer R8 rule + NOTICE; adds kotlinx-coroutines-core (impl) and kotlinx-coroutines-test (test) to telemetry-engine | coding.normal | core/telemetry-engine/{build.gradle.kts, consumer-rules.pro, src/main/proto/**, src/main/kotlin/.../dump/**, src/test/kotlin/.../dump/**}, NOTICE | — | `:core:telemetry-engine:testDebugUnitTest`. Fixtures: batterystats api35/36/37 → uids 66/70/70; kernel_wakelock **entries** 66/68/69 (before merging); UidProto.wakelocks entries summed over UIDs 25/133/113; wakeup_alarm entries 3/5/4; power `wake_locks` 2/0/1 and `suspend_blockers` 5; alarm `alarm_stats` entries 5 and `pending_alarms` 56/49/49. Synthetic cases: truncated, empty, error text, invalid UTF-8, api37 enum value 14 preserved, over cap, report_version 37 parses with a warning. Starting point: SQ-17's trimmed protos in the scratchpad `trim*/` (copy and adapt; they're not authoritative) |
| T4 | readBatteryHistory (AIDL 8) + history stubs + usage DTO additions (statsDurationMs, deviceComponents, truncatedUids, 512 KiB parcel budget) + UserService impl | coding.hard | core/hidden-api-stub/**, core/shizuku-ipc/src/main/aidl/**, .../ipc/model/{BatteryHistory*,BatteryUsageSnapshot,TelemetryValues}.kt, .../ipc/client/{BatteryHistoryReader,BatteryHistoryAdapter,BatteryStatsClient,BatteryUsageAdapter}.kt, .../ipc/service/TelemetryUserService.kt, matching tests, app/src/debug/** (probe adds a `history` line) | — | `:core:shizuku-ipc:testDebugUnitTest :app:assembleDebug`. JVM cases through an **adapter seam** (not stubs): wall anchoring across CURRENT_TIME/RESET, LEVEL only on change, keep-oldest cap + `truncated`, cursor + `sessionChanged`, LinkageError → unavailable, budget truncation drops the smallest UIDs, the caller-UID check runs first. Device evidence by the orchestrator. **review-audit** bound to the candidate |
| T3 | Room 2.8.5 DB: schema v1 entities, DAOs, WAL, schema export, prune/downsample SQL; Robolectric test infra | coding.normal | gradle/libs.versions.toml (room 2.8.5 runtime/ktx/compiler/plugin/testing, robolectric, androidx.test core: the ticket authorizes these), core/database/** | — | `:core:database:testDebugUnitTest` (Robolectric: open DB, `PRAGMA journal_mode` = wal, cascades, dedup UNIQUE, wakelock merge-on-conflict, prune keeps marker-referenced snapshots, 30-day downsample) |
| T9 | ShizukuManager.withTelemetryService (ref-counted bind/unbind) + holdBinding; start() no longer binds | coding.normal | core/shizuku-ipc/src/main/kotlin/.../ipc/{ShizukuManager,ShizukuManagerImpl,ShizukuFacade}.kt, its tests, app/src/debug/** (the probe uses `holdBinding` / `withTelemetryService`) | — | `:core:shizuku-ipc:testDebugUnitTest :app:assembleDebug`: count → 0 unbinds with remove = true; a holder keeps it bound; timeout → null; binder death mid-block → null; concurrent callers share one bind |
| T2 | Domain snapshot model, Clocks, SnapshotDiffer, SleepStats, ReferenceResolver, SnapshotStore interface, LiveReading | coding.normal | core/telemetry-engine/src/{main,test}/kotlin/.../telemetry/{common,snapshot,diff,store}/** | T1 | `:core:telemetry-engine:testDebugUnitTest`: same session; reset (statsStart change); reboot (bootCount); session skew; negative UID Δ; missing UID; package change for UID ≥ 10000; light+light deep sleep; every ReferenceKind with gap reporting (in-memory fake store) |
| T5 | ChargeSegmentExtractor + CapacityEstimator + Integrator | coding.normal | core/telemetry-engine/src/{main,test}/kotlin/.../telemetry/capacity/** | T4 (HistoryEvent), T1 (build deps) | `:core:telemetry-engine:testDebugUnitTest`: the ≥ 40 rule; 45 °C reject; design bounds; median after ≥ 3; reset after 3 rejections; chronological EMA; independence flag; trapezoid gap |
| T6 | TelemetrySource (IPC through withTelemetryService + BatteryManager + sticky BATTERY_CHANGED), Android Clocks, CalibrationInput reader, SnapshotCapturer (full/light, merge, history paging ≤ 8 pages + cursor, deadline → Missed + recordMiss), LiveTelemetry | coding.normal | core/telemetry-engine/src/{main,test}/kotlin/.../telemetry/{capture,source,live}/** | T1, T2, T4, T5, T9 | `:core:telemetry-engine:testDebugUnitTest` (fake source/store): merge (process states, components, alarm/wakelock merging), partial availability, session skew, history paging + cursor persistence, deadline, LiveTelemetry stops on cancel (virtual time) |
| T7 | Room-backed SnapshotStore (mappers, history/cursor, markers, samples, charge sessions, misses, prune) | coding.normal | core/database/src/{main,test}/kotlin/.../database/store/**, core/database/build.gradle.kts (project dep on telemetry-engine) | T2, T3, T5 | `:core:database:testDebugUnitTest` (Robolectric): the round-trip oracle above; firstAtOrAfter honours kind/statsStart/bootCount; history dedup + cursor; prune |
| T8 | App wiring: CapturePolicy, runtime receiver, BOOT/MY_PACKAGE_REPLACED receiver, persisted charging JobService (re-arm with 45 min latency, idempotent), APP_OPEN from MainActivity.onStart, AppContainer, **backup off + dataExtractionRules**, telemetry-engine → implementation, debug `PROBE_CAPTURE` / `PROBE_DIFF` / DB check, timing the same usage call in-process vs in the UserService (PROGRESS D1 note) | coding.normal | app/src/main/**, app/src/debug/**, app/src/test/**, app/build.gradle.kts (project deps only) | T6, T7 | `:app:assembleDebug :app:testDebugUnitTest :app:minifyReleaseWithR8` plus a grep of `app/build/outputs/mapping/release/configuration.txt` for the GeneratedMessageLite keep rule. CapturePolicy unit tests. **review-audit** bound (zero-observer seam). Orchestrator device run: `cmd battery set ac 1` → `dumpsys jobscheduler` (the job ran once, re-armed with latency), screen off/on → light rows, `PROBE_CAPTURE` / `PROBE_DIFF` JSON, `PRAGMA journal_mode` |

Review budget: T4 (shell boundary + ABI) and T8 (zero-observer seam) get a GPT `review-audit`. The others have deterministic oracles.

## Design-reopen evidence
- T3 Robolectric can't open Room 2.8.5 on SDK 35/36 → move the DAO tests to `androidTest`, run by the orchestrator.
- T4 history is unavailable or empty on any of API 35/36/37 → E5 falls back to light/full snapshots only; record it.
- The emulator `chargeUah` is synthetic or constant → E7 is unit-proven only; mark "unverified on hardware".
- Any fixture fails to parse → a hand decoder for that root (SQ-17 option 3).
- Bound full-capture p95 > 1 s on the emulator → split the capture (usage + kernel first; dump and history in the same window).
- History `historyTimeMs` isn't monotonic across an emulator reboot within one session → change the dedup key to (statsStartMs, wallMs, kind) and note the clock-jump risk.
