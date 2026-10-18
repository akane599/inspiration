---
name: android-ui-overhaul
description: "Plan and implement a coordinated Android UI redesign with tokens and screenshot verification."
---

Run from the project root. Read AGENTS.md and applicable nested instructions. Helpers live in `.codex/android-kit/`. Preserve existing project conventions. Use normal Codex permissions. Do not change provider/authentication settings or launch nested agent CLIs. Never claim a check ran unless you observed its result.

Use $android-compose-design first. Inventory screens and existing test tooling; capture baseline images when available. Agree the target scope from the user request, then separate shared theme changes from screen changes. Use $android-board to record dependencies and verification. Change shared tokens first, then screens with clear ownership; never run multiple writers on shared theme files. Preserve navigation, semantics, state restoration and existing behavior unless requested. Run the narrow applicable build/test and inspect screenshots before and after. Do not approve visual work based only on compilation.
