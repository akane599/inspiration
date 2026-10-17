# Onboarding
*Last Updated: 2026-10-17*

- Build + verify: `bash .claude/kit/gradle-check.sh :app:assembleDebug :app:testDebugUnitTest` (keeps exit code, logs in `.claude/kit/logs/`).
- Screenshots: `:app:validateDebugScreenshotTest` (diff), `:app:updateDebugScreenshotTest` (UI tickets only).
- Device: `./gradlew :app:installDebug -q && adb shell am start -n com.akane.inspiration/.MainActivity`; AVD `batstats-api36` (others: `screenoff-api37`, `pixel-api35`). Shizuku must be installed and running on the device for any IPC feature.
- Start here: read `TEMPLATE.md` §7 for the current phase, then `architecture.md`. Work is planned as Sidequest stories (see CLAUDE.md).
