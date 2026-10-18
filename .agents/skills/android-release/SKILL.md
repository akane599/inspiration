---
name: android-release
description: "Prepare Android versions, signing configuration, multi-app Gradle structure and release checks."
---

Run from the project root. Read AGENTS.md and applicable nested instructions. Helpers live in `.codex/android-kit/`. Preserve existing project conventions. Use normal Codex permissions. Do not change provider/authentication settings or launch nested agent CLIs. Never claim a check ran unless you observed its result.

Read the relevant `.codex/android-kit/guides/droidforge/` references for package naming, multi-app structure, Gradle practices, keystore conventions or release workflow. Discover actual release tasks and existing version management before modifying them. Prepare a concrete release checklist and artifacts requested by the user. Do not print secrets, hardcode passwords or commit signing material. Treat reference commands as examples, not authorization to upload, publish, push tags, delete keys or rotate credentials. Separate local preparation from any external release action and follow the user's authorization. Verify current store requirements from official sources before treating historical numeric guidance as current.
