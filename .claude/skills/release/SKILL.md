---
name: release
description: Ship an Inspiration delivery end to end. Bump versionCode/versionName, run the full gate, build the signed release APK and check its certificate pin, run a same-key in-place upgrade check from the previous GitHub release on the redroid device, then publish the APK and its .sha256 as a GitHub release on akane599/inspiration and verify the download. Use this whenever work is ready to deliver, or the user says "release", "ship it", "cut 0.x.y", "publish the APK", "bump the version", "make a GitHub release", or "is the APK up?". Use it at the end of a story or an audit remediation that ends in a delivery, even if the request only mentions the APK or the version number.
---

# Release

In this project every delivery ends the same way: a version bump, a signed release APK and a GitHub release on the private `akane599/inspiration` repo, with the APK and its `.sha256` attached. That is the user's standing instruction (auto memory: "Post-release push + APK"). This skill is that procedure, plus the step most likely to be skipped: a same-key in-place upgrade from the previous release. A user who updates the app keeps their database, preferences and Shizuku grant, and nothing else in the gate tests that path.

Publishing to this private repo is pre-authorized. Every other outward-facing action still needs the user. Work on `main`, the integration branch; commit and push freely.

## Ground rules

- **Signing secrets stay unseen.** Gradle reads the four `INSPIRATION_RELEASE_*` properties from `~/.gradle/gradle.properties`. Never read, print or commit that file, a keystore, `local.properties` or `.env*`. If release packaging fails over missing signing keys, tell the user; don't work around it.
- **One certificate, forever.** Every release from 0.2.5 on is signed with SHA-256 `88292f2344df4095f69e70db728ece8ac6ffcde4564d355a20181391d054d723`. 0.1.0–0.2.4 used a lost key (`1d344dd2…3de60`), so the upgrade check's previous tag must be v0.2.5 or later. A different key can't upgrade an existing install: Android refuses it, and the user's only way out is uninstalling, which loses all their data. A mismatch stops the release.
- **The upgrade check uses redroid** (`ANDROID_SERIAL=localhost:5555`), which the user lets you use without asking. It uninstalls the app there, which clears the app's data on the device. It also restarts the Shizuku server once, which briefly drops other apps' Shizuku connections. Say both in the report.

## Steps

### 1. Preconditions

- Check that `git status` is clean apart from the known kit files: `.claude/settings.json`, `.codex/android-kit/*` and `.claude/worktrees/`. Everything that ships must be committed.
- `git fetch`, then check that `main` contains `origin/main`.
- Find the previous release: `gh release list -R akane599/inspiration --limit 1`. That tag is the upgrade check's starting point.
- List what ships: `git log --oneline <prev-tag>..HEAD`.

### 2. Version bump

In `app/build.gradle.kts`:
- `versionCode` goes up by exactly 1.
- `versionName` follows semver: a patch bump for fixes (0.2.3 → 0.2.4), a minor bump for a new feature phase (0.1.0 → 0.2.0).

Commit it alone as `chore(release): bump version to X.Y.Z (N)`.

### 3. Full gate

The gate runs alone, as one invocation:
- First end orphaned `GradleWorkerMain` processes and stray Kotlin daemons (redroid-gradle-memory rule).
- Check that no other Gradle invocation is running: `pgrep -f '[G]radleWrapperMain'` finds nothing. If one is, wait for it or use `--max-workers=4`.

```bash
bash .claude/kit/gradle-check.sh testDebugUnitTest lintDebug validateDebugScreenshotTest :app:assembleDebug :app:minifyReleaseWithR8 :app:assembleRelease --max-workers=8 --rerun-tasks
bash .claude/skills/release/scripts/gate-summary.sh
```

`--rerun-tasks` makes every number come from this commit, not from a cached task. `gate-summary.sh` prints the totals the notes and PROGRESS cite: per-module test counts, failures, screenshot references and `:app` lint. A failing gate means no release; fix the code first.

### 4. Check the APK and stage the assets

```bash
v=X.Y.Z; bt=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
mkdir -p build/release/$v && cp app/build/outputs/apk/release/app-release.apk build/release/$v/inspiration-$v.apk
"$bt/apksigner" verify --print-certs build/release/$v/inspiration-$v.apk | grep 'SHA-256'
"$bt/aapt2" dump badging build/release/$v/inspiration-$v.apk | head -1
(cd build/release/$v && sha256sum inspiration-$v.apk > inspiration-$v.apk.sha256)
```

- The certificate must equal the pin above. Note that build-tools 37 prints `V2 Signer: certificate SHA-256 digest: …`, not `Signer #1 …`.
- The badging line must show the bumped versionCode/versionName.
- The `.sha256` holds the bare file name, so `sha256sum -c` works in the download directory.

### 5. Upgrade check (required before publishing)

```bash
bash .claude/skills/release/scripts/upgrade-check.sh <prev-tag> build/release/$v/inspiration-$v.apk
```

What the script does:
1. Downloads the previous release and checks it against its own `.sha256`.
2. Confirms both APKs carry the same certificate.
3. Installs the previous release cleanly and grants it Shizuku and notifications.
4. Lets the previous release store data: an app-open full capture plus screen off/on snapshots.
5. Upgrades in place with `adb install -r` and starts the new version.

It then checks:
- the install was in place (same first-install time, new versionCode);
- Room `user_version` and every table's row count only grew;
- the shared_prefs files survived;
- the new version stored a new snapshot;
- a full (Shizuku) capture still works;
- the process is alive and the crash buffer is clean.

Exit codes: 0 PASS, 1 a check failed, 2 setup problem. The report lands in `build/release-check/<prev>-to-<apk>/upgrade-check.txt`.

A FAIL is a real bug for every user who updates, so it blocks the release. A WARN means a check couldn't run, for example the privileged path when Shizuku wasn't granted. Fix the setup and run it again, or list it under "Not checked" in the notes. Never present a warning as a pass.

### 6. Release notes

Write `build/release/$v/notes.md` for the user, not for the code. The structure follows the earlier releases (`gh release view v0.2.4 -R akane599/inspiration`):

- **Title line and one sentence** on what this release is.
- **Highlights:** the changes a user notices. Say what was wrong before and what happens now.
- **Other fixes:** grouped by screen or area, in short bullets.
- **Compatibility:**
  - Room schema change or not.
  - Toolkit API version.
  - New preference files.
  - Dependency, SDK or toolchain changes.
- **Verification:**
  - The gate numbers from `gate-summary.sh`.
  - The certificate.
  - The upgrade check's result, naming the previous version.
  - Other device checks run for this release.
  - An honest **Not checked on a device** list. Battery-less redroid can't prove rates, capacity or real fuel-gauge behaviour. API 31–34, 36 and 37 behaviour can't be checked here: redroid is API 35 and the host has no KVM for an emulator.
- **Known follow-ups**, if any (AUDIT_REPORT.md, PROGRESS.md).

Take the substance from the commits since the previous tag, `PROGRESS.md` decisions and `AUDIT_REPORT.md`. Say only what the evidence supports.

### 7. Publish

```bash
git push origin main
sha=$(git rev-parse HEAD)
gh release create v$v -R akane599/inspiration --target "$sha" --title "Inspiration $v" \
  --notes-file build/release/$v/notes.md build/release/$v/inspiration-$v.apk build/release/$v/inspiration-$v.apk.sha256
```

- `--target` takes the full SHA. `gh` creates the tag on GitHub, so no local `git tag` is needed.
- If Sidequest is active, its hook blocks a manual tag: acquire the publish lock (`sidequest publish lock`) first and release it afterwards.

### 8. Verify the published release

```bash
d=$(mktemp -d) && gh release download v$v -R akane599/inspiration -D "$d" && (cd "$d" && sha256sum -c inspiration-$v.apk.sha256)
```

The downloaded APK must match the local hash. The upgrade check left this exact APK installed on redroid. To show the device runs the published bytes, compare against it:

```bash
adb shell pm path com.akane.inspiration
adb pull <path> "$d/installed.apk"
sha256sum "$d/installed.apk"
```

### 9. Record and report

- **PROGRESS.md:** add the "Baseline" line in the existing form: `YYYY-MM-DD @ X.Y.Z (full gate at <sha>, --rerun-tasks)`, then build status, test counts per module, screenshots and lint. Commit and push it.
- **Auto memory (`redroid-test-device`):** update the "current install" line, since the upgrade check changed what is installed.
- **Report to the user:** the version and release URL, the APK's local path and sha256, the certificate, the gate numbers, the upgrade check result, the not-checked items, and the device state change.

## Not part of this skill

- Play Store submission and CHANGELOG.md. This project ships through GitHub releases.
- Manual `git tag`.
- Changing the signing key, or creating a keystore. If the keystore is missing, ask the user.
