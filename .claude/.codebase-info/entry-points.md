# Entry Points
*Last Updated: 2026-10-17*

## Production entry points

- **`MainActivity`** — [`app/src/main/kotlin/com/akane/inspiration/MainActivity.kt`](../../app/src/main/kotlin/com/akane/inspiration/MainActivity.kt). The exported launcher activity enables edge-to-edge, installs `AppTheme`, and renders `HomeRoute`.
- **`InspirationApplication`** — [`app/src/main/kotlin/com/akane/inspiration/InspirationApplication.kt`](../../app/src/main/kotlin/com/akane/inspiration/InspirationApplication.kt). Registered as the application class in [`app/src/main/AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml). It attempts `HiddenApiBypass.addHiddenApiExemptions("L")` on API 28+ and creates `AppContainer`.
- **`AppContainer`** — [`app/src/main/kotlin/com/akane/inspiration/AppContainer.kt`](../../app/src/main/kotlin/com/akane/inspiration/AppContainer.kt). Creates the application-scoped `ShizukuManagerImpl` used by the app and debug probe.
- **`ShizukuProvider`** — `rikka.shizuku.ShizukuProvider`, declared directly in [`app/src/main/AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml) with `${applicationId}.shizuku` authority and the cross-user permission. It is a dependency entry point, not an app-owned Kotlin class.
- **`TelemetryUserService`** — [`core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/service/TelemetryUserService.kt`](../../core/shizuku-ipc/src/main/kotlin/com/akane/inspiration/ipc/service/TelemetryUserService.kt). Shizuku constructs it as a user service under shell; its `Context` constructor resolves the app UID and all AIDL methods enforce that caller UID before reading privileged data.

## Debug entry point

- **`ProbeReceiver`** — [`app/src/debug/kotlin/com/akane/inspiration/debug/ProbeReceiver.kt`](../../app/src/debug/kotlin/com/akane/inspiration/debug/ProbeReceiver.kt), registered by [`app/src/debug/AndroidManifest.xml`](../../app/src/debug/AndroidManifest.xml). It accepts `com.akane.inspiration.debug.PROBE_REQUEST_PERMISSION` and `com.akane.inspiration.debug.PROBE`, performs the bounded IPC/calibration probe, and emits structured logcat output. The receiver is debug-only and protected by `android.permission.DUMP`.

## Internal lifecycle handoff

`InspirationApplication.onCreate()` creates `AppContainer`; a consumer calls `ShizukuManager.start()`. `ShizukuManagerImpl` registers Shizuku listeners, derives `ShizukuState`, and binds `TelemetryUserService` when permission is granted. The manager publishes the service as a `StateFlow<ITelemetryUserService?>`; `ProbeReceiver` waits for a non-null value before reading telemetry.
