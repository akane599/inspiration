# Wear OS Development

## Setup

```toml
# libs.versions.toml
wear-compose = "1.4.0"
wear-compose-material3 = "1.0.0-alpha32"   # alpha — API may change between releases
horologist = "0.6.21"
wear-tiles = "1.4.1"
wear-watchface = "1.2.1"
health-services = "1.1.0-rc01"
play-services-wearable = "18.1.0"          # Wearable Data Layer (phone ↔ watch)
kotlinx-coroutines-guava = "1.8.1"         # ListenableFuture <-> coroutines bridge
```

```kotlin
// wear/build.gradle.kts
android {
    defaultConfig {
        minSdk = 30   // Wear OS 3.0+ (API 30)
    }
}

dependencies {
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.horologist.compose.layout)
    implementation(libs.horologist.compose.material)
    implementation(libs.horologist.tiles)              // SuspendingTileService
    implementation(libs.wear.tiles)
    implementation(libs.wear.tiles.material3)
    implementation(libs.health.services.client)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.guava)
}
```

---

## Compose for Wear OS — Core Patterns

### App Entry Point

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // WearMaterial3Theme wraps all Wear screens
            WearMaterial3Theme {
                WearApp()
            }
        }
    }
}
```

### Navigation

```kotlin
@Composable
fun WearApp() {
    val navController = rememberSwipeDismissableNavController()

    SwipeDismissableNavHost(
        navController = navController,
        startDestination = "home",
    ) {
        composable("home") {
            HomeScreen(onNavigateToDetail = { navController.navigate("detail/$it") })
        }
        composable("detail/{id}") { backStackEntry ->
            // !! safe: NavBackStackEntry always provides declared route arguments
            DetailScreen(id = backStackEntry.arguments?.getString("id")!!)
        }
    }
}
```

### Screen Layout — TimeText + ScalingLazyColumn + PositionIndicator

`ScreenScaffold` requires `TimeText` (always shown at top) and wires `PositionIndicator`
automatically when you pass a `scrollState`.

```kotlin
// ImmutableList<T> required for Compose stability — import kotlinx.collections.immutable
@Composable
fun WordListScreen(
    words: ImmutableList<Word>,
    onWordClick: (Word) -> Unit,
) {
    val scrollState = rememberResponsiveColumnState(
        contentPadding = ScalingLazyColumnDefaults.padding(
            first = ScalingLazyColumnDefaults.ItemType.Text,
            last  = ScalingLazyColumnDefaults.ItemType.Chip,
        ),
    )

    // TimeText at top + PositionIndicator on the side are wired automatically
    ScreenScaffold(
        scrollState = scrollState,
        timeText = { TimeText() },
    ) {
        ScalingLazyColumn(columnState = scrollState) {
            item {
                ResponsiveListHeader(contentColor = MaterialTheme.colorScheme.primary) {
                    Text("Due Today")
                }
            }
            items(words, key = { it.id }) { word ->
                Chip(
                    label = { Text(word.original, maxLines = 1) },
                    secondaryLabel = { Text(word.translated, maxLines = 1) },
                    onClick = { onWordClick(word) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
```

### Rotary Input (Crown / Bezel Scrolling)

Rotary input is the primary non-touch interaction on Wear OS — always wire it to scrollable
screens.

```kotlin
@Composable
fun WordListScreen(words: ImmutableList<Word>, onWordClick: (Word) -> Unit) {
    val scrollState = rememberResponsiveColumnState(...)
    val focusRequester = rememberActiveFocusRequester()
    val coroutineScope = rememberCoroutineScope()

    ScreenScaffold(
        scrollState = scrollState,
        timeText = { TimeText() },
    ) {
        ScalingLazyColumn(
            columnState = scrollState,
            modifier = Modifier
                .onRotaryScrollEvent { event ->
                    coroutineScope.launch {
                        scrollState.state.animateScrollBy(event.verticalScrollPixels)
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
        ) { /* items */ }
    }

    // Grab focus so rotary events are delivered immediately
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
```

### Dialogs and Confirmations

```kotlin
// androidx.wear.compose.material3.dialog.AlertDialog — NOT the standard AlertDialog
@Composable
fun DeleteConfirmationDialog(
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        AlertDialog(
            title = { Text("Delete word?") },
            onDismiss = onDismiss,
            positiveButton = {
                Button(onClick = onConfirm) { Text("Delete") }
            },
            negativeButton = {
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            },
        )
    }
}
```

### Picker (Time / Date Input)

```kotlin
@Composable
fun HourPickerScreen(
    selectedHour: Int,
    onHourSelected: (Int) -> Unit,
) {
    val items = (0..23).map { "%02d".format(it) }
    val pickerState = rememberPickerState(
        initialNumberOfOptions = items.size,
        initiallySelectedOption = selectedHour,
    )

    ScreenScaffold(timeText = { TimeText() }) {
        Picker(
            state = pickerState,
            contentDescription = "Select hour",
            modifier = Modifier.size(100.dp, 100.dp),
        ) {
            Text(items[it], style = MaterialTheme.typography.displayMedium)
        }
        Button(
            onClick = { onHourSelected(pickerState.selectedOption) },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { Text("OK") }
    }
}
```

### Previews

Use `@WearPreviewDevices` and `@WearPreviewFontScales` instead of the phone `@Preview`.

```kotlin
@WearPreviewDevices
@WearPreviewFontScales
@Composable
fun WordListScreenPreview() {
    WearMaterial3Theme {
        WordListScreen(words = previewWords, onWordClick = {})
    }
}
```

---

## Tiles API (Wear OS 3+)

Tiles appear on the watch face swipe carousel — no Activity context.

```xml
<!-- AndroidManifest.xml -->
<service
    android:name=".tiles.ReviewTileService"
    android:label="Review Words"
    android:permission="com.google.android.wearable.permission.BIND_TILE_PROVIDER">
    <intent-filter>
        <action android:name="androidx.wear.tiles.action.BIND_TILE_PROVIDER" />
    </intent-filter>
    <meta-data
        android:name="androidx.wear.tiles.PREVIEW"
        android:resource="@drawable/tile_preview" />
</service>
```

```kotlin
// SuspendingTileService (Horologist) provides suspend functions — never use runBlocking
class ReviewTileService : SuspendingTileService() {

    // Must override — called for every resourcesVersion string declared in tileRequest
    override suspend fun resourcesRequest(requestParams: ResourcesRequest): Resources =
        Resources.Builder().setVersion("1").build()

    override suspend fun tileRequest(requestParams: TileRequest): Tile {
        val wordCount = getWordCount()   // suspend call — no blocking needed
        return Tile.Builder()
            .setResourcesVersion("1")
            .setTileTimeline(Timeline.fromLayoutElement(layout(requestParams, wordCount)))
            .setFreshnessIntervalMillis(60_000L * 30)   // refresh every 30 min
            .build()
    }

    private fun layout(params: TileRequest, dueCount: Int): LayoutElement {
        // Use device parameters from the request — don't build from resources
        val deviceParams = params.deviceConfiguration
        val clickable = ModifiersBuilders.Clickable.Builder()
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setClassName(MainActivity::class.java.name)
                            .setPackageName(packageName)
                            .build()
                    ).build()
            ).build()

        return PrimaryLayout.Builder(deviceParams)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryChipContent(
                CompactChip.Builder(this, "Study now", clickable, deviceParams).build()
            )
            .setContent(
                Text.Builder(this, "$dueCount words due")
                    .setTypography(Typography.TYPOGRAPHY_DISPLAY1)
                    .setColor(argb(0xFFFFFFFF.toInt()))
                    .build()
            )
            .build()
    }
}
```

### Pushing Tile Updates on Data Change

```kotlin
// Call from your repository or ViewModel when data changes
fun requestTileUpdate(context: Context) {
    TileService.getUpdater(context).requestUpdate(ReviewTileService::class.java)
}
```

---

## Complications

```xml
<!-- AndroidManifest.xml -->
<service
    android:name=".complications.ReviewCountComplicationService"
    android:icon="@drawable/ic_complication"
    android:label="Words Due"
    android:permission="com.google.android.wearable.permission.BIND_COMPLICATION_PROVIDER">
    <intent-filter>
        <action android:name="android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST" />
    </intent-filter>
    <meta-data
        android:name="android.support.wearable.complications.SUPPORTED_TYPES"
        android:value="SHORT_TEXT,RANGED_VALUE,LONG_TEXT" />
    <meta-data
        android:name="android.support.wearable.complications.UPDATE_PERIOD_SECONDS"
        android:value="1800" />  <!-- 30 minutes -->
</service>
```

```kotlin
class ReviewCountComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val dueCount = getWordRepository().getDueWordCount()
        val launchIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("$dueCount").build(),
                contentDescription = PlainComplicationText.Builder("$dueCount words due").build(),
            ).setTapAction(launchIntent).build()

            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = dueCount.toFloat(),
                min = 0f,
                max = 50f,
                contentDescription = PlainComplicationText.Builder("Words due").build(),
            ).setText(PlainComplicationText.Builder("$dueCount").build())
             .setTapAction(launchIntent)
             .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder("$dueCount words due today").build(),
                contentDescription = PlainComplicationText.Builder("$dueCount words due").build(),
            ).setTapAction(launchIntent).build()

            else -> null
        }
    }

    // Provide preview for EVERY declared type — missing entries hide the complication in picker
    override fun getPreviewData(type: ComplicationType): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder("12").build(),
            contentDescription = PlainComplicationText.Builder("12 words due").build(),
        ).build()

        ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
            value = 12f, min = 0f, max = 50f,
            contentDescription = PlainComplicationText.Builder("Words due").build(),
        ).setText(PlainComplicationText.Builder("12").build()).build()

        ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
            text = PlainComplicationText.Builder("12 words due today").build(),
            contentDescription = PlainComplicationText.Builder("12 words due").build(),
        ).build()

        else -> null
    }
}
```

---

## Health Services API

### Passive Monitoring (background heart rate, steps)

Use `PassiveMonitoringClient` for continuous background metrics — no active exercise session
required. Requires `android.permission.BODY_SENSORS` in the manifest.

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.BODY_SENSORS" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
```

```kotlin
class HeartRateMonitor(private val context: Context) {

    private val client = HealthServices.getClient(context).passiveMonitoringClient

    suspend fun registerHeartRateListener() {
        val capabilities = client.getCapabilities()
        if (DataType.HEART_RATE_BPM !in capabilities.supportedDataTypesPassiveMonitoring) return

        val config = PassiveListenerConfig(
            dataTypes = setOf(DataType.HEART_RATE_BPM),
            shouldUserActivityInfoBeRequested = false,
        )
        client.setPassiveListenerCallback(config, heartRateCallback)
    }

    suspend fun unregister() = client.clearPassiveListenerCallback()

    private val heartRateCallback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            dataPoints.getData(DataType.HEART_RATE_BPM)
                .lastOrNull()
                ?.value
                ?.let { bpm -> /* emit to StateFlow or channel */ }
        }
    }
}
```

### Active Exercise Monitoring

Use `ExerciseClient` only when the user starts an explicit workout session.

```kotlin
class ExerciseTracker(private val context: Context) {

    private val client = HealthServices.getClient(context).exerciseClient

    suspend fun startWorkout() {
        val capabilities = client.getCapabilities()
        if (ExerciseType.RUNNING !in capabilities.supportedExerciseTypes) return

        val config = ExerciseConfig(
            exerciseType = ExerciseType.RUNNING,
            dataTypes = setOf(DataType.HEART_RATE_BPM, DataType.STEPS),
            isAutoPauseAndResumeEnabled = false,
        )
        client.startExercise(config)
    }

    fun observeUpdates(): Flow<ExerciseUpdate> = callbackFlow {
        val callback = object : ExerciseUpdateCallback {
            override fun onExerciseUpdateReceived(update: ExerciseUpdate) { trySend(update) }
            override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
            override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
        }
        client.setUpdateCallback(ContextCompat.getMainExecutor(context), callback)
        awaitClose { client.clearUpdateCallback(callback) }
    }
}
```

---

## Ambient Mode

```kotlin
@Composable
fun StudyScreen(
    words: ImmutableList<Word>,
    isAmbient: Boolean,
) {
    if (isAmbient) {
        // OLED-safe: black background, no animations, no bright colors, no images
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${words.size} words",
                style = MaterialTheme.typography.displayMedium,
                color = Color.White,
            )
        }
    } else {
        WordList(words)
    }
}

// In Activity — AmbientLifecycleObserver.AmbientLifecycleCallback (not Consumer)
class MainActivity : ComponentActivity() {

    private var isAmbient by mutableStateOf(false)

    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                isAmbient = true
            }
            override fun onExitAmbient() { isAmbient = false }
            override fun onUpdateAmbient() {}
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        setContent {
            WearMaterial3Theme {
                StudyScreen(words = /* ... */, isAmbient = isAmbient)
            }
        }
    }
}
```

---

## Wearable Data Layer — Phone ↔ Watch Sync

```xml
<!-- Both phone and wear manifests -->
<service
    android:name=".sync.WearDataListenerService"
    android:exported="true">
    <intent-filter>
        <action android:name="com.google.android.gms.wearable.DATA_CHANGED" />
        <data android:scheme="wear" android:host="*" android:pathPrefix="/words" />
    </intent-filter>
</service>
```

```kotlin
// Phone side — push data to all paired watches
class PhoneDataSync(private val context: Context) {
    private val dataClient = Wearable.getDataClient(context)

    suspend fun syncWords(words: List<Word>) {
        val request = PutDataMapRequest.create("/words").apply {
            dataMap.putString("words_json", Json.encodeToString(words))
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request).await()
    }
}

// Watch side — receive data changes via WearableListenerService
class WearDataListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == "/words" }
            .forEach { event ->
                val json = DataMapItem.fromDataItem(event.dataItem)
                    .dataMap.getString("words_json") ?: return
                val words = Json.decodeFromString<List<Word>>(json)
                // persist to local Room / SQLDelight DB
            }
    }
}
```

---

## OngoingActivity — Persistent Session Indicator

Shows a persistent dot/icon on the watch face while an exercise or session is active.

```kotlin
fun startOngoingActivity(context: Context, notificationId: Int) {
    val launchIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_workout)
        .setContentTitle("Workout active")
        .setOngoing(true)

    OngoingActivity.Builder(context, notificationId, notificationBuilder)
        .setStaticIcon(Icon.createWithResource(context, R.drawable.ic_workout))
        .setTouchIntent(launchIntent)
        .build()
        .apply(context)

    NotificationManagerCompat.from(context).notify(notificationId, notificationBuilder.build())
}
```

---

## Wear OS Design Principles

- **Round first** — use `ScreenScaffold` + `ScalingLazyColumn`, never flat `Column`
- **Always show time** — every screen needs `TimeText` in `ScreenScaffold`
- **Rotary always wired** — scrollable screens must handle crown/bezel via `onRotaryScrollEvent`
- **Thumb-reachable targets** — minimum 48dp tap targets, prefer chips/buttons to text taps
- **Concise text** — watch screens are ~1.5" — one line per chip, truncate gracefully
- **Swipe to dismiss** — all screens must support `SwipeDismissableNavHost`
- **Battery aware** — minimize wake locks, use tile freshness intervals ≥15 min; passive monitoring over active exercise for background metrics
- **Offline first** — sync from phone when connected, cache on watch for offline use
- **No splash screens** — watches respond instantly or show a loading chip
- **Ambient safe** — black background, no animations, white-only pixels, no dynamic content updates
