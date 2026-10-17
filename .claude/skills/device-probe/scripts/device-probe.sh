#!/usr/bin/env bash
# Run one debug ProbeReceiver probe on an emulator and keep its InspirationProbe JSON lines.
# Usage: device-probe.sh [--avd NAME] [--probe ACTION] [--clear] [--no-install]
#                        [--timeout SEC] [--extras "<am extras>"]
# Output: build/device-evidence/api<SDK>-<probe>.jsonl (one JSON object per line).
# Exit: 0 probe finished; 2 setup failure (boot/install/Shizuku); 3 probe timed out or crashed.
set -uo pipefail

PKG=com.akane.inspiration
RECEIVER=$PKG/.debug.ProbeReceiver
SHIZUKU_PKG=moe.shizuku.privileged.api
TAG=InspirationProbe

avd=batstats-api36
probe=PROBE
clear=0
install=1
timeout=120
extras=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --avd) avd=$2; shift 2 ;;
    --probe) probe=$2; shift 2 ;;
    --clear) clear=1; shift ;;
    --no-install) install=0; shift ;;
    --timeout) timeout=$2; shift 2 ;;
    --extras) extras=$2; shift 2 ;;
    -h|--help) sed -n 2,6p "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
probe=${probe#$PKG.debug.}
action=$PKG.debug.$probe

root=$(git -C "$(dirname "$0")" rev-parse --show-toplevel) || exit 2
cd "$root" || exit 2

say() { echo "[device-probe] $*" >&2; }

# 1. Device: reuse an attached one (honours ANDROID_SERIAL), else boot the AVD headless.
if ! adb get-state >/dev/null 2>&1; then
  say "booting $avd"
  nohup emulator -avd "$avd" -no-window -no-audio -no-snapshot-save >"build/emulator-$avd.log" 2>&1 &
  adb wait-for-device || exit 2
fi
for _ in $(seq 1 180); do
  [[ $(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]] && break
  sleep 1
done
[[ $(adb shell getprop sys.boot_completed | tr -d '\r') == 1 ]] || { say "boot timed out"; exit 2; }
sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
say "device $(adb get-serialno) api $sdk"

# 2. Install, then optionally wipe app data (needed after an in-place Room schema change).
if [[ $install == 1 ]]; then
  ./gradlew :app:installDebug --console=plain -q || { say "installDebug failed"; exit 2; }
fi
if [[ $clear == 1 ]]; then
  adb shell pm clear "$PKG" >/dev/null || { say "pm clear failed"; exit 2; }
fi

# 3. Shizuku: must be installed; start its server via the adb starter if it is not running.
shizuku_apk=$(adb shell pm path "$SHIZUKU_PKG" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1)
[[ -n $shizuku_apk ]] || { say "Shizuku ($SHIZUKU_PKG) not installed on this AVD"; exit 2; }
if [[ -z $(adb shell pidof shizuku_server 2>/dev/null | tr -d '\r') ]]; then
  abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
  case "$abi" in arm64*) libdir=arm64 ;; armeabi*) libdir=arm ;; x86_64) libdir=x86_64 ;; *) libdir=x86 ;; esac
  say "starting Shizuku server ($libdir)"
  adb shell "$(dirname "$shizuku_apk")/lib/$libdir/libshizuku.so" >/dev/null 2>&1
  sleep 2
  [[ -n $(adb shell pidof shizuku_server | tr -d '\r') ]] || { say "Shizuku server did not start"; exit 2; }
fi

# 4. Fire the probe and collect until the receiver's terminal line.
out=build/device-evidence/api$sdk-$probe.jsonl
mkdir -p build/device-evidence
adb logcat -c
# shellcheck disable=SC2086 # extras are intentionally word-split into am arguments
adb shell am broadcast -a "$action" -n "$RECEIVER" $extras >/dev/null || { say "broadcast failed"; exit 2; }

status=3
for _ in $(seq 1 "$timeout"); do
  adb logcat -d -v raw -s "$TAG:I" | tr -d '\r' | grep '^{' >"$out.tmp"
  if grep -qE '"probe":"(done|timeout)"' "$out.tmp"; then
    grep -q '"probe":"done"' "$out.tmp" && status=0
    break
  fi
  sleep 1
done
mv "$out.tmp" "$out"

lines=$(wc -l <"$out")
say "$lines line(s) -> $out"
if grep -q '"state"' "$out" && ! grep -q '"state":"Ready' "$out"; then
  say "Shizuku state is not Ready: $(grep -o '"state":"[^"]*"' "$out" | head -1)"
  say "grant it: rerun with --probe PROBE_REQUEST_PERMISSION, tap 'Allow all the time' on the AVD"
fi
[[ $status == 0 ]] || say "probe did not finish cleanly (timeout ${timeout}s or probe-side timeout)"
exit $status
