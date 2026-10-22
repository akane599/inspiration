#!/usr/bin/env bash
# Same-key in-place upgrade check for an Inspiration release.
#
# Installs the previous GitHub release on a clean device, lets it store data (an app-open capture plus
# screen off/on snapshots), upgrades in place to the new APK with `adb install -r`, then checks that:
#   - the install really was in place (same first-install time, new versionCode);
#   - the smoke scope: Room user_version did not go down and no table's row count went down (counts, not
#     contents), and every shared_prefs file is still there;
#   - the content scope, where the 7->8 migration touches data: every history_event row that is not a START and
#     was stored before the upgrade is identical afterwards (the migration re-dates or deletes STARTs only; the
#     START rows are listed, not judged: history-replay-check.sh covers them), the history cursor keeps its stats
#     session, and every shared_prefs key present before is present after (values are rewritten by the app on
#     start and are not compared);
#   - the new version starts, stores a new snapshot, and a full (Shizuku) capture still works if it
#     worked before the upgrade;
#   - nothing crashed.
# It uninstalls the app first, which clears the device's existing app data, and restarts the Shizuku server once,
# which drops other apps' Shizuku connections for a moment. Both go in the report.
#
# Usage, from the repo root:
#   bash .claude/skills/release/scripts/upgrade-check.sh <prev-tag> <new.apk> [out-dir]
#   e.g. bash .claude/skills/release/scripts/upgrade-check.sh v0.2.3 build/release/0.2.4/inspiration-0.2.4.apk
# Env: ANDROID_SERIAL (default localhost:5555, the redroid device), REPO (default akane599/inspiration);
#   DEVICE_LOCK, a flock file shared with other sessions driving the device (default
#   $HOME/.cache/akastudio-emulator-5554.lock when it exists); DEVICE_LOCK_FD, its descriptor when the caller
#   already holds the lock around a longer sequence (this, then history-replay-check.sh), so it is not taken twice.
# Exit: 0 every check passed, 1 a check failed, 2 setup problem (device, root, gh, APKs or signing key).
set -uo pipefail

pkg=com.akane.inspiration
db=/data/data/$pkg/databases/inspiration.db
repo=${REPO:-akane599/inspiration}
export ANDROID_SERIAL=${ANDROID_SERIAL:-localhost:5555}
tables="snapshot snapshot_uid snapshot_availability snapshot_system history_event marker charge_session
battery_health_sample telemetry_sample telemetry_hourly capture_miss toolkit_action"

die() { echo "upgrade-check: $*" >&2; exit 2; }
[ $# -ge 2 ] || die "usage: upgrade-check.sh <prev-tag> <new.apk> [out-dir]"
prev_tag=$1
new_apk=$2
[ -f "$new_apk" ] || die "no such APK: $new_apk"
out=${3:-build/release-check/${prev_tag}-to-$(basename "$new_apk" .apk)}
rm -rf "$out/prev"
mkdir -p "$out/prev" || die "cannot create $out"
report=$out/upgrade-check.txt
: >"$report"
log() { printf '%s\n' "$*" | tee -a "$report"; }

command -v gh >/dev/null || die "gh is not installed"
bt=$(ls -d "${ANDROID_HOME:?ANDROID_HOME is unset}"/build-tools/* | sort -V | tail -1)
[ -x "$bt/aapt2" ] && [ -x "$bt/apksigner" ] || die "aapt2/apksigner missing in $bt"

# The device is shared with a peer session: hold its lock for the whole check (uninstall, Shizuku restart, the
# upgrade), or use the caller's inherited descriptor: flock on it is a no-op when the caller holds the lock, takes it
# on that description when nobody does (it then lasts until the caller closes it), and fails when another process
# holds it; never a second lock.
lock=${DEVICE_LOCK:-$HOME/.cache/akastudio-emulator-5554.lock}
if [ -n "${DEVICE_LOCK_FD:-}" ]; then
  [[ $DEVICE_LOCK_FD =~ ^[0-9]+$ ]] && [ "$(readlink "/proc/$$/fd/$DEVICE_LOCK_FD" 2>/dev/null)" = "$(readlink -f "$lock")" ] ||
    die "DEVICE_LOCK_FD=$DEVICE_LOCK_FD is not an open descriptor of $lock"
  flock -n "$DEVICE_LOCK_FD" || die "DEVICE_LOCK_FD=$DEVICE_LOCK_FD is open on $lock but another process holds the lock"
elif [ -n "${DEVICE_LOCK:-}" ] || [ -e "$lock" ]; then
  exec 9>"$lock"
  flock -w 300 9 || die "device lock $lock is busy"
fi

adb get-state >/dev/null 2>&1 || die "device $ANDROID_SERIAL is not attached"
# This check uninstalls the app. Through an SSH-forwarded adb server, localhost:5555 can be the user's phone.
model=$(adb shell getprop ro.product.model | tr -d '\r')
[ "$model" = redroid15_x86_64 ] ||
  die "device $ANDROID_SERIAL is '$model', not the disposable redroid15_x86_64: refused before any install (is adb reaching a personal phone?)"
if adb shell 'su 0 id' 2>/dev/null | grep -q 'uid=0'; then
  root='su 0 '
elif adb root >/dev/null 2>&1 && adb wait-for-device && adb shell id | grep -q 'uid=0'; then
  root=''
else
  die "no root shell on $ANDROID_SERIAL (needed to read the app database)"
fi

# sql <statements>: prints the rows (CRs stripped) and returns sqlite3's status; a busy database waits up to 5 s.
sql() { printf '%s\n' "$1" | adb shell "${root}sqlite3 -batch -cmd '.timeout 5000' $db" 2>/dev/null | tr -d '\r'; return "${PIPESTATUS[1]}"; }
count() { sql "SELECT COUNT(*) FROM $1;"; }
# history_event counts leave the STARTs out: the 7->8 migration deletes a START without a same-wall companion and
# the next ingest replays it, so a START count can fall and come back; the START rows are listed separately below.
count_history() { sql "SELECT COUNT(*) FROM history_event WHERE kind != '7';"; }
# The re-armed replay writes the marker (id 2) together with a cursor rewound to 0 before it pages anything; it has
# paged past the previous state once the marker is there and the cursor is back at or beyond where it stood.
replay_paged() {
  [ "$(sql 'SELECT COUNT(*) FROM history_cursor WHERE id = 2;')" = 1 ] &&
    [ "$(sql 'SELECT lastHistoryTimeMs FROM history_cursor WHERE id = 1;')" -ge "${cursor_ht:-0}" ] 2>/dev/null
}
full_count() { sql "SELECT COUNT(*) FROM snapshot WHERE kind='FULL';"; }
prefs() { adb shell "${root}ls /data/data/$pkg/shared_prefs" 2>/dev/null | tr -d '\r' | sort | tr '\n' ' '; }
version_code() { adb shell dumpsys package $pkg | tr -d '\r' | sed -n 's/^ *versionCode=\([0-9]*\).*/\1/p' | head -1; }
first_install() { adb shell dumpsys package $pkg | tr -d '\r' | sed -n 's/^ *firstInstallTime=//p' | head -1; }
badging() {
  "$bt/aapt2" dump badging "$1" |
    sed -n "s/^package: name='\([^']*\)' versionCode='\([^']*\)' versionName='\([^']*\)'.*/\1 \2 \3/p"
}
# apksigner prints "Signer #1 certificate …" or, in build-tools 37, "V2 Signer: certificate …".
cert() {
  "$bt/apksigner" verify --print-certs "$1" 2>/dev/null |
    sed -n 's/^.*Signer[^:]*: certificate SHA-256 digest: //p; s/^Signer #1 certificate SHA-256 digest: //p' | head -1
}
# wait_for <seconds> <command...>: polls every 3 s until the command succeeds.
wait_for() {
  local end=$((SECONDS + $1))
  shift
  until "$@" 2>/dev/null; do
    [ "$SECONDS" -lt "$end" ] || return 1
    sleep 3
  done
}
gt() { [ "$(eval "$1")" -gt "$2" ]; }

# 1. The previous release, verified against its own .sha256, and both APKs' identity and signing key.
gh release download "$prev_tag" -R "$repo" -p '*.apk' -p '*.apk.sha256' -D "$out/prev" ||
  die "cannot download $prev_tag from $repo"
prev_apk=$(ls "$out"/prev/*.apk 2>/dev/null | head -1)
[ -n "$prev_apk" ] || die "$prev_tag has no APK asset"
(cd "$out/prev" && sha256sum -c --quiet ./*.apk.sha256) || die "the $prev_tag APK does not match its .sha256"
read -r p_pkg p_code p_name < <(badging "$prev_apk")
read -r n_pkg n_code n_name < <(badging "$new_apk")
[ "${p_pkg:-}" = "$pkg" ] && [ "${n_pkg:-}" = "$pkg" ] || die "package mismatch: ${p_pkg:-?} / ${n_pkg:-?}"
[ "$n_code" -gt "$p_code" ] || die "new versionCode $n_code is not above $p_code"
p_cert=$(cert "$prev_apk")
n_cert=$(cert "$new_apk")
[ -n "$n_cert" ] && [ "$p_cert" = "$n_cert" ] ||
  die "signing certificates differ (prev ${p_cert:-none}, new ${n_cert:-none}): not a same-key upgrade"
log "device  $ANDROID_SERIAL"
log "prev    $prev_tag = $p_name ($p_code)"
log "new     $n_name ($n_code)  $new_apk"
log "cert    $n_cert (same key)"

# 2. Clean install of the previous release, which then stores data.
adb uninstall $pkg >/dev/null 2>&1
log "setup   uninstalled $pkg (its data on this device was cleared)"
adb logcat -b crash -c
adb install "$prev_apk" >/dev/null || die "installing $prev_tag failed"
adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
shizuku=absent
manager=$(adb shell pm path moe.shizuku.privileged.api 2>/dev/null | tr -d '\r' | sed -n 's#^package:\(.*\)/base.apk$#\1#p')
if [ -n "$manager" ]; then
  adb shell pm grant $pkg moe.shizuku.manager.permission.API_V23 >/dev/null 2>&1 && shizuku=granted || shizuku=not-granted
  # A running Shizuku server reads its allow list by uid and doesn't see a runtime grant for the new uid
  # until it restarts. The restart briefly drops other apps' Shizuku connections; RESTART_SHIZUKU=0 skips it.
  if [ "$shizuku" = granted ] && [ "${RESTART_SHIZUKU:-1}" = 1 ]; then
    case $(adb shell getprop ro.product.cpu.abi | tr -d '\r') in
      arm64*) abi=arm64 ;;
      armeabi*) abi=arm ;;
      x86_64) abi=x86_64 ;;
      *) abi=x86 ;;
    esac
    if adb shell "$manager/lib/$abi/libshizuku.so" 2>&1 | grep -q 'exit with 0'; then
      shizuku="granted, server restarted"
      log "setup   restarted the Shizuku server: other apps' Shizuku connections on this device were dropped for a moment"
      sleep 5
    else
      shizuku="granted, server restart failed"
    fi
  fi
fi
log "setup   Shizuku permission: $shizuku"
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -W -n $pkg/.MainActivity >/dev/null
wait_for 60 gt full_count 0 || wait_for 30 gt "count snapshot" 0
adb shell input keyevent KEYCODE_SLEEP
sleep 4
adb shell input keyevent KEYCODE_WAKEUP
sleep 4

declare -A before after
for t in $tables; do before[$t]=$(count "$t"); done
before[history_event]=$(count_history)
before[user_version]=$(sql 'PRAGMA user_version;')
before[full]=$(full_count)
before[prefs]=$(prefs)
# Content, not counts, where the 7->8 migration edits data: the history rows stored so far (by id, so rows the new
# version appends later are out of scope), the cursor's stats session, and the shared_prefs files with their keys.
hist_cols="id,statsStartMs,historyTimeMs,wallMs,kind,levelPct,chargeUah,temperatureDeciC,voltageMv,plugType"
history_rows() { # history_rows <max-id> <kind test>: returns sqlite3's status; a failed read is never an empty set
  sql "SELECT $hist_cols FROM history_event WHERE id <= $1 AND kind $2 ORDER BY id;"
}
prefs_pull() { # prefs_pull <dir>: copies every shared_prefs file there and prints its "file:key" lines, sorted
  local f
  rm -rf "$1" && mkdir -p "$1" || die "cannot create $1"
  for f in $(prefs); do adb exec-out "${root}cat /data/data/$pkg/shared_prefs/$f" >"$1/$f"; done
  for f in "$1"/*; do [ -e "$f" ] && sed -n "s/.* name=\"\([^\"]*\)\".*/$(basename "$f"):\1/p" "$f"; done | sort
}
hist_max=$(sql 'SELECT COALESCE(MAX(id), 0) FROM history_event;') || die "reading history_event failed"
history_rows "$hist_max" "!= '7'" >"$out/history-other-before.txt" || die "reading history_event failed"
history_rows "$hist_max" "= '7'" >"$out/history-start-before.txt" || die "reading history_event failed"
cursor_session=$(sql 'SELECT statsStartMs FROM history_cursor WHERE id = 1;')
cursor_ht=$(sql 'SELECT lastHistoryTimeMs FROM history_cursor WHERE id = 1;')
prefs_pull "$out/prefs-before" >"$out/prefs-keys-before.txt"
before_first=$(first_install)
before_code=$(version_code)
[ "$before_code" = "$p_code" ] || die "after installing $prev_tag the device reports versionCode ${before_code:-none}"
[ "${before[snapshot]:-0}" -gt 0 ] 2>/dev/null || die "$prev_tag stored no snapshot, so there is nothing to carry over"

# 3. In-place upgrade, then a fresh start of the new version.
fails=0
warns=0
check() { # check <ok|FAIL|WARN> <text>
  case $1 in
    ok) log "ok      $2" ;;
    WARN) log "WARN    $2"; warns=$((warns + 1)) ;;
    *) log "FAIL    $2"; fails=$((fails + 1)) ;;
  esac
}
if adb install -r "$new_apk" >/dev/null; then
  check ok "adb install -r over $p_name"
else
  check FAIL "adb install -r over $p_name was refused"
  log "result  FAIL ($fails failed)"
  exit 1
fi
sleep 5
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -W -n $pkg/.MainActivity >/dev/null
if [ "${before[full]:-0}" -gt 0 ] 2>/dev/null; then
  wait_for 60 gt full_count "${before[full]}"
else
  wait_for 30 gt "count snapshot" "${before[snapshot]}"
fi
# The migration re-arms the one-time history replay; wait until it has paged past the previous cursor, so the START
# rows below reflect a replayed state rather than a half-way one.
if [ -n "$cursor_ht" ]; then
  replay="paged past the previous cursor"
  wait_for 60 replay_paged || replay="not past the previous cursor within 60 s"
else
  replay="no cursor before the upgrade, nothing to page past"   # the previous version never ingested history
fi
sleep 3
for t in $tables; do after[$t]=$(count "$t"); done
after[history_event]=$(count_history)
after[user_version]=$(sql 'PRAGMA user_version;')
after[full]=$(full_count)
after[prefs]=$(prefs)
# An unreadable database after the upgrade is a regression for the user, not a setup problem.
history_rows "$hist_max" "!= '7'" >"$out/history-other-after.txt" || echo unreadable >"$out/history-other-after.txt"
history_rows "$hist_max" "= '7'" >"$out/history-start-after.txt" || echo unreadable >"$out/history-start-after.txt"
prefs_pull "$out/prefs-after" >"$out/prefs-keys-after.txt"

# 4. Checks.
[ "$(version_code)" = "$n_code" ] && check ok "versionCode $p_code -> $n_code" ||
  check FAIL "device reports versionCode $(version_code), expected $n_code"
[ "$(first_install)" = "$before_first" ] && check ok "first install time kept ($before_first): an in-place upgrade" ||
  check FAIL "first install time changed ($before_first -> $(first_install)): not an in-place upgrade"
if [ -n "${after[user_version]}" ] && [ "${after[user_version]}" -ge "${before[user_version]:-0}" ] 2>/dev/null; then
  check ok "Room user_version ${before[user_version]} -> ${after[user_version]}"
else
  check FAIL "Room user_version ${before[user_version]} -> ${after[user_version]:-unreadable}"
fi
for t in $tables; do
  b=${before[$t]}
  a=${after[$t]}
  if [ -z "$b" ]; then
    [ -n "$a" ] && check ok "$t: new table, $a rows" || log "-       $t: absent before and after"
  elif [ -n "$a" ] && [ "$a" -ge "$b" ]; then
    if [ "$t" = history_event ]; then
      check ok "$t: $b -> $a non-START rows (count only; the START rows are listed below)"
    else
      check ok "$t: $b -> $a rows (count only; contents not compared)"
    fi
  else
    check FAIL "$t: $b -> ${a:-unreadable} rows (rows lost)"
  fi
done
# Content checks, scoped to what the 7->8 migration touches. An empty before-set is reported, not passed. Rows the
# routine prune may remove between the two reads (wallMs older than the retention window) would show up as a
# difference; on a stats session days old that cannot happen, and the diff names any row it did happen to.
n_other=$(wc -l <"$out/history-other-before.txt")
if [ "$n_other" -eq 0 ]; then
  log "-       history_event: no non-START rows were stored before the upgrade, so there is nothing to compare"
elif cmp -s "$out/history-other-before.txt" "$out/history-other-after.txt"; then
  check ok "history_event: all $n_other non-START rows stored before the upgrade are identical after it (every column, same id)"
else
  check FAIL "history_event: non-START rows stored before the upgrade changed or went missing (diff $out/history-other-before.txt $out/history-other-after.txt)"
fi
n_start=$(wc -l <"$out/history-start-before.txt")
n_same=$(grep -cFxf "$out/history-start-before.txt" "$out/history-start-after.txt")
if [ "$n_start" -eq 0 ]; then
  log "-       history_event: no START rows were stored before the upgrade"
elif cmp -s "$out/history-start-before.txt" "$out/history-start-after.txt"; then
  log "-       history_event: the $n_start START rows stored before the upgrade are unchanged (replay $replay)"
else
  log "-       history_event: of the $n_start START rows stored before the upgrade, $n_same are unchanged (replay $replay); the 7->8 migration re-dates or deletes a START without a same-wall companion and the next ingest replays it under a new id (not judged here: history-replay-check.sh does), diff:"
  diff "$out/history-start-before.txt" "$out/history-start-after.txt" | sed 's/^/        /' | tee -a "$report"
fi
[ "$replay" != "not past the previous cursor within 60 s" ] || check WARN "the re-armed history replay was $replay of the new version's start, so the START rows above may show a half-way state"
cursor_after=$(sql 'SELECT statsStartMs FROM history_cursor WHERE id = 1;')
if [ -z "$cursor_session" ]; then
  log "-       history_cursor: no cursor row before the upgrade (now: ${cursor_after:-none})"
elif [ "$cursor_after" = "$cursor_session" ]; then
  check ok "history_cursor: stats session $cursor_session kept"
else
  check FAIL "history_cursor: stats session changed ($cursor_session -> ${cursor_after:-none})"
fi
missing=""
for f in ${before[prefs]}; do
  case " ${after[prefs]} " in *" $f "*) ;; *) missing="$missing $f" ;; esac
done
if [ -z "${before[prefs]}" ]; then
  log "-       shared_prefs: no files before the upgrade (the app writes preferences only on settings or calibration), so nothing to carry over (now: ${after[prefs]:-none})"
else
  [ -z "$missing" ] && check ok "shared_prefs files kept: ${before[prefs]} (now: ${after[prefs]:-none})" ||
    check FAIL "shared_prefs files lost:$missing"
  lost_keys=$(comm -23 "$out/prefs-keys-before.txt" "$out/prefs-keys-after.txt" | tr '\n' ' ')
  [ -z "$lost_keys" ] && check ok "shared_prefs keys kept: $(wc -l <"$out/prefs-keys-before.txt") before, $(wc -l <"$out/prefs-keys-after.txt") after (values are rewritten by the app on start and are not compared; copies in $out/prefs-before and $out/prefs-after)" ||
    check FAIL "shared_prefs keys lost: $lost_keys"
fi
[ "${after[snapshot]:-0}" -gt "${before[snapshot]}" ] 2>/dev/null &&
  check ok "new version stored new snapshots (${before[snapshot]} -> ${after[snapshot]})" ||
  check FAIL "new version stored no snapshot after starting"
if [ "${before[full]:-0}" -gt 0 ] 2>/dev/null; then
  [ "${after[full]:-0}" -gt "${before[full]}" ] 2>/dev/null &&
    check ok "full (Shizuku) capture works after the upgrade (${before[full]} -> ${after[full]})" ||
    check FAIL "a full capture worked before the upgrade but not after (${before[full]} -> ${after[full]:-?})"
else
  check WARN "no full capture before the upgrade (Shizuku $shizuku), so the privileged path was not exercised"
fi
[ -n "$(adb shell pidof $pkg | tr -d '\r')" ] && check ok "process alive" || check FAIL "process not running"
crashes=$(adb logcat -b crash -d | tr -d '\r' | grep -c "$pkg")
[ "$crashes" -eq 0 ] && check ok "no crash in the crash buffer" ||
  check FAIL "$crashes crash-buffer lines mention $pkg (see: adb logcat -b crash -d)"

{
  echo
  echo "trigger counts after the check:"
  sql "SELECT kind, \"trigger\", COUNT(*) FROM snapshot GROUP BY 1, 2 ORDER BY 1, 2;"
} >>"$report"
if [ "$fails" -eq 0 ]; then
  log "result  PASS ($warns warning(s)); report: $report"
  exit 0
fi
log "result  FAIL ($fails failed, $warns warning(s)); report: $report"
exit 1
