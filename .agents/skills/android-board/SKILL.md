---
name: android-board
description: "Track Android implementation tasks, dependencies and verification in a project-local Codex board."
---

Run from the project root. Read AGENTS.md and applicable nested instructions. Helpers live in `.codex/android-kit/`. Preserve existing project conventions. Use normal Codex permissions. Do not change provider/authentication settings or launch nested agent CLIs. Never claim a check ran unless you observed its result.

This is the kit's native board, not Eigenwise Sidequest and not compatible with its database. Use `python3 .codex/android-kit/board.py --help`. Keep each task bounded, with concrete paths, acceptance criteria and an exact verification argv. `add`, `start`, `verify`, `finish`, `block` and `list` manage tasks. Verification runs only when explicitly invoked; inspect the command first. `finish` requires successful verification for the current repository fingerprint. Record test limitations in the task description. Never use `true` or a metadata-only command to certify code behavior.
Work sequentially by default. Independent tasks may use native subagents when authorized and available; allocate distinct files or worktrees and integrate deliberately. The board does not provide Sidequest's automatic dispatch, file locks, worktree provisioning or integration gates. Review shared-file dependencies before delegation. Do not modify old Sidequest state or impersonate its executors. Inherit current Codex model/settings.
