---
name: android-kit-refresh
description: "Refresh Codex kit discovery and checks after merging or updating project skills."
---

Run from the project root. Read AGENTS.md and applicable nested instructions. Helpers live in `.codex/android-kit/`. Preserve existing project conventions. Use normal Codex permissions. Do not change provider/authentication settings or launch nested agent CLIs. Never claim a check ran unless you observed its result.

Run $android-kit-doctor. Codex normally rediscovers skill edits; request a fresh session only if updated skills do not appear in `/skills`. Detect duplicate android-* skills in visible scopes without deleting user skills. This does not upgrade Toolshed or update other projects. For a kit upgrade rerun the supplied setup.sh from the new archive, then resolve reported conflicts.
