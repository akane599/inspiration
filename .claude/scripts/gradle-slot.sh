#!/usr/bin/env bash
# Run Gradle through .claude/kit/gradle-check.sh while holding one of the host-wide Gradle slots, so
# every session and worktree together stays within the android-build invocation cap. Waiting holds no
# slot. The exit code is Gradle's own (gradle-check.sh keeps the full log and prints PASS or the tail).
# Usage, from a checkout or worktree root: bash .claude/scripts/gradle-slot.sh <tasks and args>
#   e.g. bash .claude/scripts/gradle-slot.sh :core:database:testDebugUnitTest --max-workers=4
# GRADLE_SLOTS sets the slot count (default 2); use GRADLE_SLOTS=1 after an OOM.
set -uo pipefail
[ -f .claude/kit/gradle-check.sh ] || { echo 'gradle-slot: run from a checkout or worktree root' >&2; exit 2; }
slots=${GRADLE_SLOTS:-2}
dir=/tmp/claude-$(id -u)/gradle-slots
mkdir -p "$dir" || exit 2
announced=0
while :; do
  for i in $(seq 1 "$slots"); do
    exec {fd}>"$dir/slot$i.lock"
    if flock -n "$fd"; then
      bash .claude/kit/gradle-check.sh "$@"
      exit $?
    fi
    exec {fd}>&-
  done
  if [ "$announced" -eq 0 ]; then
    echo "gradle-slot: all $slots slots busy, waiting..." >&2
    announced=1
  fi
  sleep 5
done
