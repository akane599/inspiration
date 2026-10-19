---
description: Blind-audit workers use the audit-reviewer agent
prompt: ["full-repo-audit", "audit-remediate", "/blind[- ]audit/i"]
priority: 15
---
- When /full-repo-audit dispatches subsystem reviewers or validators before the findings freeze, use `subagent_type: "audit-reviewer"` (`.claude/agents/audit-reviewer.md`). Never use `general-purpose` or a fork for those workers.
- /audit-remediate implementers and diff reviewers are not blind work: they may use `general-purpose` and benefit from the map. Prefer `audit-reviewer` for its read-only reviews when it is available.
- Why: the codebase-mapper SubagentStart hook matches `general-purpose`/`sidequest-exec*` agents and makes them read `.claude/.codebase-info` docs. That contaminated every worker in the 2026-10-19 audit. `audit-reviewer` doesn't match the hook.
- If `audit-reviewer` isn't among the available agent types (it needs a new session after being added), stop and say so. Don't fall back to `general-purpose` for a blind run.
- This doesn't stop the SessionStart map injection into the main session; keep disclosing that in the contamination ledger.
