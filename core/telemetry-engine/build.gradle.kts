plugins {
    id("inspiration.android.library")
    alias(libs.plugins.protobuf.gradle)
}

android {
    namespace = "com.akane.inspiration.telemetry"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                register("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    api(project(":core:shizuku-ipc"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.protobuf.javalite)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
