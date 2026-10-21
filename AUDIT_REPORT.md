# Inspiration — Whole-Repository Current-Source Audit

**Date:** 2026-10-21  
**Revision:** `8cbe51f012e65253249647d8bf18f784f7853dc6`  
**Result:** **10 confirmed findings: 8 MEDIUM, 2 LOW.** No CRITICAL/HIGH finding was established in the examined scope.  
**Isolation:** **CONTAMINATED / NOT BLIND.** Separate native reviewers and a separate validator were used, but automatically injected instructions, memory and recent commit subjects exposed historical context. No previous report was opened before this report's freeze. Separate-context validation does not establish historical blindness.  
**Change policy:** Audit/report/evidence only. All application fixes below are proposals, unapplied and uncompiled. Original tests passing is not verification of these proposed fixes.

## 1. Executive Summary & System Health Scorecard

| Dimension | Qualitative score | Basis |
|---|---:|---|
| Architecture & Modularity | 8/10 | Explicit module/store/source boundaries, typed IPC and test seams are generally coherent; independent source provenance is not consistently preserved. |
| Correctness & Resilience | 6/10 | Eight material defects remain in calibration, historical boundaries, incomplete counters, maintenance, operation outcomes and persisted-data presentation. |
| Concurrency & Resource Safety | 6/10 | Lifecycle and cancellation paths have substantial guards, but logical Binder deadlines do not bound physically outstanding work. |
| Security & Privilege Hygiene | 8/10 | Examined caller-UID, owner-user, argument and debug-only boundaries reject the investigated unauthorized paths; no vulnerability-database scan or complete runtime matrix was available. |
| Performance & Efficiency | 6/10 | Subscription/idle ownership is generally controlled, but stalled transactions can consume shared IO and long charge sessions can indefinitely skip retention. No physical-battery efficiency measurement was performed. |

These are judgments, not measured guarantees or an exhaustive assurance claim.

### Top three systemic risks

1. **A01 — Cancellation is confused with physical completion.** Timed-out Binder transactions can accumulate until shared IO becomes unavailable.
2. **A02 — Calibration loses source provenance.** A sysfs sign convention is persisted and applied to independently signed BatteryManager readings, corrupting live/stored current and charging status.
3. **A03 — History clock discontinuities are anchored too early.** A reboot START receives the previous boot's wall time and is durably stored, affecting interval boundaries and baselines.

### Evidence notation

Finding locations use exact repository-relative paths. To keep full cross-file traces readable, the following prefixes are expanded literally wherever used:

| Prefix | Exact directory |
|---|---|
| `APP/` | `app/src/main/kotlin/com/akane/inspiration/` |
| `IPC/` | `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/` |
| `TEL/` | `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/` |
| `DB/` | `core/database/src/main/kotlin/com/akane/inspiration/database/` |
| `DIAG/` | `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/` |
| `DS/` | `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/` |
| `HEALTH/` | `feature/health/src/main/kotlin/com/akane/inspiration/health/` |
| `SLEEP/` | `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/` |
| `DASH/` | `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/` |
| `TOOL/` | `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/` |

## 2. Confirmed Findings — Ordered by Severity

### [A01] [MEDIUM] Timed-out Binder calls retain unaccounted physical IO workers

- **Category:** Concurrency / Resource Leak
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/source/AndroidTelemetrySource.kt:255-273`; kernel deadline at `:164-175`.
- **Confidence:** Confirmed via independent call-trace and existing blocked-call semantics; conditional on a stalled service/driver transaction. No affected-device stall was induced.
- **Root Cause & Execution Trace:**
  1. `DASH/CockpitViewModel.kt:138-165` retains the binding while collecting live telemetry.
  2. `APP/AppContainer.kt:118-125,248-249` supplies `TEL/live/LiveTelemetry.kt:35-45`, whose loop continues after a null/failed reading.
  3. `TEL/source/AndroidTelemetrySource.kt:164-175` imposes a coroutine deadline, but `:255-273` dispatches the physical call independently:
     ```kotlin
     ioDispatcher.asExecutor().execute {
         if (!continuation.isActive) return@execute
         val value = runCatching(call).getOrElse { /* ... */ null }
         continuation.resume(value) { _, unused, _ -> unused?.let(release) }
     }
     ```
  4. `IPC/service/TelemetryUserService.kt:191-209,350-403` performs synchronous kernel-file reads. Once the Binder transaction starts, cancelling the waiting continuation does not interrupt it.
  5. `IPC/ShizukuManagerImpl.kt:264-294` releases logical service usage; the visible holder prevents idle teardown at `:420-433`. Later ticks can enter more transactions.
- **Failure Scenario:** A service or kernel-driver read remains stalled while the dashboard stays visible. Repeated deadlines leave more client IO workers blocked. Dispatcher capacity eventually bounds thread use, but unrelated database/file/IPC work then competes with exhausted shared IO. Binder server-thread limits do not release clients waiting on queued synchronous transactions. This is **not unlimited thread creation**.
- **Concrete Fix:** Keep a **process-wide** physical admission budget until each worker actually exits, including cancellation and scheduling failure. Add to the existing companion object:
  ```kotlin
  private val binderSlots = java.util.concurrent.Semaphore(4)
  ```
  Replace `binderCall`, retaining its late-result release contract:
  ```kotlin
  private suspend fun <T : Any> binderCall(
      release: (T) -> Unit = {},
      call: () -> T?,
  ): T? {
      currentCoroutineContext().ensureActive()
      if (!binderSlots.tryAcquire()) return null

      val released = java.util.concurrent.atomic.AtomicBoolean(false)
      val releaseSlot: () -> Unit = {
          if (released.compareAndSet(false, true)) binderSlots.release()
      }
      return suspendCancellableCoroutine { continuation ->
          try {
              ioDispatcher.asExecutor().execute {
                  try {
                      if (!continuation.isActive) return@execute
                      val value = runCatching(call).getOrElse { failure ->
                          if (failure is CancellationException) {
                              continuation.cancel(failure)
                              return@execute
                          }
                          null
                      }
                      continuation.resume(value) { _, unused, _ ->
                          unused?.let(release)
                      }
                  } finally {
                      releaseSlot()
                  }
              }
          } catch (failure: Throwable) {
              releaseSlot()
              continuation.resumeWith(Result.failure(failure))
          }
      }
  }
  ```
  Do not release permits in a cancellation handler, which recreates the defect. A per-instance semaphore does not bound aggregate process use across source instances. Four is a conservative proposed budget, not a measured optimal value.
- **Regression Gap:** `core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/source/AndroidTelemetrySourceDeadlineTest.kt:93-153,199-219` times out one call and then releases its latch. Add repeated blocked calls across multiple source instances, assert at most four physical entries, fail-fast capacity behavior, pre-execution cancellation, executor rejection, recovery and late-FD disposal. The fix and new tests have not run.

### [A02] [MEDIUM] Current sign calibration crosses independent source boundaries

- **Category:** Logic Bug / Architecture
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/calibration/PowerCalibrator.kt:111-115,160-164,193-209,237-246`; `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/status/LightCurrent.kt:32-35`.
- **Confidence:** Confirmed by independent source tracing and replay through existing compiled calibration/light-current classes.
- **Root Cause & Execution Trace:**
  1. `TEL/source/AndroidTelemetrySource.kt:179-191` reads sysfs and BatteryManager independently.
  2. `TEL/calibration/PowerCalibrator.kt:237-246` chooses one preferred current to learn the shared sign:
     ```kotlin
     val currentMa =
         sample.sysfsCurrentRaw?.let { it * sysfsUnit.scaleToMa() }
             ?: sample.bmCurrentNowUa?.let { it * batteryManagerUnit.scaleToMa() }
     ```
  3. `TEL/live/LiveTelemetry.kt:59-85` persists this shared sign; the calibrator applies it to either selected source, and `TEL/status/LightCurrent.kt:32-35` applies it specifically to BatteryManager:
     ```kotlin
     val dischargePositive = start.locks.dischargePositive ?: start.guesses.dischargePositive
     return if (dischargePositive) -currentMa else currentMa
     ```
  4. `APP/status/StatusNotificationService.kt:109-115` consumes light current; `TEL/status/ChargeState.kt:45-51` uses its sign to classify charging. `TEL/capture/SnapshotCapturer.kt:119-120,262-270` persists these values. Live fallback also uses BatteryManager when sysfs becomes unreadable.
- **Failure Scenario:** Three unplugged samples have sysfs `+300000 µA`, BatteryManager `-300000 µA`, voltage `3900000 µV`. The sysfs convention locks `dischargePositive=true`; BatteryManager discharge −300 mA becomes +300 mA. A genuine BatteryManager charge +250 mA becomes −250 mA and can show plugged-but-not-charging. The compiled replay reproduced these inversions. AOSP BatteryManager specifies positive charging/negative discharging; it does not guarantee the same sign for vendor sysfs.
- **Concrete Fix:** Preserve per-source sign evidence, locks, confidence and preferences rather than treating source fallback as interchangeable provenance:
  1. Append `batteryManagerDischargeIsNegative: Boolean = true` to `PowerCalibration` and `batteryManagerDischargePositive: Boolean? = null` to `CalibrationLocks`; include the latter in `orElse` and emitted locks.
  2. Keep existing votes **sysfs-only**; add BatteryManager votes:
     ```kotlin
     private val bmUnpluggedDischargePositive = Vote(
         start.locks.batteryManagerDischargePositive,
         guesses.batteryManagerDischargePositive,
     )
     private val bmStatusDischargePositive = Vote(lockable = false)

     private fun addSignEvidence(sample: CalibrationInput) {
         fun add(currentMa: Double?, unplugged: Vote, status: Vote) {
             if (currentMa == null || abs(currentMa) < NOISE_FLOOR_MA) return
             when {
                 !sample.plugged -> unplugged.add(currentMa > 0.0)
                 sample.isCharging -> status.add(currentMa < 0.0)
             }
         }
         add(
             sample.sysfsCurrentRaw?.let { it * sysfsUnit.scaleToMa() },
             unpluggedDischargePositive, statusDischargePositive,
         )
         add(
             sample.bmCurrentNowUa?.let { it * batteryManagerUnit.scaleToMa() },
             bmUnpluggedDischargePositive, bmStatusDischargePositive,
         )
     }
     ```
  3. Populate the added conversion flag and lock:
     ```kotlin
     batteryManagerDischargeIsNegative = !(
         bmUnpluggedDischargePositive.decision()
             ?: bmStatusDischargePositive.clearMajority()
             ?: false
     )
     // In emitted CalibrationLocks:
     batteryManagerDischargePositive = bmUnpluggedDischargePositive.locked
     ```
     Compute mixed-sign confidence within each source, not between valid opposing conventions.
  4. In `toReading`, choose the matching source's sign:
     ```kotlin
     val dischargeIsNegative =
         if (raw.sysfsCurrentRaw != null) cal.dischargeIsNegative
         else cal.batteryManagerDischargeIsNegative
     val currentMa =
         if (dischargeIsNegative) unnormalizedCurrentMa else -unnormalizedCurrentMa
     ```
     In `LightCurrent`, use only `batteryManagerDischargePositive` locks/guesses.
  5. Update `TEL/calibration/SharedPreferencesCalibrationStore.kt:38-57,69-73` load/save with separate versioned keys, e.g. `sysfsDischargePositive.v2` and `batteryManagerDischargePositive.v2`. Do **not** reuse the legacy shared sign for either source: its acquisition provenance is unknown. Relearn it.
- **Regression Gap:** `PowerCalibratorTest.kt:143-152` uses agreeing source signs; `AndroidTelemetrySourceKernelReadingTest.kt:46-57` covers fallback without opposite prior calibration (both under the corresponding telemetry test packages). Add opposite conventions through persistence/restart, each missing/reappearing source, light/full paths, live fallback, legacy-key invalidation, build-change handling and charge labels. No affected-hardware test or proposed-fix execution ran.

### [A03] [MEDIUM] Reboot START is durably anchored to the previous boot's wall time

- **Category:** Logic Bug
- **Location:** `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/BatteryHistory.kt:123-145,227-239`.
- **Confidence:** Confirmed by independent code/AOSP tracing and the existing compiled extractor.
- **Root Cause & Execution Trace:**
  1. `APP/AppContainer.kt:172-177` requests a FULL capture → `TEL/capture/SnapshotCapturer.kt:70-75` supplies cursor/persistence callback → `TEL/source/AndroidTelemetrySource.kt:226-245` requests pages.
  2. `IPC/service/TelemetryUserService.kt:241-248` authorizes the caller → `IPC/client/BatteryStatsClient.kt:89-103,174-207` copies framework records into the extractor.
  3. The walker updates wall anchors only for CURRENT_TIME/RESET and immediately emits START:
     ```kotlin
     if (record.cmd == HistoryBits.CMD_CURRENT_TIME || record.cmd == HistoryBits.CMD_RESET) {
         anchorWallMs = record.currentTime
         anchorTimeMs = record.time
     }
     // ... CMD_START -> listOf(HistoryKind.START).also { setBaseline(record) }
     // ... wallMs = anchorWallMs?.let { it + (record.time - anchorTimeMs) } ?: -1L
     ```
  4. Android resumes persisted history by writing START then CURRENT_TIME at the **same history timestamp**. That history clock omits the powered-off wall interval. START therefore receives the old boot's extrapolated wall time; the next anchor only fixes later events.
  5. `TEL/capture/SnapshotCapturer.kt:157-161` → `DB/store/RoomSnapshotStore.kt:215-237,715-737` stores the wrong event and advances the cursor.
  6. `DIAG/analysis/IntervalAnalyzer.kt:63-68` → `TEL/interval/IntervalMetrics.kt:137-150` uses START for discontinuities/level baselines. `DIAG/analysis/TimelineBuilder.kt:233-238` also uses it as a boundary.
- **Failure Scenario & Reproduction:** Framework-shaped records RESET(time=1000, wall=1000000), SHUTDOWN(2000,1001000), START(2001), CURRENT_TIME(2001,8201000), UPDATE-screen-on(2002) produced START wall **1001001**, not **8201000**, while UPDATE correctly used 8201001. Feeding the emitted events into interval `[1500000,8201500]` gave `discontinuities=[] startLevel=0`, although the actual reboot lies inside it. The error survives persistence.
- **Platform Evidence:** [Android 15 BatteryStatsHistory.java](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/com/android/internal/os/BatteryStatsHistory.java#1229), lines1215-1239; [Android 14 equivalent](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/core/java/com/android/internal/os/BatteryStatsHistory.java#916), lines916-937. [Android 15 MonotonicClock.java](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/com/android/internal/os/MonotonicClock.java#75), lines75-88,127-152, establishes the persisted elapsed-based clock.
- **Concrete Fix:** Before START emission in `advance`, invalidate the old anchor:
  ```kotlin
  if (record.cmd == HistoryBits.CMD_START) {
      anchorWallMs = null
      anchorTimeMs = record.time
  }
  ```
  Immediately after `walker.advance(record)` in `extract`, **before empty-event/cursor continuation and capacity handling**, repair preceding same-time START events:
  ```kotlin
  if (record.cmd == HistoryBits.CMD_CURRENT_TIME ||
      record.cmd == HistoryBits.CMD_RESET
  ) {
      for (index in events.indices.reversed()) {
          val event = events[index]
          if (event.historyTimeMs != record.time) break
          if (event.kind == HistoryKind.START) {
              events[index] = event.copy(wallMs = record.currentTime)
          }
      }
  }
  ```
  Exact-capacity START is still repairable because extraction does not stop just for reaching capacity; a subsequent empty anchor is consumed. Existing timestamp-group rollback handles same-time emitted transitions that overflow capacity. An unanchored START remains unknown; `TEL/store/HistoryWatermark.kt:22-27,51-53` and `DB/store/RoomSnapshotStore.kt:226-229` filter unknown wall time, though the cursor advances.

  **Upgrade repair:** `DB/Entities.kt:253-258` includes wall time in the unique key, so replay alone leaves erroneous duplicates. Propose a schema8 migration that invalidates old STARTs and resets the existing one-time rewind marker, retaining the retention watermark:
  ```kotlin
  val MIGRATION_7_8: Migration = object : Migration(7, 8) {
      override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("DELETE FROM history_event WHERE kind = '7'")
          db.execSQL(
              "DELETE FROM history_cursor WHERE id = ${HistoryDao.REWOUND_MARKER.id}",
          )
      }
  }
  ```
  Register it in `DB/InspirationDatabase.kt`, increment the schema version and export schema8. Existing bounded `resumeCursor` replay can then restore retained raw history. Expired framework anchors cannot be reconstructed; invalidation is not complete historical recovery. This migration is proposed, not performed.
- **Regression Gap:** `core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/client/BatteryHistoryTest.kt:235-263` checks START kind but not adjacent START/CURRENT_TIME wall anchoring. Add long off-gap, exact page budget, same-time anchor transitions, cursor resume, missing anchor, downstream discontinuity and migration/replay duplicate tests. No device reboot or new migration/regression execution ran.

### [A04] [MEDIUM] Partial CPU-policy recovery is reported as interval residency since zero

- **Category:** Logic Bug
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/SnapshotDiffer.kt:37,148-164`.
- **Confidence:** Confirmed by full source trace and compiled private-diff replay.
- **Root Cause & Execution Trace:**
  1. `IPC/service/TelemetryUserService.kt:214-233,363-398` omits unreadable policies/malformed rows; another readable policy keeps the result available.
  2. `TEL/source/AndroidTelemetrySource.kt:75,107` → `TEL/capture/SnapshotCapturer.kt:237,255` → `DB/store/RoomSnapshotStore.kt:86-94,450-453` persists/reconstructs the partial set with no membership-completeness guarantee.
  3. `SnapshotDiffer` subtracts the union of observed keys:
     ```kotlin
     val time = (nowTimes[key] ?: 0L) - (refTimes[key] ?: 0L)
     ```
  4. `DIAG/analysis/IntervalAnalyzer.kt:44-49` passes this purportedly valid result to `SLEEP/SleepLabMappers.kt:285-320,409-415` → `SLEEP/SleepLabCpu.kt:108-113,129-140` as selected-interval residency.
- **Failure Scenario:** Policy0 is present at both endpoints; policy4 was unreadable at the reference and recovers with 3,600,000ms cumulative residency. All 3,600,000ms is attributed to the selected interval with CPU validity true. The compiled helper reproduced that delta. Whole-source availability checks do not guard partial loss. Hotplug/new-frequency membership is not proof of a zero historical baseline.
- **Concrete Fix:** Replace the existing CPU validity calculation with membership-aware gating, leaving unrelated telemetry available:
  ```kotlin
  val cpuMembershipMatches =
      ref.cpuFreq.map { it.policy to it.freqKhz }.toSet() ==
          now.cpuFreq.map { it.policy to it.freqKhz }.toSet()
  val cpuSourcesAvailable =
      ref.sourceAvailable("cpu") && now.sourceAvailable("cpu")
  val cpuFreqValid =
      kernelCounterSessionValid && cpuSourcesAvailable && cpuMembershipMatches
  if (kernelCounterSessionValid && cpuSourcesAvailable && !cpuMembershipMatches) {
      problems += "cpu_freq_membership"
  }
  ```
  Existing output gating then withholds the incomparable result. A more selective policy-level implementation is acceptable only if it retains proven complete baselines; never invent absent counters as zero.
- **Regression Gap:** `core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/diff/SnapshotDifferTest.kt:321-353` covers complete unavailability; `:525-550` currently expects zero baselining for an added frequency. Revise that expectation and test policy/row recovery, disappearance, topology changes and unchanged membership. No affected-device topology/permission test or proposed-fix compilation ran.

### [A05] [MEDIUM] Recorded temperature changes disappear unless battery percentage changes

- **Category:** Logic Bug
- **Location:** `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/TimelineBuilder.kt:62-63,108-123`.
- **Confidence:** Confirmed through independent current-source tracing and existing compiled extractor/builder replay.
- **Root Cause & Execution Trace:**
  1. `IPC/service/TelemetryUserService.kt:241-248` → `IPC/client/BatteryStatsClient.kt:89-103` → `IPC/client/BatteryHistory.kt:147-160,227-239` emits screen/plug/idle changes carrying current temperature without a LEVEL event when percentage is unchanged.
  2. `TEL/source/AndroidTelemetrySource.kt:226-244` → `TEL/capture/SnapshotCapturer.kt:148-162` → `DB/store/RoomSnapshotStore.kt:215-237,715-737` preserves that temperature.
  3. `DASH/CockpitViewModel.kt:166-170,257-275` → `APP/AppContainer.kt:218-229` builds the timeline; the shared points helper samples only:
     ```kotlin
     ordered.filter { it.kind == HistoryKind.LEVEL && it.wallMs > from }
     ```
  4. `DASH/CockpitMappers.kt:259-266` → `DS/chart/scrub/ScrubChartRenderer.kt:149-164,424-431` renders/interpolates the incomplete series and critical-temperature coloring.
- **Failure Scenario:** At100% while plugged, RESET records30°C; SCREEN_ON30minutes later records45°C at the same battery percentage. With a60minute complete horizon, actual compiled output was `[(10000000,30.0),(13600000,30.0)]`, with no45°C point. The chart and cursor falsely remain30°C despite recorded overheating evidence. No physical overheating was induced.
- **Concrete Fix:** Add an optional sampling predicate before `value` in `points`:
  ```kotlin
  sample: (HistoryEvent) -> Boolean = { it.kind == HistoryKind.LEVEL },
  ```
  Replace its in-window filter/map with equal-time deduplicated sampling:
  ```kotlin
  ordered.asSequence()
      .filter { sample(it) && it.wallMs > from }
      .forEach { event ->
          val point = TimelinePoint(event.wallMs, value(event))
          if (out.lastOrNull()?.tMs == point.tMs) {
              out[out.lastIndex] = point
          } else {
              out += point
          }
      }
  ```
  Change only the temperature invocation:
  ```kotlin
  temperaturePoints = points(
      ordered, from, laneEnd, carryIn,
      sample = { it.kind != HistoryKind.START },
  ) { it.temperatureDeciC / 10f },
  ```
  Preserve existing carry-in START rejection and LEVEL-only percentage sampling. START contains synthetic zeros; RESET contains current state and framework SHUTDOWN uses existing current history state. Update the sampling KDoc.
- **Regression Gap:** `core/diagnostics/src/test/kotlin/com/akane/inspiration/diagnostics/analysis/TimelineBuilderTest.kt:357-407` covers LEVEL temperatures/carry-in, not in-window non-LEVEL changes. Add every transition family with stable percentage, equal timestamps, RESET/SHUTDOWN, START exclusion, horizon endpoint and critical chart-color cases. Proposed changes remain unexecuted.

### [A06] [MEDIUM] ART cancellation or fatal dexopt output is recorded as success

- **Category:** Logic Bug / Error Handling
- **Location:** `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/BgDexoptController.kt:307-314`.
- **Confidence:** Confirmed by independent source trace and official Android13–16 command implementations; no privileged optimization was executed.
- **Root Cause & Execution Trace:**
  1. `TOOL/ToolkitViewModel.kt:768-776` → `TEL/toolkit/ToolkitActions.kt:436-475` creates a durable PENDING action → `TEL/toolkit/ToolkitGateway.kt:118-127` → `IPC/service/TelemetryUserService.kt:322-326` starts/observes the controller.
  2. At `IPC/toolkit/BgDexoptController.kt:244-252,307-314`, completion ignores output:
     ```kotlin
     val exit = process.output(timedOut = false).exitCode
     // ...
     finish(BgDexoptState.FINISHED, exit = exit, why = BgDexoptReason.NONE)
     ```
  3. Android14/15/16 ART returns0 for `Job finished. See logs for details`, `Job cancelled. See logs for details`, and `Job encountered a fatal error`. Only Inspiration's own cancellation sets its local flag.
  4. `TEL/toolkit/ToolkitActions.kt:1859-1866,1907-1911` settles FINISHED/0 as DONE. `TOOL/ToolkitSections.kt:237-242` and `TOOL/ToolkitText.kt:378-383` show success.
- **Failure Scenario:** A system/other-client cancellation or fatal ART job result returns exit0. Inspiration permanently audits and displays a successful optimization that did not successfully finish.
- **Platform Evidence:** [ART Android15 ArtShellCommand.java](https://android.googlesource.com/platform/art/+/android-15.0.0_r1/libartservice/service/java/com/android/server/art/ArtShellCommand.java), `handleBgDexoptJob`, lines413-425; corresponding Android14 lines382-395 and Android16 lines416-428. [Android13 legacy PackageManagerShellCommand](https://android.googlesource.com/platform/frameworks/base/+/android-13.0.0_r1/services/core/java/com/android/server/pm/PackageManagerShellCommand.java), `runDexoptJob`,1961-1970, instead prints Success/Failure and returns0/−1.
- **Concrete Fix:** Preserve the **real exit code** and model whether successful completion was verified. Add `BgDexoptReason.RESULT_NOT_SUCCESSFUL = 6` in `IPC/model/BgDexoptStatus.kt`; permit FINISHED reasons in its documentation. Replace `finishFromExit`:
  ```kotlin
  private fun finishFromExit(process: CommandProcess) {
      val output = process.output(timedOut = false)
      val terminal = output.text.lineSequence()
          .map(String::trim)
          .lastOrNull(String::isNotEmpty)
      when {
          synchronized(lock) { userCancelRequested } ->
              finish(BgDexoptState.CANCELLED, output.exitCode, BgDexoptReason.USER)
          output.complete && terminal == "Job cancelled. See logs for details" ->
              finish(BgDexoptState.CANCELLED, output.exitCode, BgDexoptReason.CALLER)
          else -> finish(
              BgDexoptState.FINISHED,
              output.exitCode,
              if (output.complete &&
                  terminal in setOf("Success", "Job finished. See logs for details")
              ) BgDexoptReason.NONE else BgDexoptReason.RESULT_NOT_SUCCESSFUL,
          )
      }
  }
  ```
  `CommandOutput.complete` means successful exit **and** non-truncated/EOF-complete output (`IPC/toolkit/CommandRunner.kt:29-41,158-162`); retained dexopt output is4096bytes with continued draining. Unknown/OEM/truncated output must not be asserted as verified success.

  Extend `TEL/toolkit/ToolkitModels.kt:324-327`:
  ```kotlin
  data class Finished(
      val exitCode: Int,
      val endedAtMs: Long,
      val completionVerified: Boolean = true,
  ) : BgDexoptStatus {
      val succeeded: Boolean get() = exitCode == 0 && completionVerified
  }
  ```
  In gateway `statusOf`, pass `completionVerified = service.reason == BgDexoptReason.NONE`. Replace Finished success tests in durable settlement, color and text with `status.succeeded`. Add localized unverified-completion text, e.g. “Successful completion was not confirmed”, instead of inventing a nonzero process exit. Preserve nonzero real exits and explicit local cancellation.
- **Regression Gap:** `core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/toolkit/BgDexoptControllerTest.kt:61` emits empty fake output. Give success fixtures realistic terminal text and cover legacy/ART success, external cancellation, fatal/empty/unknown/truncated output, nonzero exit, local cancellation, FAILED/CANCELLED durable settlement and non-success UI. No new tests or fix compilation ran.

### [A07] [MEDIUM] Long same-charge sessions stop retention maintenance indefinitely

- **Category:** Performance / Logic Bug
- **Location:** `app/src/main/kotlin/com/akane/inspiration/capture/ChargingCaptureRunner.kt:19-28,49-69`.
- **Confidence:** Confirmed via independent tracing of all production pruning/scheduling callers; no30-day runtime experiment.
- **Root Cause & Execution Trace:**
  1. `APP/capture/ChargingCaptureScheduler.kt:67-84` schedules persisted charging-constrained jobs with45minute rearm → `ChargingCaptureJobService.kt:24-52` → `ChargeJobRun.kt:21-39`.
  2. The runner `:49-69` ingests/persists history **before** computing capture policy.
  3. `APP/capture/CapturePolicy.kt:106-113` suppresses another saved snapshot during the same boot/charge session without a newer PLUGGED event.
  4. The runner's common helper gates unrelated maintenance:
     ```kotlin
     if (!shouldCapture) return
     capture()
     analyze()
     prune()
     ```
  5. `TEL/capture/SnapshotCapturer.kt:157-162` → `DB/store/RoomSnapshotStore.kt:215-237` filters incoming rows but does not delete previously stored expired ones. Actual deletion/downsampling is in `DB/Daos.kt:576-590`.
  6. The only other production prune call found, `APP/AppContainer.kt:150-158`, requires successful foreground refresh; there is no independent maintenance scheduler.
- **Failure Scenario:** A continuously plugged device stays on the same boot without reopening Inspiration beyond the30day window. Jobs ingest new history, but expired history/unmarked snapshots/misses persist and old telemetry samples are not downsampled. Storage can exceed its intended retention bound indefinitely and increase later maintenance costs.
- **Concrete Fix:** Replace the helper body with:
  ```kotlin
  if (shouldCapture) {
      capture()
      analyze()
  }
  prune()
  ```
  This runs retention on a completed skipped-capture pass while retaining existing cancellation/capture/analysis failure behavior. Do not move pruning into `finally` without a separate consistency review.
- **Regression Gap:** `app/src/test/kotlin/com/akane/inspiration/capture/ChargeJobCaptureTest.kt:91-112` covers skip without asserting pruning. Add skipped-success ordering, existing cancellation invariants and a same-session clock-advanced Room-backed retention test beyond30days. No proposed test/fix execution ran.

### [A08] [MEDIUM] Health excludes newer persisted charge observations when no live reading arrives

- **Category:** Logic Bug
- **Location:** `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthMappers.kt:295-306,323-329`.
- **Confidence:** Confirmed via independent full source trace and compiled mapper replay.
- **Root Cause & Execution Trace:**
  1. `HEALTH/HealthRoute.kt:63-71` reports plug state → `HEALTH/HealthViewModel.kt:331-367` starts live collection → `APP/AppContainer.kt:118-125,249` → `TEL/live/LiveTelemetry.kt:35-47` → `DB/store/RoomSnapshotStore.kt:336-340` persists plugged samples even if full history ingestion is unavailable.
  2. Leaving Health clears visit-local live/plug observations at `HEALTH/HealthViewModel.kt:311-322`.
  3. `APP/MainActivity.kt:74-79` → `HEALTH/HealthRoute.kt:53-56` reopens Health. `HealthViewModel.kt:300-308,459-495` reloads samples through `APP/AppContainer.kt:273-276` → `DB/store/RoomSnapshotStore.kt:303-306`.
  4. `HEALTH/HealthViewModel.kt:74-79,201-214` resolves the curve, but the mapper gates a newer sample span on a **current-visit** live reading:
     ```kotlin
     return if (hasLive && (span == null || extendsCompleted)) {
     ```
  5. With `hasLive=false`, `HealthMappers.kt:323-329` filters later recorded samples against the obsolete completed span. `HEALTH/HealthScreen.kt:75-91` renders that older curve/no recent charge. The persisted-point mapper requires `plugged == true` at `HealthMappers.kt:71-83`, so these are valid charging observations.
- **Failure Scenario:** Old history describes06:00–07:00 charge; a later09:00 Health visit persists new charging levels while history ingestion fails. The user leaves Health, stops Shizuku, returns unplugged. No new live reading/history repair arrives, so the newer recorded charge is hidden. This is display/analysis loss, not deletion. The compiled mapper reproduced an old span with no selected newer points.
- **Concrete Fix:** Infer a later persisted run independently of current live collection, while excluding samples at/before the already-completed boundary:
  ```kotlin
  internal fun effectiveChargeSpan(
      span: ChargeSpan?,
      samples: List<CurvePoint>,
      hasLive: Boolean,
  ): ChargeSpan? {
      val later = span?.endMs
          ?.let { end -> samples.filter { it.wallMs > end } }
          .orEmpty()
      val inferred = when {
          later.isNotEmpty() -> later
          hasLive && span == null -> samples
          else -> return span
      }
      return lastRun(inferred).firstOrNull()?.let { ChargeSpan(it.wallMs, null) }
  }
  ```
  Existing observed plug/unplug precedence remains; without live collection, existing `HealthMappers.kt:482-483` limits analysis to the last sample rather than now.
- **Regression Gap:** `feature/health/src/test/kotlin/com/akane/inspiration/health/HealthMappersTest.kt:282-286` uses `hasLive=true`; `HealthViewModelTest.kt:1212-1232` has matching persisted history. Add later stored run/no live, samples before/at old end, short-gap separate sessions, visit reentry with unavailable Shizuku and observed-boundary precedence. No runtime/fix tests ran.

### [A09] [LOW] DST fallback can remove the hypnogram's visible time axis

- **Category:** Logic Bug
- **Location:** `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/math/TimeTicks.kt:45-46,74-85`.
- **Confidence:** Confirmed via independent source trace and actual compiled tick-generator call.
- **Root Cause & Execution Trace:**
  1. `APP/AppContainer.kt:218-229` supplies epoch-bounded timelines → `SLEEP/SleepLabViewModel.kt:218-228` → `SLEEP/SleepLabMappers.kt:41-59` trims to actual stage coverage.
  2. `SLEEP/SleepLabScreen.kt:205-216` → `DS/chart/hypnogram/SleepHypnogram.kt:305-309` requests ticks.
  3. `TimeTicks.kt:74-80` resolves every ambiguous wall time to the earlier offset:
     ```kotlin
     val t = ZonedDateTime.of(date, LocalTime.ofSecondOfDay(minute * 60L), zone)
         .toInstant().toEpochMilli()
     ```
     A window entirely in the second repeated hour gets no candidates; `:45-46` accepts the empty result as within budget.
  4. `DS/chart/draw/TimeAxis.kt:44-63` draws no labels/ticks. Accessibility endpoints are not a visible fallback.
- **Failure Scenario:** Europe/Berlin,2025-10-26T01:10Z..01:50Z (second02:10..02:50), maxTicks5. Actual compiled result `ticksMs=[]`; five10minute ticks should fit. Bars/totals/data remain intact; this is an intermittent visual-label defect.
- **Concrete Fix:** Replace `wallClockTicks` with both-offset enumeration, skipping nonexistent spring times and sorting after enumeration:
  ```kotlin
  private fun wallClockTicks(
      window: TimeRange,
      zone: ZoneId,
      stepMinutes: Int,
  ): List<Long> {
      val out = ArrayList<Long>()
      var date = localDate(window.startMs, zone).minusDays(1)
      val lastDate = localDate(window.endMs, zone).plusDays(1)
      while (!date.isAfter(lastDate)) {
          var minute = 0
          while (minute < MINUTES_PER_DAY) {
              val local = date.atTime(LocalTime.ofSecondOfDay(minute * 60L))
              for (offset in zone.rules.getValidOffsets(local)) {
                  val t = local.toInstant(offset).toEpochMilli()
                  if (t in window.startMs..window.endMs) out += t
              }
              minute += stepMinutes
          }
          date = date.plusDays(1)
      }
      return out.distinct().sorted()
  }
  ```
  Remove the unused `ZonedDateTime` import. Sorting is necessary because enumerating local minutes with both offsets otherwise interleaves instants.
- **Regression Gap:** `core/designsystem/src/test/kotlin/com/akane/inspiration/designsystem/chart/math/TimeTicksTest.kt:108-113` tests fallback with3hour spacing, avoiding ambiguity. Add the second occurrence with5ticks, both occurrences with21ordered5minute ticks, spring gap/dedup/budget checks. Proposed tests, screenshots and device DST rendering were not run.

### [A10] [LOW] Ancillary scaffolder accepts reserved package names and generates invalid projects

- **Category:** Logic Bug
- **Location:** `android-kit-codex/template/.codex/android-kit/scaffold.py:165-170`.
- **Confidence:** Confirmed by independent dry-run input validation, actual Kotlin2.4.20 PSI parsing and AGP9.4.1/JDK namespace checks.
- **Root Cause & Execution Trace:** CLI `scaffold.py:694-695` → incomplete reserved-segment set at `:165-170` → accepted string retained at `:192` → namespace/applicationID at `:279-283` and unescaped Kotlin package/import declarations at `:361-369`.
  ```python
  # Existing reserved-word membership check omits typealias, typeof,
  # float, native and other Java keywords.
  ```
- **Failure Scenario:** `--name Sample --package com.example.typealias --offline` is accepted, but generated Kotlin cannot parse. Read-only `--offline --dry-run` also accepts `typeof`, `float`, `native`. **Correction:** float/native are valid Kotlin identifiers here; their failure is Android's Java namespace validation (`validateNamespaceValue` → `SourceVersion.isName`), not Kotlin parsing. No generated application was built.
- **Concrete Fix:** Define a complete applicable keyword union and reject before destination creation:
  ```python
  PACKAGE_KEYWORDS = frozenset("""
  abstract assert boolean break byte case catch char class const continue
  default do double else enum extends final finally float for goto if
  implements import instanceof int interface long native new package
  private protected public return short static strictfp super switch
  synchronized this throw throws transient try void volatile while
  true false null as fun in is object typealias typeof val var when
  """.split())
  ```
  Replace the existing partial check with:
  ```python
  if any(part in PACKAGE_KEYWORDS for part in a.package.split(".")):
      ap.error("package contains a Kotlin/Java keyword")
  ```
  The existing identifier regex already excludes standalone `_` and punctuation-bearing tokens. Do not escape Kotlin-only identifiers while retaining an Android-invalid namespace.
- **Regression Gap:** `android-kit-codex/tests/test_kit.py:68-69` covers successful scaffold only. Reject every union keyword in first/middle/final segments, both dry-run and ordinary mode, assert exit2 before writes/destination creation, retain valid-package tests. This affects ancillary tooling, not the currently built app.

## 3. Dead Code, Redundancy & Technical Debt Cleanup

No class/function/dependency removal was certified safe by this audit; no cleanup was performed. The examined hidden API stubs are intentional compile-only contracts, unsigned release output is an explicitly supported fallback, and application-lifetime listeners are not automatically leaks. No stale TODO or naming-based dead-code claim is elevated without caller/reachability evidence.

High-value verified test debt is attached to A01–A10. In particular, the CPU added-frequency test currently endorses an unsafe zero baseline, and dexopt fakes omit platform terminal output. Replace those oracles when implementing the corresponding fix rather than adding tests that merely repeat the old assumptions. No whole-repository static reachability analyzer was run, and targeted test review was not a line-by-line review of every test body.

## 4. Prioritized Remediation Roadmap

### Phase 1 — Immediate isolated correctness/retention fixes

- A04: invalidate incomparable CPU membership instead of inventing zero.
- A05: retain transition temperatures without altering battery-percentage semantics.
- A07: run pruning on completed skipped-capture passes.
- A08: show valid later stored charge observations without requiring current live input.
- A06: propagate verified dexopt completion through IPC/domain/durable/UI status as one coherent change.

No critical emergency patch is asserted. Each root cause should have its own reproducing test and atomic change.

### Phase 2 — Structural & concurrency hardening

- A01: process-wide physical Binder admission, abandoned-result ownership and repeated-stall tests.
- A02: separate per-source sign evidence, preference versioning and legacy-sign invalidation.
- A03: correct adjacent reboot anchoring, then schema migration/bounded replay with explicit historical-recovery limits.

These cross-boundary fixes need independent diff review and scoped verification after implementation. A03 also requires actual Room migration tests and, when a source-matching debug device is authorized, runtime ingestion evidence.

### Phase 3 — Boundary coverage and smaller defects

- A09: DST overlap/gap tests using actual tick generation and screenshot checks.
- A10: complete package validation plus rejection-before-write tests.
- Add a source-matched device matrix for API31/33/35/36 where available; exercise Shizuku death/stalls, process death, repeated visibility, migration and history discontinuities. Physical-device battery accuracy/power tests are separate from redroid wiring.
- Add maintained dependency-vulnerability/secret scanning without uploading protected credentials. Version-update advisories alone are not security findings and do not authorize upgrades.

### Coverage Ledger

| Scope | What was examined | Limits / result |
|---|---|---|
| app composition, DI, capture jobs/receivers, status service, shell, debug | Production files, manifests, backup/resources, test seams, telemetry/Shizuku/Room callers | A07; no fresh device lifecycle or background-restriction test |
| IPC lifecycle/service/client/model/AIDL, hidden API stubs | All non-toolkit production main/debug paths, manager/history/service/client tests, focused API12–16 and Shizuku13.1.5 sources | A03; remaining tests inventoried; no device reboot/OEM matrix |
| privileged toolkit + durable action state | All six IPC toolkit and four telemetry toolkit production files, dexopt tests, auth/wiring/feature settlement | A06; other toolkit tests surveyed, not all bodies exhaustively read |
| telemetry non-toolkit | Acquisition/capture/live/diff/reference/history/interval/calibration/capacity/status/store contracts, protobuf declarations, targeted regressions | A01/A02/A04; not all593 test bodies read; no driver/hardware fault injection |
| Room database | All four production files, all current tests, exported schemas1–7 and migrations; in-memory SQLite3.46.1 schema equivalence paths1–6→7 | No additional database-owned defect; not Android migration/power-loss/corruption execution |
| diagnostics | All19 production Kotlin files, dictionary/assets/resources, focused tests/callers | A05;72 dictionary IDs/action validity and18 EN/TR resource-key sets checked |
| dashboard/health/sleeplab | ViewModels/mappers/routes/relevant render/callback paths and targeted tests/resources | A08; completed replacement review after original scope failed provider limit |
| toolkit/xray features | Production source/selected tests, bounded app/core admission/target/state traces | No feature-owned defect; localized key/format parity203 Toolkit,170 X-Ray entries |
| designsystem | All47 production Kotlin files/six localized XMLs;23 test files inventoried, focused math/format/gesture bodies | A09;46 matching EN/TR keys; no on-device gesture/DST check |
| build/packaging | Root/module Gradle, three convention plugins, catalog/wrapper/Unix launcher, manifests/backup/R8 consumer rules | No additional defect; no .github directory; release/R8 packaging not run |
| ancillary current template executables | board.py, scaffold.py, ui_pick.py and three ancillary test files | A10; two wrapper containment tests, Python3.9 AST/annotation/picker-contract checks; full suite not run because it invokes installers/active copies outside approved scope |
| Excluded assessment/config/history material | Required instructions read; prior reports/maps/boards/history deferred | Not application audit coverage; protected skills/kit/instructions never modified |

All six failure domains were applied as investigation prompts, not as proof that every possible path was exhaustively tested. No additional unauthorized IPC, shell injection, cross-user write or overlay bypass was established in examined reachable paths; specific upstream guards are recorded in `slice-evidence.md`. Absence of a reported flaw is not a security guarantee.

### Tools Actually Run and Baseline

One shared Gradle invocation from the checkout root completed **exit0**:

```sh
bash .claude/scripts/gradle-slot.sh testDebugUnitTest lintDebug validateDebugScreenshotTest :app:assembleDebug --continue --max-workers=8
```

Accepted XML outputs: **2,115 unit tests**, **214 screenshot cases**, zero failures/errors/skips. This was an ordinary incremental check, **not** `--rerun-tasks`; some outputs were reused/up-to-date. Counts describe accepted reports, not a claim that every case re-executed this session. Quiet log `.claude/kit/logs/gradle.nQMl7bcm.log` is empty; wrapper recorded PASS/exit0.

| Module | Unit cases | Screenshot cases |
|---|---:|---:|
| app | 216 | 8 |
| core/database | 103 | — |
| core/designsystem | 197 | 64 |
| core/diagnostics | 197 | — |
| core/hidden-api-stub | 0 (compile-only stubs) | — |
| core/shizuku-ipc | 320 | — |
| core/telemetry-engine | 593 | — |
| feature/dashboard | 81 | 16 |
| feature/health | 117 | 12 |
| feature/sleeplab | 97 | 32 |
| feature/toolkit | 94 | 49 |
| feature/xray | 100 | 33 |

Lint: zero errors; seven app-only advisories—OldTargetApi at `app/build.gradle.kts:28`, wrapper-version advisory at `gradle/wrapper/gradle-wrapper.properties:4`, annotation at `gradle/libs.versions.toml:8`, compose/parcelize at`:3` twice, hiddenapibypass at`:14`, Robolectric at`:19`. These are not proven CVEs or independent correctness findings; no versions changed.

Other actual checks: bundled source-only recon; bounded `rg`/source/caller reads; current revision/status and source hash; audit-guard begin/check; resource-policy probe; generated XML/resource/schema checks; host in-memory SQLite; read-only scaffolder dry-runs; JShell/JDK/actual Kotlin parser probes and selected existing compiled production methods; direct official AOSP/Shizuku/AGP implementation checks. All proposed remediation/new regression code remains uncompiled/unexecuted. Some investigator replays were not retained as standalone scripts; their concrete inputs/outputs are preserved in this report/evidence, not a claim of a complete reusable test harness.

Unavailable/not run: `osv-scanner`, `trivy`, `gitleaks` absent; no automated CVE/secret-database scan; no release/R8 build; no instrumentation source in any module; no physical battery/power/OEM matrix, corruption/power-loss test or long-duration load benchmark. Compile-only stubs and build-logic have no direct unit suites in this invocation. Every non-stub production Android module does have unit coverage, but counts do not prove integration/failure-path completeness.

### Device Evidence Boundary

Read-only provenance checks used adb server5038 and explicit `localhost:5555`; model `redroid15_x86_64`, API35, Shizuku running. Installed app0.2.8/code10 lacks DEBUGGABLE and differs from the accepted source debug artifact:

- Installed baseAPK SHA-256: `5cfdd669937b2b0864552750d45fb2367611d1995fe28a2636a2aad741e24ccc`.
- Accepted debugAPK SHA-256: `e6fe57e1bb6f3e39bddf95bcea9871ef1a6cd021407650f14e6cdf5095ca6c58`.

No behavioral debug probe was run against a source-matching binary. Switching signing keys would require uninstall/data loss and was not attempted. No install/uninstall/clear/grant/settings/server-start/logcat-clear or privileged toolkit action occurred. The device-probe skill/helper was read but not executed. Redroid cannot validate real battery quantities, suspend or thresholds; API31/33/36/37 were not device-tested.

## 5. Audit Isolation, Preservation and Freeze Evidence

### Contamination Ledger

- **Before investigation:** Coordinator automatically received required CLAUDE.md/product/module summary, generated `.claude/.codebase-info/INDEX.md` excerpt, memory index and recent commit subjects. The full saved SessionStart map output was not opened.
- **Fresh reviewer availability:** Separate native `audit-reviewer` contexts were available and used; no forks, nested CLIs, board tickets, model/provider overrides or hook disabling. Workers/validator nevertheless reported actual automatic memory/instruction/recent-history exposure. Thus the run is **contaminated/not blind**, not “fresh workers prove blindness”. User was informed when worker exposure was established.
- **During source review:** Current comments/test names disclosed historical findings/regressions. Later automatic live-rule injection also named earlier Compose-test work. These exposures are not used as proof of new findings.
- **Excluded until freeze:** Existing audit report/archive contents; PROJECT/MAP/PROGRESS/TEMPLATE and generated maps beyond already injected required context; boards/issues/PR discussion/session logs; Git log/blame/show; incoming/retired/backup tooling. Exact current ancillary template executables were explicitly inventoried as source scope. No prior assessment was used as finding evidence.
- **Context failure:** Original broad five-feature worker terminated HTTP413 `Prompt is too long` (native model metadata `claude-gpt-6.1-sol`) before usable findings. Its partial output is not counted as review coverage. Two fresh bounded replacements completed the feature scopes; this failure and replacement are preserved rather than called a successful first pass.
- **Provider boundary:** Sidequest disabled; Model Gateway remained enabled. Native configuration used. No Claude/GPT diversity or cross-provider-independent-review claim is made.

### Source and Protected-File Integrity

- Pinned HEAD above; app/core/feature/build source initially clean. Existing dirty user tooling/instructions preserved: `.claude/commands/{bootstrap,bug-hunt,kit-doctor,kit-merge,ui-overhaul}.md`, `.claude/kit/{detect-stack.py,resolutions.json}`, `.claude/live-rules/{manifest.json,rules/android-orchestration.md}`, `.claude/settings.json`, `CLAUDE.md`; pre-existing untracked `.claude/kit/resource-policy.json` and audit-script `__pycache__/`.
- Guard session: `/home/akane/projects/inspiration/.git/audit-history/run-3dqloc6v`. Original uppercase `AUDIT_REPORT.md` remains in place before publish and was archived without displaying its contents.
- Current-source fingerprint: SHA-256 `c91717b3b32f3d16badceadbc689a06f63d543339143926574f9ceb61368ac86`,746 tracked audited non-document/non-secret source/build/resource/schema/reference files, sorted path+content hashes. Scope/repro command are recorded in `ledger.md`; fingerprint matched after tooling/reviews, with final status/guard check required immediately before freeze.
- Guard protected-file/original-report checks passed at the recorded checkpoints. No application/skill/kit/instruction changes were made. Only report/evidence and native build outputs were written. Existing user changes were neither reset nor integrated. Idle Gradle daemons were stopped after confirming no active wrapper command, per host rules.
- Evidence in this session: `ledger.md`, `baseline.md`, `slice-evidence.md`, `candidate-c1.md`, `additional-candidates.md`, `validation.md`, this `draft.md`. Guard freeze creates immutable `independent.md` plus `freeze.json` SHA-256. The freeze's own hash is intentionally stored externally to avoid self-reference. Final guarded publication checks it and protected files again.

### Delta Boundary

No prior-report comparison is included in these pre-delta findings. After successful freeze, a separately labeled Audit Delta will compare root causes using source/verification evidence. Absence from this report must never be interpreted as proof an old issue is resolved. Post-freeze discoveries belong only in that delta.


## Audit Delta (post-freeze comparison)

### Comparison boundary and method

This section is **post-freeze, intentionally nonblind comparison**, not part of the independent finding set. Date: 2026-10-21. Current source revision: `8cbe51f012e65253249647d8bf18f784f7853dc6`.

- Independent report was frozen successfully before the archived previous report was opened. Its SHA-256 is `c6eb1e36a0af7a0438c0717576611fc00fc8eccb04b09be314e5bce32ad946b7` (`independent.md`, recorded in `freeze.json`). The frozen content has not been amended.
- The coordinator subsequently read the entire archived previous report. That report identifies the 2026-10-20 B-series audit at `c74d8f8eadc8087570dac7cb3aece971bd1defa2` and later 2026-10-21 remediation annotations. Historical FIXED labels are claims to compare, not proof of current resolution.
- A separate read-only comparison reviewer (`a0febd0a182d14849`) examined focused prior excerpts and current production source/regression tests. It confirmed the current HEAD. It made no file changes and performed no Gradle, device, release or source-history operations. Automatically supplied commit subjects were present but were not used as evidence.
- Matching below is by root cause, not ID, shared file or subsystem. Absence from the new findings is never treated as resolution. Historical R/N tables were not fully reassessed; their closure claims remain carried prior claims.
- The pre-freeze audit itself remains **CONTAMINATED / NOT BLIND** because automatic instructions, memory, generated-map excerpts, recent commit subjects and source/test annotations disclosed history. A freeze prevents later report changes; it does not retroactively establish blindness. No historical independence or provider diversity is claimed.

### Primary B-series comparison

**F = fix present in source; verification limited.** Every primary B finding below has a corresponding fix in current source. This is not a fresh execution of its original failure scenario. Existing regression references identify evidence inspected, not tests freshly rerun by the comparison reviewer. The shared audit baseline passed incrementally; it does not establish that every referenced test executed anew. Therefore none of these rows is labelled newly “verified resolved.” B-09 also retains an explicitly accepted trade-off.

All paths are relative to the repository root.

| Prior finding / root cause | Classification | Current source and existing regression evidence |
|---|---|---|
| B-01 Late reconcile drift attributed as an owned change / Reset all | F | Late outcomes become unattributed; Reset all refuses them. `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt:1699` and `:381`. Drifted-bucket/no-write regression: `core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActionsLateReconcileTest.kt:169` and `:160`. Earlier-process wall-clock limits remain separate below. |
| B-02 Capped history page recorded complete | F | Truncated final pages produce `page_cap`; only complete availability establishes the horizon. `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/store/HistoryHorizon.kt:48`; consumer `app/src/main/kotlin/com/akane/inspiration/capture/ChargingCaptureRunner.kt:65`. Eight-page cap/subsequent-drain regression: `core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/source/AndroidTelemetrySourceDeadlineTest.kt:275`. |
| B-03 All-invalid app UIDs produce all-clear | F | No proven application UID yields `APPS_UNPROVEN`; story withholds its all-clear/anomalies sentence. `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/rules/DiagnosticRuleEngine.kt:75`, `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/story/BatteryStoryBuilder.kt:36`, `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabDiagnostics.kt:130`. Rule/story regression: `core/diagnostics/src/test/kotlin/com/akane/inspiration/diagnostics/rules/DiagnosticRuleEngineTest.kt:265`. |
| B-04 Wrong missing-data explanation | F | Missing screen-off figures differ from short intervals; unavailable lists distinguish reset, restart and absent capture. `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/rules/DiagnosticRuleEngine.kt:152`; `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabMappers.kt:161` and `:378`. LIGHT-end and missing-dump regressions: `feature/sleeplab/src/test/kotlin/com/akane/inspiration/sleeplab/SleepLabMissingSourceTest.kt:108` and `:131`. Residual copy limitations are listed separately. |
| B-05 Timeline window-edge carry-in/hold | F | Level/temperature seed the start and hold through known history. `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/TimelineBuilder.kt:116`. Both-edge/no-internal-level-step regressions: `core/diagnostics/src/test/kotlin/com/akane/inspiration/diagnostics/analysis/TimelineBuilderTest.kt:358` and `:394`. This does not address frozen A05's in-window temperature sampling defect. |
| B-06 X-Ray Restore ignored with unreadable controls | F | Admission requires a listed restorable row, not successful control loading. `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitAdmission.kt:57`; caller `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayViewModel.kt:411`. Regression: `feature/xray/src/test/kotlin/com/akane/inspiration/xray/XRayViewModelTest.kt:723`. |
| B-07 Charge-counter unit mismatch in remaining time | F | Shared µAh plausibility floor is applied before extrapolation. `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitMappers.kt:228`. mAh fallback/floor-boundary regressions: `feature/dashboard/src/test/kotlin/com/akane/inspiration/dashboard/CockpitMappersTest.kt:328` and `:335`. |
| B-08 Shared UID assigned to alphabetically first owner/actions | F | Only an identifying installed owner is selected; otherwise the UID remains shared/actionless. `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/app/AppFacingModels.kt:112`; consumer `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayViewModel.kt:257`. Regressions: `feature/xray/src/test/kotlin/com/akane/inspiration/xray/XRayViewModelTest.kt:360` and `feature/sleeplab/src/test/kotlin/com/akane/inspiration/sleeplab/SleepLabViewModelTest.kt:229`. |
| B-09 Unsupported SDK history query / repeated per-page query | F for SDK gate; per-page trade-off retained | API below 34 returns before the expensive query. Supported pages still query/re-walk history; the source explicitly retains that choice. Gate/rationale: `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/BatteryStatsClient.kt:80`. Below-34 no-query regression: `core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/client/BatteryHistoryTest.kt:435`. Do not call repeated-query cost eliminated. |
| B-10 Main-thread Shizuku ping/permission/state IPC | F | Permission, refresh and listener-triggered state work use IO. `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/ShizukuManagerImpl.kt:212`, `:232`, `:254`. Wedged-server/thread-recording regressions: `core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/ShizukuManagerImplTest.kt:1701` and `:1741`. No new on-device threading proof. |
| B-11 Consumed gesture Down stops fling/clears selection | F | Consumed presses exit before chart gesture creation. `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/scrub/ScrubGestures.kt:55`. Ancestor-consumption regression: `core/designsystem/src/test/kotlin/com/akane/inspiration/designsystem/chart/scrub/ScrubGesturesUiTest.kt:137`. Real-device fling remains unverified. |
| B-12 X-Ray write ownership / reopened busy state / stale reads | F | Writes remain tracked per target across sheet closure; generation guards reject stale reads. `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayViewModel.kt:474` and `:334`. Reopen/stale-read-after-restore regressions: `feature/xray/src/test/kotlin/com/akane/inspiration/xray/XRayViewModelTest.kt:781` and `:827`. |
| B-13 Refresh-backoff epoch race | F | Clear increments epoch; failures retain their capture epoch. `app/src/main/kotlin/com/akane/inspiration/di/RefreshCoordinator.kt:72`, `:81`, `:105`. Clear-during-publication regression: `app/src/test/kotlin/com/akane/inspiration/di/RefreshCoordinatorTest.kt:308`. |
| B-14 Health kernel probe delays local Room data | F | Kernel starts asynchronously; stored day publishes after a short grace without waiting for the kernel deadline. `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthViewModel.kt:474` and `:497`. Stored-frame regression: `feature/health/src/test/kotlin/com/akane/inspiration/health/HealthKernelFallbackTest.kt:94`. |
| B-15 Health backward-wall-clock live freeze | F | Same-boot acceptance uses elapsed time; observed backward wall steps reset held points and reload. `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthViewModel.kt:157` and `:404`. Regression: `feature/health/src/test/kotlin/com/akane/inspiration/health/HealthViewModelTest.kt:1516`. Persisted old-clock and exact plug-transition cases remain separate below. |
| B-16 Raw UID instead of app-id classification | F | Shared helper compares app-id portion; diagnostic rules use it. `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/snapshot/UidIdentity.kt:42`; `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/rules/DiagnosticRuleEngine.kt:134`. Regressions: `core/diagnostics/src/test/kotlin/com/akane/inspiration/diagnostics/rules/NightVampireRuleTest.kt:79` and `SensorLeakRuleTest.kt:114` in the same directory. |
| B-17 Sleep Lab package IPC for every UID/emission | F | Ownership reads are limited to displayed rows/culprits, concurrent, with null results retained for that shaping pass. `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabViewModel.kt:175` and `:197`. Lookup-count regression: `feature/sleeplab/src/test/kotlin/com/akane/inspiration/sleeplab/SleepLabViewModelTest.kt:296`. |
| B-18 Negative/stale label/icon cache after package changes | F | Missing entries are not cached; invalidation rejects stale in-flight insertion. `app/src/main/kotlin/com/akane/inspiration/di/CachedAppLabels.kt:125`; broadcast wiring `app/src/main/kotlin/com/akane/inspiration/AppContainer.kt:298`. Regressions: `app/src/test/kotlin/com/akane/inspiration/di/CachedAppLabelsTest.kt:244` and `PackageChangesTest.kt:42` in the same directory. Locale invalidation remains separate below. |
| B-19 Marker list failure conflated with capture failure | F | Listing sets `listFailed`; capture sets `captureFailed`. `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitViewModel.kt:315` and `:332`. Successful-capture/failed-list regression: `feature/dashboard/src/test/kotlin/com/akane/inspiration/dashboard/CockpitViewModelTest.kt:901`. |
| B-20 Unbounded stuck service dump writers | F | Two writers per target admitted; completion/start failure releases capacity. Dumps still have no physical timeout. `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/service/TelemetryUserService.kt:262` and `:438`. Bound/release regression: `core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/service/TelemetryUserServiceTest.kt:249`. This is not frozen A01's client Binder-worker admission root. |
| B-21 Reset-all confirmation includes unrestorable rows | F | Counts only restorable verified overrides; other rows counted separately. `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitAdmission.kt:73`; `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitViewModel.kt:418`. Count/result regression: `feature/toolkit/src/test/kotlin/com/akane/inspiration/toolkit/ToolkitViewModelTest.kt:1221`. |
| B-22 Zero-age usage query causes duplicate sync | F | FULL dump precedes usage query with a 5,000-ms allowance. `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/source/AndroidTelemetrySource.kt:34` and `:71`. Order/nonzero-age regression: `core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/source/AndroidTelemetrySourceTest.kt:51`. Actual framework sync reuse was not measured. |
| B-23 Positive-component shares exceed net total | F | Denominator is larger of net total and positive-component sum. `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayMapper.kt:116`. Reattribution regression: `feature/xray/src/test/kotlin/com/akane/inspiration/xray/XRayMapperTest.kt:280`. |

### Frozen A-series versus earlier roots

No primary B root was independently rediscovered as an A finding. Shared modules and similar symptoms do not establish root equivalence. The frozen set remains **eight MEDIUM and two LOW**, unchanged by this comparison.

| Frozen finding | Root-cause distinction |
|---|---|
| A01 Physical outstanding Binder admission | Client physical transaction/worker capacity after logical deadline; not B-20's service dump-writer admission and not B-10's main-thread dispatch. |
| A02 Per-source current polarity | Shared calibration sign reused across independently signed sysfs/BatteryManager sources; not B-07's charge-counter unit plausibility. |
| A03 Reboot START wall-time anchor | START attached to previous boot's wall anchor; not B-02's capped-page completeness. |
| A04 Partial CPU-counter membership | Missing policy/frequency baseline treated as zero and converted to lifetime interval delta; not B-04's missing-source explanation. |
| A05 Temperature transitions | In-window measured temperature on non-LEVEL records discarded; not B-05's window-edge carry/hold. |
| A06 ART dexopt terminal semantics | Real ART cancellation/fatal terminal output can accompany exit zero and be recorded as success. This is not the older rejected supervisor RuntimeException theory. |
| A07 Charging retention gate | Successful history ingestion on a skipped snapshot bypasses maintenance; no matching primary B root established. |
| A08 Persisted Health charge-span selection | Newer stored charge excluded without current live reading; not B-14's initial local-data latency or B-15's live timestamp ordering. |
| A09 DST overlap axis | Valid repeated-hour ticks disappear; no matching primary B root established. |
| A10 Scaffold reserved package names | Ancillary template executable validation was outside the prior audit's stated scope. |

### Latest prior follow-ups: individual dispositions

**N = not assessed / insufficient evidence for closure.** “Condition remains” means current source supports the stated limitation, not that a fresh runtime reproduction was performed. These are carried follow-ups, not additional frozen findings or additional confirmed severity counts.

| Prior follow-up | Current disposition and evidence |
|---|---|
| History charge counter must use µAh plausibility floor, then recorded-current fallback | **F.** Extraction now uses `microampHourCounter`: `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/capacity/CapacityEstimator.kt:138`; small-counter regression `core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/capacity/CapacityEstimatorTest.kt:147`. Recorded-current fallback was not freshly executed. |
| Stored Health old-clock data after large backward wall step | **N; carried open.** Stored selection still uses wall-time window; handling adjusts observed edges/reloads, not persisted timestamps. `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthViewModel.kt:472` and `:421`. |
| Health clock step exactly at plug-in | **N; carried open.** Plug transition clears live comparison buffer: `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthViewModel.kt:337`. Exact boundary regression closure not established. |
| Toolkit attribution from an earlier process in the same boot | **N; source limitation remains.** Missing process-local timing falls back to wall age plus since-boot validation: `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt:1745`. |
| Toolkit unattributed “can't be read or restored” wording | **N; wording remains.** `feature/toolkit/src/main/res/values/strings.xml:204`. Does not prove unattributed rows cannot be restored individually. |
| Core can decode an older row not listed by UI | **N; admission distinction remains.** Core calls `decodeRestore(actionId)` at `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt:340`; screen requires listing at `ToolkitAdmission.kt:57` in the same directory. No exploit/runtime claim added. |
| Exact newest-at-original unattributed link with pruned-prefix test | **N.** Adjacent stacked-original regression exists at `core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActionsLateReconcileTest.kt:509`, but exact requested combination was not established. Previous probe correctness remains a carried claim. |
| Sleep Lab non-absence CPU failures say “kernel did not report” | **N; source condition remains.** Invalid FULL CPU data maps to `NoData`: `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabMappers.kt:289`; copy `feature/sleeplab/src/main/res/values/strings.xml:145`. |
| Sleep Lab LIGHT-end kernel copy says “Missing from one snapshot” rather than needs FULL | **N; source condition remains.** Unavailable-cause mapping `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabMappers.kt:211`; title `feature/sleeplab/src/main/res/values/strings.xml:83`. |
| API 31–33 history iterator unsupported | **N for closure; intentionally unsupported condition remains.** SDK refusal: `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/BatteryStatsClient.kt:91`. Future `next(HistoryItem)` reflection support was not demonstrated or validated. |
| No immediate follow-up drain after capped history read | **N; carried open.** Cap returns at `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/source/AndroidTelemetrySource.kt:235`; test drains with separate subsequent capture at `core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/source/AndroidTelemetrySourceDeadlineTest.kt:296`. Completeness fix does not imply immediate scheduling. |
| Cold-process RuntimeCaptureReceiver may choose LIGHT | **N; source condition remains.** Receiver snapshots readiness before async work, without awaiting it: `app/src/main/kotlin/com/akane/inspiration/capture/RuntimeCaptureReceiver.kt:20`. This synchronous state-flow read is not the Binder IPC fixed under B-10. |
| Label cache survives locale changes | **N; carried open.** Entries remain package-keyed: `app/src/main/kotlin/com/akane/inspiration/di/CachedAppLabels.kt:78`; inspected actions cover package broadcasts, not locale: `app/src/main/kotlin/com/akane/inspiration/di/PackageChanges.kt:10`. |

### Prior verification gaps and inherited claims

The following remain **not assessed / insufficient evidence for closure**, not resolved by silence in this report:

- Feature-level privileged-control touch-guard wiring. Shared guard regression exists at `core/designsystem/src/test/kotlin/com/akane/inspiration/designsystem/component/ObscuredTouchGuardUiTest.kt:79`; it cannot prove every feature's wiring.
- Targeted execution of `Framework*` reflection adapters.
- Receiver `goAsync` and JobService platform integration. Runner tests alone do not prove platform wiring.
- B-10 on-device threading, B-15 actual clock changes, PackageManager refusal across API 31–36, API 31–34 device behavior, physical battery/cpufreq telemetry, real fling behavior and visual copy. No new device behavioral checks established these.
- Updated installed-artifact equivalence: the available redroid app is a non-debug release binary with a different APK hash from the accepted debug artifact. No uninstall, signing switch, data clear or new debug probe was performed. Prior release-build success does not bridge that gap.
- Vulnerability-database scanning, comprehensive compiler-warning collection, exhaustive screenshot/reference inspection and earlier minor leads were not newly established by this comparison.
- Historical R/N tables, old cleanup certifications and other inherited claims were not fully re-audited. They remain explicitly **unassessed in this delta**. No safe deletion is authorized or certified here.

### Baseline comparison, with revision and execution caveats

| Evidence | Prior archived report | Current audit |
|---|---|---|
| Unit results | Original 1,932; later remediation claims 2,063 at `29c7853` | Accepted XML: 2,115, zero failures/errors/skips |
| Screenshot results | Original 196; later remediation claims 214 | Accepted XML: 214, zero failures/errors/skips |
| Lint | Later remediation claims seven warnings | Zero errors; seven app update advisories |
| Build/device | Prior signed release success claimed; updated device run unavailable | Debug assembly passed; no release/R8 gate or source-matched debug device probe |

Current shared command: `bash .claude/scripts/gradle-slot.sh testDebugUnitTest lintDebug validateDebugScreenshotTest :app:assembleDebug --continue --max-workers=8`, exit 0. It was an **ordinary incremental invocation**, not a forced rerun. Accepted XML counts do not prove every test executed anew. The count difference from prior remediation is informative, not proof of coverage growth, equivalent workloads or independently reproduced prior fixes. No Gradle rerun was performed for this delta.

`osv-scanner`, `trivy` and `gitleaks` were unavailable. Redroid API 35 cannot validate physical battery values, real power or deep sleep. The audit's host SQLite migration-shape checks were not Android migration execution. All proposed fixes, new regressions and migration proposals remain unapplied and uncompiled.

### Integrity and publication evidence

Immediately before writing this delta:

- HEAD remained `8cbe51f012e65253249647d8bf18f784f7853dc6`.
- Tracked audited-source fingerprint matched across 746 non-document/non-secret files: `c91717b3b32f3d16badceadbc689a06f63d543339143926574f9ceb61368ac86`.
- `audit-guard.py check` returned **“Protected files and original report unchanged.”**
- Frozen `independent.md` hash matched the freeze SHA above.
- Git status matched the initial existing dirty kit/instruction files and untracked resource-policy/cache entries; no application changes appeared. Those existing changes were preserved.

Guarded publication combines the unchanged frozen independent report with this separately labelled delta and atomically replaces only the selected root `AUDIT_REPORT.md`. The archived previous report and session evidence remain under `.git/audit-history/run-3dqloc6v/`. The guard rechecks protected paths, original-report identity and frozen-report hash at publication. No application remediation, kit maintenance, commit, push, release, device mutation or external publication is included in this audit.

## Remediation execution — 2026-10-21

This section is subsequent to the frozen audit and post-freeze comparison. Earlier statements that proposed fixes were unapplied describe the audit-time state; current remediation status is recorded here. The immutable audit-session `independent.md` is not being edited.

- Requested scope: A01–A10. Carried historical follow-ups in the delta are not automatically included.
- Base: `8cbe51f012e65253249647d8bf18f784f7853dc6`; integration branch: `audit-remediate-20261021-a01-a10-171906`.
- Standalone eligibility checked: Sidequest disabled, Model Gateway enabled. Native configuration retained; no provider-diversity claim.
- Existing dirty report, kit/instruction files and untracked resource-policy/cache entries preserved. No implicit stash/reset/clean.
- Each finding has one implementation owner in a separate worktree. The coordinator owns integration and this report; workers must not edit the shared report.
- Commits/push/release/device data changes are not authorized. Verified changes will be recorded as **VERIFIED, UNCOMMITTED**, not FIXED-in-a-SHA.
- Checks: focused per-finding regressions/compile/lint, appropriate screenshot validation, independent review for higher-risk changes, then one integrated full unit/lint/screenshot/debug-assembly gate. Required unavailable checks remain verification gaps, not passes. Scoped Gradle checks share the current resource-policy command limit and slot wrapper.

| Finding | Implementation | Integration / verification status |
|---|---|---|
| A01 | Isolated Binder-admission worker | VERIFIED, UNCOMMITTED; scoped checks + independent diff review + integrated full gate passed |
| A02 | Isolated calibration-provenance worker | VERIFIED, UNCOMMITTED; scoped checks + independent diff review + integrated full gate passed |
| A03 | Isolated history/migration worker | BLOCKED; two-repair limit exhausted; failing replay-order regression retained in worktree, no A03 changes integrated |
| A04 | Isolated CPU-membership worker | VERIFIED, UNCOMMITTED; scoped checks + independent diff review + integrated full gate passed |
| A05 | Isolated temperature-sampling worker | VERIFIED, UNCOMMITTED; scoped checks + independent diff review + integrated full gate passed |
| A06 | Isolated dexopt-result worker | VERIFIED, UNCOMMITTED; scoped checks + diff/Compose reviews + integrated full gate passed |
| A07 | Isolated charging-retention worker | VERIFIED, UNCOMMITTED; scoped checks + independent diff review + integrated full gate passed; Room end-to-end experiment unavailable |
| A08 | Isolated persisted-Health worker | VERIFIED, UNCOMMITTED; scoped checks + independent diff review + integrated full gate passed |
| A09 | Isolated DST-tick worker | VERIFIED, UNCOMMITTED; scoped checks + independent diff review + integrated full gate passed |
| A10 | Isolated ancillary-scaffold worker | VERIFIED, UNCOMMITTED; scoped checks + independent diff review + integrated full gate passed |

Passing worktree checks so far: A01 source regressions24 +telemetry lint; A04 SnapshotDifferTest44 + telemetry lint; A05 TimelineBuilderTest33 + diagnostics lint; A08 Health122 units + lint; A09 TimeTicksTest17 + designsystem lint +64 screenshots; A10 four targeted Python test methods (including378 keyword/position/mode cases) +two containment tests and syntax checks. All exited0 on their final scoped invocation. A01 corrected initial test-compilation and exception-identity assertions; its production admission fix did not need a repair. A05/A09 first green attempts exited1 from a shared daemon-stop interruption, not a source diagnostic; unchanged retries passed. Daemon cleanup is now coordinator-owned until all batch verifications drain. Independent actual-diff reviews found no blocking issue in A04/A05/A08/A09/A10 within their stated scopes (A10 reviewer also reran its four targeted Python methods successfully). These are worktree results, not integrated batch completion. The prior audit's incremental baseline remains inherited evidence, not verification of new changes.

### Scoped verification evidence

Commands below ran from their owning isolated worktree, each after a fresh `python3 .claude/kit/resource-policy.py` probe. Gradle commands used `bash .claude/scripts/gradle-slot.sh` and `--max-workers=4`. These do not replace the integrated full gate. Native logs reside under the listed worktree's `.claude/kit/logs/`.

| ID | Scoped tasks / tests | Final worktree evidence and qualifications |
|---|---|---|
| A01 | `:core:telemetry-engine:testDebugUnitTest --tests 'com.akane.inspiration.telemetry.source.AndroidTelemetrySource*Test' :core:telemetry-engine:lintDebug` | Exit0,24 tests/lint0; `agent-acc624fc67536fc45`, `gradle.XVoAARrb.log`. Regression reproduced before fix. Initial test compilation and copied-exception identity assertions corrected; physical stalls simulated. Actual-diff review found no blocker. |
| A02 | `:core:telemetry-engine:testDebugUnitTest --tests 'com.akane.inspiration.telemetry.calibration.*' --tests 'com.akane.inspiration.telemetry.live.LiveTelemetryTest' --tests 'com.akane.inspiration.telemetry.status.LightCurrentTest' --tests 'com.akane.inspiration.telemetry.status.ChargeStateTest' --tests 'com.akane.inspiration.telemetry.capture.SnapshotCapturerTest' :core:telemetry-engine:lintDebug --continue` | Exit0,117 tests/lint0; `agent-a179cdc5dceeea30b`, `gradle.RuXjSMIM.log`. Opposite-sign red reproduced. One flowOn timing assertion repair; actual-diff review no blocker. |
| A03 | `:core:shizuku-ipc:testDebugUnitTest --tests '*BatteryHistoryTest' :core:database:testDebugUnitTest --tests '*InspirationDatabaseTest' --tests '*History*Test' --tests '*IntervalHistoryEquivalenceTest' :core:shizuku-ipc:lintDebug :core:database:lintDebug --continue` | **Final exit1: BLOCKED**, IPC26/26 passed, database50/51 passed, lint0both; `agent-a8ff782c4b22c7f8c`, `gradle.GpenjCHs.log`. Actual Room MigrationTestHelper paths1–7→8 plus builder/replay under API35 Robolectric. Initial75-test pass (`gradle.qAr2YafS.log`) is superseded: after two repairs, retention-pruned START replay still orders transitions incorrectly. No A03 files integrated; root schema7 retained. |
| A04 | `:core:telemetry-engine:testDebugUnitTest --tests '*.SnapshotDifferTest' :core:telemetry-engine:lintDebug` | Exit0,44 tests/lint0; `agent-a61e9b81d6060dd46`, `gradle.H5iNCewI.log`. Red44/3 expected failures. Actual-diff review no blocker; identical partial membership remains undetectable. |
| A05 | `:core:diagnostics:testDebugUnitTest --tests 'com.akane.inspiration.diagnostics.analysis.TimelineBuilderTest' :core:diagnostics:lintDebug` | Exit0,33 tests/lint0; `agent-a7cd88311ef3e6bdb`, `gradle.fDdO7TDI.log`. Red33/6 expected failures. Earlier green attempt externally daemon-stopped; unchanged retry passed. Actual-diff review no blocker. |
| A06 | `:core:shizuku-ipc:testDebugUnitTest --tests '*BgDexoptControllerTest' --tests '*ProcessCommandRunnerTest' :core:telemetry-engine:testDebugUnitTest --tests '*toolkit.*' :feature:toolkit:testDebugUnitTest :core:shizuku-ipc:lintDebug :core:telemetry-engine:lintDebug :feature:toolkit:lintDebug :feature:toolkit:validateDebugScreenshotTest` | Exit0,IPC51/telemetry278/feature98 units,49 screenshots,lint0all3; `agent-a5de52681ee259c85`, `gradle.2wxrVb9c.log`. Red33/2 expected failures. First fixed batch exit1 from four existing Robolectric native `/tmp` quota failures (`gradle.tnw33GMO.log`); identical batch passed with invocation-local temp relocation, no tests omitted. Cross-layer and Compose reviews no blocker; actual APK-upgrade stale-service behavior unverified. |
| A07 | `:app:testDebugUnitTest --tests 'com.akane.inspiration.capture.ChargingCaptureRunnerTest' --tests 'com.akane.inspiration.capture.ChargeJobCaptureTest' --tests 'com.akane.inspiration.capture.ChargeHealthPipelineTest' --tests 'com.akane.inspiration.capture.ChargeJobRunTest' --tests 'com.akane.inspiration.capture.CapturePolicyTest' --tests 'com.akane.inspiration.capture.CapturePolicyFallbackTest' :app:lintDebug` | Exit0,64 tests,lint0errors/8 SDK/version advisories; `agent-a688153ca49b7ff6c`, `gradle.pXXV6PWX.log`. Four target red failures. One retention-anchor fixture assertion repair; review no blocker. Fake-store call-chain coverage, not actual Room deletion/downsampling or physical30day experiment. |
| A08 | `:feature:health:testDebugUnitTest :feature:health:lintDebug` | Exit0,122 tests/lint0; `agent-a20e8c28b818c44bc`, `gradle.SNRITDPM.log`. Red113/3 expected failures. Actual-diff review no blocker; Shizuku reentry is fake-backed, no production ViewModel change. |
| A09 | `:core:designsystem:testDebugUnitTest --tests 'com.akane.inspiration.designsystem.chart.math.TimeTicksTest' :core:designsystem:lintDebug :core:designsystem:validateDebugScreenshotTest` | Exit0,17 units/64 screenshots/lint0; `agent-aba7853e6fd36f35d`, `gradle.svVioCYt.log`. Red17/6 expected failures. Earlier green externally daemon-stopped; unchanged retry passed. Actual-diff review no blocker. |
| A10 | `python3 -B android-kit-codex/tests/test_scaffold_packages.py -v`; `python3 -B android-kit-codex/tests/test_scaffold_containment.py -v`; compile/AST Python3.9 compatibility checks | All exit0,4 targeted+2 containment methods (378 keyword-position-mode cases plus regex/valid-package cases); `agent-abe33fd2262f20590`. Red8 reported CLI failures. Independent reviewer reran4 targeted methods successfully. Full installer/live-kit suite and generated Android build not run. |

For A06's passing command only, environment was set literally to `TMPDIR=/home/akane/projects/inspiration/.claude/worktrees/agent-a5de52681ee259c85/build/tmp/a06-native` and `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/home/akane/projects/inspiration/.claude/worktrees/agent-a5de52681ee259c85/build/tmp/a06-native`. Existing JAVA_TOOL_OPTIONS was confirmed empty before setting the invocation-local value. No global environment, JVM heap, OS or live Gradle configuration was changed.

### A03 blocked recovery — do not integrate

The second repair cycle invalidated each stored START's entire `(statsStartMs, historyTimeMs)` group before rewind. This passed actual Room regressions for existing same-time SCREEN_ON/RESET, including persisted ordering and metrics. However, retention can already have removed the incorrectly dated START while preserving its correctly anchored companions. That group is then not identifiable from stored START rows. Replay yields `[SCREEN_ON, LEVEL, START]`; screen-on duration expected1000ms, actual0ms.

The final scoped A03 command listed above exited **1**, IPC26/26 passed, database50/51 passed, both lint reports zero issues. Failing log: `/home/akane/projects/inspiration/.claude/worktrees/agent-a8ff782c4b22c7f8c/.claude/kit/logs/gradle.GpenjCHs.log`. The reviewer-case reproduction also exited1 (`gradle.4uyfZ08d.log`). The earlier75-test passing result is superseded and is not a final pass.

**Status: BLOCKED, not integrated.** The two-repair budget is exhausted. No global event-order rewrite or broader deletion was attempted. All seven A03 files, including the active failing Room regression and proposed schema8, remain uncommitted in `/home/akane/projects/inspiration/.claude/worktrees/agent-a8ff782c4b22c7f8c`. This isolated tree is preserved instead of resetting user/other-agent work. The integration checkout must retain its original A03 history code and schema7. The final gate for the other nine findings does not resolve or verify A03. A subsequent narrowly scoped replay-write design and explicit continuation are required before another A03 repair cycle.

### Integration recovery

The first integration context terminated with HTTP413 `Prompt is too long`. Completed changes were preserved, not replayed blindly. Exact byte/hash comparison established A04/A05/A10 complete, A08 mapper/mapper-test complete, and A08 ViewModel test/A09/A01 still at baseline. A fresh bounded context integrated only the missing A08 ViewModel test and two A09 files; exact worker/destination hashes and scoped `git diff --check` passed. Subsequent integrations remained serialized and bounded. No model/provider fallback was used. Final integrated evidence for the nine nonblocked findings is recorded below; A03 remains open.

The peer session subsequently confirmed that its `./gradlew --stop` caused the earlier shared-daemon interruption. Both A05/A09 unchanged retries passed. Peer sessions agreed not to stop daemons; AkaStudio will pause new Gradle starts during the integrated gate while allowing already-running checks to finish. No peer settings or permissions were changed by this remediation.

Integrated checkout A10 verification also passed: `python3 -B android-kit-codex/tests/test_scaffold_packages.py -v` (4 methods) and `python3 -B android-kit-codex/tests/test_scaffold_containment.py -v` (2 methods), both with invocation-local `TMPDIR=/tmp/claude-1000/-home-akane-projects-inspiration/bcf28c2b-6fee-4a2d-9648-e1eea3be9722/scratchpad`. This did not run installers or modify live kit copies.

### Final integrated gate and disposition

**Result: 9 VERIFIED, UNCOMMITTED; A03 BLOCKED and not integrated.** This is partial remediation of the requested ten findings, not closure of the whole audit. No commit SHA exists for these changes. All independent implementation trees, including A03's failed repair/regression, are retained because their work is uncommitted.

The integrated checkout passed this one full invocation, **exit0**, log `.claude/kit/logs/gradle.b0WN1MgP.log`:

```sh
TMPDIR=/home/akane/projects/inspiration/build/tmp/remediation-native \
JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/home/akane/projects/inspiration/build/tmp/remediation-native \
bash .claude/scripts/gradle-slot.sh \
  testDebugUnitTest lintDebug validateDebugScreenshotTest :app:assembleDebug \
  --continue --max-workers=8
```

The fresh resource probe allowed two commands, four workers per scoped command, no heavy-build deferral/warnings; this whole-project gate used the host-measured eight-worker cap while exclusive. `JAVA_TOOL_OPTIONS` was confirmed empty before the literal invocation-local override. Peer new-build starts were held, existing builds were never stopped, and global wrappers were absent before safe idle-daemon cleanup. Cleanup after the gate also completed with no global builds active; the peer hold was then released. No global JVM/OS configuration was changed.

| Module | Accepted unit cases | Accepted screenshot cases |
|---|---:|---:|
| app | 223 | 8 |
| core/database | 103 | — |
| core/designsystem | 203 | 64 |
| core/diagnostics | 203 | — |
| core/hidden-api-stub | 0 (compile-only) | — |
| core/shizuku-ipc | 327 | — |
| core/telemetry-engine | 618 | — |
| feature/dashboard | 81 | 16 |
| feature/health | 122 | 12 |
| feature/sleeplab | 97 | 32 |
| feature/toolkit | 98 | 49 |
| feature/xray | 100 | 33 |
| **Total** | **2,175** | **214** |

Accepted XML has **zero failures, errors or skips**. This was an ordinary incremental invocation, **not `--rerun-tasks`**; counts describe accepted reports, not proof every case re-executed. Lint has **zero errors and eight app SDK/version advisories**, including the now-reported Kotlin compose-compiler update advisory. No versions/dependencies were changed. Debug APK SHA-256: `fa21c434df6d167d8a2ea5ddbe8a20671ba1961c50b2428ce63ff72560aa343c` (`app/build/outputs/apk/debug/app-debug.apk`); it was not installed or released.

| ID | Commit SHA | Integrated files | Verification | Final status |
|---|---|---:|---|---|
| A01 | — (uncommitted) | 2 | Scoped source tests/lint + independent review + full gate | VERIFIED, UNCOMMITTED |
| A02 | — (uncommitted) | 8 | Scoped calibration/live/status/capture tests/lint + independent review + full gate | VERIFIED, UNCOMMITTED |
| A03 | — | 0 (7 retained separately) | Final isolated regression exit1, replay ordering still wrong after two repairs | BLOCKED |
| A04 | — (uncommitted) | 2 | SnapshotDifferTest/lint + independent review + full gate | VERIFIED, UNCOMMITTED |
| A05 | — (uncommitted) | 2 | TimelineBuilderTest/lint + independent review + full gate | VERIFIED, UNCOMMITTED |
| A06 | — (uncommitted) | 14 | IPC/domain/feature regressions, lint/screenshots + diff/Compose reviews + full gate | VERIFIED, UNCOMMITTED |
| A07 | — (uncommitted) | 3 | Capture/policy regressions/lint + independent review + full gate | VERIFIED, UNCOMMITTED |
| A08 | — (uncommitted) | 3 | Health units/lint + independent review + full gate | VERIFIED, UNCOMMITTED |
| A09 | — (uncommitted) | 2 | TimeTicksTest/lint/screenshots + independent review + full gate | VERIFIED, UNCOMMITTED |
| A10 | — (uncommitted) | 2 | Python package/containment/syntax checks + independent review; integrated Python rerun and full Android gate passed | VERIFIED, UNCOMMITTED |

#### Exact integrated source/test manifest

All paths below matched the final verified worker bytes, **35 tracked edits + 3 new files = 38 files**. The ordered finding/path/content-hash manifest SHA-256 is `f81c5309224c82d4dd89531129cb427fcd914c68c2a76af536e3dba5768225b0`.

- **A01:**
  - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/source/AndroidTelemetrySource.kt`
  - `core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/source/AndroidTelemetrySourceAdmissionTest.kt` (new)
- **A02:**
  - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/calibration/PowerCalibrator.kt`
  - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/calibration/SharedPreferencesCalibrationStore.kt`
  - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/status/LightCurrent.kt`
  - `core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/calibration/PowerCalibratorTest.kt`
  - `core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/calibration/SharedPreferencesCalibrationStoreTest.kt`
  - `core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/capture/SnapshotCapturerTest.kt`
  - `core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/live/LiveTelemetryTest.kt`
  - `core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/status/LightCurrentTest.kt`
- **A04:**
  - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/SnapshotDiffer.kt`
  - `core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/diff/SnapshotDifferTest.kt`
- **A05:**
  - `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/TimelineBuilder.kt`
  - `core/diagnostics/src/test/kotlin/com/akane/inspiration/diagnostics/analysis/TimelineBuilderTest.kt`
- **A06:**
  - `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/model/BgDexoptStatus.kt`
  - `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/BgDexoptController.kt`
  - `core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/toolkit/BgDexoptControllerTest.kt`
  - `core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/toolkit/ProcessCommandRunnerTest.kt`
  - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt`
  - `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitModels.kt`
  - `core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActionsTest.kt`
  - `core/telemetry-engine/src/test/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitGatewayTest.kt`
  - `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitSections.kt`
  - `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitText.kt`
  - `feature/toolkit/src/main/res/values/strings.xml`
  - `feature/toolkit/src/main/res/values-tr/strings.xml`
  - `feature/toolkit/src/screenshotTest/kotlin/com/akane/inspiration/toolkit/ToolkitPreviewData.kt`
  - `feature/toolkit/src/test/kotlin/com/akane/inspiration/toolkit/ToolkitDexoptCompletionTest.kt` (new)
- **A07:**
  - `app/src/main/kotlin/com/akane/inspiration/capture/ChargingCaptureRunner.kt`
  - `app/src/test/kotlin/com/akane/inspiration/capture/ChargingCaptureRunnerTest.kt`
  - `app/src/test/kotlin/com/akane/inspiration/capture/ChargeJobCaptureTest.kt`
- **A08:**
  - `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthMappers.kt`
  - `feature/health/src/test/kotlin/com/akane/inspiration/health/HealthMappersTest.kt`
  - `feature/health/src/test/kotlin/com/akane/inspiration/health/HealthViewModelTest.kt`
- **A09:**
  - `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/math/TimeTicks.kt`
  - `core/designsystem/src/test/kotlin/com/akane/inspiration/designsystem/chart/math/TimeTicksTest.kt`
- **A10:**
  - `android-kit-codex/template/.codex/android-kit/scaffold.py`
  - `android-kit-codex/tests/test_scaffold_packages.py` (new)

#### Documentation and final integrity

Incremental mapper refresh updated six existing documents under `.claude/.codebase-info/`: `architecture.md`, `communication.md`, `entry-points.md`, `modules.md`, `patterns.md`, `tech-landscape.md`, plus `.map-state.json`. No new top-level subsystem/document was warranted or removed; INDEX/document set remains nine documents. The map describes schema7 and current source, not isolated A03/schema8 proposals. The release version was refreshed from already-committed source0.2.8/code10, not bumped. A stale production BinderResult map link was corrected to current `BinderCalls.kt`; all115 local links in touched documents and every recorded map hash were checked. The map remains uncommitted with the fixes.

Final source integrity checks matched all38 integrated paths to their workers, confirmed all seven A03 proposals absent/at base and schema7 retained, found no staged changes and unchanged base HEAD, and matched all599 protected-file hashes plus frozen independent audit/original archive. Scoped source/map `git diff --check` passed. Unrestricted `git diff --check` reports four intentional trailing two-space Markdown hard breaks in the preserved frozen audit header; those inherited audit bytes were not rewritten to silence it.

Verification limits remain explicit: A01 real hardware stalls/exhaustive scheduling races, A02 affected hardware, A06 stale-service behavior after an actual APK upgrade, A07 real Room-backed app retention/downsampling and a physical long-session experiment, A08 actual Shizuku reentry, A09 device DST rendering, and A10 a generated Android build/full live-kit installer suite were not established. No source-matching device install/probe, dependency-vulnerability scan, release/R8 gate, commit, push or release occurred. Passing tests/reviews are evidence, not a zero-regression guarantee. A03 requires an explicitly resumed, narrowly scoped replay-write design before another repair cycle.

## A03 continuation — new authorization, 2026-10-21

After the blocked result was explained, the user explicitly requested **“try again now.”** A new bounded A03-only cycle was therefore opened; the prior failed cycle and its evidence above are not relabelled as successful. **Continuation outcome: BLOCKED, NOT INTEGRATED.** The nine verified fixes remain unchanged and the root still uses schema7.

Read-only migration investigation confirmed that retained companion IDs can put replayed START after its state, and that arbitrary reinsertion can also corrupt insertion-ordered session runs. Independent plan review is complete. The approved implementation preserves raw rows and original IDs, reconciles only provable positions using logical-order/pending metadata, and treats irretrievably ambiguous legacy boundaries as explicitly uncertified rather than inventing history. Previously stored derived capacity estimates are preserved but quarantined until certified replacement; independent kernel measurements remain available. This conservative uncertainty policy is the coordinator's implementation default, not additional user approval or a promise of complete historical reconstruction.

A fresh isolated implementation was executed in `.claude/worktrees/agent-ab7206bc9644efc19` from pinned `8cbe51f012e65253249647d8bf18f784f7853dc6`. Its **40 A03-owned files remain unintegrated**, alongside the seeded, unchanged A01 admission test. The reviewed overlap was additive history-source work in `AndroidTelemetrySource.kt`; root A01 and all other nine-fix source bytes remain untouched. Both the original failed tree and this new dirty candidate are preserved. Initial implementation plus both allowed repairs were used; **no further repair or replacement executor is authorized in this cycle**.

### Continuation verification and remaining failures

- Red parser/order batch: exit **1**, five tests/four failures; `gradle.oDNsYRJj.log`.
- Initial implementation batch: exit **1**, AGP schema-assets source-set configuration failure; `gradle.3fcXTEjN.log`.
- Repair1 batch: exit **1**, missing required `SourceAvailability.reason` arguments; `gradle.57PxdM9I.log`.
- Repair2/final scoped batch: exit **1**, accepted XML independently counted **1,579 unit tests, 19 failures, zero errors/skips**; `gradle.aQ9iJs48.log`. All logs are in the preserved candidate's `.claude/kit/logs/`.

| Module | Tests | Failures |
|---|---:|---:|
| shizuku-ipc | 326 | 0 |
| telemetry-engine | 606 | 3 |
| database | 128 | 16 |
| diagnostics | 200 | 0 |
| xray | 101 | 0 |
| sleeplab | 99 | 0 |
| health | 119 | 0 |

The 19 failures comprise three existing telemetry deadline/paging tests whose fakes do not implement the capability probe; seven actual `MigrationTestHelper` paths1..7→8 that cannot find generated8.json in merged test assets; and nine existing database tests exposing unresolved cursor/overlap, watermark, unknown-wall, session-run and interval-query behavior. One concrete cursor regression expects `(50000,200)` but retains `(50000,100)`. These are **not waived** as stale fixtures; behavior and fixtures need evidence-based reconciliation before certification. The passing production-prune/replay regression and extraction cases do not override the failing suite. Schema-helper migration/data parity is unverified, and the candidate implements conservative global certification rather than finite reset-fenced scopes.

Requested lint checks passed (app: zero errors/eight advisories), debug assembly passed, and **only the app's eight screenshot cases** were requested/passed. No separate feature screenshot tasks ran; no reference images were changed. Independent actual-diff/Compose review and a new integrated full gate were **not run**, because the candidate failed its scoped gate and was not integrated. The prior2175unit/214screenshot full gate remains evidence for the unchanged nine-fix root, not evidence for A03.

Exact final failing command, from the candidate checkout root (original `JAVA_TOOL_OPTIONS` was unset; native temp is disk-backed):

```bash
TMPDIR=/home/akane/projects/inspiration/.claude/worktrees/agent-ab7206bc9644efc19/build/tmp/a03-native \
JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/home/akane/projects/inspiration/.claude/worktrees/agent-ab7206bc9644efc19/build/tmp/a03-native -Dorg.sqlite.tmpdir=/home/akane/projects/inspiration/.claude/worktrees/agent-ab7206bc9644efc19/build/tmp/a03-native -Djna.tmpdir=/home/akane/projects/inspiration/.claude/worktrees/agent-ab7206bc9644efc19/build/tmp/a03-native' \
bash /home/akane/projects/inspiration/.claude/worktrees/agent-ab7206bc9644efc19/.claude/scripts/gradle-slot.sh \
  :core:shizuku-ipc:testDebugUnitTest :core:telemetry-engine:testDebugUnitTest \
  :core:database:testDebugUnitTest :core:diagnostics:testDebugUnitTest \
  :feature:xray:testDebugUnitTest :feature:sleeplab:testDebugUnitTest :feature:health:testDebugUnitTest \
  :core:shizuku-ipc:lintDebug :core:telemetry-engine:lintDebug :core:database:lintDebug \
  :core:diagnostics:lintDebug :feature:xray:lintDebug :feature:sleeplab:lintDebug \
  :feature:health:lintDebug :app:lintDebug :app:validateDebugScreenshotTest :app:assembleDebug \
  --continue --max-workers=4
```

The read-only candidate manifest (40 owned files, excluding the seeded admission-test baseline) hashes to `f975b3a6f3129a62b7e18dd958e772308763df40c6de0630f951d1b839e0c9d0` using sorted compact JSON of relative-path→SHA256. Root integrity was independently rechecked: all38 nine-fix files still match their verified workers, all599 protected paths and frozen/archive hashes match, schema7 remains current, no staged changes or new commits. No device operation, release, dependency/toolchain change, live kit maintenance or root codebase-map update occurred. Shared daemon cleanup was deferred after a peer reported a standing no-stop constraint/uncertain shared ownership; temporary build holds were released without terminating processes.

## A03 resolution — third cycle, 2026-10-21 (Fable 5.1, inline)

The user asked Fable, the final escalation route, to solve A03 directly. The change was implemented inline in the integration checkout (five files, uncommitted like the nine verified fixes); neither failed candidate tree was reused as a whole, and both remain untouched. **Outcome: FIXED and VERIFIED on the JVM; the in-place device upgrade check is recorded below.**

**Why the two earlier cycles failed.** Both tried to make the stored history whole again by replaying it after deleting rows. Replay inserts a START as a new row with a new id, and every consumer orders rows of one wall time or one session run by id, so a replayed START lands *after* the transitions that were written with it, which ends their state at once (the "screen-on 0 ms" regression). Deleting those companions too (cycle one) fails where retention already pruned the mis-dated START but kept the companions, because the group is then unidentifiable. Cycle two grew that into a 40-file certification layer and still failed its gate.

**What this fix does instead.**

1. *Extractor* (`core/shizuku-ipc/.../BatteryHistory.kt`): a START record clears the wall anchor (the previous boot's anchor cannot date this boot), and the CURRENT_TIME or RESET written with the START at the same history time dates every START already emitted at that time. The repair runs right after the walker step, before the empty-record skip and the capacity check, so it also applies when the anchor emits nothing or overflows an exactly full page; the existing same-timestamp rollback then moves START and its transitions to the next page together. A START that never receives its boot's anchor stays unknown (`-1`) and is filtered at persistence, instead of carrying the old boot's time.
2. *Migration 7→8* (`InspirationDatabase.kt`, schema `8.json`, entities unchanged): repairs only what is provable and never reorders. A START whose same-session, same-history-time group has a row with a known wall time takes that wall time in place (`UPDATE OR IGNORE`, so ids and order are kept; a duplicate START at that wall time wins). Every other START is deleted: its wall time is wrong by the powered-off gap and nothing stored can date it. The rewind marker (`history_cursor` id 2) is removed so the next ingest pages the stats session once more; the new extractor restores each deleted START the framework still holds, and since those STARTs had no stored same-time rows, the new id cannot misorder anything. The ingest cursor and the watermark are untouched.
3. *Tests*: five extractor cases (long off-gap, empty anchor at exact capacity, overflow rollback with CURRENT_TIME and RESET, RESET-dated START as a whole page, no after-the-fact dating) and one real v7 Room migration (companion-dated START keeps id and precedence; lone, unknown and duplicate STARTs dropped; another session's same-history-time row ignored; cursor and watermark preserved; marker gone and `resumeCursor` rewinds exactly once more). The 6→7 test now expects the marker to be gone after the chain.

**Known limits, stated rather than papered over.** A START the framework no longer holds, or that retention pruned while its companions survived, is not reconstructed; if the framework still holds such a pruned-START group, the replay re-inserts the START after its companions for that one about-30-day-old group until the next prune removes it. A lone START replayed after the stats session was relabelled by a wall-clock step is stored under the current label and so sorts to the head of that run in `ChargeSegmentExtractor`, which can discard a charge spanning the relabel; this needs a relabel, a lone START and an open charge at once, and loses an estimate rather than producing a wrong one. Charge sessions are otherwise unaffected: START only discards an open charge and its position in history time, which this bug never changed, decides that.

**Verification (integration checkout, resource probe: 2 commands, 4 workers, no deferral; `JAVA_TOOL_OPTIONS` empty before the invocation-local override, native temp disk-backed under `build/tmp/a03-native`).**

| Command | Result |
|---|---|
| `gradle-slot.sh :core:shizuku-ipc:testDebugUnitTest --tests '*BatteryHistoryTest' :core:database:testDebugUnitTest :core:shizuku-ipc:lintDebug :core:database:lintDebug --continue --max-workers=4` | exit 0, `gradle.tiSbExqM.log`; 26 extractor tests, 104 database tests, 0 failures/errors/skips; 0 lint issues in both modules |
| `gradle-slot.sh :core:shizuku-ipc:testDebugUnitTest :core:telemetry-engine:testDebugUnitTest :core:diagnostics:testDebugUnitTest :app:assembleDebug :app:assembleRelease --continue --max-workers=4` | exit 0, `gradle.8N7LANST.log`; shizuku-ipc 332, telemetry-engine 618, diagnostics 203 tests, 0 failures/errors/skips; `app-release.apk` signed with the pinned certificate `88292f23…4d723` (sha256 `516be36711db005a…`), `app-debug.apk` sha256 `5a03ea96133bae43…` |

`git diff --check` over both modules is clean. Schema `8.json` has the same entities and identity hash as `7.json`, version 8. No dependency, toolchain, Gradle or JVM setting changed; no daemon was stopped (a peer audit session was running Gradle alongside).

**Device evidence (redroid, Android 15, `ro.product.model` = `redroid15_x86_64` checked before the install; device handed over by the peer session between its instrumented runs).** The installed 0.2.8 release had schema 7 and 16 history rows in one stats session, including two STARTs dated from the previous boot exactly as A03 describes, both stored alone (redroid boots unplugged with the screen off, so nothing was written with them):

| Row | historyTimeMs | wallMs before | wallMs after (new id) | Next event |
|---|---:|---:|---:|---|
| START 5 | 80881306 | 1792507301529 (12 ms after SHUTDOWN 4) | 1792507305864 (id 21) | PLUGGED 6 at 80881983 / 1792507306541 |
| START 8 | 80885622 | 1792507310180 (9 ms after SCREEN_ON 7) | 1792507568946 (id 24) | PLUGGED 9 at 80886318 / 1792507569642 |

`adb install -r` of the signed `app-release.apk` succeeded in place (same versionCode 10, same signer). One launch of `.MainActivity` opened the database at version 8 and ran the app-open ingest: the migration had deleted both lone STARTs and the marker, the re-armed replay re-paged the session, and the extractor re-inserted both STARTs with wall times that match the anchor of the event that follows them to the millisecond (new boot wall = next wall − history delta: 1792507306541 − 677, 1792507569642 − 696), ordered before those events. Row count returned to 16, cursor (1, 1792507301350, 136275367) and the marker row were rewritten, H unchanged, W raised only by the routine prune at app open. The device now runs this locally built release (sha256 `516be36711db005a…`), not the published 0.2.8 APK.

## Final gate and commits for A01–A10, 2026-10-21

All ten findings were committed after one whole-project gate over the complete working tree: **exit 0**, log `.claude/kit/logs/gradle.lgji4lbA.log`, 20:30:46–20:32:58. The resource probe allowed two commands and four workers per scoped command with no deferral or warnings; no other Gradle build ran, so the gate used the host-measured cap of eight workers. A peer session held its new Gradle starts for the duration. `JAVA_TOOL_OPTIONS` was empty before the invocation-local override. No orphaned Gradle workers existed. One Kotlin daemon reparented to init was left running under the standing peer no-stop agreement, and no daemon was stopped.

```sh
TMPDIR=/home/akane/projects/inspiration/build/tmp/remediation-native \
JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/home/akane/projects/inspiration/build/tmp/remediation-native \
bash .claude/scripts/gradle-slot.sh \
  testDebugUnitTest lintDebug validateDebugScreenshotTest :app:assembleDebug :app:assembleRelease \
  --continue --max-workers=8
```

| Module | Unit cases | Screenshot cases |
|---|---:|---:|
| app | 223 | 8 |
| core/database | 104 | — |
| core/designsystem | 203 | 64 |
| core/diagnostics | 203 | — |
| core/hidden-api-stub | 0 (compile-only) | — |
| core/shizuku-ipc | 332 | — |
| core/telemetry-engine | 618 | — |
| feature/dashboard | 81 | 16 |
| feature/health | 122 | 12 |
| feature/sleeplab | 97 | 32 |
| feature/toolkit | 98 | 49 |
| feature/xray | 100 | 33 |
| **Total** | **2,181** | **214** |

Accepted XML has zero failures, errors or skips. The six cases added since the nine-fix gate are A03's five extractor cases and one migration case. This was an ordinary incremental invocation, **not `--rerun-tasks`**. Lint has zero errors in every module and eight app advisories, all SDK, AGP or dependency version notices. The signed release, including R8, matches the pinned certificate `88292f23…4d723` (sha256 `516be36711db005a…`); the debug APK sha256 is `5a03ea96133bae43…`. Neither was published.

| ID | Commit | Status |
|---|---|---|
| A01 | `8edfffb` | FIXED, committed |
| A02 | `bbc9a58` | FIXED, committed |
| A03 | `4ce857d` | FIXED, committed; real v7 Room migration test and redroid in-place upgrade evidence |
| A04 | `0d913b2` | FIXED, committed |
| A05 | `cfff51f` | FIXED, committed |
| A06 | `0eb7210` | FIXED, committed |
| A07 | `8136847` | FIXED, committed |
| A08 | `3082580` | FIXED, committed |
| A09 | `6a3c2b6` | FIXED, committed |
| A10 | `9383186` | FIXED, committed |

The fix commits are on branch `audit-remediate-20261021-a01-a10-171906` from base `8cbe51f`. Nothing was pushed, released or version-bumped. Still open: A03 has had no independent cross-provider diff review, which this report asks for on cross-boundary fixes. The hardware-dependent limits listed earlier for A01, A02 and A06–A09 still apply. The two preserved A03 candidate worktrees, `agent-a8ff782c4b22c7f8c` and `agent-ab7206bc9644efc19`, are superseded but keep uncommitted work, so they were not removed.

