#!/usr/bin/env bash
# Summarise the full gate's results for the release notes and the PROGRESS.md baseline line:
# unit tests per module (from the JUnit XML), screenshot references, and :app's lint totals.
# Run it from the repo root right after the gate; it reads whatever results the last run left behind,
# which is why the gate uses --rerun-tasks. Exit 1 if any test failed or errored, else 0.
# ./build is pruned: trees exported there (such as build/tmp/negative-control-02e225d) carry their own results.
set -uo pipefail
total=0
bad=0
line=""
for dir in $(find . \( -path ./.claude -o -path ./build \) -prune -o -type d -path '*/build/test-results/testDebugUnitTest' -print | sort); do
  module=${dir#./}
  module=${module%/build/test-results/testDebugUnitTest}
  read -r tests failures errors < <(cat "$dir"/*.xml 2>/dev/null |
    sed -n 's/.*<testsuite [^>]*tests="\([0-9]*\)"[^>]*failures="\([0-9]*\)"[^>]*errors="\([0-9]*\)".*/\1 \2 \3/p' |
    awk '{t += $1; f += $2; e += $3} END {print t + 0, f + 0, e + 0}')
  total=$((total + tests))
  bad=$((bad + failures + errors))
  line="$line${line:+, }${module##*/} $tests"
  [ $((failures + errors)) -eq 0 ] || echo "FAILING: $module has $failures failure(s) and $errors error(s)"
done
shots=$(find . \( -path ./.claude -o -path ./build \) -prune -o -path '*/src/screenshotTestDebug/reference/*' -name '*.png' -print | wc -l)
lint=$(tail -n 1 app/build/reports/lint-results-debug.txt 2>/dev/null)
echo "unit tests: $total ($line), $bad failing"
echo "screenshot references: $shots"
echo "lint (:app): ${lint:-no report found}"
[ "$bad" -eq 0 ]
