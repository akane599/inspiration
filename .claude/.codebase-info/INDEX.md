# Codebase Map — Inspiration
*Last Updated: 2026-10-18*

Shizuku-powered battery analytics and diagnostics for Android power users. Phase 2 provides the telemetry snapshot pipeline, history ingestion, Room persistence, and capture scheduling. Phase 3 adds interval metrics and the `:core:diagnostics` insight layer (rule engine R1–R4, dictionary, Battery Story) with debug `PROBE_INSIGHTS`; Phase 4 adds the leaf `:core:designsystem` module (theme tokens, components, synchronized scrubbing chart, sleep hypnogram) plus the debug-only `DesignGalleryActivity`. Phase 5 adds the app shell (bottom bar / rail, `RefreshCoordinator`), the manual-DI `AppContainer` that implements five per-feature dependency interfaces, the five MVI feature screens (Cockpit, X-Ray, Sleep Lab, Charge & Health, Toolkit), the typed privileged toolkit write path (`ToolkitActions`, AIDL txns 20–31) and the shared `AnalysisSession`.

| Doc | What |
|---|---|
| [architecture.md](architecture.md) | Runtime layers, capture flow, persistence, and privileged data flow |
| [modules.md](modules.md) | Gradle module graph, `:app` shell/di, `:core:diagnostics`, `:core:designsystem`, the five feature modules and the toolkit write path |
| [entry-points.md](entry-points.md) | Application, activity (`MainActivity` → `AppShell` → five `*Route` composables), receiver, job, service, and debug entry points |
| [communication.md](communication.md) | AIDL methods, transaction codes, parcelables, dump targets, history, and failure reasons |
| [patterns.md](patterns.md) | Availability, validity, merge, persistence, test-double, chart draw-phase/static-overlay, MVI/visibility, refresh and toolkit-write conventions |
| [tech-landscape.md](tech-landscape.md) | Toolchain, dependency catalog, protobuf, Room, Inter fonts, and test/screenshot infrastructure |
| [directory-structure.md](directory-structure.md) | Source tree, excluding generated output |
| [database.md](database.md) | Room schema (incl. signal columns, `snapshot_uid_sensor`, `toolkit_action`), DAOs (incl. `lastOfEachKindBefore`), WAL setup, retention, and snapshot-store mapping |
| [onboarding.md](onboarding.md) | Build, test, emulator, probes (incl. `PROBE_INSIGHTS`), design gallery launch, the `device-probe` skill, and Shizuku workflow |

## Map maintenance

Read this index first. Refresh the map after module, entry-point, persistence, or stack changes. The authoritative Phase 5 design is [`docs/plans/phase-5.md`](../../docs/plans/phase-5.md) (rev 2, decisions H1–H18 and the pinned shared surface; Phase 5 decisions are also in `PROGRESS.md`). The Phase 4 design is [`docs/plans/phase-4.md`](../../docs/plans/phase-4.md) (rev 2, decisions G1–G13; Phase 4 decisions are also in `PROGRESS.md`). The Phase 3 design is [`docs/plans/phase-3.md`](../../docs/plans/phase-3.md) (decisions F1–F9 and the pinned shared surface); the Phase 2 design is [`docs/plans/phase-2.md`](../../docs/plans/phase-2.md); durable decisions and device evidence are in [`PROGRESS.md`](../../PROGRESS.md). Phase 1 boundaries remain in [`docs/plans/phase-1.md`](../../docs/plans/phase-1.md).
