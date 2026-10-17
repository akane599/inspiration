plugins {
    id("inspiration.android.library")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    namespace = "com.akane.inspiration.shizukuipc"
    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)
    implementation(libs.kotlinx.coroutines.core)
    compileOnly(project(":core:hidden-api-stub"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
