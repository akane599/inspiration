# Codebase Map — Inspiration
*Last Updated: 2026-10-19*

Shizuku-powered battery analytics and diagnostics for Android power users. Phase 2 provides the telemetry snapshot pipeline, history ingestion, Room persistence, and capture scheduling. Phase 3 adds interval metrics and the `:core:diagnostics` insight layer (rule engine R1–R4, dictionary, Battery Story) with debug `PROBE_INSIGHTS`; Phase 4 adds the leaf `:core:designsystem` module (theme tokens, components, synchronized scrubbing chart, sleep hypnogram) plus the debug-only `DesignGalleryActivity`. Phase 5 adds the app shell (bottom bar / rail, `RefreshCoordinator`), the manual-DI `AppContainer` that implements five per-feature dependency interfaces, the five MVI feature screens (Cockpit, X-Ray, Sleep Lab, Charge & Health, Toolkit), the typed privileged toolkit write path (`ToolkitActions`, AIDL txns 20–31) and the shared `AnalysisSession`. Earlier remediation added deadline-bound binder calls, monotonic-clock retention, failure containment (`attempt {}`), selection-tagged analysis and observed-change override chains. The current 2026-10-19 remediation ([`AUDIT_REPORT.md`](../../AUDIT_REPORT.md), A-01–A-24) adds capture-time UID ownership proof and Room schema v5, conservative truncated/absent-source handling, atomic charging-job scheduling, recoverable toolkit restore/admission state, measurement-scoped feature data and non-pointer interval controls. The independent 2026-10-19 audit remediation (F-01–F-25, release 0.2.3) adds:
- app-op `allow` writes (toolkit API 3) and user-0 toolkit containment;
- cross-snapshot UID identity by namespace plus owner fingerprint, proven with one bulk package read;
- a shared ±2 s battery-stats session predicate;
- the history horizon with a 2-minute grace;
- `AnalysisSession.recompute()`;
- spent-stub dead-binder recovery;
- chart handle slop and step-based tick labels.

The second independent audit's remediation (N-01–N-28, release 0.2.4) adds:
- RESET records that restate the live screen/doze state, and reference windows reconciled with capture anchors;
- a process-wide store tick (`StoreTicks`) after every history-ingesting capture, so the visible Cockpit and Health reload;
- toolkit API 4: bg-dexopt is refused below API 33, and a cancel is confirmed only by our client's exit;
- lift-only undo of Inspiration's own change on protected apps, and never-dispatched writes finalized at once;
- persisted live-current calibration, and a gauge charging state taken from status and current;
- history runs in insertion order, an ingest that skips past-retention rows, `PACKAGE_REPLACED` captures, and boots detected from the clocks;
- Sui support: a live binder outranks the manager app;
- a hidden-API exemption narrowed to `Sensor.getHandle()`, and release resource shrinking with en/tr locales;
- reserved AIDL txns 4/5 and dump targets 2/4, plus dead-code removal.

| Doc | What |
|---|---|
| [architecture.md](architecture.md) | Runtime layers, capture flow, persistence, and privileged data flow |
| [modules.md](modules.md) | Gradle module graph, `:app` shell/di, `:core:diagnostics`, `:core:designsystem`, the five feature modules and the toolkit write path |
| [entry-points.md](entry-points.md) | Application, activity (`MainActivity` → `AppShell` → five `*Route` composables), receiver, job, service, and debug entry points |
| [communication.md](communication.md) | AIDL methods, transaction codes, parcelables, dump targets, history, and failure reasons |
| [patterns.md](patterns.md) | Availability, validity, merge, persistence, binder-call deadlines, monotonic time, test-double, chart draw-phase/static-overlay, MVI/visibility, failure containment, refresh and toolkit-write conventions |
| [tech-landscape.md](tech-landscape.md) | Toolchain, dependency catalog, protobuf, Room, Inter fonts, and test/screenshot infrastructure |
| [directory-structure.md](directory-structure.md) | Source tree, excluding generated output |
| [database.md](database.md) | Room schema v5 and migrations (incl. nullable UID ownership proof, signal columns, `snapshot_uid_sensor`, `toolkit_action`, the trigger index), boot/elapsed trigger ordering, DAOs, WAL setup, retention, and snapshot-store mapping |
| [onboarding.md](onboarding.md) | Build, test, emulator, probes (incl. `PROBE_INSIGHTS`), design gallery launch, the `device-probe` skill, and Shizuku workflow |

## Map maintenance

Read this index first. Refresh the map after module, entry-point, persistence, or stack changes. The authoritative Phase 5 design is [`docs/plans/phase-5.md`](../../docs/plans/phase-5.md) (rev 2, decisions H1–H18 and the pinned shared surface; Phase 5 decisions are also in `PROGRESS.md`). The Phase 4 design is [`docs/plans/phase-4.md`](../../docs/plans/phase-4.md) (rev 2, decisions G1–G13; Phase 4 decisions are also in `PROGRESS.md`). The Phase 3 design is [`docs/plans/phase-3.md`](../../docs/plans/phase-3.md) (decisions F1–F9 and the pinned shared surface); the Phase 2 design is [`docs/plans/phase-2.md`](../../docs/plans/phase-2.md); durable decisions and device evidence are in [`PROGRESS.md`](../../PROGRESS.md). Phase 1 boundaries remain in [`docs/plans/phase-1.md`](../../docs/plans/phase-1.md).
