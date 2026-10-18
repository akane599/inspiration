---
name: android-bootstrap
description: "Set up a new Android app or adopt an existing Android project; establish stack, baseline and project map."
---

Run from the project root. Read AGENTS.md and applicable nested instructions. Helpers live in `.codex/android-kit/`. Preserve existing project conventions. Use normal Codex permissions. Do not change provider/authentication settings or launch nested agent CLIs. Never claim a check ran unless you observed its result.

1. Run `python3 .codex/android-kit/doctor.py`. Resolve template conflicts with $android-kit-merge before continuing. Read existing AGENTS.md and CLAUDE.md as project evidence; reconcile useful facts without editing the Claude setup.
2. Run `bash .codex/android-kit/detect-stack.sh`. If an Android build exists, discover actual modules, variants, wrapper, JDK and tests. Record pre-existing failures; do not fix them as part of adoption.
3. For a new app ask for missing app name, package ID, purpose and minSdk together. Inspect `python3 .codex/android-kit/scaffold.py --help`. Use the scaffold only in a genuinely new app directory; `--offline` produces files without downloading a wrapper, and `--wrapper-only` completes it later. The inherited version snapshot is unverified here: check compatibility and resolve actual available versions before claiming build readiness.
4. Run the appropriate assemble/unit-test baseline using `bash .codex/android-kit/gradle-check.sh <actual tasks>`. Use existing screenshot tests when available; do not assume :app or Debug in adopted projects. Report unavailable JDK/SDK/network separately from code failures.
5. Record stack, real commands, baseline and decisions in `.codex/android-kit/PROJECT.md`. Do not overwrite existing project notes. Use $android-map to create a compact map.
6. For a newly created project, initialize a Git repository if absent, and explain that the board requires an initial commit before fingerprinted verification; commit only with user authorization. Use $android-board for requested work only. Do not silently import old Sidequest tasks or delete old configuration. Finish with $android-kit-doctor. Do not automatically commit, publish, or create remotes.
