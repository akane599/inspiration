---
name: android-kit-doctor
description: "Check Android Codex kit installation, tools, skill files and pending merges."
---

Run from the project root. Read AGENTS.md and applicable nested instructions. Helpers live in `.codex/android-kit/`. Preserve existing project conventions. Use normal Codex permissions. Do not change provider/authentication settings or launch nested agent CLIs. Never claim a check ran unless you observed its result.

Run `python3 .codex/android-kit/doctor.py` from the root and report failures separately from optional missing tools. Check `/skills` in the user's actual Codex session when possible; a filesystem scan does not prove runtime loading. Read PROJECT.md for actual build verification, and report missing inference/device/build evidence. Do not change configuration to hide a failure.
