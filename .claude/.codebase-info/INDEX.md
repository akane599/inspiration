# Codebase Map — Inspiration
*Last Updated: 2026-10-17*

Shizuku-powered battery analytics and diagnostics for Android power users. Phase 1 now has the real module graph, privileged telemetry path, and a debug probe; feature screens and most Phase 2 domain work remain skeletons.

| Doc | What |
|---|---|
| [architecture.md](architecture.md) | Runtime layers, responsibilities, and privileged data flow |
| [modules.md](modules.md) | Gradle module graph and implementation status |
| [entry-points.md](entry-points.md) | Application, activity, provider, service, and debug entry points |
| [communication.md](communication.md) | AIDL methods, transaction codes, parcelables, dump targets, and failure reasons |
| [patterns.md](patterns.md) | Result/availability, version-gating, and test-double conventions |
| [tech-landscape.md](tech-landscape.md) | Toolchain and dependency catalog |
| [directory-structure.md](directory-structure.md) | Source tree, excluding generated output |
| [onboarding.md](onboarding.md) | Build, test, emulator, and Shizuku probe workflow |

## Map maintenance

Read this index first. Refresh the map after module, entry-point, or stack changes. The authoritative Phase 1 design is [`docs/plans/phase-1.md`](../../docs/plans/phase-1.md); durable decisions and emulator evidence are in [`PROGRESS.md`](../../PROGRESS.md).
