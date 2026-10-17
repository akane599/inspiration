plugins {
    id("inspiration.android.library")
}

android {
    namespace = "com.akane.inspiration.telemetry"
}

dependencies {
    api(project(":core:shizuku-ipc"))

    testImplementation(libs.junit)
}
