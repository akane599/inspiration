audited: 2026-10-18 (SQ-121, Opus/medium plan-review: approve with fixes; 3 blockers + fixes applied → rev 2)

# Persistent status notification, rev 2 (+ X-Ray app-name bug as separate SQ-122)

## Context
The user asked for a useful persistent notification. Decisions taken 2026-10-18:
- It is backed by a **foreground service** (`specialUse`). This reverses Phase 2's "no resident service" (phase-2.md:27) and gets a DECISION line.
- It shows **drain since unplug**: total %, elapsed time, screen-on %/h and screen-off %/h.
- It shows **deep sleep share**: CPU suspend / screen-off time since unplug.
- It is **on by default**, with a Toolkit switch.
- Delivery is the branch `feat/status-notification` with a PR to main (the user's standing instruction; no approval gates).

A process with a running foreground service is never cached or frozen, so the runtime SCREEN_* receiver gets its broadcasts on time. PROGRESS.md records that on API 35+ these broadcasts coalesce while the app is frozen.

**Data source.** The numbers come from light snapshots, which need no Shizuku. Each one carries level, plugged, elapsedRealtime, uptime, bootCount and its trigger (`SnapshotModels.kt:27-66`). The calculation is stateless from Room, so it survives a process death. `IntervalAnalyzer` is not used: it scans the whole history twice and needs Shizuku history.

## Constraints (spec §6)
- **There is no BATTERY_CHANGED receiver** (rev 2, per the SQ-121 contrarian point). Updates come only from the already-registered SCREEN_ON/OFF and POWER_CONNECTED/DISCONNECTED captures, plus app open (refresh). §6.2 stays untouched; the only spec-level change is the resident service.
- There are no loops, timers or handlers, and the service never holds the Shizuku binding.
- **When notifications are blocked, the service is not started.** An invisible resident service would be observer cost with no benefit.

## Design

### T1, core (coding.normal, Sonnet/high). Files: `:core:telemetry-engine`, `:core:database`, every `SnapshotStore` fake
- **`SnapshotStore.snapshotsBetween(fromWallMs, toWallMs): List<BatterySnapshot>`**
  - It is an abstract member, ordered by wall time, returning both kinds. It fills the snapshot and battery columns only; per-UID lists stay empty.
  - Add the Room DAO query and the `RoomSnapshotStore` mapping. There is no schema change, since `Entities.kt:17-36` already has the columns.
  - Implement it in every fake. Find them with `grep -rn 'SnapshotStore' --include=*.kt | grep -E 'class|object'`. They include:
    - `AnalysisFixtures.FakeSnapshotStore`
    - the toolkit/xray `MemoryStore`
    - `SnapshotCapturerTest` FakeStore
    - `SnapshotDifferTest.kt:484`
    - `ChargeHealthPipelineTest` FakeStore (`:app` test)
    - `PreviewDependencies.kt:95` EmptyStore (`:app` screenshotTest)
  - Pinned-surface DECISION.
- **Pure `telemetry/status/UnplugSummary.kt`:** `UnplugSummaryCalculator.compute(snapshots, now: BatterySnapshot): UnplugSummary`, where `now` is a fresh light read wrapped as a snapshot.
  - **Anchor** (blocker fix): the latest snapshot with `plugged == true`, then the first snapshot after it with `plugged == false`. This is robust to a missed or full-only POWER_DISCONNECTED. If `now.plugged == true`, the summary is `Charging`. If there was never a plugged snapshot, the anchor is the earliest snapshot and `anchorIsUnplug = false`.
  - **Window:** the service calls `snapshotsBetween(now − 30 d, now)` (retention is 30 d, `Daos.kt:283`) and the calculator picks the anchor.
  - **Screen state at the anchor:** take the last SCREEN_* trigger at or before the anchor. If there is none, the state is unknown, and unknown time is counted in `elapsedMs` but not in the on or off totals. Non-screen triggers (APP_OPEN, BOOT, BATTERY_*, POWER_*) keep the current state.
  - **Segments:** each segment's drop is `max(0, startLevel − endLevel)`, and a null level on either end contributes no drop. Off segments within the same `bootCount` add `Δ(elapsed − uptime)` to suspend time and `Δelapsed` to off time. A boot change breaks the segment: its time is counted, but it adds no suspend time.
  - **Output:** `sealed UnplugSummary { Charging(levelNow); Discharging(levelNow, dropPct, elapsedMs, screenOnPctPerHour?, screenOffPctPerHour?, deepSleepRatio?, anchorIsUnplug, anchorWallMs) }`. Rates and the deep-sleep ratio are null under 15 min of state time.
  - **Tests** use a new fixture in telemetry-engine's test sources, `statusSnapshot(wallMs, elapsedMs, uptimeMs, bootCount, level, plugged, trigger)`. Cases:
    - plain unplug
    - charging now
    - never plugged
    - missed disconnect (only a light APP_OPEN after the last plugged snapshot)
    - reboot mid-interval
    - rates under 15 min
    - unknown screen state at the anchor
    - a null level
    - empty input
- **Verify:** `bash .claude/kit/gradle-check.sh :core:telemetry-engine:testDebugUnitTest :core:database:testDebugUnitTest :core:diagnostics:testDebugUnitTest :feature:toolkit:testDebugUnitTest :feature:xray:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugScreenshotTestKotlin`. If the screenshot compile task name differs, use the existing screenshot validate task for `:app`.

### T2, Toolkit switch (interaction-design-implementation, Sonnet/high). Files: `:feature:toolkit` only
- `ToolkitDependencies` gets members **with interface defaults** (blocker fix), so `:app` compiles untouched:
  - `val statusNotificationEnabled: StateFlow<Boolean> get() = StatusNotificationDefaults.OFF`, a shared constant `MutableStateFlow(false).asStateFlow()`.
  - `fun setStatusNotificationEnabled(enabled: Boolean) {}`
  - `fun notificationsBlocked(): Boolean = false`
  - A pinned-surface DECISION.
- `ToolkitIntent.SetStatusNotification(enabled)` and `ToolkitIntent.OpenNotificationSettings`, plus `ToolkitEffect.OpenNotificationSettings`. The Route launches `Settings.ACTION_APP_NOTIFICATION_SETTINGS` with `EXTRA_APP_PACKAGE`. `notificationsBlocked()` is re-read on every `Visible`.
- The card is full width, right after `PersistWarning` (`ToolkitScreen.kt:79-99`), **outside the Shizuku gate**.
  - It uses the `OpSwitchRow` pattern (`ToolkitApps.kt:286-317`).
  - Subtitle: "Shows drain since unplug and deep sleep. Updates only on screen and charger events."
  - When blocked, the switch is disabled and the card shows "Notifications are blocked" with an "Allow in settings" button.
  - Strings in en and tr.
- ViewModel tests use `ToolkitFixtures`. Add screenshot previews for on, off and blocked, at 360/840 dp and fontScale 1.5.
- **Verify:** `bash .claude/kit/gradle-check.sh :feature:toolkit:testDebugUnitTest :feature:toolkit:updateDebugScreenshotTest :feature:toolkit:validateDebugScreenshotTest`. Render and inspect the PNG.

### T3a, app plumbing (coding.normal, Sonnet/high). Depends on T1, T2 and SQ-122. Files: `AppContainer.kt`, `RuntimeCaptureReceiver.kt`, `app/.../status/StatusNotificationSettings.kt`, `PreviewDependencies.kt`, tests
- `StatusNotificationSettings`: platform `SharedPreferences` holding `enabled` (default true) and `postPromptShown`. It exposes `enabled: StateFlow<Boolean>`.
- `AppContainer`:
  - `statusTicks: SharedFlow<Unit>` (`MutableSharedFlow(extraBufferCapacity = 1, DROP_OLDEST)`), plus `emitStatusTick()`.
  - It overrides the three `ToolkitDependencies` members. `setStatusNotificationEnabled` persists the value and calls a `statusSync: (Boolean) -> Unit` hook, which defaults to a no-op until T3b wires it.
  - `notificationsBlocked()` is `!NotificationManagerCompat.areNotificationsEnabled()` or the `status` channel's importance being NONE.
- `RuntimeCaptureReceiver`: emit the tick in `finally`, on both the light and full paths, including a timeout or miss. `refresh()` success also emits it.
- Tests are plain JVM: the settings default via a fake `SharedPreferences`-free seam, and the tick emitted on the failure path.
- **Verify:** `bash .claude/kit/gradle-check.sh :app:testDebugUnitTest :app:assembleDebug`

### T3b, service and notification (coding.normal, Sonnet/high). Depends on T3a. Files: `app/.../status/{StatusNotificationService,StatusNotificationController,StatusNotificationContent}.kt`, manifest, `MainActivity.kt`, `BootCaptureReceiver.kt`, `app/src/main/res/{values,values-tr}/strings.xml`, `res/drawable/ic_stat_status.xml`, tests
- **`StatusNotificationController.sync(context)`**:
  - The pure decision is `shouldRun(enabled, blocked)`.
  - It starts with `ContextCompat.startForegroundService` and stops with `stopService`.
  - It catches `ForegroundServiceStartNotAllowedException` and `IllegalStateException`, and logs them.
  - It is called from `MainActivity.onStart`, after the POST_NOTIFICATIONS result, and from the `AppContainer.statusSync` hook.
  - In `BootCaptureReceiver` it is called **synchronously in `onReceive` before `goAsync`**, so it falls inside the boot exemption window.
- **`StatusNotificationService`**:
  - `onStartCommand` is idempotent: there is a single collector job, guarded.
  - It posts a placeholder ("Reading battery…") through `ServiceCompat.startForeground(this, ID, n, if (SDK >= 34) FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)`, inside try/catch with `stopSelf()` on failure, and returns `START_STICKY`.
  - It computes once at start, then on each `statusTicks` emission: `store.snapshotsBetween(now − 30 d, now)` plus `source.light()` wrapped as a snapshot, then `UnplugSummaryCalculator`, then `notify`.
  - The scope is cancelled in `onDestroy`.
- **`StatusNotificationContent`** is pure (blocker fix). It takes a `StatusStrings` data class of resolved strings and the pure `TelemetryFormat` functions, so it is a plain JVM test with no Robolectric. Wording:
  - Discharging: title `−18% since unplug · 6 h 12 min`, text `Screen on 9.4%/h · off 0.6%/h · Deep sleep 92%`. Missing parts read "—", and with no unplug anchor the title says "since <time>".
  - Charging: `Charging · 64%` with `Drain stats start when you unplug`.
  - On a charger but not charging (full, held at a limit, or losing to the load): `Plugged in, not charging · 80%` with the same hint. Since SQ-4 the choice is the live gauge's rule (`telemetry/status/ChargeState.kt`: battery status plus the current, never the plug alone), and the light read's current is first calibrated with the gauge's persisted calibration (`LightCurrent`).
- **Notification:**
  - Channel `status`, `IMPORTANCE_LOW`, no badge.
  - `setOngoing`, `setOnlyAlertOnce`, `setSilent`, `setShowWhen(false)`, category STATUS.
  - BigTextStyle, a tap opens MainActivity through an immutable PendingIntent, and the small icon is `ic_stat_status`: a 24dp monochrome simplified launcher mark.
- **Manifest:** `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`. The service is `exported=false`, `foregroundServiceType="specialUse"`, and has the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property.
- **MainActivity:** on API 33+, when enabled and `!postPromptShown`, request POST_NOTIFICATIONS once through `registerForActivityResult`, then `sync`.
- AppContainer wiring of `statusSync` happens in `InspirationApplication.onCreate` (one line), which is owned here.
- **Verify:** `bash .claude/kit/gradle-check.sh :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`

### SQ-122, X-Ray UIDs → names (debugging, Opus/medium; already dispatched)
Sanity check confirmed: `BatteryUsageAdapter.kt:147` and `XRayViewModel.kt:172-174`. Dump-only and alarm-only UIDs also get a null package (`SnapshotCapturer.kt:79-85`). It touches `AppContainer`/`di`, so T3a runs after it is integrated.

## Order (at most 2 concurrent Gradle tickets, the user's override)
- **Now:** SQ-122 (running) ‖ T1.
- **Then:** T2, when a slot frees.
- **Then:** T3a, after T1, T2 and SQ-122. Then T3b.
- **Integration** is on `feat/status-notification`, never main.
- **Final:**
  - Gate: whole-project unit tests, lint and screenshot validate with `--max-workers=4`.
  - Read-only review-audit of T3a+T3b (foreground-service lifecycle, high stakes).
  - Emulator QA by the orchestrator.
  - Docs: `docs/plans/status-notification.md`, PROGRESS.md, then a map refresh.
  - Push and `gh pr create`.

## Verification (device; emulator `-memory 2048` after `./gradlew --stop`)
1. Fresh install and launch: the permission prompt appears, then the "Reading battery…" notification, then real content.
2. Run `dumpsys battery unplug`, `set level 80`, screen off/on (`input keyevent 26`), `set level 74`, then screen off/on. The drop updates. Rates read "—" under 15 min; that is expected and recorded.
3. `dumpsys activity services com.akane.inspiration` shows the foreground service, and `dumpsys activity processes` shows the process is not frozen. `dumpsys activity broadcasts` shows no BATTERY_CHANGED registration.
4. Toolkit switch off: the service is stopped and the notification gone. On: both are back. Block notifications in settings: the service stops after the next sync, and the card shows the blocked state.
5. Emulator reboot, and a reinstall (MY_PACKAGE_REPLACED): the service comes back.
6. Run on API 36 only. The API 31–33 risk is recorded: on 31–32 there is no runtime POST_NOTIFICATIONS, and the specialUse type is ignored below 34.
7. X-Ray Apps shows names and icons.
