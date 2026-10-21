# Directory Structure
*Last Updated: 2026-10-21*

Generated `build/`, `.gradle/`, screenshot intermediates, Room generated sources, and other build output are intentionally omitted.

```
app/
  src/main/AndroidManifest.xml
  src/main/kotlin/com/akane/inspiration/
    MainActivity.kt
    InspirationApplication.kt
    AppContainer.kt
    capture/{BootCaptureReceiver,BroadcastCaptureRunner,CapturePolicy,ChargeJobRun,
      ChargingCaptureJobService,ChargingCaptureRunner,ChargingCaptureScheduler,
      HistoryPageCounter,MarkerCapture,RuntimeCaptureReceiver}.kt
    di/{AppEntries,ApplicationScope,CachedAppLabels,PackageChanges,Preloaded,
      ReconcileOnReady,RefreshCoordinator,StoreTicks,TimelineEvents}.kt
    shell/{AppShell,ShellIcons,ShellState}.kt
    status/{StatusNotificationContent,StatusNotificationController,StatusNotifications,
      StatusNotificationService,StatusNotificationSettings,StatusStartDecision,StatusTicks}.kt
  src/main/res/xml/data_extraction_rules.xml
  src/debug/AndroidManifest.xml
  src/debug/kotlin/com/akane/inspiration/debug/{ProbeReceiver,DesignGalleryActivity}.kt
  src/debug/kotlin/com/akane/inspiration/debug/gallery/{GalleryData,RecomposeCounter}.kt
  src/testDebug/kotlin/com/akane/inspiration/debug/gallery/GalleryDataTest.kt
  src/test/kotlin/com/akane/inspiration/{capture,di,shell}/*Test.kt
  src/screenshotTest/kotlin/com/akane/inspiration/shell/{PreviewDependencies,ShellScreenshots}.kt
  src/screenshotTestDebug/reference/.../*.png

build-logic/
  gradle.properties   (Kotlin daemon -Xmx2g; included builds don't read the root file)
  convention/build.gradle.kts
  convention/src/main/kotlin/com/akane/inspiration/convention/
    AndroidLibraryConventionPlugin.kt
    AndroidLibraryComposeConventionPlugin.kt
    AndroidFeatureConventionPlugin.kt

core/
  hidden-api-stub/src/main/java/android/os/{AggregateBatteryConsumer,BatteryConsumer,BatteryStats,
    BatteryUsageStats,BatteryUsageStatsQuery,IDeviceIdleController,IPowerManager,IPowerStatsService,
    ServiceManager,UidBatteryConsumer}.java
  shizuku-ipc/
    src/main/aidl/com/akane/inspiration/ipc/ITelemetryUserService.aidl
    src/main/aidl/com/akane/inspiration/ipc/model/{BatteryHistorySnapshot,BatteryUsageSnapshot,
      BgDexoptStatus,CpuTimeSnapshot,DumpResult,KernelBatterySnapshot,ToolkitResult}.aidl
    src/main/kotlin/com/akane/inspiration/ipc/
      ShizukuFacade.kt, ShizukuManager.kt, ShizukuManagerImpl.kt,
      ShizukuState.kt                 # + BlockedReason / blockedReason(), shared by X-Ray and Toolkit
      client/{BatteryHistory,BatteryStatsClient,BatteryUsageAdapter,BinderCalls,
        UsageParcelBudget}.kt
      model/{BatteryHistorySnapshot,BatteryUsageSnapshot,CpuTimeSnapshot,DumpResult,
        DumpTarget,KernelBatterySnapshot,TelemetryValues,ToolkitResult,BgDexoptStatus}.kt
        # HistoryEvent is nested in BatteryHistorySnapshot.kt and travels inside its parcel
      service/TelemetryUserService.kt
      toolkit/{BgDexoptController,CommandRunner,Inspection,Toolkit,ToolkitExecutor,
        ToolkitParsers}.kt            # typed privileged write executor (txns 20-30)
    src/debug/kotlin/com/akane/inspiration/ipc/   # debug-probe in-process reads only; no .aidl
      client/{BinderResult,DeviceIdleReader,PowerStatsClient}.kt
      model/{DeviceIdleSnapshot,PowerMonitorSnapshot}.kt
    src/test/kotlin/com/akane/inspiration/ipc/**/*.kt
    src/testDebug/kotlin/com/akane/inspiration/ipc/client/{DeviceIdleReader,PowerStatsClient}Test.kt
  telemetry-engine/
    build.gradle.kts
    src/main/proto/{android/os/batterystats.proto,
      android/service/batterystats/batterystats.proto,
      com/android/server/alarm/alarmmanagerservice.proto}
    src/main/kotlin/com/akane/inspiration/telemetry/
      calibration/{ChargeCounterCheck,PowerCalibrator,SharedPreferencesCalibrationStore}.kt
      capacity/CapacityEstimator.kt
      capture/SnapshotCapturer.kt
      common/Clocks.kt
      diff/{BatteryStatsSession,ReferenceResolver,SnapshotDiffer}.kt
      dump/{DumpModels,DumpParser,DumpReader}.kt
      interval/{IntervalHistory,IntervalMetrics}.kt
      app/AppFacingModels.kt       # RefreshResult, KernelCapacitySnapshot, AppEntry, AppLabels, uidIdentity/UidIdentity, identifyingPackage
      app/Attempt.kt               # shared attempt {} for feature ViewModels
      live/{KernelUnits,LiveTelemetry}.kt
      snapshot/{SnapshotModels,UidIdentity}.kt
      source/{AndroidClocks,AndroidTelemetrySource,TelemetrySource,UidIdentityReader}.kt
      status/{ChargeState,LightCurrent,UnplugSummary}.kt
      store/{HistoryHorizon,RetentionPrune,SnapshotStore,StoreModels}.kt
      toolkit/{ToolkitActions,ToolkitAdmission,ToolkitGateway,ToolkitModels}.kt
    src/test/kotlin/com/akane/inspiration/telemetry/**
    src/test/resources/proto/{alarm-api35,alarm-api36,alarm-api37,
      batterystats-api35,batterystats-api36,batterystats-api37}.pb
  database/
    build.gradle.kts
    schemas/com.akane.inspiration.database.InspirationDatabase/{1,2,3,4,5,6}.json
    src/main/kotlin/com/akane/inspiration/database/
      Daos.kt,Entities.kt,InspirationDatabase.kt   # version 6, MIGRATION_1_2..5_6
      store/RoomSnapshotStore.kt
    src/test/kotlin/com/akane/inspiration/database/
      InspirationDatabaseTest.kt                   # incl. migrations + EXPLAIN QUERY PLAN
      store/{HistoryCursorTest,IntervalHistoryEquivalenceTest,RoomSnapshotStoreTest,
        ToolkitActionRetentionTest,ToolkitRetentionParityTest}.kt
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
      story/{BatteryStory,BatteryStoryBuilder,AndroidStoryWriter,SleepWindow}.kt
    src/test/kotlin/com/akane/inspiration/diagnostics/**   # per-rule, dictionary, story, sensor tests + fixtures

  designsystem/
    build.gradle.kts, FONTS.md, licenses/OFL-Inter.txt
    src/main/kotlin/com/akane/inspiration/designsystem/
      theme/{Color,Shape,Spacing,TelemetryColors,Theme,Type}.kt
      component/{CardTitle,EmptyState,InspirationCard,LegendRow,LiveGauge,MetricBar,MetricTile,
        MetricValue,ReducedMotion,ReferencePill,ScreenHeader,SegmentedToggle,SemanticDot,
        ShizukuPulse,StackedBar,TimeSliceBanner}.kt
      component/{ObscuredTouchGuard,VisibilityEffect}.kt
      component/format/{CurrentLocale,Monogram,SystemZone,TelemetryFormat}.kt
      chart/model/{ChartLabels,ChartModels}.kt
      chart/math/{ClockLabels,Scales,ScrubGestureReducer,SeriesMath,TimeRanges,TimeTicks}.kt
      chart/draw/{SelectionDim,TimeAxis}.kt
      chart/scrub/{ScrubChartMath,ScrubChartRenderer,ScrubChartState,ScrubGestures,
        ScrubReadout,SliceControls,SynchronizedScrubbingChart}.kt
      chart/hypnogram/{HypnogramMath,SleepHypnogram}.kt
    src/main/res/font/inter_{regular,medium,semibold}.ttf
    src/main/res/{values,values-tr}/strings_chart.xml
    src/test/kotlin/.../designsystem/**       # math, model, contrast tests; component/ObscuredTouch{Gate,GuardUi}Test, VisibilityEffectTest (Robolectric)
    src/screenshotTest/kotlin/.../designsystem/{theme,component,chart/scrub,chart/hypnogram}/   # component/ incl. Phase5Component*
    src/screenshotTestDebug/reference/.../    # per-area PNG references

feature/                           # each: src/main/kotlin/com/akane/inspiration/<name>/, src/main/res/{values,values-tr},
                                   # src/test (VM + mapper tests with fakes), src/screenshotTest (previews + PreviewData)
  dashboard/                       # Cockpit{Route,ViewModel,State,Screen,Dependencies,Mappers,Format,Header,Cards,
                                   #   ChartCard,MarkerSheet}.kt
  xray/                            # XRay{Route,ViewModel,Models,Screen,Dependencies,Mapper,Detail,Text}.kt
  sleeplab/                        # SleepLab{Route,ViewModel,State,Screen,Dependencies,Mappers,Format,Cpu,Diagnostics,Culprits}.kt
  health/                          # Health{Route,ViewModel,State,Screen,Dependencies,Mappers,Format,Cards,Charts}.kt
  toolkit/                         # Toolkit{Route,ViewModel,Models,Screen,Dependencies,Mapper,Text,Apps,Dialogs,
                                   #   Sections}.kt

settings.gradle.kts                # includes all core and feature modules
gradle/libs.versions.toml          # toolchain and dependency catalog
docs/plans/phase-1.md              # audited Phase 1 design
docs/plans/phase-2.md              # audited Phase 2 design and contracts
docs/plans/phase-3.md              # audited Phase 3 design (F1–F9)
docs/plans/phase-4.md             # audited Phase 4 design (G1–G13)
docs/plans/phase-5.md             # audited Phase 5 design (authoritative; H1–H18, pinned shared surface)
.claude/skills/device-probe/       # SKILL.md + scripts/device-probe.sh (redroid probe runner)
.claude/skills/{full-repo-audit,audit-remediate}/   # standalone review-mode audit + remediation skills
.claude/skills/release/            # SKILL.md + scripts/{upgrade-check,gate-summary}.sh (delivery + same-key upgrade check)
.claude/scripts/gradle-slot.sh     # host-wide Gradle slots shared by sessions/worktrees, then gradle-check.sh
AGENTS.md, .agents/skills/, .codex/android-kit/     # Codex-side mirror of the hybrid android kit (instructions, skills, rules)
PROGRESS.md                        # durable decisions and device evidence
NOTICE                              # AOSP protobuf attribution
```

The package path `com.akane.inspiration` is used by the app; library namespaces are declared in their module build files. The feature modules are wired into `:app` and implement the five Phase 5 screens.
