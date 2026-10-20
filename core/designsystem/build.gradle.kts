plugins {
    id("inspiration.android.library.compose")
    alias(libs.plugins.compose.screenshot)
}

android {
    namespace = "com.akane.inspiration.designsystem"

    experimentalProperties["android.experimental.enableScreenshotTest"] = true
}

dependencies {
    // Public signatures expose MaterialTheme extensions, TextStyle, Color and ImmutableList.
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material3)
    api(libs.kotlinx.collections.immutable)

    implementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    // Drives the touch guard with real MotionEvents (ObscuredTouchGuardUiTest).
    testImplementation(libs.robolectric)

    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}
