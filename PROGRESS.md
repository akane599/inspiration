# PROGRESS.md — Inspiration

_Work items, stories and blockers live on the Sidequest board. This file keeps what outlives tickets._

## Baseline
- 2026-10-17 @ scaffold commit: build ok, 1 unit test, 0 failing; 5 screenshot refs (HomeScreen) render.
- 2026-10-17 @ end of Phase 1 (6a9e787): build ok incl. minifyReleaseWithR8; 60 unit tests (app 1, shizuku-ipc 47, telemetry-engine 12), 0 failing; screenshot refs validate.

## Decision log (never delete; one line each)
- 2026-10-17: scaffolded with android-kit snapshot — Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.20, Compose BOM 2026.09.00, lifecycle 2.11.0, core-ktx 1.19.1, screenshot 0.0.1-alpha16. (bootstrap)
- 2026-10-17: compileSdk 37 / targetSdk 36 instead of spec's compileSdk 36, because the pinned AndroidX libs (core-ktx 1.19.1, lifecycle 2.11.0, compose ui 1.12.1) require compileSdk ≥37; targetSdk 36 keeps the spec's Android 16 target with Android 17 forward compatibility. Revisit when targeting API 37. (bootstrap)
- 2026-10-17: minSdk 31 per TEMPLATE.md. (bootstrap)
- 2026-10-17: dynamic color off, placeholder palette; brand (AMOLED black + semantic colors from TEMPLATE.md §5) comes from /ui-overhaul. (bootstrap)
- 2026-10-17: Phase 1 plan audited (SQ-3 Opus, SQ-4 GPT Terra): privileged calls primarily in the shell-UID UserService (D1); no generic command runner, allowlisted dumps + caller-UID check (D5); Room deferred to Phase 2 (D6); daemon(false) + <150 ms only for a bound service (D10/D11). See docs/plans/phase-1.md. (US-1)
- 2026-10-17: Phase 1 device evidence (debug ProbeReceiver, Shizuku 13.6.0 via adb starter; emulator batteries are synthetic). On API 35, 36 and 37, every source was available: state, kernel sysfs, getBatteryUsageStats incl. byProcessState, PowerStats monitors (on 35 too, so D3's ≥35 gate stands), DeviceIdle, cpu time_in_state, BATTERYSTATS_PROTO dump of 25–38 KB, and the in-process wrapper path (47–95 ms). Warm UserService call 2–3 ms; cold bind after force-stop 295 ms on API 37 (D11 holds). No design-reopen condition fired: the in-process path works, but its latency was measured on different calls, so D1 stays UserService-primary. Phase 2 should time the same call on both paths before changing D1. (US-1)
- 2026-10-17: ShizukuState.NotInstalled = Shizuku manager package `moe.shizuku.privileged.api` absent (needs the `<queries>` entry); Sui/other forks are not detected yet. (SQ-9)
- 2026-10-17: TelemetryUserService.openDump closes both pipe ends if the writer thread can't start and swallows dump exceptions in the writer (the reader sees early EOF) so the shell process never dies on a bad dump. (SQ-13 review → SQ-14)

## Audit status
| Area | Last run | Result | How |
|---|---|---|---|
| Plan audit | 2026-10-17 | Phase 1: approve with fixes (applied) | SQ-3 + SQ-4 |
| Bug hunt | — | — | `/bug-hunt` |
| Security | — | — | `/claude-security` |
| UI / design | — | — | `/ui-overhaul` phase review |
| Lint | — | — | `./gradlew :app:lintDebug -q` |
| Device QA | 2026-10-17 | Phase 1 IPC probe pass on API 35/36/37 emulators | debug ProbeReceiver (`adb shell am broadcast -a com.akane.inspiration.debug.PROBE -n com.akane.inspiration/.debug.ProbeReceiver`) |

## Environment notes
- SDK platforms installed: android-35, android-36, android-37.0.
- Probe AVDs: pixel-api35, batstats-api36, screenoff-api37. Shizuku is installed and started with adb (run `lib/<abi>/libshizuku.so` from its APK dir). The first PROBE_REQUEST_PERMISSION broadcast shows the Shizuku dialog; tap "Allow all the time".
