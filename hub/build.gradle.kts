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
        applicationId = "com.loopermallee.moncchichi"
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
        viewBinding = true // Generate binding classes for XML-backed screens
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.icons.extended)
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    debugImplementation("androidx.compose.ui:ui-tooling")

    kapt(libs.hilt.android.compiler)
}
