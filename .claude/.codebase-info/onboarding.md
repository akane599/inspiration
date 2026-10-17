# Onboarding
*Last Updated: 2026-10-17*

## Read first

1. Read [`docs/plans/phase-2.md`](../../docs/plans/phase-2.md) for the selected capture, validity, Room, and module boundaries; consult [`docs/plans/phase-1.md`](../../docs/plans/phase-1.md) for the privileged IPC contract.
2. Read [`architecture.md`](architecture.md), [`modules.md`](modules.md), [`communication.md`](communication.md), and [`database.md`](database.md) before changing capture, IPC, or persistence wiring.
3. Use [`PROGRESS.md`](../../PROGRESS.md) for durable decisions and API 35/36/37 device evidence. Work items and blockers live on the Sidequest board, as described by [`CLAUDE.md`](../../CLAUDE.md).

## Build and tests

From the repository root:

```sh
./gradlew :app:assembleDebug --console=plain -q
./gradlew :app:testDebugUnitTest --console=plain -q
./gradlew :core:telemetry-engine:testDebugUnitTest --console=plain -q
./gradlew :core:database:testDebugUnitTest --console=plain -q
./gradlew :app:lintDebug --console=plain -q
```

The documented helper form is `bash .claude/kit/gradle-check.sh :app:assembleDebug :app:testDebugUnitTest`. Screenshot references are validated with `:app:validateDebugScreenshotTest`; update references only for an intentional UI ticket with `:app:updateDebugScreenshotTest`.

Room tests use Robolectric and cover WAL, cascades, history deduplication, snapshot round trips, and 30-day maintenance behavior. Protobuf tests use committed API 35/36/37 fixtures under [`core/telemetry-engine/src/test/resources/proto`](../../core/telemetry-engine/src/test/resources/proto).

## Emulator and app launch

The documented AVDs are `batstats-api36`, `screenoff-api37`, and `pixel-api35`. The normal launch flow is:

```sh
emulator -avd batstats-api36 -no-window -no-audio &
adb wait-for-device shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'
./gradlew :app:installDebug -q
adb shell am start -n com.akane.inspiration/.MainActivity
```

The app manifest registers `rikka.shizuku.ShizukuProvider` and queries the Shizuku manager package. Install and start Shizuku on the emulator before testing full capture; without it the manager remains `NotInstalled` or `NotRunning`, while light capture still uses local battery state.

## Debug Shizuku and capture probes

`ProbeReceiver` exists only in the debug source set and requires `android.permission.DUMP` on its receiver. With a running debug app and Shizuku:

```sh
adb shell am broadcast -a com.akane.inspiration.debug.PROBE_REQUEST_PERMISSION \
  -n com.akane.inspiration/.debug.ProbeReceiver
adb shell am broadcast -a com.akane.inspiration.debug.PROBE_CAPTURE \
  -n com.akane.inspiration/.debug.ProbeReceiver
adb shell am broadcast -a com.akane.inspiration.debug.PROBE_DIFF \
  -n com.akane.inspiration/.debug.ProbeReceiver
adb logcat -d -s 'InspirationProbe:*'
```

`PROBE_CAPTURE` exercises the full capture/history path and Room persistence; `PROBE_DIFF` resolves stored references and reports validity and sleep/delta values. The ordinary `PROBE` action retains the Phase 1 bounded IPC/calibration/dump probe. Outputs report availability/reasons rather than throwing for unavailable telemetry.

The charging job is armed by BOOT_COMPLETED/MY_PACKAGE_REPLACED and can be inspected with `dumpsys jobscheduler`. The app disables cloud backup and device transfer in [`app/src/main/AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml), [`app/src/main/res/xml/backup_rules.xml`](../../app/src/main/res/xml/backup_rules.xml), and [`app/src/main/res/xml/data_extraction_rules.xml`](../../app/src/main/res/xml/data_extraction_rules.xml).

The emulator setup recorded in [`PROGRESS.md`](../../PROGRESS.md) uses the Shizuku APK's `lib/<abi>/libshizuku.so` started with adb. Emulator batteries are synthetic; Phase 2 capture/job evidence covers API 35, 36, and 37, not production hardware.
