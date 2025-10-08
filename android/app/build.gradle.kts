plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.loopermallee.moncchichi"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    sourceSets["main"].manifest.srcFile("src/main/AndroidManifest.xml")
}
