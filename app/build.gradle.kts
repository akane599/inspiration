plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.screenshot)
}

android {
    namespace = "com.akane.inspiration"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.akane.inspiration"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1"
    }

    // Release signing comes from user-level Gradle properties (~/.gradle/gradle.properties);
    // without them the release APK is built unsigned.
    val releaseStoreFile = providers.gradleProperty("INSPIRATION_RELEASE_STORE_FILE").orNull
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("INSPIRATION_RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("INSPIRATION_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("INSPIRATION_RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
}

dependencies {
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:xray"))
    implementation(project(":feature:sleeplab"))
    implementation(project(":feature:health"))
    implementation(project(":feature:toolkit"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:shizuku-ipc"))
    implementation(project(":core:telemetry-engine"))
    implementation(project(":core:database"))
    implementation(project(":core:diagnostics"))
    implementation(libs.hiddenapibypass)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.collections.immutable)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.shizuku.api)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}
