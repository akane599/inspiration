plugins {
    id("inspiration.android.library")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    namespace = "com.akane.inspiration.shizukuipc"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures {
        aidl = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.shizuku.api)
    api(libs.shizuku.provider)
    compileOnly("androidx.annotation:annotation:1.3.0")
    implementation(libs.kotlinx.coroutines.core)
    compileOnly(project(":core:hidden-api-stub"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
