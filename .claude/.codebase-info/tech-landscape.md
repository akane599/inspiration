# Tech Landscape
*Last Updated: 2026-10-17*

## Current (source of truth: `gradle/libs.versions.toml`, `app/build.gradle.kts`)
| Item | Version |
|---|---|
| Gradle / AGP / Kotlin (built-in Kotlin, no kotlin-android plugin applied) | 9.8.0 / 9.4.1 / 2.4.20 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 31 (compileSdk 37 forced by AndroidX libs; see PROGRESS.md) |
| JDK target | 17 |
| Compose BOM / Material3 | 2026.09.00 |
| lifecycle / activity-compose / core-ktx | 2.11.0 / 1.13.0 / 1.19.1 |
| kotlinx-collections-immutable / coroutines-test | 0.5.2 / 1.11.0 |
| Tests | JUnit 4.13.2; Compose Preview Screenshot Testing 0.0.1-alpha16 |

## Planned (TEMPLATE.md §1; not yet added — each needs a ticket)
Shizuku `dev.rikka.shizuku:api` + `provider`; `org.lsposed.hiddenapibypass:hiddenapibypass`; Room (+KSP); Protobuf (lite) for `BatteryStatsProto`; AIDL for `ITelemetryUserService`.
