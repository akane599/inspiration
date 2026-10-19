# Onboarding
*Last Updated: 2026-10-19*

## Read first

1. Read [`docs/plans/phase-4.md`](../../docs/plans/phase-4.md) for the design system and charts (tokens, chart contract, static/overlay split); read [`docs/plans/phase-3.md`](../../docs/plans/phase-3.md) for the insight layer (rules, dictionary, story, schema additions); read [`docs/plans/phase-2.md`](../../docs/plans/phase-2.md) for the selected capture, validity, Room, and module boundaries; consult [`docs/plans/phase-1.md`](../../docs/plans/phase-1.md) for the privileged IPC contract.
2. Read [`architecture.md`](architecture.md), [`modules.md`](modules.md), [`communication.md`](communication.md), and [`database.md`](database.md) before changing capture, IPC, or persistence wiring.
3. Use [`PROGRESS.md`](../../PROGRESS.md) for durable decisions and API 35/36/37 device evidence. Work items and blockers live on the Sidequest board, as described by [`CLAUDE.md`](../../CLAUDE.md).

## Build and tests

From the repository root:

```sh
./gradlew :app:assembleDebug --console=plain -q
./gradlew :app:testDebugUnitTest --console=plain -q
./gradlew :core:telemetry-engine:testDebugUnitTest --console=plain -q
./gradlew :core:database:testDebugUnitTest --console=plain -q
./gradlew :core:diagnostics:testDebugUnitTest --console=plain -q
./gradlew :core:designsystem:testDebugUnitTest --console=plain -q
./gradlew :app:lintDebug --console=plain -q
```

The documented helper form is `bash .claude/kit/gradle-check.sh :app:assembleDebug :app:testDebugUnitTest`. When other sessions or worktrees may build too, run it through [`bash .claude/scripts/gradle-slot.sh <tasks>`](../scripts/gradle-slot.sh). That script holds one of the host-wide Gradle slots (`GRADLE_SLOTS`, default 2) and then calls `gradle-check.sh`. Screenshot references are validated with `:app:validateDebugScreenshotTest`; update references only for an intentional UI ticket with `:app:updateDebugScreenshotTest`. The design-system module has its own references: `:core:designsystem:validateDebugScreenshotTest` and, for an intentional change, `:core:designsystem:updateDebugScreenshotTest` (every preview needs an explicit `heightDp`).

Release: `bash .claude/kit/gradle-check.sh :app:assembleRelease`. It is signed only when `~/.gradle/gradle.properties` has the `INSPIRATION_RELEASE_*` entries (otherwise unsigned); check with `apksigner verify --print-certs` (certificate SHA-256 `1d344dd2…3de60`). The [`release`](../skills/release/SKILL.md) skill holds the whole delivery procedure:
- version bump and full gate;
- certificate pin check;
- same-key in-place upgrade check from the previous GitHub release on redroid;
- the GitHub release and download verification.

Its `scripts/upgrade-check.sh` and `scripts/gate-summary.sh` also run on their own. Their first run passed the v0.2.3→v0.2.4 upgrade.

Diagnostics tests run under Robolectric (org.json and resources are needed by the dictionary loader and story writer). Room tests use Robolectric and cover WAL, cascades, history deduplication, snapshot round trips, and 30-day maintenance behavior. Schema v5 adds nullable UID identity proof through `MIGRATION_4_5`; existing v4 data must migrate without a clear or inferred historical proof. Run the focused database gate with `bash .claude/kit/gradle-check.sh :core:database:testDebugUnitTest --tests '*InspirationDatabaseTest' --tests '*RoomSnapshotStoreTest'`. The current audit's same-key v0.2.1→v0.2.2 redroid upgrade evidence is recorded in [`AUDIT_REPORT.md`](../../AUDIT_REPORT.md) under "Current-build Mobile MCP/redroid verification". Protobuf tests use committed API 35/36/37 fixtures under [`core/telemetry-engine/src/test/resources/proto`](../../core/telemetry-engine/src/test/resources/proto).

## Emulator and app launch

The always-on redroid device is Android 15 + Shizuku 13.6.0 at `ANDROID_SERIAL=localhost:5555`; select it explicitly when other devices are connected. Root shell/SQLite checks use `adb shell su 0`. Debug and release APKs use different signing keys, so switching variants requires uninstalling the app and loses its data; do not use that switch or `pm clear` to claim an in-place migration test. Use a same-key upgrade when checking preservation. The container has no physical battery, so it proves wiring and persistence, not hardware accuracy.

The documented AVDs are `batstats-api36`, `screenoff-api37`, and `pixel-api35`. `batstats-api31` (Android 12) and `batstats-api33` (Android 13) cover the bottom of the minSdk range, for example bg-dexopt below API 33. Both are rootable `google_apis` images. Never pass `-port 5554`: that emulator's adb port is 5555, which redroid holds, so adb never sees it. Leave `-port` out or use 5556 or above. The normal launch flow is:

```sh
./gradlew --stop   # free daemon memory first: 15 GiB RAM + zram/swapfile; Gradle limits are in the android-build live rule
emulator -avd batstats-api36 -memory 2048 -no-window -no-audio &
adb wait-for-device shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'
./gradlew :app:installDebug -q
adb shell am start -n com.akane.inspiration/.MainActivity
```

The app manifest registers `rikka.shizuku.ShizukuProvider` and queries the Shizuku manager package. Install and start Shizuku on the emulator before testing full capture; without it the manager remains `NotInstalled` or `NotRunning`, while light capture still uses local battery state.

## Design gallery (debug)

`DesignGalleryActivity` is a debug-only, exported activity showing the scrubbing chart and hypnogram with synthetic data:

```sh
./gradlew :app:installDebug -q
adb shell am start -n com.akane.inspiration/.debug.DesignGalleryActivity
adb logcat -d -s InspirationGallery:*
```

The `InspirationGallery` tag reports recomposition counts; for frame stats while dragging, run `adb shell setprop debug.hwui.profile true`, relaunch, drag, then `adb shell dumpsys gfxinfo com.akane.inspiration` (percentiles plus Draw/Prepare/Process/Execute columns; `framestats` returned no rows on the API 36 emulator). The emulator renders in software, so compare relative numbers only.

## Debug Shizuku and capture probes

`ProbeReceiver` exists only in the debug source set and requires `android.permission.DUMP` on its receiver. With a running debug app and Shizuku:

```sh
adb shell am broadcast -a com.akane.inspiration.debug.PROBE_REQUEST_PERMISSION \
  -n com.akane.inspiration/.debug.ProbeReceiver
adb shell am broadcast -a com.akane.inspiration.debug.PROBE_CAPTURE \
  -n com.akane.inspiration/.debug.ProbeReceiver
adb shell am broadcast -a com.akane.inspiration.debug.PROBE_DIFF \
  -n com.akane.inspiration/.debug.ProbeReceiver
adb shell am broadcast -a com.akane.inspiration.debug.PROBE_INSIGHTS \
  --es reference_kind since_unplugged -n com.akane.inspiration/.debug.ProbeReceiver
adb logcat -d -s 'InspirationProbe:*'
```

`PROBE_CAPTURE` exercises the full capture/history path and Room persistence; `PROBE_DIFF` resolves stored references and reports validity and sleep/delta values. `PROBE_INSIGHTS` runs the diff, `IntervalMetricsCalculator`, `DiagnosticRuleEngine`, `BatteryStoryBuilder` and dictionary lookups for the top wakelocks, logging `insightsMetrics`, `insight`, `insightsReport` (with `notEvaluable` and `skippedInvalidUids`), `story` and `dictionary` lines. `PROBE_DIFF` and `PROBE_INSIGHTS` accept the string extra `reference_kind` (`since_unplugged`, `since_screen_off`, `screen_on_session`, `last_24h`); without it they pair the latest two snapshots, which is often a near-zero LIGHT interval. The ordinary `PROBE` action retains the Phase 1 bounded IPC/calibration/dump probe. Outputs report availability/reasons rather than throwing for unavailable telemetry.

The charging job is armed by BOOT_COMPLETED/MY_PACKAGE_REPLACED and can be inspected with `dumpsys jobscheduler`. The app disables cloud backup and device transfer in [`app/src/main/AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml) and [`app/src/main/res/xml/data_extraction_rules.xml`](../../app/src/main/res/xml/data_extraction_rules.xml).

The emulator setup recorded in [`PROGRESS.md`](../../PROGRESS.md) uses the Shizuku APK's `lib/<abi>/libshizuku.so` started with adb.

To test the permission flow from scratch on Shizuku 13.5, revoking in the server config alone is not enough: the server re-grants from the runtime permission when it starts. Stop `shizuku_server`, run `pm revoke com.akane.inspiration moe.shizuku.manager.permission.API_V23`, write `{"version":2,"packages":[]}` to `/data/user_de/0/com.android.shell/shizuku.json`, then start the server again. The Cockpit then shows "Shizuku permission needed", and a grant in Shizuku's app management screen is picked up on the next `onStart`, without a restart. Emulator batteries are synthetic; Phase 2 capture/job evidence covers API 35, 36, and 37, not production hardware.

## Device-probe skill

[`.claude/skills/device-probe/SKILL.md`](../skills/device-probe/SKILL.md) wraps boot, install, Shizuku start, optional permission grant, broadcast and logcat capture in [`scripts/device-probe.sh`](../skills/device-probe/scripts/device-probe.sh):

```sh
bash .claude/skills/device-probe/scripts/device-probe.sh --avd batstats-api36 --probe PROBE_INSIGHTS --clear --grant \
  --extras "--es reference_kind since_unplugged"
```

It saves JSON lines to `build/device-evidence/api<SDK>-<PROBE>.jsonl` (gitignored), ending with `{"probe":"done"}` or `timeout`. Exit codes: 0 finished, 2 setup failed, 3 no terminal line. Use `--clear` for an install from before the Phase 3 in-place schema change (`pm clear`; later schema changes migrate), with `--grant` because clearing can drop the Shizuku permission. Only the orchestrator runs emulators, one at a time; emulator batteries are synthetic, so evidence proves wiring and parsing, not thresholds. On the emulator the batterystats fixtures carry no signal bins or UID sensors, so R3 reports `signalAvailable = false` and R4 is `NotEvaluable("source_absent")`.
