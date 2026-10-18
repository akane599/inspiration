# Android TV Development — Compose for TV

## Setup

```toml
# libs.versions.toml
[versions]
tv-compose = "1.0.0"

[libraries]
tv-compose-foundation = { module = "androidx.tv:tv-foundation", version.ref = "tv-compose" }
tv-compose-material3  = { module = "androidx.tv:tv-material",   version.ref = "tv-compose" }
```

```kotlin
// tv/build.gradle.kts
android {
    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    implementation(libs.tv.compose.foundation)
    implementation(libs.tv.compose.material3)
}
```

```xml
<!-- AndroidManifest.xml — mark as TV app -->
<uses-feature android:name="android.software.leanback" android:required="true" />
<uses-feature android:name="android.hardware.touchscreen" android:required="false" />

<application android:banner="@drawable/app_banner">
    <activity
        android:name=".MainActivity"
        android:label="@string/app_name"
        android:theme="@style/Theme.Leanback">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
        </intent-filter>
    </activity>
</application>
```

> **Note:** Most Compose for TV Material3 APIs are annotated `@ExperimentalTvMaterial3Api`.
> Add `@OptIn(ExperimentalTvMaterial3Api::class)` to every composable that uses TV components,
> or opt in globally via `freeCompilerArgs += "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api"` in your Gradle config.

---

## Compose for TV — Core Components

### Entry Point

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // TV Material3 theme
            MaterialTheme {
                TvApp()
            }
        }
    }
}
```

### Focus Management — The Core Challenge

Focus is driven by D-pad (←→↑↓) and must be explicit.

**`isFocused` vs `hasFocus`:** Use `isFocused` on leaf items (cards, buttons). Use `hasFocus` on containers
(rows, columns) — `isFocused` is never true on a container because focus lives on its children.

```kotlin
// focusRequester — programmatically set initial focus
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvScreen() {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()   // set focus on first item at launch
    }

    TvLazyColumn {
        item {
            WordCard(
                modifier = Modifier.focusRequester(focusRequester),
                // ...
            )
        }
    }
}
```

```kotlin
// WordCard — leaf item, use isFocused
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WordCard(
    word: Word,
    onClick: () -> Unit,
    onFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,   // ← use Card's own onClick, never Modifier.clickable on top of Card
        modifier = modifier
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused   // isFocused correct for leaf items
                onFocusChanged(focusState.isFocused)
            }
            .scale(if (isFocused) 1.05f else 1f)  // TV standard focus feedback
            .border(
                width = if (isFocused) 3.dp else 0.dp,
                color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = MaterialTheme.shapes.medium,
            ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(word.original, style = MaterialTheme.typography.titleMedium)
            Text(word.translated, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
```

### TvLazyRow — Horizontal Content Rows

Use `focusGroup()` so D-pad doesn't escape the row mid-scroll, and `focusRestorer()` so focus
returns to the last focused item when the user navigates back to this row.

```kotlin
// ImmutableList required — List<T> is unstable and causes unnecessary recomposition
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CategoryRow(
    category: String,
    words: ImmutableList<Word>,
    onWordSelected: (Word) -> Unit,
) {
    // hasFocus — true when any child card is focused
    var rowHasFocus by remember { mutableStateOf(false) }

    Column {
        Text(
            text = category,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 48.dp, bottom = 16.dp),
        )
        TvLazyRow(
            modifier = Modifier
                .focusGroup()       // keeps D-pad inside the row while scrolling
                .focusRestorer()    // restores focus to last item when returning to this row
                .onFocusChanged { rowHasFocus = it.hasFocus },  // hasFocus for containers
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(words, key = { it.id }) { word ->
                WordCard(
                    word = word,
                    onClick = { onWordSelected(word) },
                    modifier = Modifier.width(200.dp),
                )
            }
        }
    }
}
```

### TvLazyColumn — Browse Screen

```kotlin
// Use ImmutableList of pairs — Map and List are unstable Compose types
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseScreen(
    categories: ImmutableList<Pair<String, ImmutableList<Word>>>,
    onWordSelected: (Word) -> Unit,
) {
    TvLazyColumn(
        contentPadding = PaddingValues(vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        items(categories, key = { it.first }) { (category, words) ->
            CategoryRow(
                category = category,
                words = words,
                onWordSelected = onWordSelected,
            )
        }
    }
}
```

---

## Navigation — Back Stack Management

```kotlin
// TV navigation — back = D-pad Back or remote Back button
@Composable
fun TvApp() {
    val navController = rememberNavController()

    NavHost(navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onCategorySelected = { navController.navigate("category/$it") }
            )
        }
        composable("category/{id}") { backStackEntry ->
            CategoryScreen(
                id = backStackEntry.arguments!!.getString("id")!!,
                onWordSelected = { navController.navigate("word/$it") }
            )
        }
        composable("word/{id}") { backStackEntry ->
            WordDetailScreen(id = backStackEntry.arguments!!.getString("id")!!)
        }
    }
}
```

---

## D-Pad Key Handling

Handle both `Key.DirectionCenter` and `Key.Enter` — different TV remotes send different keycodes
for the OK/select button.

```kotlin
@Composable
fun TvCard(
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .focusable()
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { event ->
                val isSelectKey = event.key == Key.DirectionCenter || event.key == Key.Enter
                when {
                    isFocused && isSelectKey && event.type == KeyEventType.KeyUp -> {
                        onSelect()
                        true  // consumed
                    }
                    else -> false
                }
            }
    ) {
        content()
    }
}
```

---

## Sidebar Navigation

Pass `currentRoute` and `navController` as parameters — never reference them from an outer
scope that isn't captured as a parameter.

```kotlin
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvNavigationDrawer(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    NavigationDrawer(
        drawerContent = {
            NavigationDrawerItem(
                selected = currentRoute == "home",
                onClick = { onNavigate("home") },
                leadingContent = { Icon(Icons.Default.Home, contentDescription = null) },
            ) {
                Text("Home")
            }
            NavigationDrawerItem(
                selected = currentRoute == "study",
                onClick = { onNavigate("study") },
                leadingContent = { Icon(Icons.Default.School, contentDescription = null) },
            ) {
                Text("Study")
            }
        },
    ) {
        content(PaddingValues(start = 80.dp))  // 80dp accounts for collapsed drawer
    }
}
```

---

## TV-Specific UX Patterns

### 10-foot Rule — Text Sizing

```kotlin
// TV typography — all sizes larger than phone
val TvTypography = Typography(
    displayLarge  = TextStyle(fontSize = 57.sp, letterSpacing = (-0.25).sp),
    headlineLarge = TextStyle(fontSize = 36.sp),
    titleLarge    = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Medium),
    bodyLarge     = TextStyle(fontSize = 22.sp),   // minimum readable at 10 feet
    labelLarge    = TextStyle(fontSize = 18.sp),
)
```

### Overscan-Safe Margins

```kotlin
// TV screens may have overscan — always add safe insets
val TvContentPadding = PaddingValues(
    horizontal = 48.dp,   // standard TV horizontal margin
    vertical   = 27.dp,   // standard TV vertical margin
)
```

### Focus Highlight Animation

```kotlin
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FocusableCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "card_scale",       // required for Layout Inspector animation tooling
    )
    val elevation by animateDpAsState(
        targetValue = if (isFocused) 8.dp else 2.dp,
        label = "card_elevation",
    )

    Card(
        onClick = {},
        modifier = modifier
            .scale(scale)
            .shadow(elevation, MaterialTheme.shapes.medium)
            .onFocusChanged { isFocused = it.isFocused },
    ) {
        content()
    }
}
```

---

## Media Playback (ExoPlayer / Media3)

```kotlin
@Composable
fun VideoPlayerScreen(videoUri: String) {
    val context = LocalContext.current

    // Key on videoUri — player is recreated when the URI changes
    val player = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        update = { it.player = player },   // keep PlayerView in sync when player changes
        modifier = Modifier.fillMaxSize(),
    )
}
```

---

## TV Design Checklist

- [ ] All TV composables annotated `@OptIn(ExperimentalTvMaterial3Api::class)`
- [ ] All interactive elements have visible focus indicators (scale + border/glow)
- [ ] Initial focus is set programmatically with `FocusRequester`
- [ ] `isFocused` used on leaf items; `hasFocus` used on container rows/columns
- [ ] `focusGroup()` applied to every scrollable row — prevents D-pad escape mid-scroll
- [ ] `focusRestorer()` applied to every scrollable row — restores focus on back navigation
- [ ] D-pad navigation is logical (no focus traps, natural grid flow)
- [ ] Both `Key.DirectionCenter` and `Key.Enter` handled for select actions
- [ ] No `Modifier.clickable` layered on top of `Card` — use `Card`'s `onClick` param
- [ ] Content has 48dp horizontal margins (overscan safe)
- [ ] Text minimum 18sp body, 22sp+ for primary content
- [ ] Touch events are NOT the primary interaction model
- [ ] Back button always returns to previous screen, never closes app unexpectedly
- [ ] Loading states don't show spinners — use skeleton/placeholder rows
- [ ] Horizontal rows scrollable with D-pad left/right
- [ ] `ImmutableList<T>` used for all composable list parameters (not `List<T>` or `Map`)
- [ ] App banner is 320x180dp PNG (required for TV launcher)
