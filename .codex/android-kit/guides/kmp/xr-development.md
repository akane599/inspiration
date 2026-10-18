# Android XR Development — Jetpack XR SDK

## Overview (as of March 2026)

Android XR runs on devices like Samsung Galaxy XR. Apps run in a 2D compatibility mode by default and opt into spatial features via the Jetpack XR SDK.

```toml
# libs.versions.toml
jetpack-xr-compose = "1.0.0-alpha05"
jetpack-xr-scenecore = "1.0.0-alpha05"
jetpack-xr-arcore = "1.0.0-alpha05"
jetpack-xr-material3 = "1.0.0-alpha15"        # Material3 XR adaptive components
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(libs.jetpack.xr.compose)
    implementation(libs.jetpack.xr.scenecore)
    implementation(libs.jetpack.xr.material3)  // NavigationRail, dialogs, adaptive scaffolds
    implementation(libs.jetpack.xr.arcore)     // optional: AR features
    testImplementation(libs.jetpack.xr.compose.testing)
}
```

---

## Manifest Setup

```xml
<!-- Allow install on non-XR devices -->
<uses-feature android:name="android.hardware.xr" android:required="false" />

<uses-permission android:name="android.permission.SCENE_UNDERSTANDING" />
<uses-permission android:name="android.permission.HEAD_TRACKING" />

<application ...>
    <activity
        android:name=".MainActivity"
        android:enableOnBackInvokedCallback="true">

        <!-- Optional: launch directly into Full Space -->
        <property
            android:name="android.window.PROPERTY_XR_ACTIVITY_START_MODE"
            android:value="XR_ACTIVITY_START_MODE_FULL_SPACE_MANAGED" />
    </activity>
</application>
```

---

## XR App Entry Point

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AppTheme {
                // LocalSpatialCapabilities reflects current device + space mode capabilities
                if (LocalSpatialCapabilities.current.isSpatialUiEnabled) {
                    XrMainScreen()
                } else {
                    RegularMainScreen()
                }
            }
        }
    }
}
```

---

## Subspace — 3D Scene Container

`Subspace` is the root container for all spatial content. Every `@SubspaceComposable` must be called from within one. Every XR function needs `@OptIn(ExperimentalJetpackXrApi::class)`.

```kotlin
@OptIn(ExperimentalJetpackXrApi::class)
@Composable
fun XrMainScreen() {
    Subspace {
        MainSpatialContent()
    }
}

@OptIn(ExperimentalJetpackXrApi::class)
@SubspaceComposable
@Composable
fun MainSpatialContent() {
    SpatialPanel(
        modifier = SubspaceModifier
            .width(1280.dp)
            .height(800.dp),
        dragPolicy = MovePolicy(),
        resizePolicy = ResizePolicy(),
    ) {
        AppContent()
    }
}
```

---

## SpatialPanel — Floating 2D Window

Use `dragPolicy` / `resizePolicy` parameters (not SubspaceModifier extensions) to control panel behaviour.

```kotlin
@OptIn(ExperimentalJetpackXrApi::class)
@SubspaceComposable
@Composable
fun StudySpatialLayout() {
    // Primary panel
    SpatialPanel(
        modifier = SubspaceModifier
            .width(900.dp)
            .height(600.dp)
            .offset(x = 0.dp, y = 0.dp, z = 0.dp),
        dragPolicy = MovePolicy(),
        resizePolicy = ResizePolicy(),
    ) {
        StudyScreen()
    }

    // Orbiter attaches to the most recently declared SpatialPanel in composition order
    Orbiter(
        position = ContentEdge.Bottom,
        offset = 24.dp,
        alignment = Alignment.CenterHorizontally,
    ) {
        StudyToolbar()
    }

    // Companion panel — word list sidebar, positioned left and slightly behind
    SpatialPanel(
        modifier = SubspaceModifier
            .width(320.dp)
            .height(600.dp)
            .offset(x = (-640).dp, y = 0.dp, z = (-50).dp),
        dragPolicy = MovePolicy(),
    ) {
        WordListSidebar()
    }
}
```

---

## Orbiter — Attached UI Element

`Orbiter` is a `@SubspaceComposable` sibling of `SpatialPanel`. It attaches to the most recently declared panel in the same subspace scope.

```kotlin
@OptIn(ExperimentalJetpackXrApi::class)
@SubspaceComposable
@Composable
fun PanelWithControls() {
    SpatialPanel(
        modifier = SubspaceModifier.width(800.dp).height(500.dp),
        dragPolicy = MovePolicy(),
    ) {
        ContentArea()
    }

    // Bottom toolbar
    Orbiter(
        position = ContentEdge.Bottom,
        offset = 12.dp,
        alignment = Alignment.CenterHorizontally,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = { /* prev */ }) {
                Icon(Icons.AutoMirrored.Default.ArrowBack, "Previous")
            }
            IconButton(onClick = { /* next */ }) {
                Icon(Icons.AutoMirrored.Default.ArrowForward, "Next")
            }
        }
    }

    // Side close button
    Orbiter(
        position = ContentEdge.End,
        offset = 12.dp,
        alignment = Alignment.Top,
    ) {
        IconButton(onClick = { /* close */ }) {
            Icon(Icons.Default.Close, "Close")
        }
    }
}
```

---

## Spatial Layouts — SpatialRow / SpatialColumn

Arrange multiple panels without manual offset calculations. `SpatialCurvedRow` wraps panels around the user.

```kotlin
@OptIn(ExperimentalJetpackXrApi::class)
@SubspaceComposable
@Composable
fun ThreePanelLayout() {
    SpatialRow(arrangement = SpatialArrangement.SpaceBetween) {
        SpatialPanel(
            modifier = SubspaceModifier.width(500.dp).height(700.dp),
            dragPolicy = MovePolicy(),
        ) { NavPanel() }

        SpatialPanel(
            modifier = SubspaceModifier.width(800.dp).height(700.dp),
            dragPolicy = MovePolicy(),
        ) { MainContent() }

        SpatialPanel(
            modifier = SubspaceModifier.width(400.dp).height(700.dp),
            dragPolicy = MovePolicy(),
        ) { DetailPanel() }
    }
}

// Curved layout — recommended when arranging many panels at once
@OptIn(ExperimentalJetpackXrApi::class)
@SubspaceComposable
@Composable
fun CurvedPanelLayout() {
    SpatialCurvedRow(curveRadius = 825.dp) {
        // panels arc around the user at eye level
    }
}
```

---

## 3D Content — Volume and GltfModel

**3D content only renders when the app is in Full Space mode.**

```kotlin
@OptIn(ExperimentalJetpackXrApi::class)
@SubspaceComposable
@Composable
fun WordScene(word: Word) {
    val session = LocalSession.current

    SpatialPanel(
        modifier = SubspaceModifier.width(400.dp).height(400.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(word.original, style = MaterialTheme.typography.displayMedium)
        }
    }

    // Volume places SceneCore entities in 3D space — ignored outside Full Space
    if (session != null) {
        val gltfEntity = remember(session) {
            val model = GltfModel.create(session, Paths.get("models", "word_card.glb"))
            GltfModelEntity.create(session, model)
        }

        Volume(
            modifier = SubspaceModifier
                .offset(x = 0.dp, y = (-200).dp, z = (-100).dp),
        ) {
            SceneCoreEntity(entity = gltfEntity)
        }
    }
}
```

Play glTF animations:
```kotlin
gltfEntity.startAnimation(loop = true, animationName = "Idle")
```

Supported glTF 2.0 extensions: Draco compression, KTX2/basis textures, clearcoat, sheen, unlit, mesh variants.

---

## Spatial UI — SpatialElevation, SpatialDialog, SpatialPopup

All three fall back gracefully to their 2D equivalents on non-XR or Home Space.

```kotlin
// SpatialElevation — adds z-depth; no-op on 2D
@OptIn(ExperimentalJetpackXrApi::class)
@Composable
fun ElevatedCard() {
    SpatialElevation(elevationLevel = SpatialElevationLevel.Level4) {
        Card { CardContent() }
    }
}

// SpatialDialog — pushes panel back in Z and overlays dialog; falls back to Dialog on 2D
@OptIn(ExperimentalJetpackXrApi::class)
@Composable
fun ConfirmDeleteDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    SpatialDialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(24.dp)) {
                Text("Delete this word?")
                Row {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = onConfirm) { Text("Delete") }
                }
            }
        }
    }
}

// SpatialPopup — elevated floating content; falls back to Popup on 2D
@OptIn(ExperimentalJetpackXrApi::class)
@Composable
fun ContextMenu(onDismiss: () -> Unit) {
    SpatialPopup(onDismissRequest = onDismiss) {
        DropdownMenuContent()
    }
}
```

---

## Environment API

```kotlin
@OptIn(ExperimentalJetpackXrApi::class)
@Composable
fun XrEnvironmentController() {
    val session = LocalSession.current
    if (session != null) {
        LaunchedEffect(session) {
            // 0f = fully virtual, 1f = fully transparent (passthrough)
            session.scene.spatialEnvironment.preferredPassthroughOpacity = 0.8f

            // Switch to immersive Full Space
            session.scene.requestFullSpaceMode()
        }
    }
}

// Return to Home Space (mixed reality with system chrome)
@OptIn(ExperimentalJetpackXrApi::class)
@Composable
fun ReturnToHomeSpace() {
    val session = LocalSession.current
    if (session != null) {
        LaunchedEffect(session) {
            session.scene.requestHomeSpaceMode()
        }
    }
}
```

---

## Space Mode Transitions

`LocalSpatialCapabilities` updates automatically when the user switches modes. React to it:

```kotlin
@OptIn(ExperimentalJetpackXrApi::class)
@Composable
fun SpaceAwareContent() {
    val capabilities = LocalSpatialCapabilities.current
    val session = LocalSession.current

    // Recomposes whenever capabilities change (e.g., user leaves Full Space)
    if (capabilities.isSpatialUiEnabled) {
        Subspace { SpatialContent() }
    } else {
        FlatContent()
    }
}

// Material3 XR provides a built-in Full/Home Space toggle button
@OptIn(ExperimentalJetpackXrApi::class)
@Composable
fun SpaceToggle() {
    val capabilities = LocalSpatialCapabilities.current
    val session = LocalSession.current

    SpaceToggleButton(
        isFullSpace = capabilities.isFullSpaceEnabled,
        onClick = {
            if (capabilities.isFullSpaceEnabled) {
                session?.scene?.requestHomeSpaceMode()
            } else {
                session?.scene?.requestFullSpaceMode()
            }
        },
    )
}
```

---

## Spatial Audio

```kotlin
class SpatialAudioPlayer(private val context: Context) {

    private val mediaPlayer = MediaPlayer()

    fun playSpatial(uri: Uri) {
        mediaPlayer.apply {
            reset()
            setDataSource(context, uri)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO)
                    .build()
            )
            setOnPreparedListener { start() }
            prepareAsync()  // non-blocking
        }
    }

    fun release() {
        mediaPlayer.release()
    }
}
```

---

## Input — Eye Gaze

```kotlin
@OptIn(ExperimentalJetpackXrApi::class)
@Composable
fun GazeAwareButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var isGazeFocused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .onGazeFocusChanged { event ->
                isGazeFocused = event.isFocused
            }
            .scale(if (isGazeFocused) 1.1f else 1f)
            .clickable(onClick = onClick),
    ) {
        content()
    }
}
```

---

## 2D → XR Migration Pattern

```kotlin
// Phase 1: No changes — works in compat mode (flat window in space)

// Phase 2: Add SpatialPanel wrapper to main screens
@OptIn(ExperimentalJetpackXrApi::class)
@Composable
fun App() {
    if (LocalSpatialCapabilities.current.isSpatialUiEnabled) {
        Subspace {
            SpatialPanel(
                modifier = SubspaceModifier.width(1280.dp).height(800.dp),
                dragPolicy = MovePolicy(),
            ) {
                AppNavigation()  // existing navigation, unchanged
            }
        }
    } else {
        AppNavigation()
    }
}

// Phase 3: Add companion panels via SpatialRow / SpatialColumn
// Phase 4: Add 3D models (Volume + GltfModel) and spatial audio for key interactions
// Phase 5: Full immersive XR — Full Space, environments, spatial layouts
```

**Material3 XR adaptive components** map existing UI to XR automatically:

| Component | XR behaviour |
|---|---|
| `NavigationRail` | Becomes an `Orbiter` on the panel edge |
| `ListDetailPaneScaffold` | Each pane becomes its own `SpatialPanel` |
| `SupportingPaneScaffold` | Main + supporting panes as spatial panels |

---

## Testing

Tag spatial elements via `SubspaceModifier.testTag()` (the `name` parameter was removed in alpha05):

```kotlin
SpatialPanel(
    modifier = SubspaceModifier.width(800.dp).testTag("main-panel"),
    dragPolicy = MovePolicy(),
) { ... }
```

Provide fake capabilities in Compose tests:

```kotlin
@OptIn(ExperimentalJetpackXrApi::class)
@Test
fun spatialUiShowsWhenEnabled() {
    composeTestRule.setContent {
        CompositionLocalProvider(
            LocalSpatialCapabilities provides SpatialCapabilities(isSpatialUiEnabled = true),
        ) {
            App()
        }
    }
    composeTestRule.onNodeWithTag("main-panel").assertIsDisplayed()
}
```

---

## XR Design Principles

- **Comfort distance**: Place primary content at **1.5m** from user's eyes (center of 41° comfortable FOV)
- **Panel corner radius**: Use **32dp** rounded corners for spatial panels
- **Touch targets**: Minimum **56dp** (48dp absolute minimum) — hand tracking is less precise than touch
- **Spatial hierarchy**: Use Z-offset for importance — primary panel at 0, secondary at −50…−100dp
- **Movable by default**: All `SpatialPanel`s should have `dragPolicy = MovePolicy()` — users personalize their space
- **Graceful 2D fallback**: Always check `LocalSpatialCapabilities.current.isSpatialUiEnabled` before entering Subspace
- **No floating text without panels**: Bare text in 3D space is hard to read — anchor to panels
- **Orbiter for controls**: Toolbars and controls as Orbiters stay accessible wherever the panel moves
- **Avoid depth overload**: Max 3 depth planes; more causes discomfort
- **3D content = Full Space only**: `Volume` / `GltfModel` are silently ignored outside Full Space
