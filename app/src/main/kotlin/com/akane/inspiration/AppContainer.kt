package com.akane.inspiration

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.akane.inspiration.capture.CapturePolicy
import com.akane.inspiration.capture.HistoryPageCounter
import com.akane.inspiration.capture.captureMarker
import com.akane.inspiration.dashboard.CockpitDependencies
import com.akane.inspiration.database.InspirationDatabase
import com.akane.inspiration.database.store.RoomSnapshotStore
import com.akane.inspiration.di.AppEntries
import com.akane.inspiration.di.CachedAppLabels
import com.akane.inspiration.di.PackageManagerAppLabelSource
import com.akane.inspiration.di.Preloaded
import com.akane.inspiration.di.RefreshCoordinator
import com.akane.inspiration.di.StoreTicks
import com.akane.inspiration.di.TimelineEvents
import com.akane.inspiration.di.newApplicationScope
import com.akane.inspiration.di.reconcileOnEachReady
import com.akane.inspiration.diagnostics.analysis.AnalysisSelection
import com.akane.inspiration.diagnostics.analysis.AnalysisSession
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.analysis.IntervalAnalyzer
import com.akane.inspiration.diagnostics.analysis.Timeline
import com.akane.inspiration.diagnostics.analysis.TimelineBuilder
import com.akane.inspiration.diagnostics.dictionary.DiagnosticDictionary
import com.akane.inspiration.diagnostics.dictionary.DictionaryLoader
import com.akane.inspiration.diagnostics.rules.DiagnosticRuleEngine
import com.akane.inspiration.diagnostics.sensor.AndroidSensorCatalog
import com.akane.inspiration.health.HealthDependencies
import com.akane.inspiration.ipc.ShizukuManager
import com.akane.inspiration.ipc.ShizukuManagerImpl
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.sleeplab.SleepLabDependencies
import com.akane.inspiration.status.StatusChannel
import com.akane.inspiration.status.StatusNotificationSettings
import com.akane.inspiration.status.StatusTicks
import com.akane.inspiration.telemetry.app.AppEntry
import com.akane.inspiration.telemetry.app.AppLabels
import com.akane.inspiration.telemetry.app.KernelCapacitySnapshot
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.app.attempt
import com.akane.inspiration.telemetry.calibration.PowerCalibrator
import com.akane.inspiration.telemetry.calibration.SharedPreferencesCalibrationStore
import com.akane.inspiration.telemetry.capture.CaptureResult
import com.akane.inspiration.telemetry.capture.SnapshotCapturer
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.live.LiveTelemetry
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.source.AndroidClocks
import com.akane.inspiration.telemetry.source.AndroidTelemetrySource
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.Marker
import com.akane.inspiration.telemetry.store.SnapshotStore
import com.akane.inspiration.telemetry.store.TelemetrySample
import com.akane.inspiration.telemetry.store.pruneRetention
import com.akane.inspiration.telemetry.toolkit.ToolkitActions
import com.akane.inspiration.telemetry.toolkit.ToolkitGateway
import com.akane.inspiration.toolkit.ToolkitDependencies
import com.akane.inspiration.xray.XRayDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.akane.inspiration.diagnostics.R as DiagnosticsR

/**
 * Manual DI root. It also implements the five pinned per-feature dependency interfaces (H4), so a screen
 * only ever sees its own interface: no container, no raw `ToolkitGateway`, no Intent. Nothing in here polls;
 * live readings flow only while a screen collects [liveReadings] (H13).
 */
class AppContainer(
    context: Context,
) : CockpitDependencies,
    XRayDependencies,
    SleepLabDependencies,
    HealthDependencies,
    ToolkitDependencies {
    private val appContext = context.applicationContext

    val applicationScope: CoroutineScope = newApplicationScope()
    override val clocks: Clocks = AndroidClocks(appContext)
    val shizukuManager: ShizukuManager =
        ShizukuManagerImpl(
            context = appContext,
            scope = applicationScope,
        )
    val database: InspirationDatabase = InspirationDatabase.create(appContext)
    val store: SnapshotStore = RoomSnapshotStore(database, currentBootCount = { clocks.bootCount() })
    private val telemetrySource = AndroidTelemetrySource(appContext, shizukuManager, clocks)

    /** What the capturer writes through: a missed app-open or marker capture ticks for the pages it stored. */
    private val historyPages = HistoryPageCounter(store)
    val capturer =
        SnapshotCapturer(
            source = telemetrySource,
            store = historyPages,
            clocks = clocks,
        )
    private val liveTelemetry =
        LiveTelemetry(
            telemetrySource,
            PowerCalibrator(),
            clocks,
            store,
            calibrationStore = SharedPreferencesCalibrationStore.from(appContext),
        )

    // The bundled dictionary (a disk read plus a parse) loads on IO from construction; see [Preloaded].
    private val dictionaryLoad =
        Preloaded(applicationScope) {
            val json =
                appContext.resources
                    .openRawResource(DiagnosticsR.raw.diagnostic_dictionary)
                    .bufferedReader()
                    .use { it.readText() }
            DiagnosticDictionary(DictionaryLoader.parse(json))
        }

    /** The preloaded dictionary: it never parses on the calling thread (at most it waits for the IO load). */
    val dictionary: DiagnosticDictionary get() = dictionaryLoad.get()
    val ruleEngine: DiagnosticRuleEngine by lazy { DiagnosticRuleEngine(AndroidSensorCatalog(appContext)) }

    // ---- refresh (H7) ----------------------------------------------------------------------

    /**
     * Ticked by every capture that ingests the battery history: the app-open refresh, a marker, the charge job. A
     * missed app-open or marker capture ticks too when it stored history pages before it missed ([historyPages]).
     */
    val storeTicks = StoreTicks(clocks::wallMs)

    private val refreshCoordinator =
        RefreshCoordinator(
            nowWallMs = clocks::wallMs,
            debounceMs = CapturePolicy.APP_OPEN_DEBOUNCE_MS,
            onFailure = { android.util.Log.w("AppContainer", "App refresh failed", it) },
            afterStored = {
                store.pruneRetention(clocks)
                emitStatusTick()
            },
            onStored = storeTicks::tick,
            capture = ::captureFull,
        )

    /**
     * The last [storeTicks] value, not the refresh debounce's clock: it feeds [analysisSession] as its refresh tick,
     * and the Cockpit timeline and Health re-read the store on it while they are visible, whoever stored the history.
     */
    override val lastRefreshWallMs: StateFlow<Long?> = storeTicks.lastWallMs

    /** FULL capture plus history ingest, mutex-guarded and debounced to 60 s. Nothing calls it on a timer. */
    override suspend fun refresh(): RefreshResult = refreshCoordinator.refresh()

    private suspend fun captureFull(): RefreshResult =
        when (
            val result =
                historyPages.withMissTick(storeTicks::tick) {
                    capturer.captureFull(CaptureTrigger.APP_OPEN, FULL_CAPTURE_DEADLINE_MS, ingestHistory = true)
                }
        ) {
            // The coordinator prunes, updates the status and ticks after it (see [refreshCoordinator]).
            is CaptureResult.Saved -> {
                RefreshResult.Refreshed(clocks.wallMs())
            }

            // Ticked already when the capture stored history pages before it missed.
            is CaptureResult.Missed -> {
                RefreshResult.Failed(result.reason)
            }
        }

    // ---- analysis (H3) ---------------------------------------------------------------------

    override val selection = AnalysisSelection()

    /** The one process-wide session, recomputed on a selection change or a store tick and shared by every screen. */
    val analysisSession =
        AnalysisSession(
            analyzer = IntervalAnalyzer(store, ruleEngine),
            selection = selection,
            refreshTicks = lastRefreshWallMs.filterNotNull(),
            scope = applicationScope,
            nowWallMs = clocks::wallMs,
        )
    override val analysis: StateFlow<AnalysisState> = analysisSession.state

    /** Retry's path when a refresh brings no new capture (debounced, failed): the analysis only reads Room. */
    override fun recomputeAnalysis() = analysisSession.recompute()

    override fun dictionary(): DiagnosticDictionary = dictionary

    /**
     * The window's events plus the last of each state track before [from], so a window that opens mid screen-on,
     * mid charge or mid Doze still shows that state (see [TimelineEvents]). Building walks every event of the
     * window, which for a multi-day interval is far too much for the main thread the screens call from.
     */
    override suspend fun timeline(
        from: Long,
        to: Long,
    ): Timeline =
        withContext(Dispatchers.Default) {
            val events =
                TimelineEvents.withSeeds(
                    before = store.lastHistoryOfEachKindBefore(from),
                    inWindow = store.historyBetween(from, to),
                )
            // Lanes end at the last successful history ingest, so a failed refresh does not stretch stale state.
            TimelineBuilder.build(events, from, to, store.historyHorizonWallMs() ?: Long.MIN_VALUE)
        }

    override suspend fun historyBetween(
        from: Long,
        to: Long,
    ): List<HistoryEvent> = store.historyBetween(from, to)

    // ---- Shizuku ---------------------------------------------------------------------------

    override val shizukuState: StateFlow<ShizukuState> = shizukuManager.state

    /** The only way the app shows the Shizuku permission dialog; the toolkit's blocked state calls it. */
    override fun requestShizukuPermission() = shizukuManager.requestPermission()

    override fun holdBinding(): AutoCloseable = shizukuManager.holdBinding()

    // ---- live readings, markers, health ----------------------------------------------------

    /** Cold: the kernel is read only while a screen collects this (H13). */
    override fun liveReadings(periodMs: Long): Flow<LiveReading> = liveTelemetry.readings(periodMs)

    override suspend fun markers(): List<Marker> = store.markers()

    override suspend fun createMarker(label: String): Long? =
        captureMarker(
            capture = {
                historyPages.withMissTick(storeTicks::tick) {
                    capturer.captureFull(CaptureTrigger.MARKER, FULL_CAPTURE_DEADLINE_MS, ingestHistory = true)
                }
            },
            addMarker = { snapshotId -> store.addMarker(snapshotId, label, clocks.wallMs()) },
            onHistoryStored = storeTicks::tick,
        )

    override suspend fun kernelBattery(): KernelCapacitySnapshot? =
        KernelCapacitySnapshot.from(
            telemetrySource.kernelBatterySnapshot(KERNEL_READ_TIMEOUT_MS),
        )

    override suspend fun healthSamples(): List<HealthSample> = store.healthSamples()

    override suspend fun chargeSessions(): List<ChargeSessionRecord> = store.chargeSessions()

    override suspend fun samplesBetween(
        from: Long,
        to: Long,
    ): List<TelemetrySample> = store.samplesBetween(from, to)

    // ---- toolkit (H12) ---------------------------------------------------------------------

    /** The only toolkit API features see; the raw gateway stays inside this container. */
    override val toolkit =
        ToolkitActions(
            gateway = ToolkitGateway(shizukuManager),
            store = store,
            holdBinding = shizukuManager::holdBinding,
            nowMs = clocks::wallMs,
            elapsedMs = clocks::elapsedRealtimeMs,
        )

    // ---- app labels and the picker (H15) ---------------------------------------------------

    override val appLabels: AppLabels = CachedAppLabels(PackageManagerAppLabelSource(appContext.packageManager))

    // The ResolveInfoFlags overload is API 33+; the Int overload is identical for flags 0 and works on API 31+.
    @Suppress("DEPRECATION")
    override suspend fun installedApps(): List<AppEntry> =
        withContext(Dispatchers.IO) {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val launchable =
                appContext.packageManager
                    .queryIntentActivities(launcher, 0)
                    .map { it.activityInfo.packageName }
                    .toSet()
            // No override read here: the Toolkit screen merges restricted apps from its own activeOverrides()
            // collector, so a load reconciles and computes the overrides once under the toolkit mutex (F-19).
            AppEntries.build(
                launchable = launchable,
                excluded = setOf(appContext.packageName, SHIZUKU_PACKAGE),
                locale = Locale.getDefault(),
                label = appLabels::label,
            )
        }

    // ---- status notification (ticks, switch, blocked state) --------------------------------

    /** Owns the tick flow; the runtime and boot capture receivers wrap their captures in [StatusTicks.emitAfter]. */
    val statusTicker = StatusTicks()

    /** One value per finished capture or successful refresh; the status service redraws on each. */
    val statusTicks: SharedFlow<Unit> get() = statusTicker.ticks

    fun emitStatusTick() = statusTicker.emit()

    /**
     * How long the screen has been off by the stored screen captures ([CapturePolicy.screenOffForMs]); the
     * screen-on receiver binds the service only after a long enough period. Null when unknown or unreadable.
     */
    suspend fun screenOffForMs(): Long? =
        attempt {
            CapturePolicy.screenOffForMs(
                lastScreenOff = store.latestClockByTrigger(CaptureTrigger.SCREEN_OFF),
                lastScreenOn = store.latestClockByTrigger(CaptureTrigger.SCREEN_ON),
                nowElapsedMs = clocks.elapsedRealtimeMs(),
                bootCount = clocks.bootCount(),
            )
        }

    private val statusSettings by lazy { StatusNotificationSettings.from(appContext) }

    val postPromptShown: Boolean get() = statusSettings.postPromptShown()

    fun markPostPromptShown() = statusSettings.markPostPromptShown()

    /** Starts or stops the status service for the new switch value; T3b wires it in `InspirationApplication`. */
    var statusSync: (Boolean) -> Unit = {}

    override val statusNotificationEnabled: StateFlow<Boolean> get() = statusSettings.enabled

    override fun setStatusNotificationEnabled(enabled: Boolean) {
        statusSettings.setEnabled(enabled)
        statusSync(enabled)
    }

    override fun notificationsBlocked(): Boolean {
        val manager = NotificationManagerCompat.from(appContext)
        return StatusChannel.isBlocked(
            notificationsEnabled = manager.areNotificationsEnabled(),
            channelImportance = manager.getNotificationChannel(StatusChannel.ID)?.importance,
        )
    }

    // ---- start-up --------------------------------------------------------------------------

    /**
     * Off-main-thread start-up work (the dictionary already loads from construction). Toolkit rows left
     * PENDING by a crash are reconciled on every transition to Shizuku Ready (a read-back needs the
     * service, so a run against an unreachable service is retried at the next Ready); with Shizuku never
     * ready nothing runs and the rows simply stay PENDING. Every transition to Ready also clears the refresh
     * failure backoff first, so a refresh right after a Shizuku grant captures instead of replaying the old Failed,
     * and then re-runs a refresh whose last capture failed (e.g. the cold-start one, made before the binder came).
     * The re-run goes before the reconcile, which may throw; a refresh turns its own errors into Failed.
     */
    fun start() {
        applicationScope.launch {
            reconcileOnEachReady(shizukuManager.state) {
                refreshCoordinator.clearFailureBackoff()
                refreshCoordinator.refreshIfLastFailed()
                toolkit.reconcile()
            }
        }
    }

    private companion object {
        const val FULL_CAPTURE_DEADLINE_MS = 10_000L
        const val KERNEL_READ_TIMEOUT_MS = 2_000L
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
