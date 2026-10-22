---
name: release
description: Ship an Inspiration delivery end to end, under two explicit user gates (prepare a named version from an approved commit; publish the candidate they have seen). Bump versionCode/versionName, run the recorded full gate, select the one APK that gate wrote and check its hash, certificate pin and version, run a same-key in-place upgrade check and the history replay check from the previous GitHub release on the redroid device, then, once the user says publish, push and publish the APK and its .sha256 as a GitHub release on the public akane599/inspiration repository and verify the download. Use this whenever work is ready to deliver, or the user says "release", "ship it", "cut 0.x.y", "publish the APK", "bump the version", "make a GitHub release", or "is the APK up?". Use it at the end of a story or an audit remediation that ends in a delivery, even if the request only mentions the APK or the version number.
---

# Release

In this project every delivery ends the same way: a version bump, a signed release APK and a GitHub release on the `akane599/inspiration` repo, with the APK and its `.sha256` attached. That is the user's standing instruction, recorded in the PROGRESS.md decision log (2026-10-18). This skill is that procedure, plus the step most likely to be skipped: a same-key in-place upgrade from the previous release. A user who updates the app keeps their database, preferences and Shizuku grant, and nothing else in the gate tests that path.

The repository is **public** (`gh api repos/akane599/inspiration --jq .visibility`, checked 2026-10-22; its event feed shows no visibility change since it was created), so a release is visible to anyone the moment it is published. Earlier versions of this skill called it private.

Two explicit gates from the user, and nothing else counts as either:
- **prepare**: "prepare 0.2.9 from `<sha>`" (any wording that names the version and the approved commit) authorizes steps 1–6;
- **publish**: "publish 0.2.9" or "ship it", given after the user has seen the step 4 candidate line and the step 5 results, authorizes step 7 for that candidate only, plus the step 9 push of the PROGRESS.md record for it.

The `<approved-sha>` the scripts take is copied from the user's prepare message, never from `candidate.txt` or `git rev-parse`.

Approval of a merge, of a plan or of the evidence is neither. The blanket pre-authorization and the "commit and push freely" of earlier versions were written on the private-repo premise; until the user reconfirms them, push `main` only inside an authorized publish. Every other outward-facing action needs the user too. Work on `main`, the integration branch.

## Ground rules

- **Signing secrets stay unseen.** Gradle reads the four `INSPIRATION_RELEASE_*` properties from `~/.gradle/gradle.properties`. Never read, print or commit that file, a keystore, `local.properties` or `.env*`. If release packaging fails over missing signing keys, tell the user; don't work around it.
- **One certificate, forever.** Every release from 0.2.5 on is signed with SHA-256 `88292f2344df4095f69e70db728ece8ac6ffcde4564d355a20181391d054d723`. 0.1.0–0.2.4 used a lost key (`1d344dd2…3de60`), so the upgrade check's previous tag must be v0.2.5 or later. A different key can't upgrade an existing install: Android refuses it, and the user's only way out is uninstalling, which loses all their data. A mismatch stops the release.
- **The device checks use redroid** (`ANDROID_SERIAL=localhost:5555`), which the user lets you use without asking, and refuse any device whose `ro.product.model` is not `redroid15_x86_64` (through an SSH-forwarded adb server that serial can be the user's phone). The upgrade check uninstalls the app there, which clears Inspiration's data on that disposable device, and restarts the Shizuku server once, which drops other apps' Shizuku connections for a moment; the replay check edits the app database. Say all of that in the report. The device is shared with a peer session through the flock `$HOME/.cache/akastudio-emulator-5554.lock`: hold it once around the whole step 5 sequence and pass the descriptor down (`DEVICE_LOCK_FD`), so neither script takes it a second time.
- **Gradle daemons are shared too.** Never stop or kill Gradle or Kotlin daemons, even ones that look stray or orphaned: the peer AkaStudio session relies on them (no-stop agreement, 2026-10-22). `gradle-slot.sh` queues on the host-wide slots; `release-candidate.sh` holds `build/release/.lock` so one gate, stage or verify at a time uses this checkout's release output.

## Steps

### 1. Preconditions

- The user has given the **prepare** gate above, naming the version and the **approved commit** (a hex SHA; the scripts refuse `HEAD` and branch names). A message that reviews evidence or says a change "looks ready" authorizes neither a merge nor a release.
- Check that `git status` is clean apart from the known kit files: `.claude/settings.json`, `.codex/android-kit/*` and `.claude/worktrees/`. Everything that ships must be committed. `release-candidate.sh` applies the same allowlist and refuses otherwise.
- `git fetch`, then check that `main` contains `origin/main`.
- Find the previous release: `gh release list -R akane599/inspiration --limit 1`. That tag is the upgrade check's starting point.
- List what ships: `git log --oneline <prev-tag>..HEAD`.

### 2. Version bump

In `app/build.gradle.kts`:
- `versionCode` goes up by exactly 1.
- `versionName` follows semver: a patch bump for fixes (0.2.3 → 0.2.4), a minor bump for a new feature phase (0.1.0 → 0.2.0).

Commit it alone as `chore(release): bump version to X.Y.Z (N)`. HEAD is now the **release commit**: the approved commit plus exactly this one bump. `release-candidate.sh stage` accepts only that shape (or the approved commit itself when the bump was already in it, as for 0.2.9) and refuses any other HEAD.

### 3. Full gate

The gate runs alone, as one invocation:
- Check whether another Gradle invocation is running: `pgrep -f '[G]radleWrapperMain'`. If one is, wait for it or use `--max-workers=4`. Do not stop daemons or end leftover worker processes to make room (ground rules): `gradle-slot.sh` waits for a free host-wide slot by itself.
- Nothing else may build in this checkout while the gate runs. `release-candidate.sh` takes `build/release/.lock` for the duration, but that excludes only other `release-candidate.sh` runs: a plain Gradle build in the same checkout can still write `app-release.apk` under the gate, and the record cannot detect it. Not running Gradle here during the gate is the control.
- Send the peer session its "gate starting" and "gate finished" notices (redroid-device-lock memory).

```bash
v=X.Y.Z
bash .claude/skills/release/scripts/release-candidate.sh gate $v            # --max-workers=4 when another Gradle invocation runs
bash .claude/skills/release/scripts/gate-summary.sh
```

`gate` runs the fixed full gate itself (`testDebugUnitTest lintDebug validateDebugScreenshotTest :app:assembleDebug :app:minifyReleaseWithR8 :app:assembleRelease --rerun-tasks`, through `.claude/scripts/gradle-slot.sh`; the task list is in the script and takes no argument), only from the main checkout on `main` with a clean tree, after deleting the previous `app-release.apk`. It records in `build/release/$v/gate-result` the HEAD it ran at, the task list, the worker count, the gate's exit status, and the SHA-256 of the `app-release.apk` the run wrote, and removes any `candidate.txt` staged from an earlier gate. If HEAD moved or the tree was dirty afterwards it exits 1 and writes no record; otherwise it exits with the gate's status. Step 4 accepts only a record of the full task list at this HEAD with status 0 whose hash is the file's hash now, so a failed gate, a narrower re-run, a stale APK, or one copied in from elsewhere cannot become the candidate. `--rerun-tasks` makes every number come from this commit, not from a cached task. `$v` does not survive between shell invocations: set it again in each step. `gate-summary.sh` prints the totals the notes and PROGRESS cite: per-module test counts, failures, screenshot references and `:app` lint. A failing gate means no release; fix the code first.

### 4. Identify the candidate APK, check it and stage the assets

The candidate is exactly one file: `app/build/outputs/apk/release/app-release.apk` as written by the recorded step 3 gate, in the main checkout, at the release commit. Several release-signed APKs with the same versionCode and versionName exist on this host (negative controls, remediation builds, worktree outputs), so **never choose an APK by file name, version string or badging alone, and never search `build/tmp`, `.claude/worktrees` or earlier `build/release/` directories for one.** The selection is mechanical:

```bash
v=X.Y.Z
bash .claude/skills/release/scripts/release-candidate.sh stage <approved-sha> $v
```

It exits 1 with the reason, and stages nothing, unless all of the following hold:
- it runs at the top level of the main checkout (not a linked worktree), on `main`, with a tree clean apart from the step 1 allowlist;
- the approved commit is given as a hex SHA, and HEAD is that commit, or that commit plus exactly one commit that changes only the `versionCode`/`versionName` lines of `app/build.gradle.kts`, with versionCode one higher;
- `build/release/$v/gate-result` records the full task list at this HEAD with status 0, and the SHA-256 it recorded for the APK equals the hash of the file now, which must be a regular file (not a symlink);
- that hash is not in `scripts/forbidden-release-apks.sha256`;
- the signer is the pinned certificate (build-tools 37 prints `V2 Signer: certificate SHA-256 digest: …`, which the script accepts);
- the badging is `com.akane.inspiration`, versionName `$v`, and the versionCode in `app/build.gradle.kts` at HEAD.

On success it copies the APK to `build/release/$v/inspiration-$v.apk`, writes `inspiration-$v.apk.sha256` (bare file name, so `sha256sum -c` works in a download directory) and `candidate.txt` (commit, approved commit, version, versionCode, SHA-256, signer, gate tasks and workers, paths), and prints one `candidate` line naming both commits. Every later step takes the APK and its hash from `candidate.txt`, never from the shell's memory. `gate-result` and `candidate.txt` are written only by the script, never by hand: a hand-written record is accepted by the checks (they read it) and would make every guarantee above void. What the record does not prove: that no other process wrote to the output path during the gate; the gate runs alone (step 3), which is the control for that.

`forbidden-release-apks.sha256` lists release-signed APKs on this host that must never be published or installed on anyone's phone (currently two A03 builds with migration 7→8 but without `9ee56de`). Add a line whenever a same-key, same-version APK that must not ship is built on purpose. `release-candidate.sh forbidden <apk>` checks any file against it.

Report the candidate to the user as the `candidate` line (commit, approved commit, version, versionCode, full SHA-256, signer) plus the gate log name and, after step 5, the upgrade-check and history-replay-check results. Publication (step 7) needs the **publish** gate for that candidate.

### 5. Upgrade check and history replay check (required before publishing)

Both run under one hold of the shared device lock, passed down as a descriptor so neither script takes it again; a FAIL or a setup problem in the upgrade check stops the sequence before the replay check, and the subshell's status is the sequence's (a busy lock is exit 2, not silence). The hold lasts the whole sequence, several minutes, which is longer than the peer's usual 120 s wait: tell the peer session before starting.

```bash
v=X.Y.Z
( flock -w 600 9 || { echo "device lock busy" >&2; exit 2; }
  DEVICE_LOCK_FD=9 bash .claude/skills/release/scripts/upgrade-check.sh <prev-tag> build/release/$v/inspiration-$v.apk || exit
  DEVICE_LOCK_FD=9 bash .claude/skills/release/scripts/history-replay-check.sh --serial localhost:5555 --disposable --apk build/release/$v/inspiration-$v.apk
) 9>>"$HOME/.cache/akastudio-emulator-5554.lock"; echo "step 5 exit $?"
```

What the upgrade check does:
1. Downloads the previous release and checks it against its own `.sha256`.
2. Confirms both APKs carry the same certificate.
3. Installs the previous release cleanly and grants it Shizuku and notifications.
4. Lets the previous release store data: an app-open full capture plus screen off/on snapshots.
5. Upgrades in place with `adb install -r` and starts the new version.

It then checks, and the report says which scope each line has:
- the install was in place (same first-install time, new versionCode);
- smoke scope: Room `user_version` did not go down and no table's row count went down (counts only; a count cannot show that a row's contents survived), and every shared_prefs file is still there;
- content scope, where the 7→8 migration edits data: every `history_event` row that is not a START and was stored before the upgrade is identical afterwards, column for column with the same id; the history cursor keeps its stats session; every shared_prefs key present before is present after (values are rewritten by the app on start and are not compared; the files are copied to the out dir). The START rows stored before the upgrade are listed with a diff, not judged: the migration re-dates or deletes a START without a same-wall companion, and the replay check below is what covers the STARTs;
- the new version stored a new snapshot;
- a full (Shizuku) capture still works;
- the process is alive and the crash buffer is clean.

What it does not show: the contents of any other table (a row count that holds says nothing about the row), preference values, or anything about a START.

Exit codes: 0 PASS, 1 a check failed, 2 setup problem. The report lands in `build/release-check/<prev>-to-<apk>/upgrade-check.txt`.

A FAIL is a real bug for every user who updates, so it blocks the release. A WARN means a check couldn't run, for example the privileged path when Shizuku wasn't granted. Fix the setup and run it again, or list it under "Not checked" in the notes. Never present a warning as a pass.

The history replay check (audit A03, schema 8 on) then runs over the database the upgrade check left on redroid. It edits the app database, so it refuses to run without an explicit `--serial` and `--disposable`, or on a device whose model isn't `redroid15_x86_64`. It never uninstalls, and it copies the database files to its out dir before anything else. The installed APK must be byte-identical to `--apk`; it installs in place only with `--install`. Before any edit it needs a FULL capture with a finished history ingest and the database at the source's Room version. Then it picks a START the framework still holds and runs three phases, each re-arming the one-time replay and starting the app:
1. Restore: the START is deleted; the replay must store it again.
2. Suppress: a LEVEL row takes the START's exact instant; the replay must not append the START after it. The row's id is recorded in `<out>/fixture-row` the moment it is stored.
3. Restore: that LEVEL row and any START are deleted; the replay must store the START again.

Exit codes and the FAIL rule are the same as for the upgrade check. Exit 2 is a setup problem with no verdict. If it stops after editing (a signal, a lost replay), an exit trap runs and the exit status stays the signal's (143/130/129) or `die`'s (2). For TERM, INT or HUP sent to the script's own pid it runs after the foreground command has returned; a signal to the whole process group also kills the adb client, and then only the wait protects. Every device command in the trap is bounded by `timeout -k 2` (an earlier trap ignored further signals with `trap ''`, which adb inherited, so the uutils `timeout`'s TERM was a no-op and a hung client was waited for: runs 7 and 11 below measured 62 s per probe; the trap now catches the signal with `trap ':'`, so children keep the default TERM disposition, and the KILL stays as defence in depth; run 12 measured the bound); the worst case for the whole trap is about six minutes. PIPE is ignored for the whole script, so a gone stderr reader cannot turn `die`'s 2 or the kept signal status into 141. The trap force-stops the app and asks the device until two consecutive probes, each an adb call that returned 0 and printed exactly `ABSENT` (the probe also checks it can see `system_server`, so a blind `pidof` cannot pass as absence), confirm the process is gone; empty output, an error, a timeout, `BLIND` or `RUNNING` are not evidence. Only then does it look up the fixture row (a lookup that fails is reported, not read as "no row"), delete that LEVEL row by its recorded id (never every row at the instant) and the rewind marker in one transaction, and give the `-wal`/`-shm` files back to the app. Without that confirmation it leaves the database as the interruption left it; when sqlite3 refused the transaction, none of it applied; when the client was killed or adb failed, the outcome is all-or-nothing but unknown, and the message says to check; when only the ownership step fails, the rows are clean and the message says what to chown. Each case is an `undo    FAILED …` line with the fixture row and the backup path, and means cleaning up by hand from `<out>/fixture-row` and, if needed, `<out>/inspiration.db*`. It does **not** put the START back (the next app start does, under a new id, if Shizuku runs) and does not undo the captures the launches stored or the cursor's position. Exercised on redroid on 2026-10-22 with SIGTERM to the script's pid during phase 2: runs 1–4 on earlier versions of the trap (the second showed the status foreground service surviving a force-stop that raced the launch), then the hardened trap (positive process evidence, fixture row by id, exit status kept, `timeout` on every command but, until run 12, without the KILL; still `trap ''`, no transaction and no BLIND check) once ordinarily (run 5) and with an injected probe transport failure (6), probe timeout (7, 11 and 12), persistent process (8), SQL failure (9) and ownership failure (10); then the reviewed trap (`trap ':'`, BLIND-aware probe, lookup status check, one transaction) ordinarily (13) and with the SQL failure (14); then the trap as committed (PIPE ignored, counts read inside the transaction, outcome worded by sqlite3's real error format) ordinarily (16) and with an injected SQL failure in that format (17) (`build/release-check/history-replay-interrupt-*`). Not repeated on the committed trap: the transport, timeout, persistent-process and ownership injections; never exercised: a failed lookup, a real mid-transaction failure, the BLIND branch, a closed stderr. After SIGKILL or a lost adb the trap can't run or can't confirm; the backup in the out dir is the way back. Most often the preflight finds no FULL capture because the Shizuku server isn't running: redroid doesn't restart it after a container restart. Start it as `upgrade-check.sh` does, then rerun.

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
v=X.Y.Z &&
bash .claude/skills/release/scripts/release-candidate.sh verify <approved-sha> $v &&
sha=$(sed -n 's/^commit=//p' build/release/$v/candidate.txt) &&
{ git ls-remote --exit-code --tags origin "refs/tags/v$v" >/dev/null; [ $? -eq 2 ]; } &&   # 2 = remote reached, no such tag
git push origin main &&
gh release create v$v -R akane599/inspiration --target "$sha" --title "Inspiration $v" \
  --notes-file build/release/$v/notes.md build/release/$v/inspiration-$v.apk build/release/$v/inspiration-$v.apk.sha256
```

- The commands are one `&&` chain on purpose: a `verify` refusal (exit 1) stops the push and the release. It refuses when HEAD is no longer the candidate's commit, when the approved commit differs from the one the candidate was staged for, when the gate record no longer vouches for this HEAD and hash, when the staged APK's hash or its `.sha256` line changed, when the hash is on the forbidden list, or when the file's signers or badging no longer check out. Publish only the staged file it verified.
- `<approved-sha>` is the SHA from the user's prepare message.
- The tag check is there because `gh release create --target` is ignored when `v$v` already exists on GitHub: the release would attach to the old tag's commit. `--exit-code` returns 2 only when the remote answered and has no such tag; an existing tag (0) or an unreachable remote (128) stops the chain. An existing tag means stop and ask.
- `--target` takes the full SHA from `candidate.txt`. `gh` creates the tag on GitHub, so no local `git tag` is needed.
- If Sidequest is active, its hook blocks a manual tag: acquire the publish lock (`sidequest publish lock`) first and release it afterwards.

### 8. Verify the published release

```bash
v=X.Y.Z && d=$(mktemp -d) && gh release download v$v -R akane599/inspiration -D "$d" &&
(cd "$d" && sha256sum -c inspiration-$v.apk.sha256) &&
[ "$(sha256sum "$d/inspiration-$v.apk" | cut -d' ' -f1)" = "$(sed -n 's/^sha256=//p' build/release/$v/candidate.txt)" ] && echo "download matches candidate.txt"
```

The downloaded APK must match the hash in `candidate.txt`, not only the `.sha256` that was uploaded next to it. The upgrade check left this exact APK installed on redroid. To show the device runs the published bytes, compare against it, naming the device and checking its model first (through an SSH-forwarded adb server a bare `adb` can reach the user's phone):

```bash
v=X.Y.Z; d=<the directory from the download above>
export ANDROID_SERIAL=localhost:5555   # add ANDROID_ADB_SERVER_PORT=5038 when sshd forwards port 5037 (redroid rule)
[ "$(adb shell getprop ro.product.model | tr -d '\r')" = redroid15_x86_64 ] || exit 1
adb shell pm path com.akane.inspiration
adb pull <path> "$d/installed.apk"
sha256sum "$d/installed.apk"
```

### 9. Record and report

- **PROGRESS.md:** add the "Baseline" line in the existing form: `YYYY-MM-DD @ X.Y.Z (full gate at <sha>, --rerun-tasks)`, then build status, test counts per module, screenshots and lint. Commit it; the publish gate covers pushing this one record commit, nothing else.
- **Auto memory (`redroid-test-device`):** update the "current install" line, since the upgrade check changed what is installed.
- **Report to the user:** the version and release URL, the APK's local path and sha256, the certificate, the gate numbers, the upgrade check result, the not-checked items, and the device state change.

## Not part of this skill

- Play Store submission and CHANGELOG.md. This project ships through GitHub releases.
- Manual `git tag`.
- Changing the signing key, or creating a keystore. If the keystore is missing, ask the user.
