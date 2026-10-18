---
name: android-bug-hunt
description: "Audit existing Android code for concrete bugs with focused coverage and evidence-based triage."
---

Run from the project root. Read AGENTS.md and applicable nested instructions. Helpers live in `.codex/android-kit/`. Preserve existing project conventions. Use normal Codex permissions. Do not change provider/authentication settings or launch nested agent CLIs. Never claim a check ran unless you observed its result.

Read `.codex/android-kit/android-bug-checklist.md`. Use the requested scope or select the riskiest areas from PROJECT.md and the map. State coverage. One focused pass is the default; split distinct large areas only, up to four independent scopes per wave. Use native subagents only when available and authorized; otherwise conduct sequential passes and disclose that they were not independent contexts.
Review read-only: no source edits or Gradle runs. Report reachable failure scenarios, evidence and file/line locations. Deduplicate before validating candidates against callers and tests; batch validation and skip it when no candidates remain. Show prioritized findings and let the user select fixes unless fixing is already authorized. Record approved fixes through $android-board, reproduce each bug with a suitable test where feasible, and verify it. Explicit broad audits must cover all requested areas or clearly report unfinished coverage.
