# Directory Structure
*Last Updated: 2026-10-17*

Generated `build/`, `.gradle/`, screenshot intermediates, Room generated sources, and other build output are intentionally omitted.

```
app/
  src/main/AndroidManifest.xml
  src/main/kotlin/com/akane/inspiration/
    MainActivity.kt
    InspirationApplication.kt
    AppContainer.kt
    capture/{BootCaptureReceiver,CapturePolicy,ChargingCaptureJobService,
      ChargingCaptureRunner,ChargingCaptureScheduler,RuntimeCaptureReceiver}.kt
    di/{AppEntries,CachedAppLabels,Preloaded,ReconcileOnReady,RefreshCoordinator,
      TimelineEvents}.kt
    shell/{AppShell,ShellIcons,ShellState}.kt
  src/main/res/xml/{backup_rules,data_extraction_rules}.xml
  src/debug/AndroidManifest.xml
  src/debug/kotlin/com/akane/inspiration/debug/{ProbeReceiver,DesignGalleryActivity}.kt
  src/debug/kotlin/com/akane/inspiration/debug/gallery/{GalleryData,RecomposeCounter}.kt
  src/testDebug/kotlin/com/akane/inspiration/debug/gallery/GalleryDataTest.kt
  src/test/kotlin/com/akane/inspiration/{capture,di,shell}/*Test.kt
  src/screenshotTest/kotlin/com/akane/inspiration/shell/{PreviewDependencies,ShellScreenshots}.kt
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
    src/main/aidl/com/akane/inspiration/ipc/model/{BatteryHistorySnapshot,BgDexoptStatus,
      HistoryEvent,ToolkitResult,...}.aidl
    src/main/kotlin/com/akane/inspiration/ipc/
      ShizukuFacade.kt, ShizukuManager.kt, ShizukuManagerImpl.kt, ShizukuState.kt
      client/{BatteryHistory,BatteryStatsClient,BatteryUsageAdapter,BinderResult,
        DeviceIdleReader,PowerStatsClient,UsageParcelBudget}.kt
      model/{BatteryHistorySnapshot,BatteryUsageSnapshot,CpuTimeSnapshot,
        DeviceIdleSnapshot,DumpResult,DumpTarget,HistoryEvent,KernelBatterySnapshot,
        PowerMonitorSnapshot,TelemetryValues,ToolkitResult,BgDexoptStatus}.kt
      service/TelemetryUserService.kt
      toolkit/{BgDexoptController,CommandRunner,Inspection,Toolkit,ToolkitExecutor,
        ToolkitParsers}.kt            # typed privileged write executor (txns 20-30)
    src/test/kotlin/com/akane/inspiration/ipc/**/*.kt
  telemetry-engine/
    build.gradle.kts
    src/main/proto/{android/os/batterystats.proto,
      android/service/batterystats/batterystats.proto,
      com/android/server/alarm/alarmmanagerservice.proto,
      com/android/server/power/powermanagerservice.proto}
    src/main/kotlin/com/akane/inspiration/telemetry/
      calibration/PowerCalibrator.kt
      capacity/CapacityEstimator.kt
      capture/SnapshotCapturer.kt
      common/Clocks.kt
      diff/{ReferenceResolver,SnapshotDiffer}.kt
      dump/{DumpModels,DumpParser,DumpReader}.kt
      interval/IntervalMetrics.kt
      app/AppFacingModels.kt       # RefreshResult, KernelCapacitySnapshot, AppEntry, AppLabels
      live/{KernelUnits,LiveTelemetry}.kt
      snapshot/SnapshotModels.kt
      source/{AndroidCalibrationInputReader,AndroidClocks,AndroidTelemetrySource,
        TelemetrySource}.kt
      store/{SnapshotStore,StoreModels}.kt
      toolkit/{ToolkitActions,ToolkitGateway,ToolkitModels}.kt
    src/test/kotlin/com/akane/inspiration/telemetry/**
    src/test/resources/proto/{alarm-api35,alarm-api36,alarm-api37,
      batterystats-api35,batterystats-api36,batterystats-api37,
      power-api35,power-api36,power-api37}.pb
  database/
    build.gradle.kts
    schemas/com.akane.inspiration.database.InspirationDatabase/1.json
    src/main/kotlin/com/akane/inspiration/database/
      Daos.kt,Entities.kt,InspirationDatabase.kt
      store/RoomSnapshotStore.kt
    src/test/kotlin/com/akane/inspiration/database/
      InspirationDatabaseTest.kt
      store/RoomSnapshotStoreTest.kt
  diagnostics/
    build.gradle.kts
    src/main/res/raw/diagnostic_dictionary.json
    src/main/res/{values,values-tr}/strings.xml
    src/main/kotlin/com/akane/inspiration/diagnostics/
      analysis/{AnalysisSelection,AnalysisSession,IntervalAnalyzer,Timeline,TimelineBuilder}.kt
      dictionary/{DiagnosticDictionary,DictionaryEntry,DictionaryLoader,EntryKind}.kt
      model/{Insight,InsightOrder}.kt
      rules/{DiagnosticInput,DiagnosticRuleEngine}.kt
      sensor/{SensorCatalog,AndroidSensorCatalog}.kt
      story/{BatteryStory,BatteryStoryBuilder,AndroidStoryWriter}.kt
    src/test/kotlin/com/akane/inspiration/diagnostics/**   # per-rule, dictionary, story, sensor tests + fixtures

  designsystem/
    build.gradle.kts, FONTS.md, licenses/OFL-Inter.txt
    src/main/kotlin/com/akane/inspiration/designsystem/
      theme/{Color,Shape,Spacing,TelemetryColors,Theme,Type}.kt
      component/{CardTitle,EmptyState,InspirationCard,LegendRow,LiveGauge,MetricBar,MetricTile,
        MetricValue,ReducedMotion,ReferencePill,ScreenHeader,SegmentedToggle,SemanticDot,
        ShizukuPulse,StackedBar,TimeSliceBanner}.kt
      component/format/TelemetryFormat.kt
      chart/model/{ChartLabels,ChartModels}.kt
      chart/math/{Scales,ScrubGestureReducer,SeriesMath,TimeTicks}.kt
      chart/draw/{SelectionDim,TimeAxis}.kt
      chart/scrub/{ScrubChartMath,ScrubChartRenderer,ScrubChartState,ScrubGestures,
        ScrubReadout,SynchronizedScrubbingChart}.kt
      chart/hypnogram/{HypnogramMath,SleepHypnogram}.kt
    src/main/res/font/inter_{regular,medium,semibold}.ttf
    src/main/res/{values,values-tr}/strings_chart.xml
    src/test/kotlin/.../designsystem/**       # math, model, contrast tests
    src/screenshotTest/kotlin/.../designsystem/{theme,component,chart/scrub,chart/hypnogram}/   # component/ incl. Phase5Component*
    src/screenshotTestDebug/reference/.../    # per-area PNG references

feature/                           # each: src/main/kotlin/com/akane/inspiration/<name>/, src/main/res/{values,values-tr},
                                   # src/test (VM + mapper tests with fakes), src/screenshotTest (previews + PreviewData)
  dashboard/                       # Cockpit{Route,ViewModel,State,Screen,Dependencies,Mappers,Format,Header,Cards,
                                   #   ChartCard,MarkerSheet}.kt
  xray/                            # XRay{Route,ViewModel,Models,Screen,Dependencies,Mapper,Detail,Text,TouchGuard}.kt
  sleeplab/                        # SleepLab{Route,ViewModel,State,Screen,Dependencies,Mappers,Format,Cpu,Diagnostics}.kt
  health/                          # Health{Route,ViewModel,State,Screen,Dependencies,Mappers,Format,Cards,Charts}.kt
  toolkit/                         # Toolkit{Route,ViewModel,Models,Screen,Dependencies,Mapper,Text,Apps,Dialogs,
                                   #   Sections,TouchGuard}.kt

settings.gradle.kts                # includes all core and feature modules
gradle/libs.versions.toml          # toolchain and dependency catalog
docs/plans/phase-1.md              # audited Phase 1 design
docs/plans/phase-2.md              # audited Phase 2 design and contracts
docs/plans/phase-3.md              # audited Phase 3 design (F1–F9)
docs/plans/phase-4.md             # audited Phase 4 design (G1–G13)
docs/plans/phase-5.md             # audited Phase 5 design (authoritative; H1–H18, pinned shared surface)
.claude/skills/device-probe/       # SKILL.md + scripts/device-probe.sh (emulator probe runner)
PROGRESS.md                        # durable decisions and device evidence
NOTICE                              # AOSP protobuf attribution
```

The package path `com.akane.inspiration` is used by the app; library namespaces are declared in their module build files. The feature modules are wired into `:app` and implement the five Phase 5 screens.
