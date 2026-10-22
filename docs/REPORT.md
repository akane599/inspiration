# Inspiration 0.2.9 release-candidate verification — AVD smoke test

Date: 2026-10-22 (13:00–13:20 +03). Host: Kubuntu, KVM usable. Results dir: this directory.
Scope: APK identity verification + bounded smoke test on three existing AVDs + Shizuku install/authorize/capture.
Out of scope (per brief): redroid migration/replay, preference preservation, physical battery measurements, source audit.

## 1. Artifact verification — PASS

| Check | Expected | Observed |
|---|---|---|
| File | inspiration-0.2.9.apk | present, 4,705,864 B, mtime 12:46:13 — unchanged after testing (`sha256sum -c` OK) |
| SHA-256 | 4c00f898…a389f | 4c00f898af3d4e9683fef4d6ffdce7ba323b840ad03ca1ee698d4640494a389f (matches expected and adjacent .sha256 file) |
| package | com.akane.inspiration | com.akane.inspiration |
| versionName / versionCode | 0.2.9 / 11 | 0.2.9 / 11 |
| Signer cert SHA-256 | 88292f23…4d723 | 88292f2344df4095f69e70db728ece8ac6ffcde4564d355a20181391d054d723 (v2 only; v1/v3/v4 absent; RSA 4096; CN=akane, O=Inspiration) |
| minSdk / targetSdk / compileSdk | — | 31 / 36 / 37 |
| On-device base.apk SHA-256 after install | — | identical on all three AVDs |

Tools: `build-tools/37.0.0/aapt`, `build-tools/37.0.0/apksigner`, `platform-tools/adb 37.0.1` (the already-running adb server was this binary; it was not restarted). Evidence: `sdk/apk-verification.txt`, `sdk/manifest-xmltree.txt`, `sdk/sdk-inventory.txt`.

## 2. Emulator inventory

No emulators were running before the test. All three AVDs were empty (no Inspiration, no Shizuku) — every install below was a **fresh install**; no in-place/upgrade path and therefore no migration evidence from this run.

| AVD | Serial used | API (getprop) | Android | Build id | codename / preview_sdk | Image pkg |
|---|---|---|---|---|---|---|
| pixel-api35 | emulator-5600 | 35 | 15 | AE3A.240806.043 | REL / 0 (stable) | android-35 google_apis x86_64 rev 9 |
| pixel-api36 | emulator-5602 | 36 | 16 | BE2A.250530.026.F3 | REL / 0 (stable) | android-36 google_apis x86_64 rev 7 |
| pixel-api37 | emulator-5604 | 37 | 17 | CE2A.260420.051.A3 | REL / 0 (stable) | android-37.0 google_apis x86_64 rev 7 |

All images are userdebug, google_apis (no Play), Pixel 8 profile 1080x2400 @420dpi, emulator 37.2.12. Launched headless (`-no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect`), one at a time, each with an explicit `-port`; every device command used `adb -s <serial>`. `-no-snapshot` means existing quick-boot snapshots were neither loaded nor overwritten; disk changes (installs) persist.

## 3. Test matrix

Legend: PASS / FAIL / BLOCKED / NOT RUN. Same procedure on each AVD (scripts in `sdk/phaseA.sh`, `sdk/phaseB.sh`; every tap was driven from a `uiautomator dump` text match, no blind coordinates).

| Test | api35 | api36 | api37 | Notes |
|---|---|---|---|---|
| Fresh install of verified APK | PASS | PASS | PASS | `logs/install.txt` |
| Cold launch (`am start -W`, LaunchState COLD) | PASS | PASS | PASS | 448 ms on api35; lands on Cockpit |
| Onboarding | PASS (n/a) | PASS (n/a) | PASS (n/a) | App has no onboarding flow; first run = main screen + POST_NOTIFICATIONS prompt (`screens/01-cold-launch.png`) |
| Notification permission — deny path | PASS | PASS | PASS | "Don't allow" → app continues, no re-prompt, Toolkit shows "Notifications are blocked / Allow in settings" |
| Notification permission — enable via Settings | PASS | PASS | PASS | "Allow in settings" opens AppNotificationSettings; toggling on → granted=true; status notification posted (`logs/status-notification.txt`) |
| Navigate Cockpit / X-Ray / Sleep Lab / Health / Toolkit | PASS | PASS | PASS | `screens/04..07-tab-*.png` |
| Shizuku unavailable — clear behaviour | PASS | PASS | PASS | Cockpit: "Shizuku or Sui not installed"; X-Ray "Capture now" → "Couldn't capture fresh data. Showing the last capture."; Health/Toolkit explain Shizuku is needed (`screens/08-capture-no-shizuku.png`) |
| Background (HOME) → foreground | PASS | PASS | PASS | LaunchState WARM, UI restored (see Obs. 1) |
| Process restart (force-stop → relaunch) | PASS | PASS | PASS | LaunchState COLD, new pid, StatusNotificationService restarted |
| Shizuku install + adb start | PASS | PASS | PASS | see §4 |
| Shizuku authorization (normal dialog) | PASS | PASS | PASS | "Allow all the time" → Cockpit "Binder active · UID 2000" |
| Full capture with Shizuku | PASS | PASS | PASS | see §4 |
| Health kernel data via Shizuku | PASS | PASS | PASS | Kernel full capacity 3000 mAh, cycles 10 (emulator fake battery); design capacity "This kernel does not report it." |
| Crash / ANR evidence | none | none | none | crash buffer empty; no FATAL/ANR in time-scoped main log; only `am_kill` is my own force-stop |
| Sleep Lab with data | NOT RUN | NOT RUN | NOT RUN | needs ≥30 min screen-off; app says so clearly |
| Charge curve (Health) | NOT RUN | NOT RUN | NOT RUN | needs a real charge session |
| Toolkit doze profile apply/undo | NOT RUN | NOT RUN | NOT RUN | persists system settings across uninstall; deliberately not exercised in a smoke test |

## 4. Shizuku

| Item | Value (identical on all three) |
|---|---|
| Source | https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk (official GitHub release, published 2025-05-25) |
| File SHA-256 | 6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f (`sdk/shizuku-apk-sha256.txt`) |
| Package / version | moe.shizuku.privileged.api 13.6.0.r1086.2650830c (versionCode 1086), signer CN=Rikka, cert SHA-256 268b5590e868fb08bae7e0ac413564cd1ff88f5ccff74af9dbd0dc918e30db30 |
| Startup method | The adb method shown by Shizuku 13.6's own "View command" dialog: `adb -s <serial> shell /data/app/…/moe.shizuku.privileged.api-…/lib/x86_64/libshizuku.so` (`logs/shizuku-start.txt`). Note: the older `/sdcard/Android/data/…/start.sh` path does not exist in 13.6. No root, no wireless-debugging pairing needed, no user interaction required. |
| Server state | `shizuku_server` running as `shell` (UID 2000); app shows "Shizuku is running · Version 13.5, adb" (13.5 is the server API version string) |
| Authorization | Inspiration "Grant access" → Shizuku RequestPermissionActivity → "Allow all the time" (`screens/19-shizuku-permission-dialog.png`, `20-after-shizuku-grant.png`) |
| Capture result | Emulator battery driven with `dumpsys battery unplug / set level 90 / set level 85` (reset afterwards). Capture 1 → "No data for this interval yet" (expected, single snapshot). Capture 2 after 45 s → X-Ray Components list populated (Sensors/Screen/CPU/Idle/Mobile radio/Other with mAh and %), Apps list populated (Android system, Inspiration), Cockpit "Battery story: Over this 1 minute you used N% of your battery." (`screens/26-xray-after-capture2.png`, `27-xray-apps.png`, `28-cockpit-after-captures.png`). Logcat shows Shizuku `UserServiceManager` starting `com.akane.inspiration:telemetry` (TelemetryUserService, version=11) and each instance ending with `System.exit called, status: 0` (`logs/capture-logcat.txt`). |

Shizuku is not an application defect dependency: with it absent the app degrades clearly (see matrix).

## 5. Observations (not failures; worth a look)

1. **Tab resets to Cockpit after HOME → relaunch.** On all three AVDs the relaunch via launcher intent reported LaunchState WARM (activity re-created, process kept) and the app returned to Cockpit although Toolkit was open before HOME. Could be an `am start` artefact vs a real launcher tap; low severity. Manual confirm: open Toolkit, press Home, tap the app icon.
2. **Hidden-API link denials on API 36/37** (`logs/hiddenapi-denials.txt`): dex2oat logs `hiddenapi … api=blocked … linking: denied` for ~27 members (BatteryUsageStats*, BatteryStats$HistoryItem fields, IBatteryStats, BatteryStatsHistoryIterator). Captures still succeed, so these calls evidently run in the Shizuku shell-UID telemetry process or via reflection. Informational; not logged on API 35. If any of those were ever called in the app's own process they would throw.
3. **"Battery story" baseline depends on unplug/level ordering.** api35 reported 5 % (90→85), api36/37 reported 15 % (100→85) for the same scripted sequence (`unplug` immediately followed by `set level 90`). Artefact of the artificial sequence (level jump within ~1 s of the unplug broadcast), not reproducible as a defect; a real device cannot do this.
4. API 35 only: `WindowOnBackDispatcher: OnBackInvokedCallback is not enabled for the application` warning (predictive back not opted in). Cosmetic.
5. `Unsupported class loader: java.lang.Class<d20>` and `Not starting debugger since process cannot load the jdwp agent` are ART/release-build noise; `idmap2 … avc denied` lines come from Shizuku's shell-UID process, not the app.

## 6. Reproducible failures

None found.

## 7. Manual steps still needed

- None required for Shizuku on these AVDs (adb start method needs no pairing).
- Optional: confirm Observation 1 with a real launcher tap; exercise Toolkit doze profiles + undo on a disposable AVD if desired.

## 8. Emulators started and final state

| AVD | Started by me | Final state | Left installed |
|---|---|---|---|
| pixel-api35 | yes (13:00, port 5600) | stopped via `adb -s emulator-5600 emu kill` at 13:09 | Inspiration 0.2.9 (fresh), Shizuku 13.6.0 authorized; notifications allowed |
| pixel-api36 | yes (13:09, port 5602) | stopped at 13:14 | same |
| pixel-api37 | yes (13:14, port 5604) | stopped at 13:19 | same |

No emulator processes remain; the pre-existing adb server (pid 18960) was never killed. AVDs were not wiped; quick-boot snapshots untouched (`-no-snapshot`). Virtual battery reset to AC/100 % before each stop. No app data was cleared and nothing was uninstalled. Candidate APK and checksum file unchanged. Nothing was pushed, tagged or published.

## 9. Evidence layout

```
REPORT.md
sdk/            apk-verification.txt, aapt-badging-full.txt, manifest-xmltree.txt, sdk-inventory.txt,
                shizuku-apk-sha256.txt, h.sh phaseA.sh phaseB.sh (automation used), emu-api3x.log
api35|36|37/    screens/*.png (00..29), ui-*.xml (uiautomator dumps),
                logs/ install.txt launch1.txt getprop.txt logcat-start-marker.txt logcat-main-scoped.txt
                      logcat-crash.txt capture-logcat.txt hiddenapi-denials.txt shizuku-install.txt
                      shizuku-start.txt shizuku-version.txt status-notification.txt dumpsys-package-inspiration.txt
```
