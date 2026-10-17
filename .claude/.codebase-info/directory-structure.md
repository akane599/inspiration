# Directory Structure
*Last Updated: 2026-10-17*

Generated `build/`, `.gradle/`, screenshot intermediates, and other build output are intentionally omitted.

```
app/
  src/main/AndroidManifest.xml
  src/main/kotlin/com/akane/inspiration/
    MainActivity.kt
    InspirationApplication.kt
    AppContainer.kt
    ui/home/{HomeScreen,HomeViewModel}.kt
    ui/theme/{Color,Shape,Spacing,Theme,Type}.kt
  src/debug/AndroidManifest.xml
  src/debug/kotlin/com/akane/inspiration/debug/ProbeReceiver.kt
  src/test/kotlin/com/akane/inspiration/ui/home/HomeViewModelTest.kt
  src/screenshotTest/kotlin/.../HomeScreenScreenshots.kt
  src/screenshotTestDebug/reference/.../*.png

build-logic/
  convention/build.gradle.kts
  convention/src/main/kotlin/com/akane/inspiration/convention/
    AndroidLibraryConventionPlugin.kt
    AndroidLibraryComposeConventionPlugin.kt
    AndroidFeatureConventionPlugin.kt

core/
  hidden-api-stub/src/main/java/android/os/{BatteryConsumer,BatteryUsageStats,
    BatteryUsageStatsQuery,IDeviceIdleController,IPowerManager,IPowerStatsService,
    ServiceManager,UidBatteryConsumer}.java
  shizuku-ipc/
    src/main/aidl/com/akane/inspiration/ipc/ITelemetryUserService.aidl
    src/main/aidl/com/akane/inspiration/ipc/model/*.aidl
    src/main/kotlin/com/akane/inspiration/ipc/
      ShizukuFacade.kt, ShizukuManager.kt, ShizukuManagerImpl.kt, ShizukuState.kt
      client/{BatteryStatsClient,BatteryUsageAdapter,BinderResult,DeviceIdleReader,PowerStatsClient}.kt
      model/{BatteryUsageSnapshot,CpuTimeSnapshot,DeviceIdleSnapshot,DumpResult,DumpTarget,
        KernelBatterySnapshot,PowerMonitorSnapshot,TelemetryValues}.kt
      service/TelemetryUserService.kt
    src/test/kotlin/com/akane/inspiration/ipc/**/*.kt
  telemetry-engine/
    src/main/kotlin/com/akane/inspiration/telemetry/calibration/PowerCalibrator.kt
    src/test/kotlin/com/akane/inspiration/telemetry/calibration/PowerCalibratorTest.kt
  diagnostics/                    # module configuration only
  database/                       # module configuration only

feature/
  dashboard/                       # module configuration only
  xray/                            # module configuration only
  sleeplab/                        # module configuration only
  health/                          # module configuration only
  toolkit/                         # module configuration only

settings.gradle.kts                # includes all core and feature modules
gradle/libs.versions.toml          # toolchain and dependency catalog
docs/plans/phase-1.md              # audited Phase 1 design
PROGRESS.md                        # durable decisions and device evidence
```

The package path `com.akane.inspiration` is used by the app, while library namespaces are declared in their module build files. The feature modules are already wired into `:app`, but their source directories are intentionally empty until their respective screen tickets land.
