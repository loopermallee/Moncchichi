// Top-level build file where you can add configuration options common to all sub-projects/modules.
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.configure<KotlinAndroidProjectExtension>("kotlin") {
            jvmToolchain(17)
        }
    }

    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension>("android") {
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }

    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension>("android") {
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
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
