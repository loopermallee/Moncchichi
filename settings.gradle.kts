pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "basis"
include(":core", ":service", ":hub", ":client", ":aidl", ":subtitles")

gradle.beforeProject {
    // === Gradle & Kotlin Performance Optimization ===
    // Converts all properties to String form (Gradle 8.10+ requires string values).

    extensions.extraProperties["org.gradle.jvmargs"] =
        "-Xmx4g -Dfile.encoding=UTF-8 -XX:+UseParallelGC -XX:+HeapDumpOnOutOfMemoryError"

    // Enable incremental Kotlin builds (must be string)
    extensions.extraProperties["kotlin.incremental"] = "true"

    // Allocate more heap space for the Kotlin daemon
    extensions.extraProperties["kotlin.daemon.jvmargs"] = "-Xmx2g"

    // Speed up resource linking
    extensions.extraProperties["android.nonTransitiveRClass"] = "true"
}
