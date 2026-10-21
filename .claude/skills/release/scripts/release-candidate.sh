#!/usr/bin/env bash
# The one APK a release may publish, chosen and checked mechanically rather than by file name or version.
#
#   release-candidate.sh stage <approved-sha> <version>
#     Run from the main checkout, on main, right after the step 3 gate. Refuses unless HEAD is the approved commit
#     or that commit plus exactly one version-bump commit (only app/build.gradle.kts, only versionCode/versionName),
#     the tree is clean apart from the kit allowlist, build/release/<version>/gate-started was touched after HEAD
#     was committed and before app/build/outputs/apk/release/app-release.apk was written, the APK is not on
#     forbidden-release-apks.sha256, its signer is the pinned certificate and its version matches. Then it stages
#     build/release/<version>/inspiration-<version>.apk with its .sha256 and writes candidate.txt.
#   release-candidate.sh verify <version>
#     Right before publishing: HEAD is still the candidate's commit, the staged APK still has the recorded hash and
#     is still not forbidden.
#   release-candidate.sh forbidden <apk>
#     Says whether any APK is on the forbidden list. Exit 1 when it is.
# Exit: 0 ok, 1 refused (the reason is printed), 2 usage.
set -euo pipefail

pin=88292f2344df4095f69e70db728ece8ac6ffcde4564d355a20181391d054d723
apk=app/build/outputs/apk/release/app-release.apk
forbidden=.claude/skills/release/scripts/forbidden-release-apks.sha256
pkg=com.akane.inspiration
# Working-tree entries a release may ignore (step 1 of the skill): the kit's settings and Codex copy, and worktrees.
allow='^.. (\.claude/settings\.json|\.codex/android-kit/|\.claude/worktrees/)'

refuse() { echo "release-candidate: REFUSED: $*" >&2; exit 1; }
usage() { echo "usage: release-candidate.sh stage <approved-sha> <version> | verify <version> | forbidden <apk>" >&2; exit 2; }
sha256() { sha256sum "$1" | cut -d' ' -f1; }
forbidden_hit() { # prints the list line for a hash that is forbidden, nothing otherwise
  [ -f "$forbidden" ] || refuse "forbidden list $forbidden is missing"
  awk -v h="$1" 'tolower($1) == tolower(h)' "$forbidden"
}
bt=$(ls -d "${ANDROID_HOME:?ANDROID_HOME is unset}"/build-tools/* | sort -V | tail -1)
# apksigner prints "Signer #1 certificate …" or, in build-tools 37, "V2 Signer: certificate …".
signer() {
  "$bt/apksigner" verify --print-certs "$1" 2>/dev/null |
    sed -n 's/^.*Signer[^:]*: certificate SHA-256 digest: //p; s/^Signer #1 certificate SHA-256 digest: //p' | head -1
}
badging() {
  "$bt/aapt2" dump badging "$1" 2>/dev/null |
    sed -n "s/^package: name='\([^']*\)' versionCode='\([^']*\)' versionName='\([^']*\)'.*/\1 \2 \3/p"
}

mode=${1:-}
[ $# -ge 1 ] && shift
case $mode in
  forbidden)
    [ $# -eq 1 ] && [ -f "$1" ] || usage
    h=$(sha256 "$1")
    hit=$(forbidden_hit "$h")
    [ -z "$hit" ] || { echo "FORBIDDEN: $hit"; exit 1; }
    echo "not on the forbidden list: $h  $1"
    exit 0 ;;
  stage) [ $# -eq 2 ] || usage; approved=$1; v=$2 ;;
  verify) [ $# -eq 1 ] || usage; v=$1 ;;
  *) usage ;;
esac
dir=build/release/$v
staged=$dir/inspiration-$v.apk
candidate=$dir/candidate.txt

# Where we are: the top level of the main checkout, on main.
[ "$(git rev-parse --show-toplevel)" = "$PWD" ] || refuse "run from the repository root"
[ "$(git rev-parse --git-common-dir)" = .git ] || refuse "this is a linked worktree, not the main checkout"
branch=$(git rev-parse --abbrev-ref HEAD)
[ "$branch" = main ] || refuse "on branch $branch, not main"
head=$(git rev-parse HEAD)
dirty=$(git status --porcelain --untracked-files=all | grep -vE "$allow" || true)
[ -z "$dirty" ] || refuse "the tree is not clean outside the kit allowlist:"$'\n'"$dirty"

if [ "$mode" = verify ]; then
  [ -f "$candidate" ] || refuse "no $candidate: run 'stage' first"
  rec() { sed -n "s/^$1=//p" "$candidate"; }
  [ "$(rec commit)" = "$head" ] || refuse "HEAD $head is not the candidate's commit $(rec commit)"
  [ -f "$staged" ] || refuse "no $staged"
  [ "$(sha256 "$staged")" = "$(rec sha256)" ] || refuse "$staged no longer has the recorded hash $(rec sha256)"
  (cd "$dir" && sha256sum -c --quiet "inspiration-$v.apk.sha256") || refuse "inspiration-$v.apk.sha256 does not match"
  hit=$(forbidden_hit "$(rec sha256)")
  [ -z "$hit" ] || refuse "FORBIDDEN ARTIFACT: $hit"
  echo "verified  commit $head  $v ($(rec versionCode))  sha256 $(rec sha256)  signer $(rec signer)  $staged"
  exit 0
fi

# stage: HEAD is the approved commit, or the approved commit plus exactly one version-bump commit.
approved=$(git rev-parse --verify --quiet "$approved^{commit}") || refuse "approved commit not found"
if [ "$head" != "$approved" ]; then
  [ "$(git rev-parse HEAD^)" = "$approved" ] ||
    refuse "HEAD $head is neither the approved commit $approved nor that commit plus one bump commit"
  [ "$(git diff --name-only "$approved" HEAD)" = app/build.gradle.kts ] ||
    refuse "the commit after the approved one touches more than app/build.gradle.kts"
  if git diff -U0 "$approved" HEAD -- app/build.gradle.kts | grep -E '^[-+]' | grep -vE '^(\+\+\+|---)' |
    grep -vE '^[-+] *(versionCode|versionName) *='; then
    refuse "the bump commit changes more than versionCode/versionName"
  fi
fi

# The APK is this gate's output: the stamp was touched after HEAD was committed, the APK written after the stamp.
stamp=$dir/gate-started
[ -f "$stamp" ] || refuse "no $stamp: touch it right before the gate (step 3)"
[ "$(stat -c %Y "$stamp")" -ge "$(git log -1 --format=%ct HEAD)" ] || refuse "$stamp predates HEAD's commit: run the gate again"
[ -f "$apk" ] || refuse "no $apk"
[ "$apk" -nt "$stamp" ] || refuse "$apk is older than $stamp: this gate did not write it"

h=$(sha256 "$apk")
hit=$(forbidden_hit "$h")
[ -z "$hit" ] || refuse "FORBIDDEN ARTIFACT: $hit"
s=$(signer "$apk")
[ "$s" = "$pin" ] || refuse "signer ${s:-none} is not the pinned certificate $pin"
read -r p code name < <(badging "$apk") || refuse "cannot read the APK's badging"
[ "${p:-}" = "$pkg" ] || refuse "package ${p:-?} is not $pkg"
[ "$name" = "$v" ] || refuse "versionName $name is not $v"
grep -qE "^ *versionCode = $code\$" app/build.gradle.kts || refuse "versionCode $code is not the one in app/build.gradle.kts at HEAD"
grep -qE "^ *versionName = \"$v\"\$" app/build.gradle.kts || refuse "versionName $v is not the one in app/build.gradle.kts at HEAD"

mkdir -p "$dir"
cp "$apk" "$staged"
[ "$(sha256 "$staged")" = "$h" ] || refuse "the staged copy differs from $apk"
(cd "$dir" && sha256sum "inspiration-$v.apk" >"inspiration-$v.apk.sha256" && sha256sum -c --quiet "inspiration-$v.apk.sha256")
printf '%s\n' "commit=$head" "approved=$approved" "version=$v" "versionCode=$code" "sha256=$h" "signer=$s" \
  "apk=$staged" "source=$apk" "staged_at=$(date -u +%FT%TZ)" >"$candidate"
echo "candidate  commit $head  $v ($code)  sha256 $h  signer $s  $staged"
