# Codebase Map — Inspiration
*Last Updated: 2026-10-17* · **Greenfield seed**: only the android-kit scaffold exists; most docs describe intended design from `TEMPLATE.md`.

Shizuku-powered battery analytics/diagnostics app (Android, Kotlin, Compose). Spec (Turkish): `TEMPLATE.md`. Today: single `:app` module with a placeholder Home screen. Planned: `:core:*` + `:feature:*` clean-arch/MVI modules, built in 5 phases.

| Doc | What |
|---|---|
| [architecture.md](architecture.md) | Goals, zero-observer-effect rule, intended layers and data flow |
| [tech-landscape.md](tech-landscape.md) | Actual versions now + planned libraries |
| [directory-structure.md](directory-structure.md) | Current tree and planned module layout |
| [onboarding.md](onboarding.md) | Build/test/screenshot commands, where to start |

## How to use / maintain this map
- Read INDEX first; open a detailed doc only when the task touches it.
- After code changes, run `update-codebase-map` when structure, modules, entry points or stack changed. Mark "planned" items as real once they land; drop this seed banner once Phase 1 modules exist.
