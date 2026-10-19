plugins {
    id("inspiration.android.library")
    alias(libs.plugins.kotlin.parcelize)
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
    // api, not implementation, on purpose (SQ-109; reviewed in audit §3): only the app manifest names
    // ShizukuProvider, and :app lint must resolve that class through this module.
    api(libs.shizuku.provider)
    compileOnly(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.core)
    compileOnly(project(":core:hidden-api-stub"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
