---
name: android-map
description: "Create or refresh a compact Android repository map without repeatedly rereading unchanged code."
---

Run from the project root. Read AGENTS.md and applicable nested instructions. Helpers live in `.codex/android-kit/`. Preserve existing project conventions. Use normal Codex permissions. Do not change provider/authentication settings or launch nested agent CLIs. Never claim a check ran unless you observed its result.

Use `rg --files` and build files to identify modules, entry points, architecture, persistence, background work and test commands. Read only enough representative code to confirm boundaries. Write `.codex/android-kit/MAP.md` with HEAD, mapped paths, key symbols, risks and verified versus inferred facts. Exclude generated files and secrets. On later updates, compare changes and refresh affected sections only. Do not create backlog tasks unless requested. This is an explicit map workflow, not an automatic session hook.
