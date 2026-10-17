# Entry Points
*Last Updated: 2026-10-17*

## Production entry points

- **`MainActivity`** — [`app/src/main/kotlin/com/akane/inspiration/MainActivity.kt`](../../app/src/main/kotlin/com/akane/inspiration/MainActivity.kt). The exported launcher activity enables edge-to-edge, renders `HomeRoute`, and calls `InspirationApplication.captureAppOpen()` from `onStart`.
- **`InspirationApplication`** — [`app/src/main/kotlin/com/akane/inspiration/InspirationApplication.kt`](../../app/src/main/kotlin/com/akane/inspiration/InspirationApplication.kt). Registered in [`app/src/main/AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml); creates `AppContainer`, starts the Shizuku state watcher, registers the non-exported runtime receiver, and owns the debounced APP_OPEN capture.
- **`AppContainer`** — [`app/src/main/kotlin/com/akane/inspiration/AppContainer.kt`](../../app/src/main/kotlin/com/akane/inspiration/AppContainer.kt). Creates `ShizukuManagerImpl`, `InspirationDatabase`, `RoomSnapshotStore`, `AndroidTelemetrySource`, and `SnapshotCapturer` in the application scope. Also exposes two lazy diagnostics members: `dictionary` (`DiagnosticDictionary`, parsed from `R.raw.diagnostic_dictionary` on first access, so call it from IO, not the main thread) and `ruleEngine` (`DiagnosticRuleEngine(AndroidSensorCatalog(appContext))`). No production code calls them yet; only the debug probe does.
- **`RuntimeCaptureReceiver`** — [`app/src/main/kotlin/com/akane/inspiration/capture/RuntimeCaptureReceiver.kt`](../../app/src/main/kotlin/com/akane/inspiration/capture/RuntimeCaptureReceiver.kt). Dynamically registered with `RECEIVER_NOT_EXPORTED`; handles screen, power, and battery broadcasts according to `CapturePolicy`.
- **`BootCaptureReceiver`** — [`app/src/main/kotlin/com/akane/inspiration/capture/BootCaptureReceiver.kt`](../../app/src/main/kotlin/com/akane/inspiration/capture/BootCaptureReceiver.kt), manifest-declared for `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`. It writes a light BOOT snapshot and arms the persisted charging job.
- **`ChargingCaptureJobService`** — [`app/src/main/kotlin/com/akane/inspiration/capture/ChargingCaptureJobService.kt`](../../app/src/main/kotlin/com/akane/inspiration/capture/ChargingCaptureJobService.kt), manifest-declared with `BIND_JOB_SERVICE`. It runs bounded full capture/history/prune work, handles cancellation, and re-arms through [`ChargingCaptureScheduler.kt`](../../app/src/main/kotlin/com/akane/inspiration/capture/ChargingCaptureScheduler.kt).
- **`TelemetryUserService`** — [`core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/service/TelemetryUserService.kt`](../../core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/service/TelemetryUserService.kt). Shizuku constructs it as a shell-UID user service; every AIDL method enforces the caller UID before reading privileged data.

## Debug entry point

- **`ProbeReceiver`** — [`app/src/debug/kotlin/com/akane/inspiration/debug/ProbeReceiver.kt`](../../app/src/debug/kotlin/com/akane/inspiration/debug/ProbeReceiver.kt), registered by [`app/src/debug/AndroidManifest.xml`](../../app/src/debug/AndroidManifest.xml). It accepts `PROBE_REQUEST_PERMISSION`, `PROBE`, `PROBE_CAPTURE`, `PROBE_DIFF`, and `PROBE_INSIGHTS` (the last two take the string extra `reference_kind`), performs bounded IPC/calibration/capture/diff/insight/database checks with a 10 s deadline, and emits structured `InspirationProbe` logcat output. `PROBE_INSIGHTS` logs interval metrics, insights, notEvaluable reasons, the rendered en Battery Story and top-wakelock dictionary lookups. The receiver is debug-only and protected by `android.permission.DUMP`.

## Internal capture handoff

`InspirationApplication.onCreate()` creates the container and registers `RuntimeCaptureReceiver`. `CapturePolicy` turns each broadcast into a light or warm-service full plan. `SnapshotCapturer` calls `AndroidTelemetrySource`, merges source availability, and writes through `RoomSnapshotStore`; a missed full capture is recorded in `capture_miss`. The charging service also uses the history cursor and last plug/job rows to make its persisted run idempotent per plug session.

The Shizuku manager publishes its service as a `StateFlow<ITelemetryUserService?>`; capture uses `withTelemetryService()` for bounded on-demand binding, while a UI may use `holdBinding()` to keep it warm.
