# Inspiration — Current-Source Repository Audit

Date: **2026-10-19** · source: **`bddeec0a18656f587981bf45503a16acfaa6e808`** · branch: `main`.

**Independent assessment finalized before prior-report comparison.** Application fixes are proposals, not applied changes. The original report was preserved throughout independent discovery. This run is **contaminated/not blind**: mandatory startup injections exposed prior-assessment summaries before investigation. No previous-report body was read during independent discovery; guarded freeze evidence and the separately appended comparison are identified at the end.

## 1. Executive Summary & System Health Scorecard

The module boundaries, typed privileged interfaces, fail-closed Binder caller checks, and broad passing test suite are valuable foundations. The main weaknesses lie at boundaries the tests model incompletely: dependency callback ordering, optional persistence, uncertain privileged mutations, observation provenance, and feature-local state retained beyond its measured interval.

**24 confirmed root findings: 0 Critical, 2 High, 19 Medium, 3 Low.** Related Sleep Lab/X-Ray stale-state symptoms are grouped under one analysis-identity root; other duplicated investigator allegations are not counted again. IDs were assigned during synthesis and retained for traceability; A-21 is placed with the High findings after final validation established wrong-target privileged operations.

| Dimension | Qualitative score | Judgment |
|---|---:|---|
| Architecture & Modularity | 7/10 | Acyclic feature/core graph and explicit contracts; feature-derived caches and toolkit lifecycle coordination still duplicate state ownership. |
| Correctness & Resilience | 5/10 | Availability and counter validity are modeled, but several reachable paths turn absence into observations or retain invalid outcomes. |
| Concurrency & Resource Safety | 5/10 | Cancellation and cleanup are generally explicit; actual Shizuku ordering, blocked kernel reads, and dexopt supervision expose significant holes. |
| Security & Privilege Hygiene | 7/10 | Examined service methods authenticate callers and use typed allowlisted operations, but X-Ray loses the target Android-user namespace before privileged operations. No arbitrary-shell injection or privilege escalation was confirmed. |
| Performance & Efficiency | 7/10 | Visibility-bound live reads and bounded IPC payloads are sensible; retained bindings and missing long-session/device profiling limit confidence. |

Scores are judgments, not measurements or percentages of correctness.

**Top three systemic risks**
1. **Lifecycle state disagrees with the real transport.** A green fake reverses Shizuku's callback ordering; kernel-only transactions bypass the cancellation boundary; a failed supervisor can retain the shell-service binding.
2. **Missing or uncertain evidence becomes authoritative state.** Truncated observations, dump EOF, transient capacity prerequisites and privileged operation admission must remain distinguishable from valid zero, terminal rejection and completed mutation.
3. **Target and measurement identity are lost across boundaries.** A secondary-user X-Ray row controls the owner installation; feature-local caches can retain an old measurement, and Health merges separate observed plug sessions. Typed operations alone do not preserve the identity of their intended target.

**Verification:** the unchanged source passed **1,238 unit tests**, **168 screenshot validations**, lint (zero errors, nine warnings), debug assembly and release R8. Independent scratch JVM/SQLite reproductions supplement source traces; they are not post-fix tests. Mobile MCP/redroid smoke passed for the installed release, whose equivalence to the pinned source is **unverified**. No application remediation was performed.

## 2. Confirmed Findings

Severity is ordered High → Medium → Low; there are no confirmed Critical findings. Each confidence statement concerns the stated scenario, not every OEM/API or every proposed repair. Unless explicitly stated otherwise, **fixes below have not been applied, compiled as fixes, installed or regression-gate verified**. Exact source ranges refer to the pinned revision. Evidence filenames refer to the preserved guard session `.git/audit-history/run-rf65ec6x/`; these files contain fuller replay/classpath and rejected-candidate ledgers.

### [A-01] HIGH — Rebinding inside Shizuku's death callback loses the new connection

- **Category:** Concurrency.
- **Location:** `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/ShizukuManagerImpl.kt:97-102,302-307,387-439`; misleading fake: `core/shizuku-ipc/src/test/kotlin/com/akane/inspiration/ipc/ShizukuManagerImplTest.kt:818-850`.
- **Confidence:** Confirmed by call trace, resolved Shizuku 13.1.5 bytecode and actual compiled manager/library replay (`validation.md`, V1).
- **Root Cause & Execution Trace:** Cockpit/Health hide releases the last holder → manager's three-second idle unbind uses `remove=true` → a new holder arrives during teardown and waits → `onServiceDisconnected` executes `if (awaitingUnboundDeath) { finishUnbindSettleLocked(); return }` → that function synchronously rebinds. The actual library still caches the dying wrapper during the callback; only afterward does it clear connections and remove the wrapper. The replacement registration is erased. The test fake clears before notifying, reversing the decisive order.
- **Failure / Exploit Scenario:** Normal leave/return timing leaves Shizuku Ready with `serviceBound=true`, `telemetryService=null`. Refresh/additional holders do not rebind; visible telemetry/actions time out until all holders leave and another lifecycle cycle recovers. No attacker is required.
- **Concrete Fix:** Replace the awaited-death branch with a **non-immediate Main** posted settle, keeping acquisitions deferred until library cleanup returns:

```kotlin
if (awaitingUnboundDeath) {
    settleTimeout?.cancel()
    settleTimeout = scope.launch(Dispatchers.Main) {
        synchronized(bindingLock) {
            if (settleTimeout !== coroutineContext.job) return@launch
            settleTimeout = null
            if (awaitingUnboundDeath) finishUnbindSettleLocked()
        }
    }
    return
}
```

`Dispatchers.Main.immediate` or an IO launch is not an ordering fence. Keep job-identity checks and existing transport cleanup. **Scope:** this repairs the demonstrated awaited-death branch, not the separate timeout-fallback reuse of a dead wrapper. Full lifecycle hardening should serialize cache/callback state and use generation-specific connections; a bare remove-true/remove-false pair is not a complete solution.
- **Regression / Verification:** Replay used the real api-13.1.5 registry, bind/unbind and callback body; replacement registration vanished and manager remained falsely bound. Correct the fake to notify before clearing/removing, install a controlled Main dispatcher, and assert fresh binding only after callback return. Include stopped/dead-daemon/lock-waiting-timeout interleavings and a real-Shizuku handover test. The latter was not performed.

### [A-21] HIGH — Secondary-user X-Ray actions modify the owner-user installation instead

- **Category:** Security.
- **Location:** `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayViewModel.kt:203-238,271-278,307-350`; `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/ToolkitExecutor.kt:14,60-67,89-121,256-265`; package-only AIDL `core/shizuku-ipc/src/main/aidl/com/akane/inspiration/ipc/ITelemetryUserService.aidl:25-31`.
- **Confidence:** Confirmed full source/AOSP Android15 reachability and freshly compiled VM→Actions→Gateway→proxy Binder→executor replay (`validation-xray-late.txt`, FSXT1). No real multi-profile device write.
- **Root Cause & Execution Trace:** BatteryUsageStats query defaults USER_ALL → capture/Room/differ retain full secondary UID → QUERY_ALL_PACKAGES-visible getPackagesForUid(fullUid) resolves a dual-installed package → X-Ray pins UID only for UI correlation, but `deps.toolkit.appState(pkg)`/ToolkitAction carry only package → audit/override keys omit user → executor uses `private const val USER = "0"` and `runner.run(listOf(CMD, "activity", "force-stop", "--user", USER, pkg.orEmpty()))`. Owner package passes installation/protection checks. AOSP UID lookup/visibility and user filtering were checked, not inferred from a fixture alone.
- **Failure / Exploit Scenario:** User selects work/profile UID1010002 for an ordinary app also installed in user0. Controls read owner's bucket, restrictions/bucket/restores alter owner, and confirmed force-stop stops personal user0 while profile10 remains unchanged. Profile-only packages are normally refused by the owner installation gate; no arbitrary cross-user permission bypass, malicious Intent or privilege escalation is claimed. Severity is High because permitted privileged operations target a different installation than the selected one without requiring a race.
- **Concrete Fix:** Contain unsupported user targets before **reads, overrides, planning and writes** while preserving analytics. Add `data object UnsupportedUser : ActionsState` in XRayModels; add the corresponding XRayDetail when branch rendering `R.string.xray_actions_unsupported_user`. In reloadDetail, after `val pkg = detail.app.packageName`:

```kotlin
if (uid !in 0 until 100_000) {
    updateDetail(uid, pkg) {
        it.copy(actions = ActionsState.UnsupportedUser, overrides = persistentListOf())
    }
    return
}
```

After obtaining detail in act, add `if (detail.app.uid !in 0 until 100_000) return`. After destructuring pending in confirmPending, add `if (uid !in 0 until 100_000) { dismissConfirmation(); return }`. At executeContained entry add:

```kotlin
if (uid !in 0 until 100_000) {
    updateDetail(uid, pkg) {
        it.copy(busy = false, actions = ActionsState.UnsupportedUser, overrides = persistentListOf())
    }
    return
}
```

Add `xray_actions_unsupported_user` to both resource sets: EN `Quick actions are available only for apps in Android user 0.`; TR `Hızlı işlemler yalnızca Android kullanıcı 0 içindeki uygulamalar için kullanılabilir.` Do not modulo a UID into an owner target. Full multi-user support instead requires immutable user/full-UID identity through typed actions, versioned AIDL, audit/override keys, installed/protected checks, argv and read-back, migrating legacy audit rows as owner0. Changing only one command's --user is insufficient.
- **Regression / Verification:** Current-source replay checked63 generated argv: restriction/bucket/restore/force-stop all user0; user10 unchanged. Profile-only control generated zero commands. Post-fix secondary rows must make **zero owner reads/writes/restores/plans**, including direct intents/confirmation; owner0 behavior remains intact. A physical two-profile test and full API/OEM survey remain unexecuted.

### [A-02] MEDIUM — Kernel-only Binder reads outlive their named timeout and cancellation

- **Category:** Resource Leak / Concurrency.
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/source/AndroidTelemetrySource.kt:108-124,177-187`; `app/src/main/kotlin/com/akane/inspiration/AppContainer.kt:190-194`; manager `ShizukuManagerImpl.kt:187-214`.
- **Confidence:** Confirmed by full caller trace and deterministic stalled-transaction replay (`validation.md`, V3; `ipc-review.md`).
- **Root Cause & Execution Trace:** Health refresh → AppContainer.kernelBattery → manager.withTelemetryService → direct `runCatching { service.readKernelBattery() }`. Live Cockpit/Health and charging-job analysis use the same direct call in kernelReading. The manager's timeout ends at acquisition, before its IO block. Remote sysfs/Binder work can block; cancellation cannot complete that IO child or release its active-service-user count. Full captures already use a cancellable asynchronous `binderCall`, but these routes bypass it.
- **Failure / Exploit Scenario:** A stalled live UserService/driver leaves Health loading ahead of persisted-data reads, live cancellation retaining service use, and a stopped charging job unable to reach rearm cleanup promptly. No observed physical device stall or main-thread ANR is claimed.
- **Concrete Fix:** Add `import kotlinx.coroutines.withTimeoutOrNull` and a shared method in AndroidTelemetrySource:

```kotlin
suspend fun kernelBatterySnapshot(timeoutMs: Long = bindTimeoutMs): KernelBatterySnapshot? =
    withTimeoutOrNull(timeoutMs) {
        shizuku.withTelemetryService(timeoutMs) { service ->
            binderCall { service.readKernelBattery() }
        }
    }
```

Replace kernelReading's direct service block with `val kernel = kernelBatterySnapshot() ?: return@withContext null`; replace AppContainer.kernelBattery's service block with `KernelCapacitySnapshot.from(telemetrySource.kernelBatterySnapshot(KERNEL_READ_TIMEOUT_MS))`. Use the existing read-only cancellable boundary, not a detached arbitrary manager block or `withTimeout` around blocking Binder alone.
- **Regression / Verification:** Actual source+manager replay exceeded a 20 ms acquisition timeout; after cancellation/holder close it was cancelled-but-incomplete with one active service user until latch release. Test total deadline, prompt caller cancellation/refcount release, persisted Health fallback and job rearm while remote work is still blocked. The fix bounds callers, not remote native work; repeated retries need an in-flight bound if hardening indefinitely wedged services.

### [A-03] MEDIUM — A partially effective failed restore disappears from recovery/reset

- **Category:** Logic Bug.
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt:746-797,826-853,940-977`; caller `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitViewModel.kt:304-407,478-486`.
- **Confidence:** Confirmed by production/AOSP trace and current-source JVM reproduction (`validation-toolkit.md`, K1).
- **Root Cause & Execution Trace:** Toolkit sets bucket40→45 → restore intends40 → Android's policy minimum clamps the command to30 → executor returns FAILED with before45/after30 → audit persists a changed restore row. `computeOverrides` selects that newest write then excludes it with `it.restoresId == null`; decode/ancestry/retention also treat every restore link as closure. No upstream UI fallback reconstructs the missing override.
- **Failure / Exploit Scenario:** Reset all/active overrides become empty even though observed30 is not original40. Subsequent writes/pruning can lose the true root. Android's bucket clamping is verified; arbitrary externally changed state is not automatically toolkit-owned.
- **Concrete Fix:** In ToolkitActions introduce typed partial-restore continuation, and apply it consistently to decode, ancestry, enumeration and retention:

```kotlin
private fun partialRestore(row: ToolkitActionRecord): Boolean =
    row.restoresId != null && landedElsewhere(row) && intendedAction(row) != null

private fun closesChain(row: ToolkitActionRecord): Boolean =
    row.restoresId != null && !partialRestore(row)

private fun restoreTarget(row: ToolkitActionRecord): String? = when (val action = intendedAction(row)) {
    is ToolkitAction.SetAppOp -> action.mode.argv
    is ToolkitAction.SetBucket -> action.bucket.rawValue.toString()
    is ToolkitAction.RestoreDoze -> norm(action.rawOrNull)
    else -> error("not a typed partial restore")
}
```

Exact replacements:

```diff
@@ decodeRestore
- if ((record.status != ToolkitActionStatus.DONE && !landedElsewhere(record)) || record.restoresId != null) {
+ if ((record.status != ToolkitActionStatus.DONE && !landedElsewhere(record)) || closesChain(record)) {
@@ chainOriginal, after val key = targetKey(row)
+ if (partialRestore(row)) return restoreTarget(row)
@@ chainOriginal, inside older-row loop
- if (older.restoresId != null || !sameState(older.kind, older.afterRaw, original)) break
+ if (closesChain(older) || !sameState(older.kind, older.afterRaw, original)) break
+ if (partialRestore(older)) return restoreTarget(older)
@@ computeOverrides predicate
- it.restoresId == null && it.status != ToolkitActionStatus.RESTORED && mayBeActive(it, rows)
+ !closesChain(it) && it.status != ToolkitActionStatus.RESTORED && mayBeActive(it, rows)
@@ closedPrefix
- original = if (i > 0 && continues(links[i - 1], link)) original else link.beforeRaw
+ original = when {
+     partialRestore(link) -> restoreTarget(link)
+     i > 0 && continues(links[i - 1], link) -> original
+     else -> link.beforeRaw
+ }
- val endsHere = if (next == null) link.restoresId != null || closed else !continues(link, next) || closed
+ val endsHere = if (next == null) closesChain(link) || closed else !continues(link, next) || closed
@@ continues
- older.restoresId == null && newer.restoresId == null && sameState(older.kind, older.afterRaw, newer.beforeRaw)
+ !closesChain(older) && !closesChain(newer) && sameState(older.kind, older.afterRaw, newer.beforeRaw)
```

The failed restore's persisted intended `param`, **not its beforeRaw**, anchors original40 even after explicitly confirmed external-current divergence. Keep the live-current==recorded-after listing gate. Add this automatic-reset check:

```diff
@@ resetOne
- return run(action, confirmed = true, restoring = row)
+ return run(action, confirmed = true, restoring = row, requireExpectedCurrent = true)
@@ run signature, after restoring parameter
+ requireExpectedCurrent: Boolean = false,
@@ run, immediately after the existing alreadyRestored refusal
+ if (requireExpectedCurrent && restoring != null && !sameState(restoring.kind, prep.current, restoring.afterRaw)) {
+     return refuse(spec, restoresId, ToolkitRefusal.STALE, ToolkitCode.STALE)
+ }
```

This prevents overwriting a divergence observed between enumeration and prepare; it does not invent atomic CAS for platform bucket/app-op commands.
- **Regression / Verification:** Replay produced `overrides=0`, `reset=0`, NOT_RESTORABLE. Test partial and lost-reply/reconciled restore, explicit stale confirmation, repeated writes and >500-row pruning/recreation. All must retain true original40 and observed current30. Successful/no-change/unknown-after/external-current cases must not become automatic reset candidates. Proposed patch is unexecuted.

### [A-04] MEDIUM — One Room exception terminates dexopt binding supervision before its cap

- **Category:** Resource Leak.
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt:1075-1078,1157-1195,1293-1297`; `core/database/src/main/kotlin/com/akane/inspiration/database/store/RoomSnapshotStore.kt:198-199`.
- **Confidence:** Confirmed by call trace and virtual-time JVM reproduction (`validation-toolkit.md`, K2).
- **Root Cause & Execution Trace:** Successful start → syncHold acquires manager holder and starts one supervisor → minute tick → `withContext(NonCancellable) { mutex.withLock { refreshDexoptLocked() } }` → audit.pending Room query throws → only supervisor terminates. SupervisorJob's handler logs; dexoptHold remains non-null, preventing restart. The independent 62-minute release check is never reached again.
- **Failure / Exploit Scenario:** With no foreground collector, client binding/shell-service retention can last until later status/cancel/release/process death. **Not unbounded dexopt execution:** the service's separate 60-minute command cap still mitigates work duration, and the gateway's 30-second holder is a different owner.
- **Concrete Fix:** Contain recoverable failures inside each supervision iteration, preserving subsequent monotonic deadline checks:

```kotlin
try {
    withContext(NonCancellable) { mutex.withLock { refreshDexoptLocked() } }
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    android.util.Log.w("ToolkitActions", "hold status read failed; will retry within cap", error)
}
if (!holdTaken()) return
```

Do not add an untagged finally that could close a newer generation's holder.
- **Regression / Verification:** Injected one pending-query error left one holder after66 simulated minutes. Test one-shot/repeated DB exceptions with no collectors: later terminal status releases, and unavailable status still releases at cap. Preserve cancellation and new-start supervision. No physical dexopt run was exercised.

### [A-05] MEDIUM — Status reconciliation terminalizes a dexopt start before admission

- **Category:** Concurrency.
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt:327-350,1075-1148`; `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/BgDexoptController.kt:54-79`.
- **Confidence:** Confirmed by real-source interleaving reproduction and crash-state simulation (`validation-toolkit.md`, K3).
- **Root Cause & Execution Trace:** start inserts PENDING under mutex then calls backend outside it → controller blocks in plugged probe before RUNNING CAS → concurrent status sees a **previous IDLE/FINISHED/CANCELLED**, terminalizes the new row → new command is admitted. Fresh UNKNOWN alone does not trigger this. Normal successful reply rewrites PENDING, masking the window. If service/app are lost before settlement, recreated UNKNOWN is collapsed to Idle because the durable pending owner has already been erased.
- **Failure / Exploit Scenario:** Durable uncertainty/recovery marker is lost, so UI may no longer offer cancellation for an operation whose final outcome was never observed. This requires interruption for the durable symptom; it is not proof every crash leaves a command running. Normal destroy attempts cancellation.
- **Concrete Fix:** Track all locally in-flight start row IDs under the existing mutex; exclude them from **every** terminalizer, including cancel and supersede:

```diff
@@ fields
+ private val dexoptStartsInFlight = mutableSetOf<Long>()
@@ startBgDexopt pending insertion (inside mutex)
  insertPending(Spec(KIND_DEXOPT, null, OP_BG_DEXOPT, null), restoresId = null, beforeRaw = null)
+     .also { dexoptStartsInFlight += it.id }
@@ surround existing backend call plus existing settlement mutex expression
+ try {
      // existing backend call and settlement; retain existing cancellation fencing
+ } finally {
+     mutex.withLock { dexoptStartsInFlight -= pending.id }
+ }
@@ refreshDexoptLocked status computation
- val status = if (service.state == BgDexoptState.UNKNOWN && pending.isEmpty()) BgDexoptStatus.Idle else statusOf(service)
+ val observed = statusOf(service)
+ val status = when {
+     service.state == BgDexoptState.UNKNOWN && pending.isEmpty() -> BgDexoptStatus.Idle
+     dexoptStartsInFlight.isNotEmpty() && observed !is BgDexoptStatus.Running -> BgDexoptStatus.Unknown
+     else -> observed
+ }
@@ finalizeDexoptRows
- val rows = audit.pending().filter { it.kind == KIND_DEXOPT }
+ val rows = audit.pending().filter { it.kind == KIND_DEXOPT && it.id !in dexoptStartsInFlight }
@@ supersedeOtherDexoptRows
- for (row in audit.pending().filter { it.kind == KIND_DEXOPT && it.id != keepId }) {
+ for (row in audit.pending().filter { it.kind == KIND_DEXOPT && it.id != keepId && it.id !in dexoptStartsInFlight }) {
```

The existing outer NonCancellable context covers finally removal; do not hold the mutex around slow start, regress cancel responsiveness or remove controller generation guards. The schematic try wrapper contains the unchanged existing statements, not a new empty implementation.
- **Regression / Verification:** Reproduction persisted CANCELLED while start suspended; recreated UNKNOWN became Idle. Uninterrupted success repairing PENDING was also verified. Test all prior terminal states, crash window, cancel-before-controller-entry, probe cancellation and concurrent second start/supersede. No device process-kill experiment was performed.

### [A-06] MEDIUM — An accepted Doze original cannot be restored after ordinary service recreation

- **Category:** Logic Bug.
- **Location:** `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/ToolkitExecutor.kt:124-138,158-165,284-289`; `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/Toolkit.kt:102-109,170-176`.
- **Confidence:** Confirmed by Android15 parser semantics and fresh-executor reproduction (`validation-toolkit.md`, K4).
- **Root Cause & Execution Trace:** Apply accepts `DozeRaw.isSafe(original)` and remembers raw only in process-local seenDozeRaws → manager idle teardown recreates executor → restore accepts only isRestorable or seen raw. Valid Android duplicate-key prior `inactive_to=600000,inactive_to=1800000` is safe but not restorable; platform KeyValueListParser uses the last key. UI stores/advertises restoration, but fresh executor refuses ARG_VALUE.
- **Failure / Exploit Scenario:** Leaving Toolkit for the normal idle lifecycle can make a previously accepted custom original impossible to restore through its advertised undo path.
- **Concrete Fix:** Before any mutation, require fresh-executor replayability:

```diff
- if (current.value != null && !DozeRaw.isSafe(current.value)) {
+ if (current.value != null && !DozeRaw.isRestorable(current.value)) {
      return ToolkitResult.refused(ToolkitCode.ARG_VALUE, before = current.value)
  }
```

Do not broaden privileged replay into arbitrary opaque strings or persist an unvalidated trust exception. This prevents new unsafe transitions; already-deployed unsupported originals need a separate recovery/UX policy.
- **Regression / Verification:** Same-executor restore succeeded; fresh executor refused without writes. Test duplicate/safe-malformed originals refused **before** settings put/delete, valid OEM/nullable original fresh-executor restoration and stale-current CAS refusal.

### [A-07] MEDIUM — Parcel-truncated UID usage becomes valid whole-session interval energy

- **Category:** Logic Bug.
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/capture/SnapshotCapturer.kt:121-149,178`; `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/SnapshotDiffer.kt:181-185,215-244`; producer `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/UsageParcelBudget.kt:18-35`.
- **Confidence:** Confirmed by producer→Room→differ→consumer trace and current compiled-class reproduction (`telemetry-review.md`, T1); replacement independent source/replay validation completed (`validation-telemetry-final.md`).
- **Root Cause & Execution Trace:** Full capture →512KiB usage budget drops low-total UID rows → capturer unions independent dump UIDs and stores omitted usage as `usageUid?.totalMah ?: 0.0` → snapshot-level truncatedUids survives Room but differ ignores it → later retained usage subtracts fabricated zero and marks a positive result valid → X-Ray/rules consume it. The union's presence cannot establish a usage observation.
- **Failure / Exploit Scenario:** Actual cumulative40→50 becomes50mAh valid interval energy rather than10. Lost process-state baselines can similarly make Night Vampire report a false culprit; an actually new UID with legitimate zero is not distinguishable with present metadata.
- **Concrete Fix:** In SnapshotDiffer.diff rename the original local uids to observedUids and apply the existing metadata conservatively:

```kotlin
val observedUids = if (batteryStatsSessionValid) {
    diffUids(ref, now, problems, dumpCountersValid, alarmDumpValid)
} else emptyList()
val incompleteUidUsage = ref.truncatedUids > 0 || now.truncatedUids > 0
if (incompleteUidUsage) problems += "truncated_uid_usage"
val uids = if (incompleteUidUsage) observedUids.map { it.copy(valid = false) } else observedUids
```

Keep the remaining diff construction unchanged. If A-08 adds an owners parameter, forward it to this same diffUids call. Existing per-UID consumers honor valid; independently available aggregate device/session/CPU metrics remain available. An aggregate itself derived from the truncated UID sum is not a complete independent device measurement and must not be labeled as one. Longer term persist per-UID/per-source observation provenance so independently comparable dump counters need not be withheld with incomplete energy.
- **Regression / Verification:** Original replay returned computed50/valid=true. Test dynamic budget membership through actual capture and Room round-trip, either/both truncated endpoints, absent dump rows, complete50−40=10, new UID zero, unaffected device/session metrics and diagnostic suppression. Conservative patch not applied. The replacement independently replayed this merged-snapshot failure and controls; actual dynamic-budget capture/Room round-trip remains an acceptance oracle, not a completed experiment.

### [A-08] MEDIUM — Highest-drain package hints are mistaken for shared-UID replacement

- **Category:** Logic Bug.
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/SnapshotDiffer.kt:208-221`; `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/client/BatteryStatsClient.kt:264-270` and `BatteryUsageAdapter.kt:145-150`; analyzer `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/IntervalAnalyzer.kt:38-46`.
- **Confidence:** Confirmed by actual API35/36/37 UidBatteryConsumer semantics, call trace and compiled differ (`telemetry-review.md`, T2); replacement independent source/replay validation completed (`validation-telemetry-final.md`).
- **Root Cause & Execution Trace:** Stable shared UID has membersA/B → framework's highest-drain member since reset changesA→B → adapter stores that attribution hint as packageName → differ's `previousPackage != currentPackage` sets packageChanged and discards the baseline → X-Ray/Sleep Lab/rules filter the row. Hint changes are not proof of installation identity changes.
- **Failure / Exploit Scenario:** Legitimate cumulative30→50 UID interval disappears from lists/culprit rules. The internal delta becomes50, but consumers filter it; the demonstrated user error is omitted data, not displayed inflated energy.
- **Concrete Fix:** Bounded mitigation: add default `ownerPackages: Map<Int, Set<String>> = emptyMap()` to SnapshotDiffer.diff and forward it into diffUids. At the existing hint comparison:

```kotlin
val owners = ownerPackages[uid].orEmpty()
val bothKnownMembers = previousPackage in owners && currentPackage in owners
val packageChanged = uid >= FIRST_APPLICATION_UID &&
    previousPackage != null && currentPackage != null &&
    previousPackage != currentPackage && !bothKnownMembers
```

Add `private val packagesForUid: suspend (Int) -> List<String> = { emptyList() }` after storyBuilder in IntervalAnalyzer. Before its diff call:

```kotlin
val previousNames = resolved.ref.uids.associate { it.uid to it.packageName?.substringBefore(':') }
val owners = resolved.now.uids.mapNotNull { row ->
    val before = previousNames[row.uid]
    val after = row.packageName?.substringBefore(':')
    if (before != null && after != null && before != after) row.uid else null
}.distinct().associateWith { packagesForUid(it).toSet() }
val diff = SnapshotDiffer.diff(resolved.ref, resolved.now, owners)
```

Wire AppContainer `IntervalAnalyzer(store, ruleEngine, packagesForUid = { appLabels.packagesForUid(it) })`; that deferred lambda runs after container construction. Lookup must be **fresh** (A-17), not a cached cosmetic allowlist. Preserve conservative omission for unknown owners and all negative/reset guards. Do **not** simply delete replacement protection. Current membership is a bounded mitigation for the demonstrated stable shared UID, **not proof of historical install-incarnation continuity**. Do not accept the exemption alone as complete reuse protection. The full architectural repair must capture trusted owner/install-incarnation evidence alongside each UID at each endpoint, persist it with a tested Room migration, compare comparable incarnation records rather than highest-drain names, and retain conservative exclusion for legacy/unknown/changed incarnations. Test positive monotonic counters across real reuse; negative counters alone are insufficient. Ownership failure must not be treated as positive proof of continuity.
- **Regression / Verification:** Current compiled code produced packageChanged=true/whole-session50 for same-UID30→50. Test knownA/B members giving20 and no replacement, B-only ownership preserving omission, null/process-suffix behavior, unknown ownership and analyzer production wiring. Capture-time identity design remains a structural follow-up; no physical UID-recycling experiment was performed.

### [A-09] MEDIUM — Zero-byte failed alarm dump is advertised as an observed zero baseline

- **Category:** Logic Bug.
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/dump/DumpParser.kt:27-45`; `DumpReader.kt:81-88`; `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/service/TelemetryUserService.kt:237-264`; `SnapshotDiffer.kt:34-35,234-244,309-324` in telemetry/diff.
- **Confidence:** Confirmed parser execution and full producer/availability/differ trace (`telemetry-review.md`, T3); replacement independent source/replay validation completed (`validation-telemetry-final.md`).
- **Root Cause & Execution Trace:** openDump returns pipe before writer finishes → cached remote binder dies/throws before writing → writer closes, reader getsEOF/zero bytes → protobuf default parses successfully → `parseAlarm` returns Ok(empty) → capture marks alarm available → later since-boot cumulative counts are accepted as interval deltas. A failed current endpoint can instead produce negative counters and invalidate otherwise-good UID data. Ordinary permission denial normally writes rejected text; it is not the asserted empty-EOF trigger.
- **Failure / Exploit Scenario:** X-Ray/Sleep Lab fallback alarm counters fabricate wakeups since boot rather than since the reference, or usable unrelated UID results disappear after a failed current dump.
- **Concrete Fix:** Reject zero bytes before parsing, leaving authentic nonempty no-alarm messages valid:

```diff
  fun parseAlarm(bytes: ByteArray): DumpParse<AlarmDump> = try {
+     require(bytes.isNotEmpty()) { "empty alarm dump" }
      val dump = AlarmManagerServiceDumpProto.parseFrom(bytes)
```

Existing catch maps this to unavailable. Do not require repeated alarm_stats rows. Reliable error-pipe signaling is stronger transport hardening; EOF rejection closes this proven path without equating a valid empty list with transport success.
- **Regression / Verification:** Actual parser returned Ok(AlarmDump(filterStats=[])) for zero bytes. Test close-before-write through full-source availability and subsequent diff: alarmDumpCounters false, no fabricated delta, independent valid energy preserved. Nonempty authentic no-alarm dumps must remain available. No real system-server death was induced.

### [A-10] MEDIUM — One optional sample-write error terminates live telemetry after storage recovers

- **Category:** Logic Bug.
- **Location:** `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/live/LiveTelemetry.kt:28-37`; `core/database/src/main/kotlin/com/akane/inspiration/database/store/RoomSnapshotStore.kt:205-210`; `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthViewModel.kt:95-118,134-155`; `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitViewModel.kt:108-127,143-165`.
- **Confidence:** Confirmed current live-flow execution and production VM traces (`telemetry-review.md`, T4); replacement independent source/replay validation completed (`validation-telemetry-final.md`).
- **Root Cause & Execution Trace:** Visible live collector → source reading emitted → plugged tick executes `store?.insertSamples(listOf(reading))` → ordinary Room exception escapes cold flow → VM catch completes silently, preserving last UI. Health keeps non-null completed liveJob, blocking restart. Refresh does not recreate the live collector.
- **Failure / Exploit Scenario:** A transient write error freezes live gauge/curve while screen stays visible, even after disk recovery. Hide/show or replug can recover; this is not literally unrecoverable across lifecycle transitions.
- **Concrete Fix:** Import existing cancellation-preserving attempt and contain **only** optional persistence:

```kotlin
if (reading.plugged == true) {
    attempt { store?.insertSamples(listOf(reading)) }
}
```

Keep emit outside the containment boundary so collector failures/Flow transparency remain correct. Missing samples become gaps handled by existing coverage rules. Source-read failure/stale-state UX is separate hardening, not a reason for broad catch around emit.
- **Regression / Verification:** First-insert-only failure ended actual flow after one read/emission/write; next insert would have succeeded but never ran. Test two+ ticks after one failed write without lifecycle change, insertion cancellation propagation, collector exception transparency and VM freshness/restart without duplicate pollers. Physical disk-full recovery was not exercised.

### [A-11] MEDIUM — A transient missing design capacity permanently rejects a recoverable segment

- **Category:** Logic Bug.
- **Location:** `app/src/main/kotlin/com/akane/inspiration/capture/ChargingCaptureRunner.kt:86-101`; `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/capacity/CapacityEstimator.kt:155-170`.
- **Confidence:** Confirmed by actual compiled pipeline/estimator replay and Room storage trace (`validation.md`, V2).
- **Root Cause & Execution Trace:** Charging job captures history → partially available kernel has FCC/counters but null design → estimator persists `missing_design_capacity` → pipeline builds `val knownKeys = known.mapTo(HashSet()) { it.startWallMs to it.endWallMs }` from **all** stored verdicts → later positive design skips that wall pair. Room's replace-capable unique upsert is never called again. Source history can expire while charge_session remains.
- **Failure / Exploit Scenario:** A valid 40→80% charge-counter segment that would estimate2500mAh remains permanently rejected after a transient prerequisite recovers; transient rejects also pollute rejection-streak inputs. Other segments may still succeed.
- **Concrete Fix:** Add `SegmentVerdict` import and replace evaluateSegments with the following bounded retry policy. Only missing-design rows are retriable, genuine rejects/accepted samples remain terminal, and stored evidence recovers rows whose history expired:

```kotlin
private suspend fun evaluateSegments(kernel: KernelCapacity) {
    if (kernel.designMah?.takeIf { it > 0.0 } == null) return
    val known = store.chargeSessions()
    val retry = known.filter { !it.accepted && it.rejectReason == "missing_design_capacity" }
    val terminal = known.filterNot { !it.accepted && it.rejectReason == "missing_design_capacity" }
    val knownKeys = terminal.mapTo(HashSet()) { it.startWallMs to it.endWallMs }
    val recovered = retry.map { row ->
        row.statsStartMs to ChargeSegment(
            row.startHistoryTimeMs, row.startWallMs, row.endWallMs,
            row.startPct, row.endPct, row.deltaChargeUah, row.integratedMah,
            row.maxTemperatureDeciC,
        )
    }
    val segments = (recovered + ChargeSegmentExtractor.fromSessions(store.historySessions()))
        .distinctBy { (_, segment) -> segment.startWallMs to segment.endWallMs }
        .filter { (_, segment) -> (segment.startWallMs to segment.endWallMs) !in knownKeys }
        .sortedBy { it.second.endWallMs }
    val evaluated = terminal.map { row ->
        row.endWallMs to SegmentVerdict(row.estimateMah, row.independent, row.accepted, row.rejectReason)
    }.toMutableList()
    for ((statsStartMs, segment) in segments) {
        val previous = evaluated.filter { it.first <= segment.endWallMs }
            .sortedBy { it.first }.map { it.second }
        val accepted = previous.filter { it.accepted }.mapNotNull { it.estimateMah }
        val rejections = previous.takeLastWhile { !it.accepted }.size
        val verdict = guarded("charge segment ${segment.startHistoryTimeMs} failed") {
            val enriched = withRecordedCurrent(segment)
            CapacityEstimator.evaluate(enriched, kernel, accepted.takeLast(MEDIAN_WINDOW), rejections)
                .also { store.upsertChargeSession(statsStartMs, enriched, it) }
        } ?: continue
        evaluated += segment.endWallMs to verdict
    }
}
```

- **Regression / Verification:** Replay kept one rejected upsert after design recovery; the identical estimator input then accepted2500mAh. Test positive-design deferral, seeded persisted reject with deleted history, replacement/idempotency, genuine rejects untouched and only earlier terminal estimates influencing median/streak. Use a replace-capable fake/Room test rather than the existing append-only fake. The replacement above is not compiled as a fix.

### [A-12] MEDIUM — Stale scheduler generation check replaces a newer immediate job with a 45-minute delay

- **Category:** Concurrency.
- **Location:** `app/src/main/kotlin/com/akane/inspiration/capture/ChargeJobRun.kt:31-38`; `ChargingCaptureScheduler.kt:20-37`; `ChargingCaptureJobService.kt:21-24,44-47`; `BootCaptureReceiver.kt:17-27` in the same capture package.
- **Confidence:** Confirmed by actual runChargeJob forced inter-thread replay and Android same-ID replacement contract (`validation.md`, V4).
- **Root Cause & Execution Trace:** Old IO job checks immediate generation → boot/app IO path increments and schedules immediate JOB_ID29 → old finally invokes separate delayed rearm for the same ID. AtomicLong does not atomically guard check+schedule. JobScheduler replaces the newer job.
- **Failure / Exploit Scenario:** Intended immediate charge sampling can be postponed by the existing minimum latency2,700,000ms; this is a race, not every boot.
- **Concrete Fix:** Add one scheduler monitor used by **all** arm/ensure paths and the authoritative guarded rearm:

```kotlin
private val schedulingLock = Any()

fun ensureScheduled(context: Context) = synchronized(schedulingLock) {
    val scheduler = context.getSystemService(JobScheduler::class.java)
    if (scheduler.getPendingJob(JOB_ID) == null) arm(context)
}

fun arm(context: Context, reArmFromJob: Boolean = false) {
    synchronized(schedulingLock) {
        val builder = JobInfo.Builder(JOB_ID, ComponentName(context, ChargingCaptureJobService::class.java))
            .setRequiresCharging(true)
            .setPersisted(true)
        minimumLatencyMs(reArmFromJob)?.let(builder::setMinimumLatency)
        if (!reArmFromJob) immediateArms.incrementAndGet()
        context.getSystemService(JobScheduler::class.java).schedule(builder.build())
    }
}

fun reArmIfUnchanged(context: Context, expectedImmediateArms: Long) {
    synchronized(schedulingLock) {
        if (immediateArms.get() != expectedImmediateArms) return
        arm(context, reArmFromJob = true)
    }
}
```

Retain existing AtomicLong/count accessor. In JobService replace the reArm lambda with `ChargingCaptureScheduler.reArmIfUnchanged(this@ChargingCaptureJobService, immediateArmsAtStart)`. The outer pure guard can remain a fast precheck; the shared lock is authoritative.
- **Regression / Verification:** Actual pure runner replay forced immediate arm after its guard; delayed descriptor won. Test both monitor acquisition orders with fake JobScheduler, including failure/cancellation finally paths. No device JobScheduler race was executed; schedule-failure policy is unchanged.

### [A-13] MEDIUM — Mutable wall-clock ordering chooses an obsolete trigger capture

- **Category:** Logic Bug.
- **Location:** `core/database/src/main/kotlin/com/akane/inspiration/database/Daos.kt:116-117`; `core/database/src/main/kotlin/com/akane/inspiration/database/store/RoomSnapshotStore.kt:109-114`; `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/ReferenceResolver.kt:45-49,82-85`.
- **Confidence:** Confirmed using exact v4 exported schema/current SQL and resolver trace (`validation.md`, V5).
- **Root Cause & Execution Trace:** Runtime unplug captures do not ingest history → SinceUnplugged falls back to latestClockByTrigger → DAO `ORDER BY wallMs DESC, id DESC`. After wall rollback, older future-dated capture wins → resolver's from>now guard rejects it despite a newer usable capture.
- **Failure / Exploit Scenario:** Same boot7 old wall2,000,000/elapsed10,000; new wall1,000,000/elapsed20,000; now1,001,000. UI gets no interval even though the new unplug anchor is valid. The future guard prevents bad subtraction but cannot select another row.
- **Concrete Fix:** Prefer known current-boot elapsed chronology; use saved-ID best effort only when boot/current row is unavailable:

```kotlin
@Query("""
    SELECT * FROM snapshot WHERE trigger = :trigger
    ORDER BY CASE WHEN :currentBootCount IS NOT NULL AND bootCount = :currentBootCount THEN 0 ELSE 1 END,
        CASE WHEN :currentBootCount IS NOT NULL AND bootCount = :currentBootCount THEN elapsedRealtimeMs END DESC,
        id DESC LIMIT 1
""")
suspend fun latestByTrigger(trigger: String, currentBootCount: Int? = null): SnapshotEntity?
```

Add `private val currentBootCount: () -> Int? = { null }` to RoomSnapshotStore's constructor; centralize both latest-trigger/clock DAO calls through `database.snapshotDao().latestByTrigger(trigger.name, currentBootCount()?.takeIf { it > 0 })`. AppContainer already constructs clocks before store: instantiate `RoomSnapshotStore(database, currentBootCount = { clocks.bootCount() })`. Do not sort bootCount descending or use id-only within known boot. **No schema migration required for correctness**; existing trigger-prefix index still filters. Extra index work needs profiling and a separately tested migration, not a mandatory schema bump here.
- **Regression / Verification:** Corrected query passed late same-boot insertion, prior-boot insertion, unknown/missing current boot, and elapsed-tie SQLite cases. Add Room+resolver checks and both store API agreement. This does not repair every wall-based charge-policy comparison or establish exact cross-boot chronology without metadata.

### [A-14] MEDIUM — Feature-owned caches survive changes to their measured analysis

- **Category:** Architecture / Logic Bug.
- **Location:** `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabViewModel.kt:55-72,89-99,118-129`; `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayViewModel.kt:144-173,186-200`; `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayScreen.kt:102-110`.
- **Confidence:** Both subcases confirmed through current-source JVM replay and caller traces (`validation-features.md`, U1/U3). Grouped once by root cause, not two copies of a generic stale-state warning.
- **Root Cause & Execution Trace:** (a) Sleep Lab displays A, hides while shared selection computes B, then returns directly to correctly tagged ReadyB. It updates B's metrics/slice, but `if (_state.value.hypnogram !is HypnogramUi.Ready)` retains **any** Ready chart when timeline(B) fails. (b) X-Ray retains open AppDetail on Hidden/non-Ready and, on new Ready with no matching UID/package row, falls back to the old detail. A background plug/unplug refresh can create B while the modal survives; no selector interaction behind a modal is assumed. Shared `AnalysisSession.forSelection` is correct; local caches lack the equivalent identity.
- **Failure / Exploit Scenario:** Sleep Lab mixes B's context with A's chart indefinitely after a failed read (A's own chart ticks remain A). X-Ray can show60mAh from A while B has zero apps or Failed status. Matching new app rows correctly refresh; ordinary sheet dismissal clears it.
- **Concrete Fix — Sleep Lab:** Add `private var hypnogramWindow: Pair<Long, Long>? = null`; reset it in showNotReady. Replace showReady so the atomic B state drops a different window before its independent timeline read:

```kotlin
private fun showNotReady(analysis: AnalysisUi, slice: SliceUi?) {
    hypnogramWindow = null
    _state.update { it.copy(analysis = analysis, hypnogram = HypnogramUi.Loading, slice = slice) }
}

private suspend fun showReady(analysis: IntervalAnalysis, slice: SliceUi?) {
    val named = withOwnerPackages(analysis)
    val labels = labelsFor(named)
    val window = analysis.resolved.coveredFromWallMs to analysis.resolved.coveredToWallMs
    val sameWindow = hypnogramWindow == window
    hypnogramWindow = window
    _state.update {
        it.copy(
            analysis = sleepLabAnalysis(named, labels), slice = slice,
            hypnogram = if (sameWindow) it.hypnogram else HypnogramUi.Loading,
        )
    }
    loadHypnogram(analysis.resolved)
}
```

Key covered endpoints, not ReferenceKind. Keep useful failed-refresh retention only for that exact window.
- **Concrete Fix — X-Ray:** In show(), compute `val current = analysis.forSelection(selected)`; call `closeApp()` if current is not Ready, then switch on current using existing branches. In showReady, after computing apps, call closeApp() if an existing detail UID/package has no row. Replace its detail expression with:

```kotlin
detail = current.detail?.let { detail ->
    apps.firstOrNull {
        it.uid == detail.app.uid && it.packageName == detail.app.packageName
    }?.let { detail.copy(app = it) }
},
```

At openApp entry reject `_state.value.status != XRayStatus.READY`. Use closeApp rather than just null assignment so pending plans/confirmation/override jobs are invalidated; an already executing write may finish but cannot reopen a sheet.
- **Regression / Verification:** Replays produced B slice with A hypnogram; ReadyB apps=0/Failed with retained A60mAh. Test hidden A→B with throwing timeline, same-kind changed endpoints, same-window retention, and X-Ray automatic power-boundary refresh/zero-energy/invalid/changed-package rows plus pending planning. No physical battery interval transition was tested.

### [A-15] MEDIUM — Known unplug/replug boundaries are lost when Health merges readings

- **Category:** Logic Bug.
- **Location:** `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthViewModel.kt:85-119,187-198`; `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthMappers.kt:68-97,122-215,321-332`; runtime policy `app/src/main/kotlin/com/akane/inspiration/capture/CapturePolicy.kt:81-88`.
- **Confidence:** Confirmed with actual ViewModel/mappers and plug-event replay (`validation-features.md`, U2).
- **Root Cause & Execution Trace:** Visible route observes unplug/replug and stops/restarts polling, but only Hidden clears the live buffer. Runtime power capture uses ingestHistory=false; onPlugChanged load does not ingest history. Stale open history and stored/live plugged points merge; unplugged readings are discarded before segmentation. Gap heuristics cannot override a known short unplug interval.
- **Failure / Exploit Scenario:** Hot samples at0/3s, unplug4s, replug10s, hot samples12/15s become curve40→43 and15s observed/hot, including all unplugged time. New charge should be42→43, observed3s, start10s. This affects wear/session display, **not** persisted independent CapacityEstimator results.
- **Concrete Fix:** Preserve observed session boundaries and filter both persisted/live points, not just clear the buffer. Add `plugStateKnown=false`, nullable `observedPlugMs` and `observedUnplugMs`. Replace onPlugChanged:

```kotlin
private fun onPlugChanged(nowCharging: Boolean) {
    val hadPlugState = plugStateKnown
    plugStateKnown = true
    if (charging == nowCharging) return
    if (hadPlugState) {
        val at = deps.clocks.wallMs()
        if (nowCharging) {
            observedPlugMs = at
            observedUnplugMs = null
            live.clear()
        } else observedUnplugMs = at
    }
    charging = nowCharging
    syncLive()
    if (!nowCharging && visible) viewModelScope.launch { load() }
    render()
}
```

Reset these three fields on Hidden alongside live.clear(). Replace render's span/session assignments:

```kotlin
val inferredSpan = effectiveChargeSpan(
    loaded.history?.let(::lastChargeSpan), sampled, hasLive = live.isNotEmpty(),
)
val observedSpan = observedPlugMs?.let { start ->
    ChargeSpan(start, observedUnplugMs?.coerceAtLeast(start))
} ?: observedUnplugMs?.let { end ->
    inferredSpan?.let { ChargeSpan(minOf(it.startMs, end), end) }
}
val span = observedSpan ?: inferredSpan
val session = chargeSession(span, sampled, historyPoints,
    hasLive = observedSpan == null && live.isNotEmpty())
```

The first sticky charging state must not truncate an existing charge; only observed transitions supply boundaries.
- **Regression / Verification:** Replay included the entire[4s,10s) unplug interval. Test stale/missing history and persisted old points, repeated broadcasts, first entry already charging, completed-charge retention on unplug, and hide/show binding parity. Post-fix fixture must yield3s observed and5s charge duration at15s.

### [A-16] MEDIUM — Custom interval creation/adjustment has no supported non-pointer control

- **Category:** Logic Bug.
- **Location:** `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/scrub/SynchronizedScrubbingChart.kt:232-281`; `ScrubGestures.kt:42-47,172-180` in the same directory; `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitChartCard.kt:44-54` and `CockpitViewModel.kt:181-183`.
- **Confidence:** Confirmed by all production TimeSlice-creation callers, Compose semantics/API trace (`validation-features.md`, U4; `designsystem-review.md`). No executed assistive-technology test.
- **Root Cause & Execution Trace:** Only pointerInput→SelectionCommit.Set→CommitBrush creates/adjusts a TimeSlice. Plot semantics supply descriptions and conditional Clear, not creation/endpoint adjustment, click/progress/key handling or an alternate picker. Description does not synthesize an action.
- **Failure / Exploit Scenario:** Switch/Voice Access semantic-action users cannot discover/create/adjust custom intervals through supported controls. A service's ability to inject raw gestures does not provide semantic parity; not every assistive touch pass-through is claimed impossible.
- **Concrete Fix:** Add standard localized controls below ScrubPlot, using its existing callback, rather than assuming customActions automatically provide keyboard support. Import FlowRow/Arrangement/ExperimentalLayoutApi, TextButton and existing chart.math.normalizeSelection. Invoke `SliceControls(data.window, selection, onSelectionChange, minSelectionMs, !isEmpty)` after ScrubPlot; add:

```kotlin
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SliceControls(
    window: TimeRange, selection: TimeRange?, onSelectionChange: (TimeRange?) -> Unit,
    minSelectionMs: Long, enabled: Boolean,
) {
    val minimum = minSelectionMs.coerceAtLeast(1L)
    if (!enabled || window.durationMs < minimum) return
    val active = selection?.let { normalizeSelection(it.startMs, it.endMs, window, minimum) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
        if (active == null) {
            TextButton(onClick = {
                onSelectionChange(TimeRange.of(window.endMs - minimum, window.endMs))
            }) { Text(stringResource(R.string.chart_select_interval)) }
        } else {
            val step = 60_000L
            val shrink = minOf(step, active.durationMs - minimum)
            val moves = listOf(
                R.string.chart_start_earlier to TimeRange.of(
                    active.startMs - minOf(step, active.startMs - window.startMs), active.endMs),
                R.string.chart_start_later to TimeRange.of(active.startMs + shrink, active.endMs),
                R.string.chart_end_earlier to TimeRange.of(active.startMs, active.endMs - shrink),
                R.string.chart_end_later to TimeRange.of(
                    active.startMs, active.endMs + minOf(step, window.endMs - active.endMs)),
            )
            moves.forEach { (label, next) ->
                TextButton(onClick = { onSelectionChange(next) }, enabled = next != active) {
                    Text(stringResource(label))
                }
            }
            TextButton(onClick = { onSelectionChange(null) }) {
                Text(stringResource(R.string.chart_clear_selection))
            }
        }
    }
}
```

Add in designsystem `values/strings_chart.xml` and `values-tr/strings_chart.xml`, respectively: `chart_select_interval`="Select interval" / "Aralık seç"; `chart_start_earlier`="Move start earlier" / "Başlangıcı erkene al"; `chart_start_later`="Move start later" / "Başlangıcı ileri al"; `chart_end_earlier`="Move end earlier" / "Bitişi erkene al"; `chart_end_later`="Move end later" / "Bitişi ileri al". Existing Clear string is reused. Controls provide coarse one-minute endpoint adjustment, not exact-millisecond text entry.
- **Regression / Verification:** Semantics→OnClick→CommitBrush→shared TimeSlice test, keyboard Tab/Enter, TalkBack/Switch Access discovery, edge/minimum/empty-window checks and screenshot review. There is currently no androidTest or Compose UI action harness; static screenshots do not test operability. No dependency addition was made or authorized.

### [A-17] MEDIUM — Current UID ownership is cached as if it were cosmetic metadata

- **Category:** Logic Bug.
- **Location:** `app/src/main/kotlin/com/akane/inspiration/di/CachedAppLabels.kt:55-57,73-80`; `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/app/AppFacingModels.kt:88-90`; `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayViewModel.kt:176-181`.
- **Confidence:** Confirmed by current-source positive/negative cache transition replay (`validation-features.md`, U5).
- **Root Cause & Execution Trace:** packagesForUid caches positive/empty membership in512-entry LRU with no package-event invalidation → packageForUid validates hints/fallback against stale owners → X-Ray/Sleep Lab names and package-only action targets use that result. Toolkit validates the supplied package's installation/protection, not its association with a telemetry UID it never receives.
- **Failure / Exploit Scenario:** Previously queried empty/reused UID remains unnamed; removed shared-UID fallback remains the removed package. A valid surviving cached hint is correctly selected. Stronger wrong-installed-target behavior needs additional uninstall/reuse/reinstall steps and was not device-demonstrated. This is not privilege escalation, not every new installation, and not literally forever: eviction/restart refreshes it.
- **Concrete Fix:** Remove `private val uidPackages = Lru<List<String>>(maxLabels)` and replace membership lookup:

```kotlin
override suspend fun packagesForUid(uid: Int): List<String> {
    if (uid % PER_USER_UID_RANGE < FIRST_APPLICATION_UID) return emptyList()
    return withContext(ioDispatcher) {
        source.loadPackagesForUid(uid).distinct().sorted()
    }
}
```

Keep cosmetic label/icon LRUs. This repairs newly resolved ownership, not arbitrary package-install/write TOCTOU or already pinned sheet identity. A-08's ownership mitigation must use a fresh source, not this stale cache.
- **Regression / Verification:** Empty→new owner stayed null with one PM call; shared-owner removal retained removed fallback while valid beta hint still selected beta. Test all transitions on same instance, IO dispatch, existing throw/cancellation containment and platform-appId filtering; keep cosmetic caches bounded.

### [A-18] MEDIUM — Health describes failed reads as an empty database when kernel unavailability returns null

- **Category:** Logic Bug.
- **Location:** `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthViewModel.kt:38-39,157-177,199-206`; `HealthDependencies.kt:23-24`; `app/src/main/kotlin/com/akane/inspiration/AppContainer.kt:190-200`.
- **Confidence:** Confirmed by production null-contract trace and freshly compiled current-ViewModel reproduction (`validation-health-late.md`, DH1).
- **Root Cause & Execution Trace:** Shizuku unavailable/read failure normally returns null through KernelCapacitySnapshot.from, not an exception → kernelFailed stays false → all four Room reads throw and attempt maps them to null → `kernelFailed && healthSamples == null && sessions == null && history == null && samples == null` is false → no curve/data yields Empty. Automatic refresh failure is silent and does not correct that explanation.
- **Failure / Exploit Scenario:** The UI says there is no recorded battery data and asks for setup/charging instead of explaining that data could not be read. A successful empty list is legitimately empty; it must remain distinguishable from a failed read.
- **Concrete Fix:** Replace everyReadFailed's getter with `kernel == null && healthSamples == null && sessions == null && history == null && samples == null`. In render use `loaded.everyReadFailed && live.isEmpty() -> HealthPhase.Error` so useful live observations are not hidden by storage failure. kernelFailed becomes optional cleanup, not a necessary contract change.
- **Regression / Verification:** Null kernel + four DB exceptions reproduced Empty; throwing-kernel control produced Error; same VM recovered to Ready when storage recovered. Test null-normal/throwing kernels, successful empty DB reads (Empty), partial successful data/non-null kernel/live observations (Ready), recovery and cancellation. Real disk corruption was not injected.

### [A-19] MEDIUM — Device thermal severity is falsely presented as measured charging throttling

- **Category:** Logic Bug.
- **Location:** `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthMappers.kt:41-42,217,286-301`; `HealthCards.kt:206-240`; `feature/health/src/main/res/values/strings.xml:21-24` and `values-tr/strings.xml:20-23`.
- **Confidence:** Confirmed by complete source trace, actual API35/36/37 SDK contracts and status-only mapping replay (`validation-health-late.md`, DH2).
- **Root Cause & Execution Trace:** AndroidTelemetrySource reads PowerManager.currentThermalStatus → LiveTelemetry/Room preserve device severity → Health marks `thermalStatus >= 2` → localized resources assert “The system throttled the charge” or “The system did not throttle the charge.” The API measures device-wide thermal state, not charger current limiting; LIGHT=1 is already described as throttling by Android.
- **Failure / Exploit Scenario:** CPU/GPU thermal pressure can produce a charge-specific diagnosis without charger evidence; LIGHT/no moderate observation produces an unsupported reassuring negative. Sparse/history-only observations cannot establish absence throughout a charge. No actual charger malfunction or harm is demonstrated.
- **Concrete Fix:** Preserve the deliberate moderate threshold, but replace all four resource values in both locales with device/sample-scoped statements:

| Resource | English replacement | Turkish replacement |
|---|---|---|
| health_throttle_key | Device thermal throttling (moderate+) | Cihaz ısıl kısıtlaması (orta ve üzeri) |
| health_throttle_none | No moderate-or-higher device thermal throttling was observed in the recorded readings. | Kaydedilen örneklerde orta veya daha yüksek düzeyde cihaz ısıl kısıtlaması gözlenmedi. |
| health_throttle_range | Moderate-or-higher device thermal throttling was observed between %1$s and %2$s battery. | Pil %1$s ile %2$s arasındayken orta veya daha yüksek düzeyde cihaz ısıl kısıtlaması gözlendi. |
| health_throttle_at | Moderate-or-higher device thermal throttling was observed at %1$s battery. | Pil %1$s iken orta veya daha yüksek düzeyde cihaz ısıl kısıtlaması gözlendi. |

Update comments/test names to describe the device threshold, not charge limiting. A genuine charger-throttling feature needs an independent charger-specific signal; changing the numeric threshold alone does not fix the contract mismatch.
- **Regression / Verification:** Mapping replay varied only statuses0/1/2/null and selected the current summaries; constant synthetic power is not a physical charge-control oracle. Check both locales for no charge-specific/continuous-absence claims, unknown-history behavior and moderate threshold. Render/screenshot verification remains future remediation work.

### [A-20] MEDIUM — A history successor extends a foreground sample's trusted hold to twenty minutes

- **Category:** Logic Bug.
- **Location:** `feature/health/src/main/kotlin/com/akane/inspiration/health/HealthMappers.kt:25-29,183-214,321-332`; `HealthViewModel.kt:189-198`.
- **Confidence:** Confirmed by freshly compiled original mapper and ViewModel merged-source reproduction (`validation-health-late.md`, DH3).
- **Root Cause & Execution Trace:** chargeSession deliberately retains history beyond foreground runs → wearAnalysis uses `val cap = if (a.fromHistory || b.fromHistory) HISTORY_MAX_GAP_MS else SAMPLE_MAX_GAP_MS` → temperature/voltage is held exclusively from a, yet b's history provenance raises a foreground value's limit from30s to20min → invented observed/hot/high-voltage duration reaches HealthCards. No unplug/replug is required; this is distinct from A-15.
- **Failure / Exploit Scenario:** Two hot45°C/4400mV foreground readings three seconds apart followed by a cool30°C/4000mV history point at20min produce1,200,000ms hot/high/observed, rather than3,000ms. The unobserved background interval is reported as measured exposure.
- **Concrete Fix:** Replace only the cap expression with `val cap = if (a.fromHistory) HISTORY_MAX_GAP_MS else SAMPLE_MAX_GAP_MS`. Preserve the existing skip-entire-long-gap policy (`dt <= 0 || dt > cap`), not min(dt,cap). History predecessors retain their legitimate coarse cap regardless of successor source; do not discard all mixed-source observations.
- **Regression / Verification:** Original-source end-to-end VM reproduced20min attribution, plus30,000/30,001 and1,200,000/1,200,001ms boundary cases. Test both source directions and all observed counters; after fixing, the fixture keeps20min charge duration but only3s hot/high/observed coverage.

### [A-22] LOW — Sleep Lab discards valid persisted sleep data solely because kernel clocks crossed reboot

- **Category:** Logic Bug.
- **Location:** `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabMappers.kt:242-254`; `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/diff/SnapshotDiffer.kt:383-412`.
- **Confidence:** Confirmed by Android15 TimeBase persistence source and actual differ-derived cross-boot fixture (`validation-features.md`, U6).
- **Root Cause & Execution Trace:** Stored FULL CustomMarker can span boots → same persisted BatteryStats epoch/dump timers remain comparable while raw kernel elapsed/uptime are not → differ computes valid screen-off sleep ratio → Sleep Lab's `if (!diff.validity.kernelCounters)` suppresses it. Cockpit/story use the valid measurement. CPU-frequency residency correctly remains unavailable.
- **Failure / Exploit Scenario:** Cross-boot interval with30min screen-off,25min suspend has ratio5/6 but Sleep Lab displays no sleep result. This does not count powered-off wall time as suspend or assume every OEM preserves the epoch.
- **Concrete Fix:** Replace deepSleepUi, retaining all CPU validity checks and the shared15-minute threshold:

```kotlin
internal fun deepSleepUi(diff: SnapshotDiff): DeepSleepUi {
    val sleep = diff.sleep
    val ratio = sleep?.reportableDeepSleepRatio()
    return DeepSleepUi(ratio, sleep?.deepSleepMs?.takeIf { ratio != null })
}
```

Update comment to distinguish accumulated BatteryStats timers from frequency residency/raw kernel clocks.
- **Regression / Verification:** Actual differ returned dump=true/kernel=false/ratio5/6; mapper erased it. Replace the existing test codifying the overbroad gate with that marker fixture. Changed/unknown stats epoch, missing dump, insufficient screen-off duration and raw-kernel fallback must still withhold ratio. Persistence is source-verified, not measured on redroid.

### [A-23] LOW — Historical fallback analysis is narrated as “the last” hours

- **Category:** Logic Bug.
- **Location:** `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/IntervalAnalyzer.kt:38-46`; related BatteryStoryBuilder/story resources in `core/diagnostics/src/main`.
- **Confidence:** Confirmed by current caller/resource trace (`diagnostics-review.md`, DR3); replacement independent source/replay validation completed (`validation-telemetry-final.md`).
- **Root Cause & Execution Trace:** Refresh fails to obtain a fresh endpoint → resolver uses valid older stored coverage → IntervalAnalyzer derives endsNow from ReferenceKind rather than actual covered end → story builder chooses “In the last N hours” text. Non-TimeSlice Cockpit has no obligatory endpoint/as-of warning, and cached stories also age.
- **Failure / Exploit Scenario:** Coverage01:00–05:00 shown at24:00 says “In the last4 hours,” implying20:00–24:00 was observed when it was not. Metrics can be numerically valid for the historical interval while the temporal claim is false.
- **Concrete Fix:** Conservative truthfulness repair: call `storyBuilder.build(metrics, diff.sleep, report, endsNow = false)` in IntervalAnalyzer, reusing existing neutral English/Turkish interval resources. Remove the now-unused ReferenceKind.endsNow helper/import if no remaining caller. Richer relative text needs the covered endpoint, as-of time and a tested freshness policy; ReferenceKind alone is insufficient.
- **Regression / Verification:** Test stale stored fallback with clock24:00 and01:00–05:00 coverage, both locales, normal current coverage and story aging. Assert neutral wording, not a false relative period. No metric reset/counter gates should change.

### [A-24] LOW — A hidden Sleep Lab refresh failure is replayed as a new-visit notification

- **Category:** Logic Bug / Architecture.
- **Location:** `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabViewModel.kt:46-53,78-83,135-149`; `SleepLabRoute.kt:34-57`; lifetime callers `app/src/main/kotlin/com/akane/inspiration/MainActivity.kt:55-80` and `shell/AppShell.kt:73-83`.
- **Confidence:** Confirmed by route/owner/capture trace and freshly compiled ViewModel replay using actual ViewModelStore retention (`validation-xray-late.txt`, FSXT2). No Compose-runtime tab-switch test.
- **Root Cause & Execution Trace:** Activity hosts AppShell without destination-specific ViewModel stores → tab removal disposes SleepLabRoute/effect collector, but its Activity-keyed VM survives → explicit refresh joins the ongoing automatic capture and sets userWaiting → Hidden only sets visible=false → bounded shared NonCancellable capture fails after disposal → `if (userWaiting) effectChannel.trySend(SleepLabEffect.RefreshFailed)` queues into Channel.BUFFERED → next route collector receives the old failure even if its new refresh succeeds. SaveableStateProvider is not a new ViewModelStoreOwner; lifecycle-aware state collection does not drain effects.
- **Failure / Exploit Scenario:** After refreshing, switching tabs and returning, the user sees “Couldn't refresh. Is Shizuku running?” for the previous visit despite a successful current refresh. Pure automatic failures stay quiet; Activity/process destruction is not the reproducing lifecycle. No privileged write/data loss is claimed.
- **Concrete Fix:** Scope wait/effect eligibility to the current visible visit without cancelling shared capture. Replace the Hidden branch:

```kotlin
SleepLabIntent.Hidden -> {
    visible.value = false
    userWaiting = false
    while (effectChannel.tryReceive().isSuccess) Unit
    _state.update { it.copy(refreshing = false) }
}
```

Replace the failure branch with:

```kotlin
is RefreshResult.Failed, null ->
    if (userWaiting && visible.value) effectChannel.trySend(SleepLabEffect.RefreshFailed)
```

In SleepLabRoute import `androidx.lifecycle.Lifecycle`, `androidx.lifecycle.compose.LocalLifecycleOwner` and `androidx.lifecycle.repeatOnLifecycle`; replace the effect collector with:

```kotlin
val lifecycle = LocalLifecycleOwner.current.lifecycle
LaunchedEffect(viewModel, lifecycle, refreshFailed) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SleepLabEffect.RefreshFailed -> snackbarHost.showSnackbar(refreshFailed)
            }
        }
    }
}
```

Resetting userWaiting is essential: checking visible alone still lets an old request claim a later visit. Draining also handles an already queued event. Preserve same-visit explicit join/coalescing and the immutable ownership of a genuinely new explicit request; use visit/request epochs if refresh ownership becomes more complex. Merely replacing Channel with SharedFlow or changing UI lifecycle collection does not establish the required ownership.
- **Regression / Verification:** Original-source replay cancelled the old collector, retained the VM, completed failure while hidden, successfully refreshed on return, then received the stale event. Automatic-only and X-Ray comparator controls remained quiet. Post-fix tests must cover queued-before-Hidden draining, hidden completion, hide/reenter before old completion without a new request, visible explicit failure once, same-visit join, and a genuinely new explicit request on reentry. Proposed changes have not been executed as a fix.

## 3. Dead Code, Redundancy & Technical Debt Cleanup

**No class, function or dependency was established safe to delete by this audit.** No cleanup was performed. A bounded application/build-logic search found no TODO/FIXME/XXX/HACK markers. No module cycle or feature-to-feature dependency was found.

Source inspection noted redundant calibration-input reads/overwritten fields and unused-looking injection as possible follow-up cleanup, but did not prove a safe end-to-end removal; they are not defects or deletion instructions. Vendored kit/skill/configuration internals are excluded from substantive application assessment. Nine lint warnings are listed below, not disguised as application bugs or permission to upgrade.

## 4. Prioritized Remediation Roadmap

**Phase 1 — Immediate, isolated correctness/recovery fixes:** A-01 with dependency-faithful test ordering; A-21 owner-user-only action containment; A-02 shared kernel cancellation boundary; A-03/06 preserve privileged undo; A-04 autonomous holder cap; A-07/09 stop publishing missing observations as valid; A-10 optional recording containment. Require fault-first regression tests before accepting patches. No fix is applied by this report.

**Phase 2 — Structural/concurrency hardening:** A-05 durable operation admission/uncertainty; A-11 retriable prerequisite classification including persisted-row repair; A-12 atomic scheduling; A-13 monotonic capture recency. Establish keyed derived UI state/session boundaries (A-14/15), fresh owner lookup (A-17) and accessible equivalent interval controls (A-16). Correct Health availability, thermal interpretation and left-held provenance (A-18/19/20).

**Phase 3 — Architecture and test coverage:** Introduce explicit per-source/per-UID observation provenance rather than one universal numeric-valid flag; capture owner/install identity for definitive historical UID-reuse discrimination (A-08); give toolkit lifecycle/undo states explicit ownership and generation invariants. Full multi-user actions need end-to-end namespace identity and migrated audit keys, not removal of A-21's containment alone. Add source-faithful Shizuku integration, Room failure/recovery, repeated process-lifetime uncertainty, true semantics action, multi-profile target and physical charge tests. Address valid cross-boot sleep, historical-story wording and visit-scoped effects (A-22/23/24) with localized/lifecycle assertions. Profile long-visible Health buffers, large histories, FD/Binder-worker counts and power before making performance claims. Follow normal two-active-Gradle policy during separately authorized remediation; do not change SDK/toolchain/dependencies opportunistically.

## Coverage and Deterministic Tooling

### Source scope and architecture

Twelve Android modules plus build-logic/current build configuration were inventoried: **206 main Kotlin/Java files**, **128 JVM test/fixture files including testDebug**, **22 screenshot source files**, **no androidTest source files**. Full production-source passes were reported by subsystem reviewers; test manual coverage is bounded (some enumerated/targeted rather than every assertion). The entire native unit/screenshot suite was executed. Generated outputs serve as evidence, not source-review scope.

| Subsystem | Source review and evidence | Remaining important limit |
|---|---|---|
| app |27 main files,20 tests; DI, manifests, receivers/jobs/FGS, shell entry and clocks (`app-review.md`) | Real boot/process death/permission revoke not exercised |
| IPC / hidden stubs | All non-toolkit IPC, AIDL/DTOs/service gates;12 Java stubs; actual Shizuku bytecode (`ipc-review.md`) | API31–34/OEM hidden API and FD/load compatibility |
| telemetry |23 non-toolkit Kotlin files plus four proto schemas, targeted tests/caller traces (`telemetry-review.md`) | Not every test assertion manually read; physical driver/disk failures not injected |
| toolkit core | Actions/gateway/executor/controller/typed inputs, shell gates, restore/retention and tests (`toolkit-review.md`, `validation-toolkit.md`) | Privileged writes intentionally not run on device; crash pair simulated |
| database | All four main/test files, schemas1–4, DAO/store/retention/migrations (`database-review.md`) | Large-history performance; real Room failure and migration instrumentation |
| diagnostics | All production, rules/dictionary/story;72 entries checked for identity/localized empties (`diagnostics-review.md`) | No real populated-alarm/attribution oracle |
| designsystem |39 main,11 test,10 screenshot files and6 XML resources (`designsystem-review.md`) | No assistive-technology/keyboard runtime tests |
| dashboard / health |All20 production files, resources and targeted tests (`features-dashboard-health-review.md`) | No physical charging/wear/long-session profile |
| sleeplab / xray / toolkit features |Completed source/mappers/routes/actions pass and targeted tests (`features-sleep-xray-toolkit-review.md`) | No physical multi-profile, populated hypnogram, overlay or pixel-by-pixel visual review |
| build / dependency graph |Version catalog, conventions, module edges, manifests/R8/wrapper (`tooling-evidence.md`) | No complete build/test/native/transitive supply-chain audit |

Graph: app→features/core; features→designsystem/telemetry/diagnostics; diagnostics→telemetry→IPC; database→telemetry; IPC→compileOnly hidden stubs; designsystem leaf. No feature→app/feature→feature cycle was found. No network application layer exists in reviewed wiring.

### One shared native baseline

```text
./gradlew testDebugUnitTest lintDebug validateDebugScreenshotTest :app:assembleDebug :app:minifyReleaseWithR8 --console=plain -q --max-workers=8 --rerun-tasks
exit 0
```

| Module | Unit tests | Screenshot validations |
|---|---:|---:|
| app |115|8|
| core/database |47|—|
| core/designsystem |109|55|
| core/diagnostics |150|—|
| core/hidden-api-stub |0|—|
| core/shizuku-ipc |226|—|
| core/telemetry-engine |285|—|
| feature/dashboard |41|13|
| feature/health |66|12|
| feature/sleeplab |61|20|
| feature/toolkit |68|36|
| feature/xray |70|24|
| **Total** |**1,238**|**168**|

Zero failures/errors/skips. Screenshot references were **not** updated; stale screenshot-update XML was excluded. Zero lint errors/fatals and nine warnings: target36 versus compile37; Gradle9.8.1; annotation1.11.0; Kotlin Android/Compose/Parcelize2.4.21 (three); HiddenApiBypass6.2; Robolectric4.17; resource shrinking off. These are observations, not confirmed vulnerabilities or upgrade authorization. Toolchain: Gradle9.8.0/AGP9.4.1/Kotlin2.4.20, targetJDK17 on inspected host OpenJDK21.0.12.1, compile37/target36/min31; Room2.8.5, Compose1.12.1/Material3 1.4.0, Shizuku API13.1.5, coroutines1.11.0, protobuf4.36.2.

Raw Gradle was deliberately used because the normal helper writes protected `.claude/kit/logs`. Real exits were preserved. Independent reviews ran concurrently **without an artificial subagent cap**; two-active-Gradle/worker limits remained. No duplicate whole-project baseline was run by workers. Scratch compiler/JVM/SQLite experiments test current erroneous behavior; full commands/oracles and harness cleanup corrections are retained in validation evidence. The telemetry replacement executed18 original-class assertion checks (pure data/live flow/analyzer), **not18 new JUnit or post-fix tests**. Proposed fixes do not inherit the baseline's green status.

### Security/dependency checks actually run

`./gradlew :app:dependencies --configuration releaseRuntimeClasspath --console=plain -q --max-workers=4` exited0. OSV batch lookup submitted **only118 public resolved Maven coordinates**, received118 responses, no matches. This excludes unparsed/shaded/native/build/debug/test and unknown-to-OSV issues. Native osv-scanner/gitleaks/trufflehog were unavailable. A redacted heuristic secret-pattern scan of440 allowed current-source/build text files had zero hits; sensitive files, symlinks, binary/generated/history/vendor material were excluded. No credentials were printed. These are bounded checks, not proof of no vulnerabilities/secrets. Wrapper checksum and validateDistributionUrl are configured; release reflective-construction keep rules were inspected and R8 passed.

### Mobile MCP / redroid smoke

Mobile MCP handled device discovery, launch, hierarchy and input on **localhost:5555**, Android15/API35 redroid; ADB was limited to read-only metadata/log/APK-digest support. All five screens rendered/reachable; harmless refresh/capture/reference changes worked; Shizuku showed Binder active/UID2000. No smoke-window crash or confirmed UI defect; original Since unplugged selection restored and app left on Cockpit.

Installed package0.2.1/versionCode3, non-debuggable. APK SHA256 `877ec85525c100a1c5d1f47a4990272fd82a06620767efccb69c5487937b2e6d`; extracted v2 certificate SHA256 `1d344dd2e7269d2c6b4274881799c2d87f41d40da8ebaace7407467ec1f3de60` is not independently anchored to a trusted release certificate. **Pinned-source equivalence unverified.** Smoke window02:05:35–02:11:45 UTC. Battery-less container zeros/dashes cannot validate real charge, wear, energy attribution, alarm counts, populated hypnogram or kernel health accuracy. No reinstall/clear, permissions/settings, FGS toggles or privileged toolkit writes. No Binder-death/reboot/API matrix/load test. Old pre-window LOW_MEMORY records were not attributed to this smoke. Full provenance/actions in `device-review.md`.

## 5. Audit Isolation and Preservation

- **Classification: contaminated/not blind.** Before source discovery, startup automatically supplied generated `.claude/.codebase-info/INDEX.md`, memory-index summaries and recent commit subjects containing prior-assessment information. This was disclosed and recorded before substantive investigation in `contamination-ledger.md`. Separate non-fork native contexts avoid parent-transcript inheritance but receive those same mandatory inputs; they are independent validators, not strictly blind investigators.
- No previous-report body, PROJECT/MAP/PROGRESS retrieval, board/ticket body, prior transcript, history/log/blame/show or linked memory body was deliberately read before freeze. Required operating/audit references and current source/test comments were inputs; comments/tests exposing prior correction identifiers are disclosed in each evidence ledger. Re-injected startup context after compaction does not restore blindness.
- Sidequest effective project plugin false; no configured Sidequest hooks found. No hooks/settings/provider/model overrides, kit maintenance, map updates or remediation performed. Normal bootstrap/board loading remains deferred by the audit protocol.
- Initial dirty file `.claude/settings.json` was preserved. Pinned source HEAD unchanged at checks; application/build source diff empty. No application fixes, dependency/SDK changes, commits, pushes, version bumps or releases.
- Two source reviewers hit HTTP413: the broad five-feature worker failed before durable coverage and was replaced by two completed bounded reviewers; telemetry investigator had already saved its complete source evidence/replays. The initial telemetry validator later also failed before persisting final verdicts; its interim messages are not a completed validation report. Its bounded replacement completed final telemetry validation, and both late-feature validators completed before freeze. No completed coverage is attributed solely to a failed worker.
- Guard `begin` preserved original report/hash and protected paths under `.git/audit-history/run-rf65ec6x`; interim `check` passed. Only report/evidence and scratch inputs were authored; native build/compiler generated outputs and caches are expected verification byproducts. Vendored android-kit-codex implementation/tests were listed by recon but **excluded from substantive audit**, not silently claimed reviewed. Sensitive-file exclusions remain in force.
- **Freeze boundary:** this finalized independent assessment is the input to guarded freeze, before any prior-report body is opened. The guard creates `independent.md` and SHA-256 `freeze.json` under `.git/audit-history/run-rf65ec6x/`. The separately appended Audit Delta records actual freeze evidence and post-freeze comparison. Frozen independent findings must not be rewritten to absorb prior-report or post-freeze discoveries; publication appends the delta without modifying independent bytes.


## Audit Delta (post-freeze comparison)

### Boundary, integrity and comparison inputs

The independent assessment was finalized and **successfully frozen before the archived report was opened**. This section is a post-freeze comparison, not an extension or rewrite of the independent findings.

- Current source: `bddeec0a18656f587981bf45503a16acfaa6e808`, branch `main`; application/build source unchanged. The pre-existing `.claude/settings.json` modification was preserved.
- Session: `.git/audit-history/run-rf65ec6x/`.
- Frozen independent SHA-256 (`freeze.json`): **`2ab1455debbee9ddf0b68c9199687b00df2f2edafb6022ee822c1db4c86ef0b6`**.
- Archived original SHA-256 (`state.json`): **`4f1f23f9f2a921b6247129f0f27695e734f193eefd8211bb560bb66b7cab8b43`**.
- Existing report spelling selected by the guard: **`AUDIT_REPORT.md`**. No competing case variant was created.
- Before freeze, guard `check` and the application/build source diff passed. Guard `freeze` then reported: `Independent report frozen. Only now begin the prior-report delta phase.`
- Prior material first read after freeze: this session's `previous-report.md`. It describes the earlier audit at `60fe3d5ac2e31451498e46eb8f6b1e1c1d92a780`, includes in-place remediation tags and a later remediation log, and embeds an even older A/B-series comparison. Those historical assertions are inputs to comparison, not independently certified evidence of resolution.
- No git history, prior transcript, linked memory body, project-progress document or board was needed for this comparison. The earlier A/B reports were not separately retrieved.
- The overall run remains **contaminated/not blind**. Freezing prevents changes to this assessment after reading the prior report; it does not undo mandatory startup exposure.

### Classification method

Matching is by **root cause and demonstrated scope**, not finding number or historical `FIXED` labels. `fix present in source; verification limited` means the named repair is visible in current source and relevant existing tests passed in the shared baseline; it does **not** mean all platform behavior, every related failure path or the old reproduction was rerun. None of the old reproduction exports was retrieved or executed in this comparison. `independently rediscovered` identifies a surviving same-root variant established **before** freeze. Absence from the 24 new findings is never treated as proof of resolution.

The current baseline was run once before freeze:

```text
./gradlew testDebugUnitTest lintDebug validateDebugScreenshotTest :app:assembleDebug :app:minifyReleaseWithR8 --console=plain -q --max-workers=8 --rerun-tasks
exit 0; 1,238 unit tests; 168 screenshot validations; 0 lint errors, 9 warnings
```

Post-freeze checks were read-only source inspection and scoped `rg` caller/fix-site searches under `app/src/main`, `core/*/src/main` and `feature/*/src/main`. No further Gradle gate, device mutation, remediation or release was run. Two initial search paths for design-system formatter/hypnogram files were wrong; `rg --files core/designsystem/src/main/kotlin` located their actual `component/format` and `chart/hypnogram` paths, which were then checked. A search-path error is not an application failure.

### Per-finding comparison with the archived F-series

For compact references below, these are the exact current-source files:

| Name | Current path |
|---|---|
| Manager | `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/ShizukuManagerImpl.kt` |
| Source | `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/source/AndroidTelemetrySource.kt` |
| Actions | `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/toolkit/ToolkitActions.kt` |
| Executor | `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/ToolkitExecutor.kt` |
| Store | `core/database/src/main/kotlin/com/akane/inspiration/database/store/RoomSnapshotStore.kt` |
| DAOs | `core/database/src/main/kotlin/com/akane/inspiration/database/Daos.kt` |
| Container | `app/src/main/kotlin/com/akane/inspiration/AppContainer.kt` |
| XRay VM | `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayViewModel.kt` |
| Sleep VM | `feature/sleeplab/src/main/kotlin/com/akane/inspiration/sleeplab/SleepLabViewModel.kt` |
| Cockpit VM | `feature/dashboard/src/main/kotlin/com/akane/inspiration/dashboard/CockpitViewModel.kt` |

| Prior finding / root | Classification at the current pin | Current evidence, surviving scope and verification limit |
|---|---|---|
| **F-01 — Landed-elsewhere write lost from recovery** | **fix present in source; verification limited** | Actions:816–842 includes observed changed FAILED rows through `changedTarget`/`landedElsewhere`; non-restore handling no longer relies only on DONE. **Related unclosed sibling A-03:** a partially effective *restore* is still excluded by `restoresId` at :751 and :852 and can lose its recovery target. Original ordinary-write repair must not be described as a complete undo-state-machine repair. Independent K1 replay proves A-03, not every old F-01 case. |
| **F-02 — Confirmation retargets a different package** | **fix present in source; verification limited** | XRay VM:42–43 defines immutable PendingAction(request, uid, pkg); :289 pins it and :307–313 confirms that target; :168–170 keeps the open sheet's package. This repairs the original package-at-confirm-time substitution. **A-21 is different:** the pinned UID never reaches package-only privileged operations, so an explicitly selected secondary-user row acts on owner0. **A-14** separately covers obsolete analysis data retained in a sheet. No real confirmation race/device write was run. |
| **F-03 — Permission state cannot recover in-process** | **fix present in source; verification limited** | Manager:144–165 re-evaluates permission results and exposes refresh; `app/src/main/kotlin/com/akane/inspiration/MainActivity.kt:36` calls refresh on start. Current tests passed; this audit intentionally did not deny/regrant device permissions or certify the archived runtime experiment anew. A-01 concerns a distinct UserService teardown/rebind ordering failure. |
| **F-04 — Cold binder arrival misses capture with no retry** | **fix present in source; verification limited** | Manager:187–225 waits for acquisition/Ready within the caller budget; Container:280–285 clears backoff and calls refreshIfLastFailed on Ready. `app/src/main/kotlin/com/akane/inspiration/di/RefreshCoordinator.kt:87–95` preserves retry eligibility separately from backoff. No delayed cold-start binder delivery was injected on-device. |
| **F-05 — Blocking Binder bypasses deadline/history progress/fallback** | **independently rediscovered** | **A-02** independently proves the same blocking-call/deadline root in kernel-only routes (Source:108–124 and Container:190–194). Their direct readKernelBattery bypasses binderCall and the manager's timeout ends before the IO block. The old full/history repairs *are present*: Source:47–60,138–162 use binderCall and page sinks; `app/src/main/kotlin/com/akane/inspiration/capture/CapturePolicy.kt:92` protects light fallback with NonCancellable. Do not claim those full-capture paths were shown broken again; the remaining kernel-only path was reproduced with a stalled current-source manager/source transaction, not physical driver failure. |
| **F-06 — Wall time used for retention/hold/screen-on elapsed time** | **fix present in source; verification limited** | `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/store/RetentionPrune.kt:20–25` derives a boot-frame bound and is wired at Container:130 and `app/src/main/kotlin/com/akane/inspiration/capture/ChargingCaptureRunner.kt:64`. Container:210 injects monotonic hold time; `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/status/UnplugSummary.kt:130` uses elapsedRealtime for screen-on. **A-13 is another site**, wall-sorted latest-trigger selection, not proof that this retention repair is absent. No host-shared device clock was changed. |
| **F-07 — Override recompute cost grows through closed targets/uncached gate** | **fix present in source; verification limited** | Executor:12,241–251 has a 30-second monotonic protection cache, invalidated by writes; Actions:851–853,879–881 rejects provably closed candidates before live reads; :940–977 prunes closed chains. MAX_ROWS remains a full-history read, so this is not a measured long-install latency guarantee. A-03 shows a distinct restoration/retention correctness hole; no post-fix process-spawn benchmark was run here. |
| **F-08 — Uncontained feature/supervisor/reconcile exceptions crash or stop recovery** | **fix present in source; verification limited** | `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitViewModel.kt:121,144–145,318,393,499–511` and XRay VM:234,281,333–336 contain the named operations; Actions:1294–1297 adds a supervisor exception handler; `app/src/main/kotlin/com/akane/inspiration/di/ReconcileOnReady.kt:35–42` catches per-event failures while preserving cancellation. **A-04:** logging prevents the former unhandled crash but does not restart a failed hold supervisor or enforce its later cap. This narrower retention failure was independently reproduced; one handler is not full recovery. |
| **F-09 — Process-name hint used as installed package identity** | **fix present in source; verification limited** | XRay VM:176–181 and Sleep VM:108 resolve packageForUid through the shared owner helper, rather than accepting the hint as identity. `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/app/AppFacingModels.kt:88–90` strips a process suffix and verifies membership. **A-17** independently shows stale membership caching, **A-08** shows highest-drain hints used as historical identity in the differ, and **A-21** shows user namespace loss. These are not proof that the old raw-suffix UI path remains unchanged. |
| **F-10 — Deep-sleep diagnosis without minimum screen-off window** | **fix present in source; verification limited** | `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/story/BatteryStoryBuilder.kt:32` uses shared reportableDeepSleepRatio; current feature mappers use that threshold too. **A-22** is excessive withholding of otherwise comparable cross-boot sleep data, not missing minimum duration. Existing unit/screenshot tests passed; no physical suspend measurement. |
| **F-11 — Obscured controls give no feedback/trap Cancel** | **fix present in source; verification limited** | `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayTouchGuard.kt:35–51,60–74,96–103` and its Toolkit counterpart notify once per gesture; XRayScreen:88 and ToolkitScreen:62 provide the snackbar callback. `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitDialogs.kt:80–84` and `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayDetail.kt:587–591` separate Cancel from the destructive guard. A real overlay/touch/scroll test remains unexecuted. |
| **F-12 — Truncated protected-package enumeration fails open** | **fix present in source; verification limited** | `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/Inspection.kt:72–98` requires out.complete for roles/secure settings, and deviceAdmins receives policy.truncated. `core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/toolkit/CommandRunner.kt:40–41` defines complete as ok && !truncated. No many-service device configuration or privileged action against it was attempted. |
| **F-13 — Same-value write hides an existing override** | **fix present in source; verification limited** | Actions:879–888 compares to chainOriginal, and :904–907 no longer rejects a non-Doze row merely because after==before. This is distinct from A-03's partially effective restore chain. Current toolkit tests passed; the old exported reproduction was not rerun. |
| **F-14 — Unsupported older Shizuku server reported Ready** | **fix present in source; verification limited** | Manager:332–338 rejects known server versions below MIN_SERVER_VERSION=13 (:470) with UnsupportedServer. This audit's device has Shizuku13.5; no v11/v12 real-server check or API matrix was performed. |
| **F-15 — Historical story says “the last N hours”** | **independently rediscovered** | **A-23** was source-traced and independently replayed before opening this report. `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/IntervalAnalyzer.kt:38–46` still derives endsNow from selection kind, not covered endpoint. The kind-based repair makes TimeSlice/SinceScreenOff neutral, but a stale fallback for SinceUnplugged/Last24h/CustomMarker still claims current coverage, and cached narration ages. Same temporal-truthfulness root, surviving case narrower than “all slices are wrong.” No Android story-rendering replay was executed. |
| **F-16 — Shared Ready is paired with a different selection** | **fix present in source; verification limited** | `core/diagnostics/src/main/kotlin/com/akane/inspiration/diagnostics/analysis/AnalysisSession.kt:31,41–42,88` tags Ready and supplies forSelection; Cockpit VM:74, XRay VM:146 and Sleep VM:63 use it. **A-14** finds missing measurement identity in derived feature caches *after* this shared guard, not an absent Ready selection tag. Existing baseline plus pre-freeze source traces establish these distinct scopes; no complete UI frame-race test. |
| **F-17 — Concurrent history ingest moves cursor backwards** | **fix present in source; verification limited** | `core/telemetry-engine/src/main/kotlin/com/akane/inspiration/telemetry/capture/SnapshotCapturer.kt:47,78` shares historyIngestLock across ingesting captures; Store:143–149 enforces same-session forward movement inside a transaction. Cross-session/process-lifetime behavior and waiting-lock budgets are not certified by the passing existing tests; no new crash/concurrency experiment was added after freeze. |
| **F-18 — Live current falls back to process-start sysfs sample** | **fix present in source; verification limited** | Source:113–119 copies current/voltage only from the current kernel snapshot and fresh BatteryManager reading; it does not substitute calibrationInputReader's cached raw sample. **A-02** affects completion of that kernel read; **A-10** affects optional sample persistence after emission. Neither demonstrates the old stale-value fallback remains. No physical sensor dropout was induced. |
| **F-19 — Timestamp/existence/status queries overfetch snapshots** | **fix present in source; verification limited** | Store:109–120 exposes clock-only/latest-trigger and bounded unplug-window routes; `app/src/main/kotlin/com/akane/inspiration/status/StatusNotificationService.kt:105` uses unplugWindowSnapshots. Current schema v4 includes the trigger index. **A-13** demonstrates incorrect wall ordering in the narrowed query, a correctness issue rather than the old overfetch. No large-dataset allocation/latency benchmark or real migration instrumentation. |
| **F-20 — Wakelocks inserted individually instead of batched merge** | **fix present in source; verification limited** | Store:67 calls mergeWakelocks with a list; :475–517 builds/group-merges keys, while DAOs:65–75 performs list insertion and conflict handling transactionally. This is visible batching repair, not proof of zero per-conflict work or measured speedup. Existing database tests passed. |
| **F-21 — Timeline/chart shaping on Main** | **fix present in source; verification limited** | Container:158 uses Dispatchers.Default for timeline; Cockpit VM:46,169 and Sleep VM:40,119 use injectable Default shaping dispatchers. This does not establish all label resolution or every render cost is off Main. No frame timing/long-history profiling was performed. |
| **F-22 — Locale-insensitive monogram uppercase** | **fix present in source; verification limited** | `feature/toolkit/src/main/kotlin/com/akane/inspiration/toolkit/ToolkitApps.kt:181,191` and `feature/xray/src/main/kotlin/com/akane/inspiration/xray/XRayScreen.kt:424,434` pass currentLocale to monogram uppercase. Baseline tests/screenshots passed; no device per-app Turkish locale switch was performed. |
| **F-23 — Hypnogram locale mismatch / hard-coded 24-hour style** | **fix present in source; verification limited** | `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/chart/hypnogram/SleepHypnogram.kt:166–174,454–455` passes LocalLocale into formatting, matching `core/designsystem/src/main/kotlin/com/akane/inspiration/designsystem/component/format/TelemetryFormat.kt:202–213`. The latter still deliberately defines HH:mm (:33) and documents its single date-time style (:129). The archive explicitly skipped system hour-cycle support as a product decision; it is **not** fully fixed or a newly independently confirmed defect here. No product decision was changed. |

**Summary:** 23 prior F-series findings compared: **2 independently rediscovered surviving variants** (F-05→A-02, F-15→A-23), **21 fix present in source; verification limited**. These are classification counts, not a claim that 21 whole defect families are comprehensively resolved. A-03/A-04/A-08/A-14/A-17/A-21 explicitly show remaining boundary failures near repaired areas. No historical item receives “verified resolved within stated checks” solely from an old FIXED tag or a green aggregate baseline.

### Older comparisons, cleanup and residuals

The archive also contains A/B-series tables, skipped cleanup suggestions, test-debt notes and historical remediation assertions. Their **inherited historical status is not re-certified** by copying them here.

- Older A/B items not directly covered by the F-series/current traces: **not assessed/insufficient evidence**. No earlier report was separately recovered from history; no complete historical regression matrix was run.
- Prior cleanup/API removals, debug-only AIDL surface, hourly aggregates/capture-miss product decisions, history iterator redesign and similar skipped work: **not assessed/insufficient evidence** as new defect/removal claims. The independent report found no safely verified deletion and did not authorize cleanup. Continued presence or omission from its findings establishes neither safety nor resolution.
- API31–34/OEM hidden history compatibility, physical voltage/current/suspend behavior, overlay interactions, full multi-user action correctness, real driver/disk failures, long-session power/performance and MigrationTestHelper instrumentation remain limited as stated in the independent coverage ledger.
- The current bounded OSV-coordinate lookup is new tooling evidence relative to the archived audit's unavailable advisory scanner; zero matches is not proof of an uncompromised or vulnerability-free dependency tree. No repository source, credentials or app records were sent with that public-coordinate query.

### Baseline comparison and post-freeze discoveries

The archived **initial** audit counted 1,083 unit tests; its **post-remediation** log counted 1,238 unit tests and 168 screenshots with nine lint warnings. This run independently reran the current unchanged source and obtained **1,238 / 168**, zero failures/errors/skips, zero lint errors and nine warnings, plus debug assembly and release R8. Matching totals do not establish identical test semantics or exhaustive fix correctness.

This run's Mobile MCP/redroid check was deliberately nondestructive and covered the installed release 0.2.1 only. Its equivalence to the pinned current source is unverified, unlike any different build provenance claimed by the old report. Archived permission/settings experiments were **not** repeated or inherited as current runtime proof.

**No new defect is added after freeze.** All 24 independent findings, including the two rediscovered variants above, were finalized before the archived report was opened. The old report's claims have not been imported as new findings. Frozen bytes remain authoritative; this delta changes no severity, finding count, proposed fix or independent verification claim. No application fixes, commits, pushes, releases, kit/map maintenance or device privileged writes were performed.

## 6. Atomic remediation log — 2026-10-19

This section records **subsequent requested remediation**, not changes to the frozen independent assessment or historical delta above. Status means the scoped checks below passed and the fix was committed on `audit-remediate-d-20261019`; it is not a zero-regression or physical-device guarantee. The final combined build/test/lint/screenshot/debug/R8 gate is **PENDING**. No release, push, APK delivery or privileged device mutation has been performed during remediation. User-dirty `.claude/settings.json` is preserved.

<!-- remediation-batch-status -->
**23/24 findings integrated; A-08 remains in progress.**

| ID | Verified integration commit | Files changed (within their existing modules) | Verification job(s), all final exits 0 |
|---|---|---|---|
| A-01 | [FIXED in 601fb92] | ShizukuManagerImpl.kt; ShizukuManagerImplTest.kt | b5tlf98h9: IPC manager + ready-wait tests; IPC lint |
| A-02 | [FIXED in f849891] | AppContainer.kt; AndroidTelemetrySource.kt; source deadline/kernel tests; HealthKernelFallbackTest.kt | b9rg17ege: source, Health fallback, ChargeJobRun tests; telemetry/app lint |
| A-03 | [FIXED in 5731392] | ToolkitActions.kt; ObservedChange/Retention/ResetAll tests | b5m4ycfci: ToolkitActions regression classes; telemetry lint |
| A-06 | [FIXED in 1845040] | ToolkitExecutor.kt; ToolkitExecutorTest.kt | b6iyg4ntq: executor/package-name/service-toolkit tests; IPC lint |
| A-07 | [FIXED in dca692f] | SnapshotDiffer.kt/test; DiagnosticRuleEngineTest.kt; XRayMapperTest.kt | bjlgu03b1: differ/diagnostics/XRay mapper tests; telemetry lint |
| A-09 | [FIXED in 719ba02] | DumpParser.kt/test; AndroidTelemetrySourceDeadlineTest.kt | bg0wlfvel: parser/reader/source/capturer/differ + lint; b9pd6q68r: merged parser/source + lint |
| A-11 | [FIXED in 76f7561] | ChargingCaptureRunner.kt; ChargeHealthPipelineTest.kt | bvwtiav2a: pipeline/runner/job/estimator tests; app lint |
| A-15 | [FIXED in ab6a2a5] | HealthViewModel.kt; HealthViewModelTest.kt | bj6o0l40q: complete Health unit task + lint |
| A-17 | [FIXED in 62a548f] | CachedAppLabels.kt; CachedAppLabelsTest.kt | bz35n187y: resolver tests; app lint |
| A-21 | [FIXED in ab06895] | XRay VM/models/detail; EN/TR resources; target tests/fixtures; previews; three new references | b9hbwtpo0: VM tests/lint/render; bnnmmd28v: screenshot validation; three PNGs inspected |
| A-22 | [FIXED in b2dbe9c; reference follow-up 7ac20eb] | SleepLabMappers.kt/test; CounterReset PNG | be36wu0v4: mapper tests/lint; bjixwnaye: scoped render; b4j388de5: mapper/VM tests/lint/screenshots; expected/actual PNGs inspected |
| A-04 | [FIXED in f4a318b] | ToolkitActions.kt; ToolkitActionsHoldSupervisorTest.kt | bk3xxb145: supervisor/robustness tests; telemetry lint |
| A-10 | [FIXED in 8e0f1ef] | LiveTelemetry.kt; LiveTelemetryTest.kt | bsa110egx: live/attempt/Health VM tests; telemetry lint |
| A-12 | [FIXED in 56435dc] | ChargingCaptureScheduler.kt; ChargingCaptureJobService.kt; ChargeJobRunTest.kt | bok4g5mxi: scheduler/policy/job/runner tests; app lint |
| A-13 | [FIXED in 6905ca7] | AppContainer.kt; Daos.kt; RoomSnapshotStore.kt; database/store tests | bqfxfvju7: database/store/resolver/job/runner tests; database/app lint |
| A-14 | [FIXED in 5827ab9] | SleepLab VM/fixtures/tests; XRay VM/target tests | b7hjjdk5z: feature VM tests/lint/screenshots; independent source review |
| A-16 | [FIXED in 6e5ddee] | SliceControls.kt/test; scrub chart; EN/TR chart resources; chart/dashboard previews; 22 current PNGs | by5rh5aqu: controls/gesture/dashboard tests + lint/render; b8csf1bfj: forced dashboard update; bc085t946: DS/dashboard validation; main/independent PNG inspection |
| A-18 | [FIXED in 2dcc833] | HealthViewModel.kt; HealthViewModelTest.kt | bxnt1k7t0: complete Health unit task + lint |
| A-05 | [FIXED in 0acf198] | ToolkitActions.kt; ToolkitActionsDexoptAdmissionTest.kt | b0ita6kb7: all ToolkitActions tests + telemetry lint; main/independent source review |
| A-19 | [FIXED in 4f86e49] | Health mappers/state/preview/tests; EN/TR strings; six PNGs | btwcgr2ro: full Health unit/lint/render; bpivnb3qi: screenshot validation; main/independent source and actual PNG review |
| A-20 | [FIXED in d702152] | HealthMappers.kt; HealthMappersTest.kt | bemj3a9ak: complete Health unit task + lint; main source/16-case boundary review |
| A-24 | [FIXED in b635997] | SleepLabRoute.kt; SleepLabViewModel.kt; SleepLabViewModelTest.kt | b4j388de5: mapper/VM tests/lint/screenshots; main/independent source review |
| A-23 | [FIXED in ffe8073; preview follow-up 30c5a0c] | IntervalAnalyzer.kt; BatteryStoryBuilder.kt; analyzer/story tests; Cockpit preview + seven PNGs | bl7hlo1r0: analyzer/session/builder/writer tests + diagnostics lint + app screenshots; bz3t05i2u: dashboard render/validation; actual EN/TR PNGs inspected |

A-08 remains **VERIFICATION PENDING**, not fixed. Its independently reviewed design requires trusted capture-time identity at both endpoints and an additive Room migration; current package membership alone is not accepted as historical continuity proof. Runtime and Room source reviews are in progress while regressions are implemented.
<!-- /remediation-batch-status -->

### Scoped verification commands

Every job used its isolated worktree's absolute `gradlew -p <same-worktree>`, followed by the task/selector arguments below and `--console=plain -q --max-workers=4`. No more than two Gradle invocations ran concurrently. Job outputs are retained under `/tmp/claude-1000/-home-akane-projects-inspiration/d97c8ca1-83fc-45d8-860a-63aba97fe73e/tasks/<job>.output`; worker paths and commands are recorded in the coordinator ledger.

- **A-01:** `:core:shizuku-ipc:testDebugUnitTest --tests 'com.akane.inspiration.ipc.ShizukuManagerImplTest' --tests 'com.akane.inspiration.ipc.ShizukuManagerReadyWaitTest' :core:shizuku-ipc:lintDebug`.
- **A-02:** `:core:telemetry-engine:testDebugUnitTest --tests 'com.akane.inspiration.telemetry.source.AndroidTelemetrySource*Test' :feature:health:testDebugUnitTest --tests 'com.akane.inspiration.health.HealthKernelFallbackTest' :app:testDebugUnitTest --tests 'com.akane.inspiration.capture.ChargeJobRunTest' :core:telemetry-engine:lintDebug :app:lintDebug`.
- **A-03:** `:core:telemetry-engine:testDebugUnitTest --tests 'com.akane.inspiration.telemetry.toolkit.ToolkitActions*Test' :core:telemetry-engine:lintDebug`.
- **A-06:** `:core:shizuku-ipc:testDebugUnitTest --tests 'com.akane.inspiration.ipc.toolkit.ToolkitExecutorTest' --tests 'com.akane.inspiration.ipc.toolkit.PackageNamesTest' --tests 'com.akane.inspiration.ipc.service.TelemetryUserServiceToolkitTest' :core:shizuku-ipc:lintDebug`.
- **A-07:** `:core:telemetry-engine:testDebugUnitTest --tests 'com.akane.inspiration.telemetry.diff.SnapshotDifferTest' :core:diagnostics:testDebugUnitTest --tests 'com.akane.inspiration.diagnostics.rules.DiagnosticRuleEngineTest' :feature:xray:testDebugUnitTest --tests 'com.akane.inspiration.xray.XRayMapperTest' :core:telemetry-engine:lintDebug`.
- **A-09 initial:** `:core:telemetry-engine:testDebugUnitTest --tests 'com.akane.inspiration.telemetry.dump.DumpParserTest' --tests 'com.akane.inspiration.telemetry.dump.DumpReaderTest' --tests 'com.akane.inspiration.telemetry.source.AndroidTelemetrySourceDeadlineTest' --tests 'com.akane.inspiration.telemetry.capture.SnapshotCapturerTest' --tests 'com.akane.inspiration.telemetry.diff.SnapshotDifferTest' :core:telemetry-engine:lintDebug`; **after resolving A-02 test-helper integration:** `:core:telemetry-engine:testDebugUnitTest --tests 'com.akane.inspiration.telemetry.source.AndroidTelemetrySourceDeadlineTest' --tests 'com.akane.inspiration.telemetry.dump.DumpParserTest' :core:telemetry-engine:lintDebug`.
- **A-11:** `:app:testDebugUnitTest --tests 'com.akane.inspiration.capture.ChargeHealthPipelineTest' --tests 'com.akane.inspiration.capture.ChargingCaptureRunnerTest' --tests 'com.akane.inspiration.capture.ChargeJobCaptureTest' :core:telemetry-engine:testDebugUnitTest --tests 'com.akane.inspiration.telemetry.capacity.CapacityEstimatorTest' :app:lintDebug`.
- **A-15:** `:feature:health:testDebugUnitTest :feature:health:lintDebug`.
- **A-17:** `:app:testDebugUnitTest --tests 'com.akane.inspiration.di.CachedAppLabelsTest' :app:lintDebug`.
- **A-21:** `:feature:xray:testDebugUnitTest --tests 'com.akane.inspiration.xray.XRayViewModel*Test' :feature:xray:lintDebug :feature:xray:updateDebugScreenshotTest`, then `:feature:xray:validateDebugScreenshotTest`.
- **A-22:** `:feature:sleeplab:testDebugUnitTest --tests 'com.akane.inspiration.sleeplab.SleepLabMappersTest' :feature:sleeplab:lintDebug`.

- **A-04:** `:core:telemetry-engine:testDebugUnitTest --tests 'com.akane.inspiration.telemetry.toolkit.ToolkitActionsHoldSupervisorTest' --tests 'com.akane.inspiration.telemetry.toolkit.ToolkitActionsRobustnessTest' :core:telemetry-engine:lintDebug`.
- **A-10:** `:core:telemetry-engine:testDebugUnitTest --tests 'com.akane.inspiration.telemetry.live.LiveTelemetryTest' --tests 'com.akane.inspiration.telemetry.app.AttemptTest' :feature:health:testDebugUnitTest --tests 'com.akane.inspiration.health.HealthViewModelTest' :core:telemetry-engine:lintDebug`.
- **A-12:** `:app:testDebugUnitTest --tests 'com.akane.inspiration.capture.ChargeJobRunTest' --tests 'com.akane.inspiration.capture.CapturePolicyTest' --tests 'com.akane.inspiration.capture.ChargeJobCaptureTest' --tests 'com.akane.inspiration.capture.ChargingCaptureRunnerTest' :app:lintDebug`.
- **A-13:** `:core:database:testDebugUnitTest --tests 'com.akane.inspiration.database.store.RoomSnapshotStoreTest' --tests 'com.akane.inspiration.database.InspirationDatabaseTest' :core:telemetry-engine:testDebugUnitTest --tests 'com.akane.inspiration.telemetry.reference.ReferenceResolverTest' :app:testDebugUnitTest --tests 'com.akane.inspiration.capture.ChargeJobCaptureTest' --tests 'com.akane.inspiration.capture.ChargingCaptureRunnerTest' :core:database:lintDebug :app:lintDebug`.
- **A-14:** `:feature:xray:testDebugUnitTest --tests 'com.akane.inspiration.xray.XRayViewModel*Test' :feature:sleeplab:testDebugUnitTest --tests 'com.akane.inspiration.sleeplab.SleepLabViewModelTest' :feature:xray:lintDebug :feature:sleeplab:lintDebug :feature:xray:validateDebugScreenshotTest :feature:sleeplab:validateDebugScreenshotTest`.
- **A-16:** `:core:designsystem:testDebugUnitTest --tests 'com.akane.inspiration.designsystem.chart.scrub.SliceControlsTest' --tests 'com.akane.inspiration.designsystem.chart.scrub.ScrubGestureReducerTest' :feature:dashboard:testDebugUnitTest --tests 'com.akane.inspiration.dashboard.CockpitViewModelTest' :core:designsystem:lintDebug :core:designsystem:updateDebugScreenshotTest :feature:dashboard:updateDebugScreenshotTest`; after preview-height repair, forced `:feature:dashboard:updateDebugScreenshotTest --rerun-tasks`, then separately `:core:designsystem:validateDebugScreenshotTest :feature:dashboard:validateDebugScreenshotTest`.
- **A-18:** `:feature:health:testDebugUnitTest :feature:health:lintDebug`.
- **A-05:** `:core:telemetry-engine:testDebugUnitTest --tests 'com.akane.inspiration.telemetry.toolkit.ToolkitActions*Test' :core:telemetry-engine:lintDebug`.
- **A-19:** `:feature:health:testDebugUnitTest :feature:health:lintDebug :feature:health:updateDebugScreenshotTest`, then `:feature:health:validateDebugScreenshotTest`.
- **A-20:** `:feature:health:testDebugUnitTest :feature:health:lintDebug`.
- **A-22 supplemental/A-24:** `:feature:sleeplab:updateDebugScreenshotTest`, then `:feature:sleeplab:testDebugUnitTest --tests 'com.akane.inspiration.sleeplab.SleepLabMappersTest' --tests 'com.akane.inspiration.sleeplab.SleepLabViewModelTest' :feature:sleeplab:lintDebug :feature:sleeplab:validateDebugScreenshotTest`. Only the A-22 CounterReset reference was intentionally changed, in a separate commit from A-24's three source/test files.
- **A-23:** `:core:diagnostics:testDebugUnitTest --tests '*IntervalAnalyzerStoryTest' --tests '*IntervalAnalyzerTest' --tests '*AnalysisSessionTest' --tests '*BatteryStoryBuilderTest' --tests '*AndroidStoryWriterTest' :core:diagnostics:lintDebug :app:validateDebugScreenshotTest`; supplemental relevant-UI gate `:feature:dashboard:validateDebugScreenshotTest`, then after primary-preview alignment `:feature:dashboard:updateDebugScreenshotTest :feature:dashboard:validateDebugScreenshotTest`. No production story/resource changes accompanied the supplemental preview acceptance.

### Review, failed checks and limits

- A-01's initial job `blhcmns4m` failed one of 47 tests. Independent review and the implementer traced a test-helper refresh that independently rebound after injected transport death. The first bounded repair takes only the lock; the concurrency assertions remain. Final tests/lint passed. Actual pinned Shizuku-library replay is JVM evidence, not real-device handover. Already-disclosed timeout-fallback wrapper reuse is unchanged.
- A-03's initial tests passed, but independent review found non-reflexive comparison for observed app-op `foreground`. The repair recognizes equal nonempty observed raws without expanding writable modes. New resetAll/pruning/recreation/stale controls and independent re-review passed. No atomic platform CAS is claimed.
- A-11 tests-first job `bl1gcmfp3` failed seven assertions in 22 tests, exposing transient rejection/recovery/chronology paths. GREEN has 23 pipeline tests, including an additional shifted-wall/storage-key control. Independent review approved persisted-evidence priority, terminal immutability and strictly earlier history.
- Independent read-only review covered A-01/A-03/A-11/A-15 and A-21 containment/Compose. A-21's confirmation-boundary test can also pass via the execution guard; both source guards were inspected, so the test does not independently mutation-test each layer. Profile toolkit operations are deliberately read-only/unsupported; analytics remain visible. No real multi-profile write was attempted.
- A-02 releases callers/refcounts on timeout; it cannot interrupt an already-running native Binder transaction or bound repeated stuck native calls globally. A-07 still lacks a real dynamic parcel-budget→capture→Room acceptance replay. A-06 refuses newly unsupported originals, but already-deployed unsupported legacy originals remain refused by a fresh executor and need recovery policy. These limits must not be represented as verified physical-device recovery.
- A-04's per-iteration recovery, cancellation and monotonic cap passed main/independent review. Tests inject `audit.pending()` failures, not every backend/update failure, and replacement ownership is tested through user cancellation rather than its deadline expiry.
- A-10's first job `btyz2jnfy` failed three exception-reference assertions (11 tests total). Coroutine stacktrace recovery preserves type/message/original cause while copying the exception. First bounded repair corrected only that oracle; propagation/no-next-read/no-insert assertions and production scope stayed unchanged. Final gate passed. Genuine source-completion restart hardening remains outside this fix.
- A-12's tests-first `blkhxutvl` reproduced three assertions in 12 tests, including stale JOB29 replacement by a 2,700,000 ms delay. Shared-lock GREEN and independent review passed. Callback seams model same-ID scheduling, not native Binder callback ordering; unchanged `JobScheduler.RESULT_FAILURE` handling is not modeled by the throwing-submission test.
- A-13's first job `b8t1iyb2m` failed Kotlin test compilation on a reified heterogeneous nullable-array type. First test-only repair uses `arrayOf<Any?>`; final gate and independent review passed. Current-boot elapsed ordering is tested through real Room/resolver reads; unknown/cross-boot fallback remains best-effort saved order, not OEM performance proof.
- A-14's source/oracle review found no blockers. Dedicated suspended-old-timeline cancellation coverage is absent; source relies on cancellation-preserving `collectLatest`/`withContext`. Initial feature screenshot validation passed before later source integrations; the final combined gate remains necessary.
- A-16's initial preview validation passed but independent PNG inspection found cropped legends/hints/card edges. First preview-only repair increased four dashboard heights by 80 dp. Combined updater/validator `bcj6ayred` then failed four missing-new-hash-reference checks, not pixel differences; forced updater `b8csf1bfj` plus separate validation `bc085t946` passed. Main and independent reviewers inspected the current PNGs, including selected Turkish 2× controls. Four obsolete generated references were restored precisely to baseline bytes, not deleted. Hardware keyboard, TalkBack and Switch Access behavior remains unverified at runtime.
- A-18's full Health unit/lint task passed; the regression matrix distinguishes unavailable/throwing kernel reads and all failed/empty stored-source combinations, retaining useful live observations while propagating cancellation.
- A-05's initial gate `b2epyvnd0` failed one of 170 tests on top-level `CancellationException` identity after coroutine recovery. First bounded test-only repair asserts exact class/message and original deepest-cause identity, retaining durable pending/endedAt/recreation/reconcile/cancel assertions; `b0ita6kb7` passed. Main and independent source review found no blockers. Lost replies are exception-modeled rather than normal `GatewayReply.Unavailable`; no combined admission/hold-expiry virtual-time control was added, and controller CAS responses are scripted.
- A-19 retains the existing moderate-or-higher (`>= 2`) platform threshold but labels only device thermal status in recorded readings, not physical charger limiting or continuous coverage. All six intentional PNG changes were inspected independently and Turkish/LargeFont renders were also inspected by the coordinator; the other six references are byte-identical. Rendered variants exercise the range summary; negative/single-reading wording has source/resource coverage only. Constant synthetic power is not charger-control evidence.
- A-20's 16-case predecessor/successor provenance matrix covers 30,000/30,001 and 1,200,000/1,200,001 ms. The original merged foreground→history case retains the full 20-minute session while counting only three seconds of heat/voltage evidence. No heat/voltage thresholds or skip-entire-gap policy changed.
- A-24 revokes visit-specific explicit failure ownership, queued effects and refreshing state on Hidden without cancelling shared capture; collection uses STARTED lifecycle ownership. Main and independent source reviews accepted five new visibility/join regressions plus existing visible-failure controls. Actual route STOP/START snackbar cancellation and direct refresh cancellation remain unverified at runtime.
- The initial A-24 gate `bicc40ucj` exposed one 3.44% CounterReset screenshot difference caused by A-22's valid dump-derived sleep despite unavailable kernel/CPU data. Expected/actual PNGs and the fixture were traced; no fixture weakening or production rollback was made. Separate A-22 reference acceptance `7ac20eb` changes one PNG only. Real differ-derived A-22 unit tests, not this synthetic preview, supply cross-boot comparability evidence.
- A-23's initial gate `bvs40tn5b` failed one of 49 tests (expected 5.0%/h, actual 0.0). The fixture placed a level drop exactly on SCREEN_ON; the real metrics calculator applies screen transitions before equal-timestamp level events. First bounded repair records the drop before the transition and supplies record-faithful levels on state events. All rate, energy, CPU-counter, bounds, cache and EN/TR assertions remain unchanged; `bl7hlo1r0` passed. App shell screenshots did not exercise the story. Additional dashboard validation passed, then actual PNG inspection exposed the primary synthetic ready fixture's live/default summary. A preview-only explicit-false alignment and seven intended reference updates passed `bz3t05i2u`; actual English/Turkish neutral wording was inspected with no clipping. Runtime analysis uses neutral wording for stored coverage even when an endpoint matches now; explicit live builder/writer branches are retained. Previews are synthetic visual evidence, not persisted-session provenance proof.
- Installed PostToolUse formatting hooks mechanically reflowed existing code inside owned Kotlin files. Hooks/configuration were not disabled or changed; this accounts for substantial textual diff beyond semantic fixes. Audit skills, kit files and frozen `independent.md` were not changed.
