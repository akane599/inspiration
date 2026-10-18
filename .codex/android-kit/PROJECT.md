# Project facts

Observed with `bash .codex/android-kit/detect-stack.sh` on 2026-10-18:

- Project: Inspiration; application ID and namespace: `com.akane.inspiration`.
- Modules: `:app`, `:core:hidden-api-stub`, `:core:shizuku-ipc`, `:core:telemetry-engine`, `:core:diagnostics`, `:core:database`, `:core:designsystem`, `:feature:dashboard`, `:feature:xray`, `:feature:sleeplab`, `:feature:health`, `:feature:toolkit`.
- Mixed Kotlin/Java, Compose UI, Room, coroutines; screenshot tooling detected as `compose-preview`.
- Declared versions: Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.20, Java target 17, compileSdk 37, targetSdk 36, minSdk 31. Baseline execution is tracked below; declaration alone is not evidence of compatibility.

Kit maintenance on 2026-10-18:

- `python3 .codex/android-kit/manage.py conflicts` returned `[]`; no files required merging or resolution.
- `python3 .codex/android-kit/doctor.py` passed with 19 project skills.
- No duplicate `android-*` skill names found across project `.agents/skills`, user `~/.agents/skills`, and user `~/.codex/skills`. All 19 project skills are listed in this session's available skill catalog; `/skills` was not directly inspected.
- No Android build, test, device, or inference verification was performed for this kit-only task. Earlier Claude baseline claims have not been adopted as current evidence.

Bootstrap adoption on 2026-10-18 (HEAD `483745fd03642542e8db2bebab497c71b8594634`, branch `Review-n-fix`):

- Existing project adopted without application, dependency, SDK, provider, or architecture changes. CLAUDE.md was read as background; Claude configuration and Sidequest state were not modified.
- Runtime: OpenJDK 21.0.12.1 at `/usr/lib/jvm/java-21-openjdk-amd64`; `./gradlew --version` successfully reports Gradle 9.8.0. Java source/target compatibility remains 17. The wrapper includes its distribution checksum.
- SDK: `ANDROID_HOME=/home/dev/android-sdk`; installed platform directories include `android-37.0`, and build-tools include 36.0.0. These directory observations do not establish a successful build.
- `bash .codex/android-kit/gradle-check.sh tasks --all` passed: `.codex/android-kit/logs/gradle.LmqaC4Nx.log`. Confirmed debug/release assembly tasks, debug unit-test tasks in all 12 modules, and debug screenshot validation/update tasks in app, designsystem, and all five feature modules. No product flavors are declared in the inspected build files.
- Baseline command: `bash .codex/android-kit/gradle-check.sh :app:assembleDebug testDebugUnitTest validateDebugScreenshotTest`. The unqualified test selectors run matching tasks across subprojects. Native board task #2 records the command, exit status, full-log location and repository fingerprint. Screenshot references must not be updated during adoption.
- Baseline outcome: **failed, exit 1**. Gradle reported `Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)`. The log also reported `[CXX1101] NDK at /home/dev/android-sdk/ndk/28.2.13676358 did not have a source.properties file`. The daemon's disappearance is not proven to be caused by that SDK warning. No application-source failure was identified in the captured output. The full assembly/unit/screenshot baseline did not complete successfully.
- Partial execution evidence: `app/build/outputs/apk/debug/app-debug.apk` was produced (36,881,180 bytes, newer than this baseline's start). After termination, 42 unit-test XML reports newer than the baseline start contained 361 tests, zero failures/errors/skips: app 79, core:designsystem 100, core:shizuku-ipc 182. Reports were absent for the other modules; the complete unit-test suite and screenshot validation are **not verified**. Partial successful reports do not make the aggregate command pass.
- Failure evidence: full Gradle output `.codex/android-kit/logs/gradle.9Rvo16ps.log`; board verification output `.codex/android-kit/runtime/verify-2-1792294511297113723.log`. Task #2 has no successful verification fingerprint and is blocked pending a working baseline. Existing failures were recorded without application/toolchain/SDK fixes or screenshot reference updates.
- Existing testing: JUnit 4.13.2, coroutines-test, Robolectric 4.16.1, Room testing and AndroidX test core; Compose screenshot plugin 0.0.1-alpha16. Source inventory has 98 unit-test source files and 22 screenshot-test source files; these are file counts, not executed test counts.
- Architecture: manual `AppContainer` implements per-feature dependency interfaces; feature ViewModels expose StateFlow and handle intents/effects. `AppShell` manages five destinations without a navigation library. Room 2.8.5 stores telemetry through `RoomSnapshotStore`; Shizuku IPC provides privileged reads and toolkit commands. The detector's `di=none` means no DI framework was detected, not that dependency injection is absent.
- Preserve existing Gradle convention plugins, immutable collection usage, manual DI, Room schema exports, forced-dark theme behavior and Compose screenshot framework. Use existing task names; do not introduce scaffold defaults into this adopted project.
- Entry points and change boundaries are in `.codex/android-kit/MAP.md`.
- No device interaction, privileged Shizuku smoke test, release build/signing, lint run, or inference check is included in this bootstrap baseline. No `.github` workflow directory was found.

Whole-repository audit on 2026-10-18 (HEAD `eb3fe08185995a7925db4bc97d44c4da1cf1048c`):

- Current database schema is version 2 with explicit MIGRATION_1_2; older adoption facts remain historical.
- Fresh unit suite: `bash .codex/android-kit/gradle-check.sh testDebugUnitTest --rerun --max-workers=2` passed, 991 tests, zero failures/errors/skips, all 87 reports fresh. Log `.codex/android-kit/logs/gradle.SlSFi8yK.log`.
- Lint baseline: `bash .codex/android-kit/gradle-check.sh lintDebug testDebugUnitTest --continue --max-workers=2` failed (exit 1): nine Compose lint errors across five modules; log `.codex/android-kit/logs/gradle.TNYcdNUC.log`. No baseline suppression or source fix applied.
- Audit findings, coverage, tool limitations and proposed remediations are recorded in `AUDIT_REPORT.md`. Device enumeration alone was performed; no privileged device commands or release validation were run. Subsequent screenshot evidence is recorded below.

- Subsequent audit screenshot validation: `bash .codex/android-kit/gradle-check.sh validateDebugScreenshotTest --continue --max-workers=1` passed; 167 results, zero failures/errors/skips, 76 fresh comparisons and 91 reused results. References unchanged. Log `.codex/android-kit/logs/gradle.apFs2oG1.log`.

Remediation on 2026-10-18 (source tip `f4365f0`, branch `fix/audit-remediation-20261018`):

- All 20 confirmed audit IDs fixed in separate commits plus a prerequisite lint commit; commit/path/verification table is in AUDIT_REPORT.md.
- Current Room schema is **3**; explicit MIGRATION_2_3 clears uncertifiable derived estimates once and preserves kernel health fields. Existing 1→2 migration remains registered.
- Final gate passed: `bash .codex/android-kit/gradle-check.sh :app:assembleDebug lintDebug testDebugUnitTest --rerun validateDebugScreenshotTest --rerun --continue --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`. 1,023 fresh unit tests and 167 fresh screenshot comparisons, zero failures/errors/skips; log `.codex/android-kit/logs/gradle.nK4SlGL9.log`.
- 18 isolated kit tests and fake-command probe checks passed; no real privileged device probe/release validation is claimed. Six toolkit badge references were reviewed/updated intentionally.
- A concurrent screenshot run lost its daemon; idle JVM cleanup and one-worker/2-GiB verification completed successfully afterward. Gradle/SDK/dependency/JDK-target versions were not changed.
- Successful restore chains/pending rows survive display pruning; safe chain compaction is deferred. Previously pruned original device settings cannot be reconstructed automatically.
