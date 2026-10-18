# Android Widget Development — Glance API

Glance provides a Compose-like API for app widgets (RemoteViews internally). Requires API 23+ (min widget SDK).

## Setup

```toml
# libs.versions.toml
[versions]
glance = "1.1.1"

[libraries]
glance-appwidget = { group = "androidx.glance", name = "glance-appwidget", version.ref = "glance" }
glance-material3 = { group = "androidx.glance", name = "glance-material3", version.ref = "glance" }
glance-appwidget-preview = { group = "androidx.glance", name = "glance-appwidget-preview", version.ref = "glance" }
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    debugImplementation(libs.glance.appwidget.preview)
}
```

---

## Widget Declaration

```xml
<!-- res/xml/review_widget_info.xml -->
<appwidget-provider
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="110dp"
    android:minHeight="40dp"
    android:targetCellWidth="2"
    android:targetCellHeight="1"
    android:resizeMode="horizontal|vertical"
    android:updatePeriodMillis="1800000"
    android:initialLayout="@layout/glance_default_loading_layout"
    android:description="@string/widget_description"
    android:previewLayout="@layout/widget_preview"
    android:widgetCategory="home_screen|keyguard"
    android:configure="com.example.widgets.ReviewWidgetConfigureActivity" />
```

```xml
<!-- AndroidManifest.xml -->
<receiver
    android:name=".widgets.ReviewWidgetReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data
        android:name="android.appwidget.provider"
        android:resource="@xml/review_widget_info" />
</receiver>
```

---

## ⚠️ Glance Gotchas

These silent failures trip up everyone coming from regular Compose:

```kotlin
// ❌ Regular Compose Modifier — compiles but does nothing in Glance
Box(modifier = Modifier.fillMaxSize())
// ✅ Must use GlanceModifier
Box(modifier = GlanceModifier.fillMaxSize())

// ❌ remember{} is NOT supported — Glance recomposes the whole tree every update
val x = remember { computeSomething() }

// ❌ Wrong LocalContext — use Glance's, not Compose UI's
import androidx.compose.ui.platform.LocalContext  // ❌
import androidx.glance.LocalContext               // ✅

// ❌ Lambdas in clickable are NOT supported — must use action builders
modifier = GlanceModifier.clickable { doSomething() }  // ❌
modifier = GlanceModifier.clickable(actionRunCallback<MyCallback>())  // ✅

// updatePeriodMillis: OS enforces a hard minimum of 30 minutes regardless of the value set.
// Setting 0 or any value < 1800000 is silently clamped — use WorkManager for frequent updates.
```

---

## GlanceAppWidget

```kotlin
class ReviewWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = ReviewWidgetStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Collect state
            val state = currentState<ReviewWidgetState>()

            GlanceTheme {
                ReviewWidgetContent(state = state)
            }
        }
    }
}
```

---

## Responsive Layouts with SizeMode

Widgets can be placed in different grid sizes. Use `SizeMode.Responsive` to adapt:

```kotlin
class ReviewWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 40.dp),   // 2×1 — compact
            DpSize(220.dp, 40.dp),   // 4×1 — medium
            DpSize(220.dp, 100.dp),  // 4×2 — expanded
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val size = LocalSize.current
            GlanceTheme {
                if (size.height >= 100.dp) {
                    ReviewWidgetExpanded(state = currentState())
                } else {
                    ReviewWidgetCompact(state = currentState())
                }
            }
        }
    }
}
```

---

## Widget State

```kotlin
// Widget state — serialized to DataStore
@Serializable
data class ReviewWidgetState(
    val dueCount: Int = 0,
    val lastUpdated: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,   // null = no error; non-null = show error UI
)

// StateDefinition — how state is stored
object ReviewWidgetStateDefinition : GlanceStateDefinition<ReviewWidgetState> {
    private val Context.dataStore by dataStore(
        fileName = "review_widget_state",
        serializer = ReviewWidgetStateSerializer,
    )

    override suspend fun getDataStore(context: Context, fileKey: String) =
        context.dataStore

    override fun getLocation(context: Context, fileKey: String) =
        context.dataStoreFile("review_widget_state")
}

private object ReviewWidgetStateSerializer : Serializer<ReviewWidgetState> {
    override val defaultValue = ReviewWidgetState()
    override suspend fun readFrom(input: InputStream) =
        Json.decodeFromString(ReviewWidgetState.serializer(), input.readBytes().decodeToString())
    override suspend fun writeTo(t: ReviewWidgetState, output: OutputStream) =
        output.write(Json.encodeToString(ReviewWidgetState.serializer(), t).toByteArray())
}
```

---

## Widget Content Composables

```kotlin
@Composable
fun ReviewWidgetContent(state: ReviewWidgetState) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.isLoading -> {
                CircularProgressIndicator(modifier = GlanceModifier.size(24.dp))
            }
            state.dueCount == 0 -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_done),
                        contentDescription = "All done",
                        modifier = GlanceModifier.size(32.dp),
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    Text(
                        text = "All caught up!",
                        style = TextStyle(
                            color = ColorProvider(GlanceTheme.colors.onSurface),
                            fontSize = 14.sp,
                        ),
                    )
                }
            }
            else -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${state.dueCount}",
                        style = TextStyle(
                            color = ColorProvider(GlanceTheme.colors.primary),
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    Text(
                        text = "words due",
                        style = TextStyle(
                            color = ColorProvider(GlanceTheme.colors.onSurface),
                            fontSize = 12.sp,
                        ),
                    )
                    Spacer(GlanceModifier.height(8.dp))
                    Button(
                        text = "Study now",
                        onClick = actionStartActivity<StudyActivity>(),
                        modifier = GlanceModifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
```

---

## Widget Actions

```kotlin
// Launch Activity
clickable(actionStartActivity<MainActivity>())

// Launch Activity with extras
clickable(
    actionStartActivity(
        Intent(context, StudyActivity::class.java).apply {
            putExtra("source", "widget")
        }
    )
)

// Run WorkManager task
clickable(
    actionStartService<SyncWorkerService>(
        isForegroundService = false,
    )
)

// Run callback (Glance 1.1+)
clickable(actionRunCallback<RefreshWidgetCallback>())

// Callback implementation
class RefreshWidgetCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ReviewWidgetUpdater(context).update(glanceId)
    }
}

// Pass typed parameters to a callback via ActionParameters
private val wordIdKey = ActionParameters.Key<String>("word_id")

clickable(
    actionRunCallback<StudyWordCallback>(
        actionParametersOf(wordIdKey to word.id)
    )
)

class StudyWordCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val wordId = parameters[wordIdKey] ?: return
        context.startActivity(
            Intent(context, StudyActivity::class.java)
                .putExtra("word_id", wordId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
```

---

## Widget Receiver & Updater

```kotlin
class ReviewWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ReviewWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        scheduleUpdate(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduleUpdate(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        // Cancel associated work if no more instances remain
        val manager = GlanceAppWidgetManager(context)
        // runBlocking is acceptable here — onDeleted is called on the main thread
        // and Glance provides no coroutine-friendly alternative for this lifecycle method
        val remaining = runBlocking { manager.getGlanceIds(ReviewWidget::class.java) }
        if (remaining.isEmpty()) {
            WorkManager.getInstance(context).cancelUniqueWork("widget-update")
        }
    }

    private fun scheduleUpdate(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "widget-update",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReviewWidgetUpdateWorker>().build(),
        )
    }
}

class ReviewWidgetUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = getKoin().get<IWordRepository>()
        val dueCount = repository.getDueWordCount().getOrElse { return Result.retry() }

        val newState = ReviewWidgetState(
            dueCount = dueCount,
            lastUpdated = Clock.System.now().toString(),
            isLoading = false,
        )

        // Update all widget instances
        GlanceAppWidgetManager(applicationContext)
            .getGlanceIds(ReviewWidget::class.java)
            .forEach { glanceId ->
                updateAppWidgetState(applicationContext, ReviewWidgetStateDefinition, glanceId) {
                    newState
                }
                ReviewWidget().update(applicationContext, glanceId)
            }

        return Result.success()
    }
}
```

---

## Lazy Lists in Widgets (Glance 1.1+)

```kotlin
@Composable
fun WordListWidget(words: List<String>) {
    LazyColumn(
        modifier = GlanceModifier.fillMaxSize(),
    ) {
        items(words) { word ->
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickable(actionStartActivity<StudyActivity>()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = word,
                    style = TextStyle(fontSize = 14.sp),
                    modifier = GlanceModifier.defaultWeight(),
                    maxLines = 1,
                )
                Image(
                    provider = ImageProvider(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    modifier = GlanceModifier.size(16.dp),
                )
            }
        }
    }
}
```

---

## Widget Configuration Activity

```kotlin
class ReviewWidgetConfigureActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)  // default to cancelled if user backs out

        appWidgetId = intent.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            AppTheme {
                ConfigureScreen(
                    onConfirm = { maxWords ->
                        saveConfig(maxWords)
                        val resultValue = Intent().apply {
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        }
                        setResult(RESULT_OK, resultValue)
                        finish()
                    }
                )
            }
        }
    }

    private fun saveConfig(maxWords: Int) {
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putInt("widget_max_words_$appWidgetId", maxWords)
            .apply()
    }
}
```

---

## Triggering Updates from App

```kotlin
// Call this whenever data changes that the widget displays
suspend fun updateAllWidgets(context: Context) {
    GlanceAppWidgetManager(context)
        .getGlanceIds(ReviewWidget::class.java)
        .forEach { glanceId ->
            ReviewWidget().update(context, glanceId)
        }
}

// In Repository — trigger widget update after sync
class WordRepositoryImpl(...) : IWordRepository {
    override suspend fun syncWithRemote(): Try<Unit> = tryOf {
        // ... sync logic ...
        updateAllWidgets(context)
    }
}
```

---

## Glance Previews

Glance has its own preview annotation — regular `@Preview` does not work:

```kotlin
// Requires: debugImplementation(libs.glance.appwidget.preview)
@GlancePreview  // from androidx.glance.appwidget.preview
@Composable
fun ReviewWidgetContentPreview() {
    GlanceTheme {
        ReviewWidgetContent(
            state = ReviewWidgetState(dueCount = 5, isLoading = false),
        )
    }
}

@GlancePreview
@Composable
fun ReviewWidgetLoadingPreview() {
    GlanceTheme {
        ReviewWidgetContent(state = ReviewWidgetState(isLoading = true))
    }
}
```

---

## Widget Design Guidelines

- **Minimum touch target**: 48×48dp — never smaller
- **Max content**: show 1–5 items; more = illegible
- **No scrollable content below API 31**: `LazyColumn` requires `android:minSdk = 31` for older widgets
- **Loading state**: always show a placeholder — never blank
- **Refresh rate**: OS enforces a hard minimum of 30 minutes for `updatePeriodMillis` — use WorkManager for anything more frequent
- **Theming**: always use `GlanceTheme.colors` — respects system dark/light mode and dynamic color
- **Tap behavior**: every widget should launch the app when tapped, even if no explicit target
- **Starting activities from callbacks**: always add `Intent.FLAG_ACTIVITY_NEW_TASK` — widgets run outside an activity stack
- **Responsive sizing**: use `SizeMode.Responsive` + `LocalSize.current` rather than a single fixed layout
- **Error state**: always model an error case in state — never let the widget go blank on failure
