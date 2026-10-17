# Tech Landscape
*Last Updated: 2026-10-17*

Source of truth: [`gradle/libs.versions.toml`](../../gradle/libs.versions.toml), module build files, and the convention plugins under [`build-logic/convention`](../../build-logic/convention).

## Toolchain

| Item | Version / value |
|---|---|
| Gradle / Android Gradle Plugin / Kotlin | 9.8.0 / 9.4.1 / 2.4.20 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 31 |
| Java source and target | 17 |
| Compose BOM | 2026.09.00 |
| Activity Compose / Lifecycle | 1.13.0 / 2.11.0 |
| AndroidX core-ktx | 1.19.1 |
| kotlinx-collections-immutable | 0.5.2 |
| kotlinx-coroutines | 1.11.0 |
| JUnit | 4.13.2 |
| Compose screenshot validation | 0.0.1-alpha16 |

The compile SDK and AndroidX choices are explained in the Phase 1 decision log in [`PROGRESS.md`](../../PROGRESS.md).

## Runtime and build libraries

| Catalog entry | Version | Current use |
|---|---:|---|
| `dev.rikka.shizuku:api` / `provider` | 13.1.5 | Shizuku lifecycle, binder wrapper, and provider integration in `:app`/`:core:shizuku-ipc` |
| `org.lsposed.hiddenapibypass:hiddenapibypass` | 6.1 | Hidden API exemption in `InspirationApplication` and IPC module |
| `com.google.protobuf:protobuf-javalite` / `protobuf-kotlin-lite` | 4.36.2 | Cataloged for telemetry protobuf work; no generated proto source is present yet |
| `com.google.devtools.ksp` | 2.3.12 | Cataloged; no KSP processor is currently applied |
| `com.google.protobuf` Gradle plugin | 0.10.0 | Cataloged; no protobuf source set is currently present |
| AndroidX Compose UI / Runtime / Material3 | BOM-managed | UI shell and feature convention dependencies |

`core:shizuku-ipc` also uses compile-only Android annotations and the hidden API stub module. `core:telemetry-engine` exposes the IPC module and currently has no new runtime dependency. Room, KSP-backed database code, and network libraries are not present; `:core:database` is reserved for a later phase.

## Testing

Unit tests use JUnit and `kotlinx-coroutines-test` where needed. The app has Compose screenshot validation and committed references under `app/src/screenshotTestDebug/reference`. IPC tests live under `core/shizuku-ipc/src/test`; calibration tests live under `core/telemetry-engine/src/test`.
