---
description: Android build, test and dependency rules
priority: 20
---
- Gradle: always preserve its exit code. From the checkout root use `bash .claude/kit/gradle-check.sh <tasks>` (full log retained, last 80 lines on failure), or raw `./gradlew <tasks> --console=plain -q` if the helper is absent. Never use a grep/head pipeline as the pass/fail oracle.
- Verify with the narrowest task: `./gradlew :<module>:testDebugUnitTest --tests '<Class>' --console=plain -q`; UI changes add `:<module>:validateDebugScreenshotTest` (only UI tickets update refs, via `updateDebugScreenshotTest`).
- Batch the necessary Gradle tasks per change-set rather than running per file. Rerun relevant checks after fixes or to resolve a concrete failure.
- Follow android-orchestration for the default 2 concurrent Gradle invocations. A Gradle slot covers only a running command, not a ticket or dispatch wave; waiting verification must not block independent ticket dispatch. gradle-check.sh preserves logs/exit status but does not enforce a semaphore.
- Whole-project gates (all-module test, lint, screenshots, R8) run as one invocation: `--max-workers=8` when no other Gradle invocation is running, `--max-workers=4` when one is. Never go above 8: before the host had swap, 16 workers plus the Kotlin daemon got the Gradle daemon OOM-killed.
- Host memory: 15 GiB RAM, 7.8 GiB zstd zram (priority 100, used first), then an 8 GiB swapfile (priority 10), vm.swappiness 180, no userspace OOM killer. Measured on 2026-10-19 with redroid up and every task re-run: one gate at 8 workers took 122 s (MemAvailable down to 1.5 GB, 4.5 GB in zram). Two concurrent gates at 4 workers took 150 s and 175 s and filled zram to 7.4 GB. Neither run touched the swapfile or caused an OOM kill.
- An emulator (always `-memory 2048`, which overrides the AVDs' 2–3 GB `hw.ramSize`) or the redroid container doesn't lower the Gradle cap: 2 invocations may run alongside one of them. Before booting the emulator, run `./gradlew --stop` and end leftover Kotlin daemons.
- Never bump compileSdk/targetSdk, AGP, Gradle, Kotlin or the JDK target, and never add a dependency, without it being in the ticket. For new apps use `gradle/libs.versions.toml`; adopted projects keep their existing catalog/build-logic/versioning layout.
- The SDK comes from `ANDROID_HOME`. Don't create or edit `local.properties`.
- Never read, print or commit keystores (`*.jks`, `*.keystore`), `local.properties`, `google-services.json`, `.env*`, `.claude-plugin-config.json`.
- Library APIs: check context7 instead of recalling from memory.
