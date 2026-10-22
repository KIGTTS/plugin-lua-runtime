pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("com.android.library") version "8.13.2"
        kotlin("android") version "2.2.21"
        kotlin("jvm") version "2.2.21"
        kotlin("plugin.serialization") version "2.2.21"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "lua-runtime"
include(":plugin-api", ":plugin-android-api")
project(":plugin-api").projectDir = file(".sdk/plugin-api")
project(":plugin-android-api").projectDir = file(".sdk/plugin-android-api")
