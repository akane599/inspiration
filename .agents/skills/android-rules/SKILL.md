---
name: android-rules
description: "Maintain project and path-specific Android conventions for Codex."
---

Run from the project root. Read AGENTS.md and applicable nested instructions. Helpers live in `.codex/android-kit/`. Preserve existing project conventions. Use normal Codex permissions. Do not change provider/authentication settings or launch nested agent CLIs. Never claim a check ran unless you observed its result.

Read AGENTS.md and `.codex/android-kit/rules/`. Reconcile requested rules with existing conventions. Put broad instructions in AGENTS.md or referenced rule files; put path-specific instructions in nested AGENTS.md only when their scope is correct. Preserve user text and explain conflicts. Do not install Live Rules hooks or claim dynamic injection. Confirm each rule is concise, actionable and compatible with adopted code.
