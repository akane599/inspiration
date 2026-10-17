# PROGRESS.md — Inspiration

_Work items, stories and blockers live on the Sidequest board. This file keeps what outlives tickets._

## Baseline
- 2026-10-17 @ scaffold commit: build ok, 1 unit test, 0 failing; 5 screenshot refs (HomeScreen) render.

## Decision log (never delete; one line each)
- 2026-10-17: scaffolded with android-kit snapshot — Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.20, Compose BOM 2026.09.00, lifecycle 2.11.0, core-ktx 1.19.1, screenshot 0.0.1-alpha16. (bootstrap)
- 2026-10-17: compileSdk 37 / targetSdk 36 instead of spec's compileSdk 36, because the pinned AndroidX libs (core-ktx 1.19.1, lifecycle 2.11.0, compose ui 1.12.1) require compileSdk ≥37; targetSdk 36 keeps the spec's Android 16 target with Android 17 forward compatibility. Revisit when targeting API 37. (bootstrap)
- 2026-10-17: minSdk 31 per TEMPLATE.md. (bootstrap)
- 2026-10-17: dynamic color off, placeholder palette; brand (AMOLED black + semantic colors from TEMPLATE.md §5) comes from /ui-overhaul. (bootstrap)
- 2026-10-17: Phase 1 plan audited (SQ-3 Opus, SQ-4 GPT Terra): privileged calls primarily in the shell-UID UserService (D1); no generic command runner, allowlisted dumps + caller-UID check (D5); Room deferred to Phase 2 (D6); daemon(false) + <150 ms only for a bound service (D10/D11). See docs/plans/phase-1.md. (US-1)

## Audit status
| Area | Last run | Result | How |
|---|---|---|---|
| Plan audit | 2026-10-17 | Phase 1: approve with fixes (applied) | SQ-3 + SQ-4 |
| Bug hunt | — | — | `/bug-hunt` |
| Security | — | — | `/claude-security` |
| UI / design | — | — | `/ui-overhaul` phase review |
| Lint | — | — | `./gradlew :app:lintDebug -q` |
| Device QA | — | — | android-emulator-qa skill |

## Environment notes
- SDK platforms installed: android-35, android-36, android-37.0.
