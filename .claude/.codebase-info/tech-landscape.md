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

## Runtime and build libraries

| Catalog entry | Version | Current use |
|---|---:|---|
| `dev.rikka.shizuku:api` / `provider` | 13.1.5 | Shizuku lifecycle, binder wrapper, and provider integration in `:app`/`:core:shizuku-ipc` |
| `org.lsposed.hiddenapibypass:hiddenapibypass` | 6.1 | Hidden API exemption in `InspirationApplication` and IPC module |
| `com.google.protobuf:protobuf-javalite` / `protobuf-kotlin-lite` / `protoc` | 4.36.2 | Lite runtime and protoc for trimmed vendored batterystats, alarm, and power schemas in `:core:telemetry-engine` |
| `com.google.devtools.ksp` | 2.3.12 | Room compiler integration in `:core:database` |
| `com.google.protobuf` Gradle plugin | 0.10.0 | Lite Java code generation for telemetry proto sources |
| `androidx.room` runtime / ktx / compiler / testing | 2.8.5 | WAL database, schema v1, DAOs, and repository tests in `:core:database` |
| `org.robolectric:robolectric` | 4.16.1 | JVM Room/DAO tests |
| `androidx.test:core` | 1.7.0 | Room test application context support |
| AndroidX Compose UI / Runtime / Material3 | BOM-managed | UI shell and feature convention dependencies |

`:core:telemetry-engine` exposes the IPC module and owns parser, snapshot, diff, capacity, capture, and live-telemetry code. `:core:database` depends on telemetry-engine and owns Room entities/DAOs/store. `:app` uses both in production and keeps the debug probe in the debug source set.

## Testing and fixtures

Unit tests use JUnit and `kotlinx-coroutines-test` where needed. Telemetry parser tests consume committed API 35/36/37 protobuf fixtures under [`core/telemetry-engine/src/test/resources/proto`](../../core/telemetry-engine/src/test/resources/proto). IPC tests live under `core/shizuku-ipc/src/test`; telemetry tests live under `core/telemetry-engine/src/test`; Room tests live under `core/database/src/test`. The app has Compose screenshot validation and committed references under `app/src/screenshotTestDebug/reference`.
