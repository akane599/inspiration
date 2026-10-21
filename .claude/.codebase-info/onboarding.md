# Onboarding
*Last Updated: 2026-10-22*

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

Release: `bash .claude/kit/gradle-check.sh :app:assembleRelease`. It is signed only when `~/.gradle/gradle.properties` has the `INSPIRATION_RELEASE_*` entries (otherwise unsigned); check with `apksigner verify --print-certs` (certificate SHA-256 `88292f23…4d723` from 0.2.5 on; 0.1.0–0.2.4 used the lost key `1d344dd2…3de60`). The [`release`](../skills/release/SKILL.md) skill holds the whole delivery procedure:
- version bump and full gate;
- certificate pin check;
- same-key in-place upgrade check from the previous GitHub release on redroid;
- the A03 history replay check over the upgraded database;
- mechanical candidate selection (`scripts/release-candidate.sh`): `gate <version> -- <command>` runs the gate from a clean main checkout on `main` and records HEAD, exit status and the hash of the `app-release.apk` it wrote in `build/release/<v>/gate-result`; `stage <approved-sha> <version>` accepts only that APK at the approved commit (plus at most one +1 version-bump commit), not on `scripts/forbidden-release-apks.sha256`, with the pinned signer and matching version, and writes `build/release/<v>/candidate.txt`; `verify <approved-sha> <version>` re-checks the staged file right before publishing;
- the GitHub release (the repo is public) and download verification.

Its `scripts/upgrade-check.sh` and `scripts/gate-summary.sh` also run on their own; their first run passed the v0.2.3→v0.2.4 upgrade.

`scripts/history-replay-check.sh` edits the app database. It checks that a re-armed history replay restores a missing START and doesn't append one after a stored row at its instant, using the three phases restore, suppress, restore. It runs only on a device named with `--serial` and `--disposable`, and refuses any model other than `redroid15_x86_64`. It backs up the database first, installs only with `--install`, and edits nothing until a FULL capture proves Shizuku works. A `02e225d` build fails it; `9ee56de` passes.

Diagnostics tests run under Robolectric (org.json and resources are needed by the dictionary loader and story writer), as do `:core:designsystem`'s touch-guard UI test and the Room tests. Room tests use Robolectric and cover WAL, cascades, history deduplication, snapshot round trips, and 30-day maintenance behavior. Schema v5 adds nullable UID identity proof through `MIGRATION_4_5`; existing v4 data must migrate without a clear or inferred historical proof. Schema v6 (`MIGRATION_5_6`) only adds the `history_event(kind, wallMs)` index. Not yet checked on a device: the v5 to v6 upgrade, the R-08 binding rework and the R-03 screen captures need the `release` skill's same-key upgrade check (`AUDIT_REPORT.md` remediation log). Run the focused database gate with `bash .claude/kit/gradle-check.sh :core:database:testDebugUnitTest --tests '*InspirationDatabaseTest' --tests '*RoomSnapshotStoreTest'`. The current audit's same-key v0.2.1→v0.2.2 redroid upgrade evidence is recorded in [`AUDIT_REPORT.md`](../../AUDIT_REPORT.md) under "Current-build Mobile MCP/redroid verification". Protobuf tests use committed API 35/36/37 fixtures under [`core/telemetry-engine/src/test/resources/proto`](../../core/telemetry-engine/src/test/resources/proto).

## Device and app launch

The always-on redroid device (Android 15 / API 35 + Shizuku 13.6.0) is the only device: the host has no `/dev/kvm`, so the Android emulator can't run. adb lists the container as `emulator-5554` (its automatic scan of local port 5555) and, after `adb connect localhost:5555`, also as `localhost:5555`; both are the same container, so set `ANDROID_SERIAL` to either one, because a bare `adb` fails with "more than one device". Root shell/SQLite checks use `adb shell su 0`. Debug and release APKs use different signing keys, so switching variants requires uninstalling the app and loses its data; do not use that switch or `pm clear` to claim an in-place migration test. Use a same-key upgrade when checking preservation. The container has no physical battery, so it proves wiring and persistence, not hardware accuracy.

Earlier device evidence in [`PROGRESS.md`](../../PROGRESS.md) came from the API 35/36/37 AVDs (`pixel-api35`, `batstats-api36`, `screenoff-api37`, plus `batstats-api31`/`batstats-api33`) on a host that had KVM; they can't boot here, so behaviour on API levels other than 35 is reported as not checked on a device. The normal launch flow is:

```sh
export ANDROID_SERIAL=localhost:5555   # after `adb connect localhost:5555`, or use emulator-5554
./gradlew :app:installDebug -q
adb shell am start -n com.akane.inspiration/.MainActivity
```

The app manifest registers `rikka.shizuku.ShizukuProvider` and queries the Shizuku manager package. Shizuku is installed on redroid; start its server before testing full capture; without it the manager remains `NotInstalled` or `NotRunning`, while light capture still uses local battery state.

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

The Shizuku server is started with adb from the Shizuku APK's `lib/<abi>/libshizuku.so` (`x86_64` on redroid); the device-probe script does this when the server isn't running.

To test the permission flow from scratch on Shizuku 13.5, revoking in the server config alone is not enough: the server re-grants from the runtime permission when it starts. Stop `shizuku_server`, run `pm revoke com.akane.inspiration moe.shizuku.manager.permission.API_V23`, write `{"version":2,"packages":[]}` to `/data/user_de/0/com.android.shell/shizuku.json`, then start the server again. The Cockpit then shows "Shizuku permission needed", and a grant in Shizuku's app management screen is picked up on the next `onStart`, without a restart. Neither redroid nor the earlier emulators have a real battery; the Phase 2 capture/job evidence (API 35, 36 and 37 emulators) covers wiring, not production hardware.

## Device-probe skill

[`.claude/skills/device-probe/SKILL.md`](../skills/device-probe/SKILL.md) wraps device connection, install, Shizuku start, optional permission grant, broadcast and logcat capture in [`scripts/device-probe.sh`](../skills/device-probe/scripts/device-probe.sh):

```sh
bash .claude/skills/device-probe/scripts/device-probe.sh --probe PROBE_INSIGHTS --clear --grant \
  --extras "--es reference_kind since_unplugged"
```

It targets `ANDROID_SERIAL`, or `localhost:5555` when unset, running `adb connect` if that transport is missing; `--serial HOST:PORT` overrides both, and `--avd` exits 2 because no emulator can boot. It saves JSON lines to `build/device-evidence/api<SDK>-<PROBE>.jsonl` (gitignored; `api35-…` on redroid), ending with `{"probe":"done"}` or `timeout`. Exit codes: 0 finished, 2 setup failed (device unreachable, installDebug, app missing under `--no-install`, Shizuku), 3 no terminal line. Use `--clear` for an install from before the Phase 3 in-place schema change (`pm clear`; later schema changes migrate), with `--grant` because clearing can drop the Shizuku permission. Only the orchestrator drives the device, one adb run at a time. redroid has no real battery, so evidence proves wiring and parsing, not thresholds; its kernel battery sysfs reads come back unavailable. On the earlier emulators the batterystats fixtures carry no signal bins or UID sensors, so R3 reports `signalAvailable = false` and R4 is `NotEvaluable("source_absent")`.
