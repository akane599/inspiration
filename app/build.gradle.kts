plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.screenshot)
}

// Release signing comes from user-level Gradle properties (~/.gradle/gradle.properties) and is all or
// nothing: all four set signs the release APK, none set builds it unsigned. A partial set (a blank value
// counts as unset) must not break configuration, so debug builds and tests still run; only release
// packaging fails, naming the missing keys (never values).
val releaseSigningKeys =
    listOf(
        "INSPIRATION_RELEASE_STORE_FILE",
        "INSPIRATION_RELEASE_STORE_PASSWORD",
        "INSPIRATION_RELEASE_KEY_ALIAS",
        "INSPIRATION_RELEASE_KEY_PASSWORD",
    )
val releaseSigningValues = releaseSigningKeys.map { providers.gradleProperty(it).orNull?.takeIf(String::isNotBlank) }
val missingReleaseSigningKeys = releaseSigningKeys.filterIndexed { index, _ -> releaseSigningValues[index] == null }

android {
    namespace = "com.akane.inspiration"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.akane.inspiration"
        minSdk = 31
        targetSdk = 36
        versionCode = 9
        versionName = "0.2.7"
    }

    signingConfigs {
        if (missingReleaseSigningKeys.isEmpty()) {
            val (storePath, storeSecret, alias, keySecret) = releaseSigningValues.filterNotNull()
            create("release") {
                storeFile = file(storePath)
                storePassword = storeSecret
                keyAlias = alias
                keyPassword = keySecret
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // AGP writes META-INF/version-control-info.textproto itself; a packaging exclude does not remove it.
            vcsInfo.include = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    androidResources {
        // The app ships English (default values) and Turkish. Dropping the libraries' other translations
        // keeps Material3's built-in text in the same language as the app's own text.
        localeFilters += listOf("en", "tr")
    }
    packaging {
        resources {
            // The lite protobuf runtime needs no .proto sources or descriptor sets, DebugProbesKt.bin serves
            // only the JVM coroutines debug agent.
            excludes += listOf("**/*.proto", "**/*.proto.bin", "DebugProbesKt.bin")
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

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        // Library version markers are read by Android Studio's App Inspection, which needs a debuggable build.
        variant.packaging.resources.excludes
            .add("META-INF/*.version")
    }
}

if (missingReleaseSigningKeys.isNotEmpty() && missingReleaseSigningKeys.size < releaseSigningKeys.size) {
    val incompleteSigningMessage =
        "Release signing needs all of ${releaseSigningKeys.joinToString()}, but these are missing or blank: " +
            "${missingReleaseSigningKeys.joinToString()}. Set them, or remove all of them to build an unsigned release."
    val failIncompleteReleaseSigning =
        tasks.register("failIncompleteReleaseSigning") {
            description = "Fails release packaging while only some release-signing Gradle properties are set."
            doLast { throw GradleException(incompleteSigningMessage) }
        }
    val releasePackagingTasks =
        setOf("packageRelease", "packageReleaseBundle", "signReleaseBundle", "packageReleaseUniversalApk")
    tasks.named { it in releasePackagingTasks }.configureEach { dependsOn(failIncompleteReleaseSigning) }
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
