#!/usr/bin/env bash
# The one APK a release may publish: built by a recorded run of the full gate at the approved commit, then chosen and
# checked mechanically rather than by file name or version.
#
#   release-candidate.sh gate <version> [--max-workers=N]
#     Runs the fixed full gate (every module's unit tests, lint, screenshot validation, debug and R8 release builds,
#     --rerun-tasks) through .claude/scripts/gradle-slot.sh, from the main checkout, on main, with a clean tree (kit
#     allowlist), after deleting the previous app-release.apk. Records in build/release/<version>/gate-result: the HEAD
#     it ran at (it must not move), the task list, the worker count, the exit status, and the SHA-256 of the
#     app-release.apk the run wrote (a regular file). Removes a candidate.txt staged from an earlier gate. Exits with
#     the gate's status; exits 1 without a record when HEAD moved or the tree was dirty afterwards.
#   release-candidate.sh stage <approved-sha> <version>
#     Refuses unless: main checkout, on main, clean tree; the approved commit is given as a hex SHA (prefix of the
#     commit it resolves to); HEAD is that commit, or that commit plus exactly one version-bump commit (only the
#     versionCode/versionName lines of app/build.gradle.kts, versionCode +1); gate-result records the full gate at this
#     HEAD with status 0 and the APK's current hash; the hash is not on forbidden-release-apks.sha256; every signer is
#     the pinned certificate; the badging matches the version and HEAD's build file. Then it stages
#     build/release/<version>/inspiration-<version>.apk, its .sha256 and candidate.txt, and prints one "candidate" line.
#   release-candidate.sh verify <approved-sha> <version>
#     Right before publishing: HEAD and the approved commit match candidate.txt, the gate record still vouches for this
#     HEAD and hash, the staged APK has the recorded hash and .sha256 line, is not forbidden, and its signers and
#     badging are checked again from the file.
#   release-candidate.sh forbidden <apk>
#     Says whether any APK is on the forbidden list. Exit 1 when it is, 2 when it cannot check.
# Exit: 0 ok, 1 refused (reason printed), 2 usage or cannot check (missing list or build-tools); gate: the gate's status.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
forbidden=$here/forbidden-release-apks.sha256
pin=88292f2344df4095f69e70db728ece8ac6ffcde4564d355a20181391d054d723
apk=app/build/outputs/apk/release/app-release.apk
gradle_file=app/build.gradle.kts
pkg=com.akane.inspiration
# The full gate (release skill step 3). `stage` accepts a record of exactly these tasks and nothing narrower.
gate_tasks="testDebugUnitTest lintDebug validateDebugScreenshotTest :app:assembleDebug :app:minifyReleaseWithR8 :app:assembleRelease --rerun-tasks"
# Working-tree entries a release may ignore (skill step 1): the kit's settings and Codex copy, and worktrees.
allow='^.. (\.claude/settings\.json|\.codex/android-kit/[^ ]*|\.claude/worktrees/[^ ]*)$'
verline='^ *version(Code|Name) = '

refuse() { echo "release-candidate: REFUSED: $*" >&2; exit 1; }
cannot() { echo "release-candidate: cannot check: $*" >&2; exit 2; }
usage() {
  echo "usage: release-candidate.sh gate <version> [--max-workers=N] | stage <approved-sha> <version> | verify <approved-sha> <version> | forbidden <apk>" >&2
  exit 2
}
sha256() { sha256sum "$1" | cut -d' ' -f1; }
forbidden_hit() { # prints the list line for a hash that is forbidden, nothing otherwise
  [ -f "$forbidden" ] || cannot "forbidden list $forbidden is missing"
  awk -v h="$1" 'tolower($1) == tolower(h)' "$forbidden"
}
tool() { # tool <name>: the newest build-tools binary; exit 2 when there is none
  local bt
  bt=$(ls -d "${ANDROID_HOME:?ANDROID_HOME is unset}"/build-tools/* 2>/dev/null | sort -V | tail -1) || true
  [ -n "$bt" ] && [ -x "$bt/$1" ] || cannot "$1 not found under \$ANDROID_HOME/build-tools"
  echo "$bt/$1"
}
# signers <apksigner> <apk>: every certificate digest apksigner prints ("Signer #1 certificate SHA-256 digest: …", or
# "V2 Signer: certificate SHA-256 digest: …" in build-tools 37), one per line, deduplicated. Empty when it fails.
signers() {
  "$1" verify --print-certs "$2" 2>/dev/null |
    sed -nE 's/^.*Signer[^:]*(: | )certificate SHA-256 digest: //p' | sort -u || true
}
badging() { # badging <aapt2> <apk>: "package versionCode versionName", empty when aapt2 fails
  "$1" dump badging "$2" 2>/dev/null |
    sed -n "s/^package: name='\([^']*\)' versionCode='\([^']*\)' versionName='\([^']*\)'.*/\1 \2 \3/p" || true
}
version_code_at() { git show "$1:$gradle_file" | sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' | head -1; }
version_name_at() { git show "$1:$gradle_file" | sed -n 's/^ *versionName = "\([^"]*\)"$/\1/p' | head -1; }
rec() { sed -n "s/^$2=//p" "$1"; }
# check_apk_file <apk> <version> <hash>: forbidden list, signer pin, badging against <version> and HEAD's build file.
check_apk_file() {
  local f=$1 v=$2 h=$3 hit apksigner aapt2 s p code name
  hit=$(forbidden_hit "$h")
  [ -z "$hit" ] || refuse "FORBIDDEN ARTIFACT: $hit"
  apksigner=$(tool apksigner)   # plain assignments, so a missing tool exits 2 instead of reading as a bad APK
  aapt2=$(tool aapt2)
  s=$(signers "$apksigner" "$f")
  [ -n "$s" ] || refuse "apksigner could not verify $f (unsigned, damaged or not an APK)"
  [ "$s" = "$pin" ] || refuse "signer(s) $(tr '\n' ' ' <<<"$s")are not exactly the pinned certificate $pin"
  read -r p code name < <(badging "$aapt2" "$f") || refuse "aapt2 could not read the badging of $f"
  [ "${p:-}" = "$pkg" ] || refuse "package ${p:-?} is not $pkg"
  [ "$name" = "$v" ] || refuse "versionName $name is not $v"
  [ "$code" = "$(version_code_at HEAD)" ] || refuse "versionCode $code is not the one in $gradle_file at HEAD ($(version_code_at HEAD))"
  [ "$(version_name_at HEAD)" = "$v" ] || refuse "versionName in $gradle_file at HEAD is $(version_name_at HEAD), not $v"
  signer_out=$s
  code_out=$code
}
# check_gate_record <hash>: the recorded gate ran the full task list at HEAD, passed, and wrote the APK with <hash>.
check_gate_record() {
  [ -f "$gate_result" ] || refuse "no $gate_result: run the gate through 'release-candidate.sh gate $v' (step 3)"
  [ "$(rec "$gate_result" tasks)" = "$gate_tasks" ] ||
    refuse "the recorded gate ran '$(rec "$gate_result" tasks)', not the full gate '$gate_tasks'"
  [ "$(rec "$gate_result" commit)" = "$head" ] || refuse "the recorded gate ran at $(rec "$gate_result" commit), not at HEAD $head"
  [ "$(rec "$gate_result" status)" = 0 ] || refuse "the recorded gate exited $(rec "$gate_result" status)"
  [ "$(rec "$gate_result" apk_sha256)" = "$1" ] ||
    refuse "sha256 $1 is not the APK the recorded gate wrote ($(rec "$gate_result" apk_sha256))"
}

mode=${1:-}
[ $# -ge 1 ] && shift
workers=8
case $mode in
  forbidden)
    [ $# -eq 1 ] || usage
    [ -f "$1" ] || cannot "no such file: $1"
    h=$(sha256 "$1")
    hit=$(forbidden_hit "$h")
    [ -z "$hit" ] || { echo "FORBIDDEN: $hit"; exit 1; }
    echo "not on the forbidden list: $h  $1"
    exit 0 ;;
  gate)
    [ $# -ge 1 ] && [ $# -le 2 ] || usage
    v=$1
    if [ $# -eq 2 ]; then [[ $2 =~ ^--max-workers=([1-8])$ ]] || usage; workers=${BASH_REMATCH[1]}; fi ;;
  stage | verify) [ $# -eq 2 ] || usage; approved_arg=$1; v=$2 ;;
  *) usage ;;
esac
[[ $v =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || refuse "version '$v' is not of the form X.Y.Z"
dir=build/release/$v
staged=$dir/inspiration-$v.apk
candidate=$dir/candidate.txt
gate_result=$dir/gate-result

# Where we are: the top level of the main checkout, on main, with a clean tree.
[ "$(git rev-parse --show-toplevel)" = "$PWD" ] || refuse "run from the repository root"
[ "$(git rev-parse --git-common-dir)" = .git ] || refuse "this is a linked worktree, not the main checkout"
branch=$(git rev-parse --abbrev-ref HEAD)
[ "$branch" = main ] || refuse "on branch $branch, not main"
head=$(git rev-parse HEAD)
clean_or_refuse() { # clean_or_refuse <when>
  local status dirty
  status=$(git status --porcelain --untracked-files=all) || refuse "git status failed"
  dirty=$(grep -vE "$allow" <<<"$status" || [ $? -eq 1 ])
  [ -z "$dirty" ] || refuse "the tree is not clean outside the kit allowlist $1:"$'\n'"$dirty"
}
clean_or_refuse "now"

if [ "$mode" = gate ]; then
  [ -f .claude/scripts/gradle-slot.sh ] || cannot "no .claude/scripts/gradle-slot.sh in this checkout"
  mkdir -p "$dir"
  rm -f "$gate_result" "$candidate"   # neither an earlier gate's record nor a candidate staged from it may vouch for this run
  rm -f "$apk"                        # so the hash below can only be this run's output
  started=$(date -u +%FT%TZ)
  set +e
  # shellcheck disable=SC2086
  bash .claude/scripts/gradle-slot.sh $gate_tasks "--max-workers=$workers"
  rc=$?
  set -e
  finished=$(date -u +%FT%TZ)
  [ "$(git rev-parse HEAD)" = "$head" ] || refuse "HEAD moved during the gate; nothing recorded"
  clean_or_refuse "after the gate"
  apk_hash=none
  if [ -f "$apk" ] && [ ! -L "$apk" ]; then apk_hash=$(sha256 "$apk"); fi
  printf '%s\n' "commit=$head" "version=$v" "tasks=$gate_tasks" "workers=$workers" "status=$rc" "started=$started" \
    "finished=$finished" "apk_sha256=$apk_hash" >"$gate_result"
  echo "gate       commit $head  status $rc  apk sha256 $apk_hash  $gate_result"
  exit "$rc"
fi

[[ $approved_arg =~ ^[0-9a-f]{7,40}$ ]] || refuse "the approved commit must be a hex SHA of 7 to 40 digits, not '$approved_arg'"
approved=$(git rev-parse --verify --quiet "$approved_arg^{commit}") || refuse "approved commit $approved_arg not found or ambiguous"
[[ $approved == "$approved_arg"* ]] || refuse "'$approved_arg' resolved to $approved, a ref rather than a commit prefix"

if [ "$mode" = verify ]; then
  [ -f "$candidate" ] || refuse "no $candidate: run 'stage' first"
  [ "$(rec "$candidate" commit)" = "$head" ] || refuse "HEAD $head is not the candidate's commit $(rec "$candidate" commit)"
  [ "$(rec "$candidate" approved)" = "$approved" ] || refuse "the candidate was staged for approved commit $(rec "$candidate" approved), not $approved"
  [ -f "$staged" ] && [ ! -L "$staged" ] || refuse "no regular file at $staged"
  h=$(sha256 "$staged")
  [ "$h" = "$(rec "$candidate" sha256)" ] || refuse "$staged no longer has the recorded hash $(rec "$candidate" sha256)"
  [ "$(cat "$dir/inspiration-$v.apk.sha256")" = "$h  inspiration-$v.apk" ] || refuse "inspiration-$v.apk.sha256 does not read '$h  inspiration-$v.apk'"
  check_gate_record "$h"
  check_apk_file "$staged" "$v" "$h"
  echo "verified   commit $head  approved $approved  $v ($code_out)  sha256 $h  signer $signer_out  gate full  $staged"
  exit 0
fi

# stage: HEAD is the approved commit, or the approved commit plus exactly one version-bump commit.
rm -f "$candidate"   # a candidate stands only after every check below passed
if [ "$head" != "$approved" ]; then
  [ "$(git rev-parse HEAD^)" = "$approved" ] ||
    refuse "HEAD $head is neither the approved commit $approved nor that commit plus one bump commit"
  [ "$(git diff --name-only "$approved" HEAD)" = "$gradle_file" ] ||
    refuse "the commit after the approved one touches more than $gradle_file"
  diff <(git show "$approved:$gradle_file" | grep -vE "$verline") <(git show "HEAD:$gradle_file" | grep -vE "$verline") >/dev/null ||
    refuse "the bump commit changes more than the versionCode/versionName lines of $gradle_file"
  [ "$(version_code_at HEAD)" = "$(($(version_code_at "$approved") + 1))" ] ||
    refuse "versionCode $(version_code_at HEAD) at HEAD is not the approved commit's $(version_code_at "$approved") plus one"
fi

# The APK is the regular file the recorded full gate wrote at this HEAD, and that gate passed.
[ -f "$apk" ] && [ ! -L "$apk" ] || refuse "no regular file at $apk"
h=$(sha256 "$apk")
check_gate_record "$h"
check_apk_file "$apk" "$v" "$h"

mkdir -p "$dir"
rm -f "$staged"
cp "$apk" "$staged"
[ "$(sha256 "$staged")" = "$h" ] || refuse "the staged copy differs from $apk"
(cd "$dir" && sha256sum "inspiration-$v.apk" >"inspiration-$v.apk.sha256" && sha256sum -c --quiet "inspiration-$v.apk.sha256")
printf '%s\n' "commit=$head" "approved=$approved" "version=$v" "versionCode=$code_out" "sha256=$h" "signer=$signer_out" \
  "gate_tasks=$gate_tasks" "gate_workers=$(rec "$gate_result" workers)" "gate_finished=$(rec "$gate_result" finished)" \
  "apk=$staged" "source=$apk" "gate_result=$gate_result" "staged_at=$(date -u +%FT%TZ)" >"$candidate"
echo "candidate  commit $head  approved $approved  $v ($code_out)  sha256 $h  signer $signer_out  gate full  $staged"
