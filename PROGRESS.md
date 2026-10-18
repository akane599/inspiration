# PROGRESS.md — Inspiration

_Work items, stories and blockers live on the Sidequest board. This file keeps what outlives tickets._

## Baseline
- 2026-10-17 @ scaffold commit: build ok, 1 unit test, 0 failing; 5 screenshot refs (HomeScreen) render.
- 2026-10-17 @ end of Phase 1 (6a9e787): build ok incl. minifyReleaseWithR8; 60 unit tests (app 1, shizuku-ipc 47, telemetry-engine 12), 0 failing; screenshot refs validate.
- 2026-10-17 @ end of Phase 2: build ok incl. minifyReleaseWithR8; 137 unit tests (app 8, shizuku-ipc 78, telemetry-engine 41, database 10), 0 failing; screenshot refs validate.
- 2026-10-17 @ end of Phase 3 (b74c3a2): build ok incl. minifyReleaseWithR8; 260 unit tests (app 8, shizuku-ipc 78, telemetry-engine 59, database 13, diagnostics 102), 0 failing; screenshot refs validate.
- 2026-10-17 @ end of Phase 4 (91beb5a): build ok incl. minifyReleaseWithR8; 336 unit tests (app 15, shizuku-ipc 78, telemetry-engine 59, database 13, diagnostics 102, designsystem 69), 0 failing; 36 screenshot refs validate (app + designsystem).
- 2026-10-17 @ Phase 5 gate (4a7e857, SQ-112): build ok incl. minifyReleaseWithR8 and signed assembleRelease; 893 unit tests (app 53, database 23, designsystem 100, diagnostics 130, shizuku-ipc 170, telemetry-engine 194, dashboard 35, health 56, sleeplab 51, toolkit 39, xray 42), 0 failing; all screenshot refs validate; lint 0 errors / 10 warnings. SQ-114/115/118 then added 14 tests (shizuku-ipc 182).

## Decision log (never delete; one line each)
- 2026-10-17: scaffolded with android-kit snapshot — Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.20, Compose BOM 2026.09.00, lifecycle 2.11.0, core-ktx 1.19.1, screenshot 0.0.1-alpha16. (bootstrap)
- 2026-10-17: compileSdk 37 / targetSdk 36 instead of spec's compileSdk 36, because the pinned AndroidX libs (core-ktx 1.19.1, lifecycle 2.11.0, compose ui 1.12.1) require compileSdk ≥37; targetSdk 36 keeps the spec's Android 16 target with Android 17 forward compatibility. Revisit when targeting API 37. (bootstrap)
- 2026-10-17: minSdk 31 per TEMPLATE.md. (bootstrap)
- 2026-10-17: dynamic color off, placeholder palette; brand (AMOLED black + semantic colors from TEMPLATE.md §5) comes from /ui-overhaul. (bootstrap)
- 2026-10-17: Phase 1 plan audited (SQ-3 Opus, SQ-4 GPT Terra): privileged calls primarily in the shell-UID UserService (D1); no generic command runner, allowlisted dumps + caller-UID check (D5); Room deferred to Phase 2 (D6); daemon(false) + <150 ms only for a bound service (D10/D11). See docs/plans/phase-1.md. (US-1)
- 2026-10-17: Phase 1 device evidence (debug ProbeReceiver, Shizuku 13.6.0 via adb starter; emulator batteries are synthetic). On API 35, 36 and 37, every source was available: state, kernel sysfs, getBatteryUsageStats incl. byProcessState, PowerStats monitors (on 35 too, so D3's ≥35 gate stands), DeviceIdle, cpu time_in_state, BATTERYSTATS_PROTO dump of 25–38 KB, and the in-process wrapper path (47–95 ms). Warm UserService call 2–3 ms; cold bind after force-stop 295 ms on API 37 (D11 holds). No design-reopen condition fired: the in-process path works, but its latency was measured on different calls, so D1 stays UserService-primary. Phase 2 should time the same call on both paths before changing D1. (US-1)
- 2026-10-17: ShizukuState.NotInstalled = Shizuku manager package `moe.shizuku.privileged.api` absent (needs the `<queries>` entry); Sui/other forks are not detected yet. (SQ-9)
- 2026-10-17: TelemetryUserService.openDump closes both pipe ends if the writer thread can't start and swallows dump exceptions in the writer (the reader sees early EOF) so the shell process never dies on a bad dump. (SQ-13 review → SQ-14)
- 2026-10-17: Phase 2 plan rev 2 (docs/plans/phase-2.md, E1–E13) audited by SQ-18/19 (Opus) + SQ-20 (GPT): pull-first capture with no polling; Room 2.8.5 + WAL, Robolectric DAO tests (Room 3 deferred); trimmed vendored AOSP protos; validity tuple per snapshot; kernel FCC is the primary capacity value; backups off before any production write. (US-2)
- 2026-10-17: Charging job (E1c/d) after review SQ-32 → repair SQ-33: boot arms with no latency, and only the job's own re-arm waits 45 min. onStopJob cancels the run. Idempotency keys on the last PLUGGED *history event* (not the warm-only POWER_CONNECTED snapshot), plus a boot-count change. A failed run still finishes and re-arms (applicationScope has no exception handler). (US-2)
- 2026-10-17: Phase 2 device evidence on API 35/36/37: PROBE_CAPTURE/PROBE_DIFF work; journal_mode = wal. On the boot arm the job runs at once, and with Shizuku not up yet it records a capture_miss and re-arms at +45 min. A forced run captures; a second run in the same plug session skips. A cold plug (process killed) is detected from history. Usage read: UserService 88–104 ms vs in-process 81–93 ms on the same call, so D1 (UserService-primary) stands. API 35+: a cached app is frozen and its runtime SCREEN_* broadcasts coalesce, so screen boundaries come from history_event (E5), with light snapshots only as a supplement. (US-2)
- 2026-10-17: Device findings: the battery history buffer survives a stats reset, so history_event identity is (historyTimeMs, kind, wallMs) (SQ-34). BatteryUsageStats sensor estimates are non-monotonic on API 37, so a negative Δ invalidates only that UID/component, never the whole delta (SQ-35). (US-2)
- 2026-10-17: Phase 3 plan rev 2 (docs/plans/phase-3.md, F1–F9) audited by SQ-37 (Opus) + SQ-38 (GPT Terra): R2 culprits are attributed only over a derived screen-off-only covered interval, otherwise labelled whole-interval contributors; R4 eligibility is derived from IntervalMetrics; SnapshotDiff gains a system-timer delta; rule output types are owned by T3; the in-place schema v1 edit requires `pm clear` before device runs (schema v2 rejected); the dictionary stays bilingual JSON. (US-3)
- 2026-10-17: Dictionary matcher normalizes real subject shapes before lookup: alarm names "*walarm*:<tag>" / "*alarm*:<tag>" look up the tag first and fall back to the prefix entries (SQ-47 review → SQ-48); JobScheduler wakelocks "*job*<r|e|u>/…" are looked up as "*job*/…" (device run → SQ-49). Entry values stay in the documented unprefixed form. (US-3)
- 2026-10-17: Rule engine accepted deviations from F4: R2 adds NotEvaluable "no_awake_time" when screenOffAwakeMs is null; ModemDrain.signalBinPct is 0–100. The story uses a minutes form under 1 h (SQ-49). (US-3)
- 2026-10-17: Phase 3 device evidence on API 36/37 (after `pm clear`, via the device-probe skill): PROBE_INSIGHTS runs the full chain (diff → IntervalMetrics → rules → story → dictionary). With an unplug at 80% and ~160 s screen off, screen-off time and the 1% drop are attributed correctly (unknownMs 0, rates null below 15 min). R1/R3 evaluate and stay quiet on the emulator; R2/R4 report not-evaluable for a short, mixed interval. Unplugging at 100% resets batterystats, so that diff is battery_stats_invalid, as designed. Emulator deep sleep is 0% (emulator_wake_lock is always held), so the thresholds are unverified on hardware. (US-3)
- 2026-10-17: Phase 4 plan rev 2 (docs/plans/phase-4.md, G1–G13) audited by SQ-51 (Opus): `:core:designsystem` leaf module exposing material3/BOM/immutable as `api`; `MaterialExpressiveTheme` with the default (expressive) motion scheme, because `MotionScheme.expressive()` is internal in material3 1.4.0; forced dark AMOLED including the window theme and system bars; Inter static TTFs with tnum on title/label and numeric styles only (a variable font was rejected for layoutlib risk); chart models use sanitizing factories, not constructor `require`; draw-phase cursor/brush state with an isolated readout child; T1 split into T1a tokens / T1b app migration. (US-4)
- 2026-10-17: Correction to the line above: `MaterialExpressiveTheme`/`MotionScheme` are Kotlin-internal in material3 1.4.0 (public bytecode, hidden metadata), so InspirationTheme wraps plain `MaterialTheme(colorScheme, shapes, typography)`; motion is explicit `tween(150 ms)` for effects and `spring(0.9f, StiffnessMediumLow)` for spatial moves. (US-4)
- 2026-10-17: Phase 4 visual review (SQ-58, approve with fixes) → SQ-59. A shared SELECTION_DIM_ALPHA 0.6 toward surfaceContainer is used by both charts. The scrub legend names the Battery/Temperature series. A window of exactly 24 h counts as spanning days (dates shown). Brush handles sit at the plot top. Hypnogram maintenance alpha is 0.85 light / 0.7 dark. Hypnogram connectors stay chartAxisLabel at 50%, and the light-theme hues near 3:1 were accepted. The readout title reserves two lines, so it doesn't truncate at 360 dp or jump (SQ-61). Deferred to Phase 5: the hypnogram font-scale cap and totals gutter (F7), crowded 840 dp edge ticks, a consistent empty state, "45°C", the light Home FAB shadow, and U+2212 minus in formatters. (US-4)
- 2026-10-17: Phase 5 plan rev 2 (docs/plans/phase-5.md, H1–H18), audited by SQ-64 (Opus plan-review) and SQ-65 (Sonnet security review). Navigation: no library; destination visibility comes from composition through LifecycleStartEffect. Per-feature DI interfaces are pinned up front, and only T5 touches :app. One process-wide AnalysisSession serves all screens. Toolkit writes:
  - Typed AIDL txns 20–31 running a fixed argv over absolute /system/bin/cmd paths, pinned to user 0.
  - appId/role/flag hard-deny rules; write-ahead `toolkit_action` audit with restore.
  - Doze through the legacy `settings global device_idle_constants` key (device_config writes are denied for shell on API 36), verified against dumpsys after apply, with raw-value restore.
  - bg-dexopt guarded against double starts, re-checked for plugged state, capped at 60 min, and holds the binding.
  - Standby bucket is set with `am set-standby-bucket` (the spec's `cmd usagestats` form doesn't exist).
  - QUERY_ALL_PACKAGES for app labels; per-app network bytes are deferred. (US-5)
- 2026-10-17: Phase 5 UI decisions (SQ-93–107). One shared vocabulary in designsystem: ScreenHeader, CardTitle, MetricBar, SegmentedToggle, TimeSliceBanner, statusColors. Screens never use a raw LinearProgressIndicator.
  - LiveGauge shows "No reading" for unknown charging state, never "Discharging".
  - SegmentedToggle ellipsizes a too-long word instead of breaking it, so Turkish labels ellipsize at 360 dp/1.5× (accepted).
  - MetricValue/MetricTile shrink to fit rather than wrap mid-word.
  - tnum was removed from title/label styles; it stays on numeric styles.
  - Toolkit and X-Ray say "Restrict"/"Kısıtla" for run-in-background and wakelock ops, never "block".
  (US-5)
- 2026-10-17: Phase 5 behaviour decisions. Sleep Lab hides deep sleep when kernel counters are invalid, including across a counter reset (conservative). Health has no Shizuku grant action, so its CTA stays "Refresh" (known gap). Refresh has a 60 s success debounce and a 12 s failure backoff, and the backoff is cleared on every transition to Shizuku Ready (SQ-95/102). Toolkit restore and reset return a target to its chain original; an already-original target is refused ALREADY_RESTORED, and restore reads before it asks for confirmation (SQ-92/96/104). A fresh dexopt service status UNKNOWN is Idle unless one of our dexopt rows is still PENDING (SQ-115). (US-5)
- 2026-10-17: Shizuku UserService binding (SQ-113 device defect → SQ-114, reviewed by SQ-117 → SQ-118). shizuku-api 13.1.5 caches one connection stub per service class, and unbind(remove=true) neither clears nor evicts it, so the old process's late died() tore down the next binding and caused duplicate records (starters exit 1). Fixes:
  - New binds wait for the old death, with a 2 s fallback; one owed late disconnect is swallowed while the new binder is alive.
  - Unbind nulls the binder.
  - The last release lingers 3 s before unbinding, so tab handover keeps the process; H13 holds within about 3 s.
  - stop() can't rebind.
  - Library residual: after a swallowed death, a later death of the new service isn't delivered, and calls return null until the next unbind.
  (US-5)
- 2026-10-17: Release signing comes from ~/.gradle/gradle.properties (INSPIRATION_RELEASE_*), with the keystore at ~/.android-keystores/inspiration-release.jks, outside the repo. Without the properties the release builds unsigned. The ShizukuProvider artifact is `api` in :core:shizuku-ipc so :app lint resolves the manifest provider (SQ-109). Gates run with --max-workers=4: with no swap, 16 workers plus the Kotlin daemon got OOM-killed (SQ-111). (US-5)
- 2026-10-18: v0.1.0 shipped as a GitHub release on the private akane599/inspiration repo (signed APK + .sha256 asset) over a Play track, because the goal was a sideloadable signed build with no store listing.
- 2026-10-18: Phase 5 device evidence, API 36 emulator, signed release build. Earlier run: SQ-113 at ddcf82d. Re-test: SQ-119 at 2bf40d3.
  - R8 release launches cold in 530 ms with no missing classes. ShizukuProvider is merged at runtime (authority com.akane.inspiration.shizuku). Grant → Cockpit Ready with a live gauge.
  - All 5 tabs render. Rail sits below the system bars at 1280x800, and the IME keeps the search field visible in both rail and bar modes.
  - Tab handover keeps one service process and no starter exits 1.
  - H13: the :telemetry process exits 3.8 s after Home.
  - Toolkit Restrict→Restore and Reset all were checked by appops read-back. The standby bucket restores to a settable baseline. A system-set bucket baseline (50) is NOT_RESTORABLE by design and stays listed.
  - After a restore, appops shows an explicit "default" uid-mode entry rather than "No operations"; the effective mode is unchanged.
  (US-5)
- 2026-10-18: Status notification (plan audited SQ-121, rev 2 at ~/.claude/plans/elegant-questing-stroustrup.md → docs/plans/status-notification.md). The user chose a resident foreground service (`specialUse`), on by default with a Toolkit switch. This reverses Phase 2's "no resident service" (phase-2.md:27). It also keeps the runtime SCREEN_* receiver from being frozen on API 35+.
  - There is no BATTERY_CHANGED receiver. Updates come only from the existing SCREEN_*/POWER_* captures and app open, so §6.2 is unchanged.
  - The service is not started when notifications are blocked.
  - Emulator QA (API 36) found two issues, both fixed in d19c506:
    - The FGS notification needs `FOREGROUND_SERVICE_IMMEDIATE`. Without it the deferred placeholder overwrote the first redraw about 10 s later.
    - A `POWER_DISCONNECTED` capture anchors "since unplug" even when no plugged snapshot is stored.
  - Device evidence:
    - In the background the process is procState 4 and not frozen.
    - Our registered actions are only SCREEN_*/POWER_*/BATTERY_LOW/OKAY.
    - The switch off/on and blocked paths behave as specified.
    - The service returns after BOOT_COMPLETED and MY_PACKAGE_REPLACED. A force-stopped app gets neither broadcast; that is platform behaviour.
  - Not covered: the X-Ray app names fix on a device. A fresh emulator has no per-UID energy data; the fix is covered by unit and screenshot tests.
  - The numbers come from light snapshots (no Shizuku). The unplug anchor is the first unplugged snapshot after the last plugged one.
  - Pinned-surface changes: `SnapshotStore.snapshotsBetween`, `ToolkitDependencies` +3 defaulted members, and `AppLabels.packageForUid` (default null; SQ-122 X-Ray UID→package fix).
  - Delivered as branch feat/status-notification with a PR to main. (US-6)
- 2026-10-17: Chart perf (SQ-60): cursor/selection reads shared a draw node with static content, so every drag frame re-rasterized all paths on the RenderThread (the UI thread blocked in postAndWait). Static data is now recorded once per cache build into an Offscreen GraphicsLayer, and the overlay draws on top. New charts must keep that split. On the API 36 emulator (software rendering via SwiftShader, so read the numbers as relative): drag frames p50 34 → 17 ms, p90 40 → 23 ms, and janky 17% → 0.7% (final run, `build/gfx-final-api36.txt`). Drags trigger 0 recompositions, and a committed brush 1/1. (US-4)

## Audit status
| Area | Last run | Result | How |
|---|---|---|---|
| Plan audit | 2026-10-18 | Status notification: approve with fixes (3 blockers, applied in rev 2). Phase 5: approve with fixes (5 + 4 security blockers, all applied in rev 2); Phase 1–4 likewise | SQ-121 (status notification); SQ-64 + SQ-65 security (Phase 4: SQ-51; Phase 3: SQ-37/38; Phase 2: SQ-18/19/20) |
| Bug hunt | — | — | `/bug-hunt` |
| Security | — | — | `/claude-security` |
| UI / design | 2026-10-17 | Phase 4 design system + charts: approve with fixes; F1–F6 applied (SQ-59), readout truncation fixed (SQ-61); F7 + nits deferred to Phase 5 | SQ-58 visual-evaluation (Opus) |
| Lint | 2026-10-17 | 0 errors, 10 warnings (after SQ-109 fixed MissingClass ShizukuProvider) | `:app:lintDebug` in SQ-112 gate |
| Device QA | 2026-10-18 | Phase 5 release build on API 36: SQ-113 found the binding-handover defect (→ SQ-114/118) and the dexopt idle card (→ SQ-115); SQ-119 re-QA all 5 scenarios pass. Phase 4 DesignGalleryActivity drag frame stats on API 36: pass after SQ-60 (p50 17 ms, 0.7% janky, software renderer); Phase 3 PROBE_INSIGHTS pass on API 36/37 (2 defects → SQ-49); Phase 2 capture/diff/job pass on API 35/36/37 (SQ-34/35) | `.claude/skills/device-probe` (`device-probe.sh --probe PROBE_INSIGHTS --extras "--es reference_kind since_unplugged"`) |
| Content review | 2026-10-17 | Dictionary en/tr wording: 7 findings applied (SQ-48) | SQ-47 review-audit |

## Environment notes
- SDK platforms installed: android-35, android-36, android-37.0.
- Probe AVDs: pixel-api35, batstats-api36, screenoff-api37. Shizuku is installed and started with adb (run `lib/<abi>/libshizuku.so` from its APK dir). The first PROBE_REQUEST_PERMISSION broadcast shows the Shizuku dialog; tap "Allow all the time" (`device-probe.sh --grant` does this via uiautomator; `pm clear` can drop the grant).
