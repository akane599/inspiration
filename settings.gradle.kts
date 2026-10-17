pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Inspiration"
include(":app")
include(":core:hidden-api-stub")
include(":core:shizuku-ipc")
include(":core:telemetry-engine")
include(":core:diagnostics")
include(":core:database")
include(":core:designsystem")
include(":feature:dashboard")
include(":feature:xray")
include(":feature:sleeplab")
include(":feature:health")
include(":feature:toolkit")
