---
name: android-kit-merge
description: "Merge preserved custom Android kit files with incoming updates and record reviewed resolutions."
---

Run from the project root. Read AGENTS.md and applicable nested instructions. Helpers live in `.codex/android-kit/`. Preserve existing project conventions. Use normal Codex permissions. Do not change provider/authentication settings or launch nested agent CLIs. Never claim a check ran unless you observed its result.

Run `python3 .codex/android-kit/manage.py conflicts`. For each reported path compare active and `.codex/android-kit/incoming/<path>`; preserve project customization and incorporate the new behavior. Do not bulk-copy or manually clear conflict metadata. Validate Python with compile, shell with bash -n, and skills by checking frontmatter and referenced paths. After reviewing an individual file run `python3 .codex/android-kit/manage.py resolve '<path>'`. This acknowledges your semantic review; it does not perform the merge. Unresolved files stay flagged. Finish with $android-kit-refresh.
