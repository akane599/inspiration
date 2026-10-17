package com.akane.inspiration.convention

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("inspiration.android.library.compose")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies.apply {
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:telemetry-engine"))
            add("implementation", project(":core:diagnostics"))
            add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
            add("implementation", libs.findLibrary("kotlinx-collections-immutable").get())
        }
        Unit
    }
}
