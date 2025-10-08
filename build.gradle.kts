// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
}

tasks.register("validateManifests") {
    doLast {
        fileTree(rootDir) {
            include("**/AndroidManifest.xml")
        }.forEach {
            println("Checking manifest: ${it.path}")
            val text = it.readText()
            if ("<application" !in text) {
                println("⚠️ Missing <application> tag in ${it.path}")
            }
            if (text.contains("android:name=\"android.app.Application\"")) {
                println("⚠️ Uses system Application instead of custom MoncchichiApp: ${it.path}")
            }
        }
    }
}
