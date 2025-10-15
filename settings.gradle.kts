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
    // Improves CI and Codex build consistency by increasing memory limits
    // and enabling incremental compilation.

    extensions.extraProperties["org.gradle.jvmargs"] =
        "-Xmx4g -Dfile.encoding=UTF-8 -XX:+UseParallelGC -XX:+HeapDumpOnOutOfMemoryError"

    // Enable Kotlin incremental builds to reduce compilation time
    extensions.extraProperties["kotlin.incremental"] = true

    // Allocate more heap space for the Kotlin daemon
    extensions.extraProperties["kotlin.daemon.jvmargs"] = "-Xmx2g"

    // Generate non-transitive R classes for faster resource linking
    extensions.extraProperties["android.nonTransitiveRClass"] = true
}
