---
description: Android build, test and dependency rules
priority: 20
---
- Gradle: always preserve its exit code. From the checkout root use `bash .claude/kit/gradle-check.sh <tasks>` (full log retained, last 80 lines on failure), or raw `./gradlew <tasks> --console=plain -q` if the helper is absent. Never use a grep/head pipeline as the pass/fail oracle.
- Verify with the narrowest task: `./gradlew :<module>:testDebugUnitTest --tests '<Class>' --console=plain -q`; UI changes add `:<module>:validateDebugScreenshotTest` (only UI tickets update refs, via `updateDebugScreenshotTest`).
- Batch the necessary Gradle tasks per change-set rather than running per file. Rerun relevant checks after fixes or to resolve a concrete failure.
- Follow android-orchestration for the default 2 concurrent Gradle invocations. A Gradle slot covers only a running command, not a ticket or dispatch wave; waiting verification must not block independent ticket dispatch. gradle-check.sh preserves logs/exit status but does not enforce a semaphore.
- Whole-project gates (all-module test, lint, screenshots, R8) run with `--max-workers=4`. The host has 16 threads but only 15 GiB RAM, a 512 MiB swapfile that is mostly used at idle, and no userspace OOM killer; all 16 workers plus the Kotlin daemon got the ~4.4 GB Gradle daemon OOM-killed.
- Before booting the emulator (always `-memory 2048`, which overrides the AVDs' 2–3 GB `hw.ramSize`), run `./gradlew --stop` and end leftover Kotlin daemons. While an emulator runs, allow only one Gradle invocation alongside it: two Gradle runs plus an emulator got qemu OOM-killed.
- Never bump compileSdk/targetSdk, AGP, Gradle, Kotlin or the JDK target, and never add a dependency, without it being in the ticket. For new apps use `gradle/libs.versions.toml`; adopted projects keep their existing catalog/build-logic/versioning layout.
- The SDK comes from `ANDROID_HOME`. Don't create or edit `local.properties`.
- Never read, print or commit keystores (`*.jks`, `*.keystore`), `local.properties`, `google-services.json`, `.env*`, `.claude-plugin-config.json`.
- Library APIs: check context7 instead of recalling from memory.
