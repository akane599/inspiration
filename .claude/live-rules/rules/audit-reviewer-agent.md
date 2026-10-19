---
description: Blind-audit workers use the audit-reviewer agent
prompt: ["full-repo-audit", "audit-remediate", "/blind[- ]audit/i"]
priority: 15
---
- When /full-repo-audit dispatches subsystem reviewers or validators, or /audit-remediate runs read-only review/validation, use `subagent_type: "audit-reviewer"` (`.claude/agents/audit-reviewer.md`). Never use `general-purpose` or a fork for those workers.
- Why: the codebase-mapper SubagentStart hook matches `general-purpose`/`sidequest-exec*` agents and makes them read `.claude/.codebase-info` docs. That contaminated every worker in the 2026-10-19 audit. `audit-reviewer` doesn't match the hook.
- If `audit-reviewer` isn't among the available agent types (it needs a new session after being added), stop and say so. Don't fall back to `general-purpose` for a blind run.
- This doesn't stop the SessionStart map injection into the main session; keep disclosing that in the contamination ledger.
