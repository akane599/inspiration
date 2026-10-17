# Codebase Map — Inspiration
*Last Updated: 2026-10-17*

Shizuku-powered battery analytics and diagnostics for Android power users. Phase 2 now has the telemetry snapshot pipeline, history ingestion, Room persistence, capture scheduling, and debug capture/diff probes; feature screens remain reserved boundaries.

| Doc | What |
|---|---|
| [architecture.md](architecture.md) | Runtime layers, capture flow, persistence, and privileged data flow |
| [modules.md](modules.md) | Gradle module graph and implementation status |
| [entry-points.md](entry-points.md) | Application, activity, receiver, job, service, and debug entry points |
| [communication.md](communication.md) | AIDL methods, transaction codes, parcelables, dump targets, history, and failure reasons |
| [patterns.md](patterns.md) | Availability, validity, merge, persistence, and test-double conventions |
| [tech-landscape.md](tech-landscape.md) | Toolchain, dependency catalog, protobuf, Room, and test infrastructure |
| [directory-structure.md](directory-structure.md) | Source tree, excluding generated output |
| [database.md](database.md) | Room schema, DAOs, WAL setup, retention, and snapshot-store mapping |
| [onboarding.md](onboarding.md) | Build, test, emulator, capture probe, and Shizuku workflow |

## Map maintenance

Read this index first. Refresh the map after module, entry-point, persistence, or stack changes. The authoritative Phase 2 design is [`docs/plans/phase-2.md`](../../docs/plans/phase-2.md); durable decisions and device evidence are in [`PROGRESS.md`](../../PROGRESS.md). Phase 1 boundaries remain in [`docs/plans/phase-1.md`](../../docs/plans/phase-1.md).
