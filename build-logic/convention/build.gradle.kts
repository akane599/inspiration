plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.compose.compiler.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "inspiration.android.library"
            implementationClass = "com.akane.inspiration.convention.AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "inspiration.android.library.compose"
            implementationClass = "com.akane.inspiration.convention.AndroidLibraryComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "inspiration.android.feature"
            implementationClass = "com.akane.inspiration.convention.AndroidFeatureConventionPlugin"
        }
    }
}
