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
# exit trap removes the synthetic LEVEL row and re-arms the replay, so the next app start stores the START again.
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
#   $HOME/.cache/akastudio-emulator-5554.lock when it exists); REPLAY_TIMEOUT_S (default 90).
# Exit: 0 every check passed, 1 a check failed, 2 setup problem or refused (no phase verdict).
set -uo pipefail

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
if [ -n "${DEVICE_LOCK:-}" ] || [ -e "$lock" ]; then
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

sql() { printf '%s\n' "$1" | adb shell "su 0 sqlite3 -batch $db" 2>&1 | tr -d '\r'; }
installed_sha() { adb shell "su 0 sha256sum \$(pm path $pkg | sed -n 's/^package://p' | head -1)" | tr -d '\r' | cut -d' ' -f1; }
version_code() { adb shell dumpsys package $pkg | tr -d '\r' | sed -n 's/^ *versionCode=\([0-9]*\).*/\1/p' | head -1; }
crash_lines() { adb logcat -b crash -d | tr -d '\r' | grep -c "$pkg"; }
stop_app() { adb shell am force-stop $pkg; sleep 1; }
# Root's sqlite3 can create the -wal/-shm files; give them back to the app before it opens the database.
own_db() { adb shell "su 0 sh -c 'o=\$(stat -c %u:%g $db); for f in $db-wal $db-shm; do [ -e \$f ] && chown \$o \$f; done; restorecon $db* 2>/dev/null'"; }
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

# From here on the database is edited: if the script stops part-way, remove the synthetic row and re-arm the replay.
companion=0
undo() {
  stop_app
  [ "$companion" -gt 0 ] && sql "DELETE FROM history_event WHERE id = $companion;" >/dev/null
  sql "DELETE FROM history_cursor WHERE id = $marker_id;" >/dev/null
  own_db
  echo "undo    stopped part-way: synthetic row removed, replay re-armed; the next app start stores the START again" |
    tee -a "$report" >&2
}
trap undo EXIT

# 4. Restore (control): the START is missing and nothing else is stored at its instant.
sql "DELETE FROM history_event WHERE id = $start_id; DELETE FROM history_cursor WHERE id = $marker_id;" >/dev/null
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
stop_app
companion=$(sql "INSERT INTO history_event (statsStartMs, historyTimeMs, wallMs, kind, levelPct, chargeUah,
  temperatureDeciC, voltageMv, plugType) SELECT statsStartMs, historyTimeMs, wallMs, '$level_kind', levelPct, chargeUah,
  temperatureDeciC, voltageMv, plugType FROM history_event WHERE kind = '$start_kind' AND historyTimeMs = $ht
  AND wallMs = $wall; SELECT changes(), last_insert_rowid();
  DELETE FROM history_event WHERE kind = '$start_kind' AND historyTimeMs = $ht AND wallMs = $wall;
  DELETE FROM history_cursor WHERE id = $marker_id;")
case $companion in
  1\|*) companion=${companion#1|} ;;
  *) companion=0; die "phase 2: could not store the LEVEL row" ;;
esac
launch_and_replay || die "phase 2: the re-armed replay did not finish"
[ "$(starts_at)" = 0 ] && check ok "phase 2 suppress: no START appended after the stored row at its instant (rows: $(rows_at))" ||
  check FAIL "phase 2 suppress: the replay appended a START after the stored row at its instant (rows: $(rows_at))"
[ "$(sql "SELECT COUNT(*) FROM history_event WHERE id = $companion;")" = 1 ] &&
  check ok "phase 2 suppress: the stored row (id $companion) is kept" || check FAIL "phase 2 suppress: the stored row was lost"

# 6. Restore again, which also puts the database back: the stored row goes, the START returns.
stop_app
sql "DELETE FROM history_event WHERE id = $companion; DELETE FROM history_event WHERE kind = '$start_kind'
  AND historyTimeMs = $ht AND wallMs = $wall; DELETE FROM history_cursor WHERE id = $marker_id;" >/dev/null
companion=0
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
