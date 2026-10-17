# Onboarding
*Last Updated: 2026-10-17*

## Read first

1. Read [`docs/plans/phase-1.md`](../../docs/plans/phase-1.md) for the selected architecture and boundaries.
2. Read [`architecture.md`](architecture.md), then [`modules.md`](modules.md) and [`communication.md`](communication.md) before changing IPC or module wiring.
3. Use [`PROGRESS.md`](../../PROGRESS.md) for decisions and measured emulator evidence. Work items and blockers live on the Sidequest board, as described by [`CLAUDE.md`](../../CLAUDE.md).

## Build and tests

From the repository root:

```sh
./gradlew :app:assembleDebug --console=plain -q
./gradlew :app:testDebugUnitTest --console=plain -q
./gradlew :app:lintDebug --console=plain -q
```

The project also documents the combined helper form `bash .claude/kit/gradle-check.sh :app:assembleDebug :app:testDebugUnitTest`. Screenshot references are validated with `:app:validateDebugScreenshotTest`; update references only for an intentional UI ticket with `:app:updateDebugScreenshotTest`.

## Emulator and app launch

The documented AVDs are `batstats-api36`, `screenoff-api37`, and `pixel-api35`. The normal launch flow is:

```sh
emulator -avd batstats-api36 -no-window -no-audio &
adb wait-for-device shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'
./gradlew :app:installDebug -q
adb shell am start -n com.akane.inspiration/.MainActivity
```

The app manifest registers `rikka.shizuku.ShizukuProvider` and queries the Shizuku manager package. Install and start Shizuku on the emulator before testing IPC; without it the manager remains `NotInstalled` or `NotRunning`.

## Debug Shizuku probe

`ProbeReceiver` exists only in the debug source set and requires `android.permission.DUMP` on its receiver. With a running debug app and Shizuku:

```sh
adb shell am broadcast -a com.akane.inspiration.debug.PROBE_REQUEST_PERMISSION \
  -n com.akane.inspiration/.debug.ProbeReceiver
adb shell am broadcast -a com.akane.inspiration.debug.PROBE \
  -n com.akane.inspiration/.debug.ProbeReceiver
adb logcat -d --pid=$(adb shell pidof -s com.akane.inspiration) | tail -80
```

The first broadcast requests Shizuku permission; approve “Allow all the time” in the emulator UI. The probe then binds the shell user service, reads kernel/framework telemetry and allowlisted dumps, runs calibration, and logs JSON under the `InspirationProbe` tag. It is bounded to 10 seconds and reports availability/reason fields rather than throwing for unavailable telemetry.

The emulator setup recorded in [`PROGRESS.md`](../../PROGRESS.md) uses the Shizuku APK's `lib/<abi>/libshizuku.so` started with adb. Emulator batteries are synthetic; device evidence covers API 35, 36, and 37, not production hardware.
