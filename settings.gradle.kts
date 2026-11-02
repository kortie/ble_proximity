pluginManagement {
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

rootProject.name = "ble-proximity"

include(
    ":proximity-core",
    ":proximity-crypto",
    ":proximity-testing",
    ":proximity-platform",
    ":sample-android",
)
