#!/usr/bin/env bash
# In-place history replay check for audit A03 (schema 8): a re-armed history replay must restore a reboot START
# that is missing from the store, and must not append one after a stored row of another kind at its instant.
#
# It runs over the app database already on a disposable test device, using a START the framework still holds
# (the live stats session), so the replay is the real ingest over real batterystats history:
#   1. restore   delete that START, re-arm the replay, start the app: the START is stored again;
#   2. suppress  store a LEVEL row at the START's exact instant in its place, re-arm, start: no START comes back;
#   3. restore   delete that LEVEL row, re-arm, start: the START is stored again.
# Phase 1 is the control: it shows the framework still holds the START and the replay runs on this device.
# On a PASS the database keeps the same rows; the START comes back under a new id, which is harmless for a START
# alone at its instant (consumers order it by its own wall and history time). If the script stops part-way, an
# exit trap stops the app, waits until the device confirms the process is gone, removes the LEVEL row this script
# stored (by the id recorded in <out>/fixture-row) and re-arms the replay, in one transaction; it does not put the
# START back (the next app start does, under a new id) and does not undo the captures the launches stored. Without
# that confirmation it leaves the database as the interruption left it; when the transaction fails nothing of it
# applies; when only the ownership step fails the rows are clean. Each case is reported with the backup and the
# fixture row. Exercised on redroid on 2026-10-22 (AUDIT_REPORT.md, A03 follow-up). SIGKILL or a lost adb leave the
# out-dir backup as the way back.
#
# Safety: it edits the app database, so it runs only on a device named explicitly with --serial, confirmed with
# --disposable, whose model is redroid15_x86_64 (--allow-model names another disposable model). It never uninstalls
# or clears data. It copies the database files to the out dir before anything else, and installs nothing unless
# --install is given (then `adb install -r`, data kept). It edits nothing until the app has stored a FULL capture
# and finished its history ingest, which proves the privileged path works. With an SSH-forwarded adb server, a bare
# serial can reach a personal phone: the model check refuses it.
#
# Usage, from the repo root:
#   bash .claude/skills/release/scripts/history-replay-check.sh --serial emulator-5554 --disposable \
#     --apk app/build/outputs/apk/release/app-release.apk [--install] [--allow-model <model>] [--out <dir>]
# Env: DEVICE_LOCK, a flock file shared with other sessions driving the device (default
#   $HOME/.cache/akastudio-emulator-5554.lock when it exists); DEVICE_LOCK_FD, the descriptor of that file when the
#   caller already holds the lock for a longer sequence (upgrade check, then this), so it is not taken twice;
#   REPLAY_TIMEOUT_S (default 90); SQL_TIMEOUT_S, the bound on each sqlite3 call through adb (default 30).
# Exit: 0 every check passed, 1 a check failed, 2 setup problem or refused (no phase verdict); 143/130/129 after
#   TERM/INT/HUP, kept through the cleanup.
set -uo pipefail
# A signal sent to this script's pid ends it through the EXIT trap below, and only after the foreground command (an
# `am start -W`, an adb shell) has returned, so the cleanup does not race a launch it started. A signal to the whole
# process group (Ctrl-C, a killed terminal) also ends the adb client; then only the process wait in `undo` protects.
trap 'exit 143' TERM
trap 'exit 130' INT
trap 'exit 129' HUP
# A gone stderr reader must not kill the script (exit 141 instead of die's 2 or the signal's status): PIPE is ignored
# for the whole run. Children inherit that, which only turns a write to a closed pipe into an error they report.
trap '' PIPE

pkg=com.akane.inspiration
db=/data/data/$pkg/databases/inspiration.db
start_kind=7    # HistoryKind.START
level_kind=5    # HistoryKind.LEVEL
marker_id=2     # HistoryDao.REWOUND_MARKER
expect_model=redroid15_x86_64

report=""
die() {
  echo "history-replay-check: $*" >&2
  [ -n "$report" ] && echo "SETUP   $*" >>"$report"
  exit 2
}
need() { [ $# -ge 2 ] && [ -n "$2" ] || die "$1 needs a value"; }
serial="" apk="" out="" disposable=0 install=0
while [ $# -gt 0 ]; do
  case $1 in
    --serial) need "$@"; serial=$2; shift 2 ;;
    --apk) need "$@"; apk=$2; shift 2 ;;
    --out) need "$@"; out=$2; shift 2 ;;
    --allow-model) need "$@"; expect_model=$2; shift 2 ;;
    --disposable) disposable=1; shift ;;
    --install) install=1; shift ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$serial" ] || die "--serial is required: name the disposable test device explicitly"
[ "$disposable" = 1 ] || die "--disposable is required: this check edits the app database on $serial"
[ -f "$apk" ] || die "--apk must name the APK under test (got '${apk}')"
export ANDROID_SERIAL=$serial

schema=$(sed -n 's/^ *version = \([0-9]*\),$/\1/p' core/database/src/main/kotlin/com/akane/inspiration/database/InspirationDatabase.kt | head -1)
[ -n "$schema" ] || die "run from the repo root: cannot read the Room version from InspirationDatabase.kt"
bt=$(ls -d "${ANDROID_HOME:?ANDROID_HOME is unset}"/build-tools/* | sort -V | tail -1)
read -r a_pkg a_code a_name < <("$bt/aapt2" dump badging "$apk" |
  sed -n "s/^package: name='\([^']*\)' versionCode='\([^']*\)' versionName='\([^']*\)'.*/\1 \2 \3/p")
[ "${a_pkg:-}" = "$pkg" ] || die "$apk is not $pkg (${a_pkg:-unreadable})"
a_sha=$(sha256sum "$apk" | cut -d' ' -f1)

lock=${DEVICE_LOCK:-$HOME/.cache/akastudio-emulator-5554.lock}
if [ -n "${DEVICE_LOCK_FD:-}" ]; then
  # The caller holds the lock around a longer sequence and passed its descriptor down. flock on the inherited
  # description is a no-op when it already holds the lock, takes it on that description when nobody does (it then
  # lasts until the caller closes the descriptor), and fails when another description holds it: never a second lock.
  [[ $DEVICE_LOCK_FD =~ ^[0-9]+$ ]] && [ "$(readlink "/proc/$$/fd/$DEVICE_LOCK_FD" 2>/dev/null)" = "$(readlink -f "$lock")" ] ||
    die "DEVICE_LOCK_FD=$DEVICE_LOCK_FD is not an open descriptor of $lock"
  flock -n "$DEVICE_LOCK_FD" || die "DEVICE_LOCK_FD=$DEVICE_LOCK_FD is open on $lock but another process holds the lock"
elif [ -n "${DEVICE_LOCK:-}" ] || [ -e "$lock" ]; then
  exec 9>"$lock"
  flock -w 300 9 || die "device lock $lock is busy"
fi

adb get-state >/dev/null 2>&1 || die "device $serial is not attached"
model=$(adb shell getprop ro.product.model | tr -d '\r')
[ "$model" = "$expect_model" ] ||
  die "device $serial is '$model', not the disposable '$expect_model': refused (is adb reaching a personal phone?)"
adb shell 'su 0 id' 2>/dev/null | grep -q 'uid=0' || die "no root shell on $serial (needed to edit the app database)"
adb shell pm path $pkg >/dev/null 2>&1 ||
  die "$pkg is not installed on $serial: this check runs over an existing database (run upgrade-check.sh first)"

out=${out:-build/release-check/history-replay-$(date +%Y%m%d-%H%M%S)}
mkdir -p "$out" || die "cannot create $out"
report=$out/history-replay-check.txt
: >"$report"
log() { printf '%s\n' "$*" | tee -a "$report"; }

# Every device call that the cleanup relies on is bounded, so a hung adb ends in a reported failure, not a hang.
# `-k 2`: an earlier cleanup ignored TERM (trap ''), children inherited that across exec, timeout's TERM was a no-op
# for adb and it waited for adb to end by itself (measured: 62 s per probe instead of 10). The cleanup now catches
# the signal instead (trap ':'), so children start with the default TERM disposition; the KILL stays as defence in depth.
# dev <seconds> <adb args...>: prints the output (stderr merged, CRs stripped); returns adb's status, 124 on timeout
# (137 when the KILL was needed).
dev() { local t=$1; shift; timeout -k 2 "$t" adb "$@" 2>&1 | tr -d '\r'; return "${PIPESTATUS[0]}"; }
# sql <statements>: one sqlite3 call as root, stopping at the first error (-bail); returns its status like dev.
sql() { printf '%s\n' "$1" | timeout -k 2 "${SQL_TIMEOUT_S:-30}" adb shell "su 0 sqlite3 -batch -bail $db" 2>&1 | tr -d '\r'; return "${PIPESTATUS[1]}"; }
installed_sha() { adb shell "su 0 sha256sum \$(pm path $pkg | sed -n 's/^package://p' | head -1)" | tr -d '\r' | cut -d' ' -f1; }
version_code() { adb shell dumpsys package $pkg | tr -d '\r' | sed -n 's/^ *versionCode=\([0-9]*\).*/\1/p' | head -1; }
crash_lines() { adb logcat -b crash -d | tr -d '\r' | grep -c "$pkg"; }
stop_app() { adb shell am force-stop $pkg; sleep 1; }
# Root's sqlite3 can create the -wal/-shm files; give them back to the app before it opens the database.
own_db() { dev 20 shell "su 0 sh -c 'o=\$(stat -c %u:%g $db) || exit 1; for f in $db-wal $db-shm; do [ ! -e \$f ] || chown \$o \$f || exit 1; done; restorecon $db* 2>/dev/null; exit 0'"; }
cursor_ht() { sql "SELECT lastHistoryTimeMs FROM history_cursor WHERE id = 1;"; }
full_count() { sql "SELECT COUNT(*) FROM snapshot WHERE kind = 'FULL';"; }
# Starts the app and waits for its app-open capture: a new FULL snapshot, the rewind marker present, and the cursor
# paged past where it stood (resumeCursor writes the marker with a cursor of 0; each page commits with its cursor).
launch_and_replay() {
  local from full end=$((SECONDS + ${REPLAY_TIMEOUT_S:-90}))
  from=$(cursor_ht)
  full=$(full_count)
  own_db
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell am start -W -n $pkg/.MainActivity >/dev/null
  until [ "$(full_count)" -gt "$full" ] 2>/dev/null &&
    [ "$(sql "SELECT COUNT(*) FROM history_cursor WHERE id = $marker_id;")" = 1 ] &&
    [ "$(cursor_ht)" -ge "$from" ] 2>/dev/null; do
    [ "$SECONDS" -lt "$end" ] || return 1
    sleep 3
  done
  sleep 2
}
starts_at() { sql "SELECT COUNT(*) FROM history_event WHERE kind = '$start_kind' AND historyTimeMs = $ht AND wallMs = $wall;"; }
rows_at() { sql "SELECT id, kind FROM history_event WHERE historyTimeMs = $ht AND wallMs = $wall ORDER BY id;" | tr '\n' ' '; }

fails=0
check() { # check <ok|FAIL> <text>
  if [ "$1" = ok ]; then log "ok      $2"; else log "FAIL    $2"; fails=$((fails + 1)); fi
}
finish() {
  if [ "$fails" -eq 0 ]; then log "result  PASS; report: $report"; exit 0; fi
  log "result  FAIL ($fails failed); report: $report"
  exit 1
}

# 1. A copy of the database before anything else, taken with the app stopped.
log "device  $serial ($model)"
log "apk     $a_name ($a_code) sha256 $a_sha"
stop_app
for f in "" -wal -shm; do
  adb exec-out "su 0 sh -c '[ ! -e $db$f ] || cat $db$f'" >"$out/inspiration.db$f" || die "copying $db$f failed"
done
[ -s "$out/inspiration.db" ] || die "the database copy is empty: $out/inspiration.db"
log "backup  $out/inspiration.db (+ -wal/-shm, $(du -cb "$out"/inspiration.db* | tail -1 | cut -f1) bytes)"

# 2. The build under test is the installed one, it has finished a history ingest, and its database is current.
if [ "$(installed_sha)" != "$a_sha" ]; then
  [ "$install" = 1 ] || die "the installed APK differs from $apk; pass --install to install it in place (adb install -r)"
  adb install -r "$apk" >/dev/null || die "in-place install of $apk was refused (different signing key?)"
  log "setup   installed $apk in place (adb install -r; data kept)"
fi
[ "$(installed_sha)" = "$a_sha" ] || die "the installed APK still differs from $apk"
[ "$(version_code)" = "$a_code" ] || die "device reports versionCode $(version_code), expected $a_code"
[ -n "$(cursor_ht)" ] || die "the database has no history cursor yet: open the app once with Shizuku granted"
crashes_before=$(crash_lines)
launch_and_replay ||
  die "the app stored no FULL capture with a finished history ingest (is the Shizuku server running? redroid does not restart it with the container); nothing was edited"
user_version=$(sql 'PRAGMA user_version;')
[ "$user_version" = "$schema" ] || die "database user_version is $user_version, expected schema $schema"
log "build   installed APK matches; versionCode $a_code; database schema $user_version; FULL capture and history ingest done"

# 3. The START under test: newest in the live stats session, alone at its instant.
stop_app
session=$(sql "SELECT statsStartMs FROM history_cursor WHERE id = 1;")
target=$(sql "SELECT s.id, s.historyTimeMs, s.wallMs FROM history_event s WHERE s.kind = '$start_kind'
  AND s.statsStartMs = $session AND NOT EXISTS (SELECT 1 FROM history_event o WHERE o.historyTimeMs = s.historyTimeMs
  AND o.wallMs = s.wallMs AND o.kind != '$start_kind') ORDER BY s.id DESC LIMIT 1;")
IFS='|' read -r start_id ht wall <<<"$target"
[ -n "${wall:-}" ] || die "no START of the live stats session ($session) stands alone at its instant"
total=$(sql "SELECT COUNT(*) FROM history_event;")
log "target  START id $start_id at historyTimeMs $ht, wallMs $wall (session $session); $total history rows"

# From here on the database is edited. If the script stops part-way, the exit trap below stops the app and asks the
# device, with bounded commands, until it has positive evidence that the process is gone. Only then does it remove
# the one row this script stored (phase 2's LEVEL row, by the id recorded in $out/fixture-row, never every row at the
# instant: a row the app stored there is not ours) and re-arm the replay. The START is not put back; the next app
# start replays the history and stores it under a new id. Rows the launches stored stay. Without that evidence, or
# the cleanup's transaction fails, nothing of it applies; when only the ownership step fails, the rows are clean. Each
# case is reported with the fixture row and the backup. The trap never changes the script's exit status: the signal's
# or die's code is what the caller sees.
fixture=""        # id of the LEVEL row phase 2 stored, while it exists
fixture_file=$out/fixture-row
max_before=""     # highest history_event id just before that insert: the fixture row is the LEVEL row above it at the instant
undo_log() { echo "undo    $*" >>"$report"; echo "undo    $*" >&2; }   # the report first: a closed stderr must not lose it
undo() {
  local rc=$? state probe=none gone=0 left
  # A second signal must not cut the cleanup short, so the script catches and drops it (':'). Not '' (ignore): an
  # ignored disposition is inherited by adb across exec and would make timeout's TERM a no-op; with -k 2 the KILL
  # bounds the call either way. PIPE stays ignored (set at the top), so a gone stderr reader cannot kill the script
  # inside the trap and turn the kept exit status into 141. Every device command here is bounded.
  trap ':' INT TERM HUP
  # Stop the app and wait until the device itself reports the process gone: two consecutive answers, two seconds
  # apart, each from an adb call that returned 0 and printed exactly ABSENT (a launch still in flight can bring the
  # process back once). The probe first checks that pidof sees system_server at all, so a blind pidof prints BLIND.
  # Empty output, an error message, a timeout (124/137), BLIND or RUNNING are not evidence of absence.
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    dev 10 shell am force-stop $pkg >/dev/null
    sleep 2
    state=$(dev 10 shell "if ! pidof system_server >/dev/null; then echo BLIND; elif pidof $pkg >/dev/null; then echo RUNNING; else echo ABSENT; fi"); probe=$?
    if [ "$probe" = 0 ] && [ "$state" = ABSENT ]; then gone=$((gone + 1)); [ "$gone" -ge 2 ] && break; else gone=0; fi
  done
  if [ "$gone" -lt 2 ]; then
    local where=none
    [ -n "$fixture" ] && where="id $fixture (see $fixture_file)"
    [ -z "$fixture" ] && [ -n "$max_before" ] && where="the LEVEL row above id $max_before at $ht/$wall, if the insert ran"
    undo_log "FAILED (exit $rc kept): the device did not confirm that $pkg stopped (last probe: exit $probe, output '${state//$'\n'/ }'); the database was left as the interruption left it. Fixture LEVEL row: $where; the marker may be present; backup $out/inspiration.db*"
    return
  fi
  # The fixture row: the id phase 2 recorded, or, when the script stopped between the insert and the lookup, the
  # LEVEL row at the instant above the pre-insert maximum id (the app was stopped during the insert). A lookup that
  # fails, or answers anything but one id or nothing, is a failure, not "no row".
  local found=none
  if [ -z "$fixture" ] && [ -n "$max_before" ]; then
    state=$(sql "SELECT id FROM history_event WHERE historyTimeMs = $ht AND wallMs = $wall AND kind = '$level_kind' AND id > $max_before;"); probe=$?
    if [ "$probe" != 0 ] || { [ -n "$state" ] && ! [[ $state =~ ^[0-9]+$ ]]; }; then
      undo_log "FAILED (exit $rc kept): app stopped, but looking up the fixture LEVEL row failed (exit $probe, output '${state//$'\n'/ }'); nothing was deleted: the LEVEL row above id $max_before at $ht/$wall, if the insert ran, and the marker may remain; backup $out/inspiration.db*"
      return
    fi
    fixture=$state
    found=${fixture:-"no fixture row above id $max_before"}
  fi
  # One transaction: with -bail a failure stops sqlite3 and the open transaction rolls back, so either both rows go or
  # neither does. The counts are read inside the transaction: an error anywhere before COMMIT then implies a rollback,
  # and a COMMIT that fails still shows in the status.
  left=$(sql "BEGIN;
    ${fixture:+DELETE FROM history_event WHERE id = $fixture AND historyTimeMs = $ht AND wallMs = $wall AND kind = '$level_kind';}
    DELETE FROM history_cursor WHERE id = $marker_id;
    SELECT COUNT(*) FROM history_event WHERE id = ${fixture:-0} AND kind = '$level_kind';
    SELECT COUNT(*) FROM history_cursor WHERE id = $marker_id;
    COMMIT;"); probe=$?
  left=${left//$'\n'/ }
  if [ "$probe" != 0 ] || [ "$left" != "0 0" ]; then
    # Only sqlite3's own error proves the statements were refused on the device: exit 1 with its "... error near line
    # N: ..." message (3.38+; older builds print "Error: near line N:"). A killed or failed client (124/137, an adb
    # message) may or may not have delivered them; the transaction still makes the outcome all-or-nothing, but which
    # one is unknown.
    local outcome="the outcome is unknown (all or nothing): check whether the fixture LEVEL row ${fixture:-none} at $ht/$wall and the marker are still there"
    case "$probe:$left" in 1:*" near line "*) outcome="none of it applied: the fixture LEVEL row ${fixture:-none} at $ht/$wall and the marker remain as the interruption left them" ;; esac
    undo_log "FAILED (exit $rc kept): app stopped, but the cleanup transaction did not complete (exit $probe, output '$left'); $outcome; restore from $out/inspiration.db* or remove them by hand (see $fixture_file)"
    return
  fi
  rm -f "$fixture_file"
  state=$(own_db); probe=$?
  if [ "$probe" != 0 ]; then
    undo_log "FAILED (exit $rc kept): app stopped, fixture LEVEL row ${fixture:-none} removed, replay re-armed, but giving $db-wal/-shm back to the app failed (exit $probe, output '${state//$'\n'/ }'): the rows are clean; chown those files to the app's uid before the app starts, or it cannot open its database"
    return
  fi
  local what="fixture LEVEL row $fixture removed"
  [ -n "$fixture" ] || what="no fixture row to remove ($found)"
  undo_log "stopped part-way (exit $rc kept): app stopped (device confirmed), $what, replay re-armed, -wal/-shm ownership restored. The script changed nothing else; the START returns at the next app start (Shizuku needed), the launches' captures and the cursor's position remain"
}
trap undo EXIT

# 4. Restore (control): the START is missing and nothing else is stored at its instant.
sql "BEGIN; DELETE FROM history_event WHERE id = $start_id; DELETE FROM history_cursor WHERE id = $marker_id; COMMIT;" >/dev/null ||
  die "phase 1: deleting the START and the marker failed"
launch_and_replay || die "phase 1: the re-armed replay did not finish"
if [ "$(starts_at)" = 1 ]; then
  check ok "phase 1 restore: the replay stored the missing START again (rows at its instant: $(rows_at))"
else
  # A restore regression; later phases would have no START to work with. The exit trap re-arms the replay.
  check FAIL "phase 1 restore: $(starts_at) STARTs at the instant after the replay, expected 1 (rows: $(rows_at))"
  finish
fi

# 5. Suppress: a row of another kind is stored at the START's instant, as an earlier build left a group whose START
#    the retention prune removed; the replay must not append the START after it.
#    The row is identified for the cleanup before it is used: with the app stopped, the LEVEL row at the instant
#    whose id is above the pre-insert maximum can only be the one stored here; its id goes to $fixture_file.
stop_app
max_before=$(sql "SELECT MAX(id) FROM history_event;")
[[ $max_before =~ ^[0-9]+$ ]] || { got=$max_before; max_before=""; die "phase 2: could not read the highest history_event id ('$got')"; }
sql "BEGIN; INSERT INTO history_event (statsStartMs, historyTimeMs, wallMs, kind, levelPct, chargeUah,
  temperatureDeciC, voltageMv, plugType) SELECT statsStartMs, historyTimeMs, wallMs, '$level_kind', levelPct, chargeUah,
  temperatureDeciC, voltageMv, plugType FROM history_event WHERE kind = '$start_kind' AND historyTimeMs = $ht
  AND wallMs = $wall;
  DELETE FROM history_event WHERE kind = '$start_kind' AND historyTimeMs = $ht AND wallMs = $wall;
  DELETE FROM history_cursor WHERE id = $marker_id; COMMIT;" >/dev/null || die "phase 2: storing the LEVEL row failed"
fixture=$(sql "SELECT id FROM history_event WHERE historyTimeMs = $ht AND wallMs = $wall AND kind = '$level_kind' AND id > $max_before;")
[[ $fixture =~ ^[0-9]+$ ]] || { got=$fixture; fixture=""; die "phase 2: could not identify the stored LEVEL row ('$got'); the exit trap looks it up again"; }
echo "$fixture $ht $wall" >"$fixture_file"
launch_and_replay || die "phase 2: the re-armed replay did not finish"
[ "$(starts_at)" = 0 ] && check ok "phase 2 suppress: no START appended after the stored row at its instant (rows: $(rows_at))" ||
  check FAIL "phase 2 suppress: the replay appended a START after the stored row at its instant (rows: $(rows_at))"
[ "$(sql "SELECT COUNT(*) FROM history_event WHERE id = $fixture;")" = 1 ] &&
  check ok "phase 2 suppress: the stored row (id $fixture) is kept" || check FAIL "phase 2 suppress: the stored row was lost"

# 6. Restore again, which also puts the database back: the stored row goes, the START returns.
stop_app
sql "BEGIN; DELETE FROM history_event WHERE id = $fixture AND historyTimeMs = $ht AND wallMs = $wall AND kind = '$level_kind';
  DELETE FROM history_event WHERE kind = '$start_kind' AND historyTimeMs = $ht AND wallMs = $wall;
  DELETE FROM history_cursor WHERE id = $marker_id; COMMIT;" >/dev/null || die "phase 3: deleting the stored row and the marker failed"
fixture="" max_before=""
rm -f "$fixture_file"
launch_and_replay || die "phase 3: the re-armed replay did not finish"
trap - EXIT
[ "$(starts_at)" = 1 ] && check ok "phase 3 restore: with the stored row gone the START is stored again (rows: $(rows_at))" ||
  check FAIL "phase 3 restore: $(starts_at) STARTs at the instant, expected 1 (rows: $(rows_at))"
after=$(sql "SELECT COUNT(*) FROM history_event;")
[ "$after" -ge "$total" ] && check ok "history rows $total -> $after (none lost)" ||
  check FAIL "history rows $total -> $after (rows lost)"

[ -n "$(adb shell pidof $pkg | tr -d '\r')" ] && check ok "process alive" || check FAIL "process not running"
crashes=$(($(crash_lines) - crashes_before))
[ "$crashes" -le 0 ] && check ok "no new crash in the crash buffer" ||
  check FAIL "$crashes new crash-buffer lines mention $pkg (see: adb logcat -b crash -d)"
finish
