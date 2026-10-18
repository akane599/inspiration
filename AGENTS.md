# Android Codex project

For normal implementation, read `.codex/android-kit/PROJECT.md` and `MAP.md` when present; refresh stale facts. For blind audits, follow the audit skill isolation protocol first: defer PROJECT/MAP/PROGRESS, boards and prior-fix summaries until the independent report is frozen. Required instructions still apply; disclose any prior conclusions already loaded.
For Android code/build work, read `.codex/android-kit/rules/android-code.md` and `android-build.md`.
Use project skills under `.agents/skills/`; start normal implementation with `$android-bootstrap`. Do not bootstrap, refresh maps or run kit maintenance during a blind audit.
Use `$android-board` for requested multi-step work. Prefer one focused pass and targeted specialists; batch finding validation.
Use the current Codex model and permissions. Do not add Model Gateway, provider aliases or nested agent CLIs.
Do not modify the parallel Claude setup or Sidequest state. The Claude hooks in `.claude/` do not run under Codex.
Record actual build/test/device evidence; never turn an unavailable check into a pass.

Use $android-bug-hunt for targeted investigations during implementation. Use $full-repo-audit and $audit-remediate for standalone audits and fixes; follow their native coordination protocol without a board dependency.
Default to 2 concurrent Gradle commands; waiting for verification must not block independent native-worker dispatch. Board mutations themselves remain serial.
