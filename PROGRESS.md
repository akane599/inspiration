# PROGRESS.md — Inspiration

_Work items, stories and blockers live on the Sidequest board. This file keeps what outlives tickets._

## Baseline
- 2026-10-17 @ scaffold commit: build ok, 1 unit test, 0 failing; 5 screenshot refs (HomeScreen) render.
- 2026-10-17 @ end of Phase 1 (6a9e787): build ok incl. minifyReleaseWithR8; 60 unit tests (app 1, shizuku-ipc 47, telemetry-engine 12), 0 failing; screenshot refs validate.
- 2026-10-17 @ end of Phase 2: build ok incl. minifyReleaseWithR8; 137 unit tests (app 8, shizuku-ipc 78, telemetry-engine 41, database 10), 0 failing; screenshot refs validate.
- 2026-10-17 @ end of Phase 3 (b74c3a2): build ok incl. minifyReleaseWithR8; 260 unit tests (app 8, shizuku-ipc 78, telemetry-engine 59, database 13, diagnostics 102), 0 failing; screenshot refs validate.

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

## Audit status
| Area | Last run | Result | How |
|---|---|---|---|
| Plan audit | 2026-10-17 | Phase 4: approve with fixes (3 blockers + fixes applied); Phase 1–3 likewise | SQ-51 (Phase 3: SQ-37/38; Phase 2: SQ-18/19/20) |
| Bug hunt | — | — | `/bug-hunt` |
| Security | — | — | `/claude-security` |
| UI / design | — | — | `/ui-overhaul` phase review |
| Lint | — | — | `./gradlew :app:lintDebug -q` |
| Device QA | 2026-10-17 | Phase 3 PROBE_INSIGHTS pass on API 36/37 (2 defects → SQ-49); Phase 2 capture/diff/job pass on API 35/36/37 (SQ-34/35) | `.claude/skills/device-probe` (`device-probe.sh --probe PROBE_INSIGHTS --extras "--es reference_kind since_unplugged"`) |
| Content review | 2026-10-17 | Dictionary en/tr wording: 7 findings applied (SQ-48) | SQ-47 review-audit |

## Environment notes
- SDK platforms installed: android-35, android-36, android-37.0.
- Probe AVDs: pixel-api35, batstats-api36, screenoff-api37. Shizuku is installed and started with adb (run `lib/<abi>/libshizuku.so` from its APK dir). The first PROBE_REQUEST_PERMISSION broadcast shows the Shizuku dialog; tap "Allow all the time" (`device-probe.sh --grant` does this via uiautomator; `pm clear` can drop the grant).
