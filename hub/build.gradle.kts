plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
    alias(libs.plugins.hilt.android)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.loopermallee.moncchichi.hub"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.loopermallee.moncchichi.hub"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val crashToken = (System.getenv("GITHUB_TOKEN") ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "GITHUB_TOKEN", "\"$crashToken\"")
    }

    kapt {
        correctErrorTypes = true
    }
    hilt {
        enableAggregatingTask = false
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    buildTypes {
        debug {
            // ⚡️ Faster CI & development builds
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            // Release builds remain optimized for distribution
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":service"))
    implementation(project(":client"))
    implementation(project(":aidl"))

    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.icons.extended)
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation("androidx.preference:preference-ktx:1.2.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    kapt(libs.hilt.android.compiler)
    kapt("androidx.room:room-compiler:2.6.1")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
