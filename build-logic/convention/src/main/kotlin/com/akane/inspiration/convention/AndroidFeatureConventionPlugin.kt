package com.akane.inspiration.convention

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

/**
 * Shared setup of every `:feature:*` module: designsystem, the core modules, MVI/Compose runtime
 * dependencies, JUnit + coroutines-test, and the Compose preview screenshot test setup (same as
 * `:core:designsystem`). Features never depend on `:app` or on each other.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("inspiration.android.library.compose")
        pluginManager.apply("com.android.compose.screenshot")

        extensions.configure<LibraryExtension> {
            experimentalProperties["android.experimental.enableScreenshotTest"] = true
        }

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies.apply {
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:telemetry-engine"))
            add("implementation", project(":core:diagnostics"))
            add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
            add("implementation", libs.findLibrary("kotlinx-collections-immutable").get())
            add("implementation", libs.findLibrary("kotlinx-coroutines-core").get())
            add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())

            add("testImplementation", libs.findLibrary("junit").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())

            add("screenshotTestImplementation", libs.findLibrary("screenshot-validation-api").get())
            add("screenshotTestImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
        }
        Unit
    }
}
