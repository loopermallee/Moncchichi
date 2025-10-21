plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.loopermallee.moncchichi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.loopermallee.moncchichi.subtitles"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    buildTypes {
        debug {
            // ⚡ Fast debug builds for rapid CI/local iteration
            isMinifyEnabled = false
        }
        release {
            // 🧩 Production-ready build stays optimized via R8 (resource shrinker unsupported for this module)
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
    // Internal modules
    implementation(project(":core"))
    implementation(project(":client"))
    implementation(project(":service"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.material)
    implementation(libs.androidx.material3)
    implementation(libs.coroutines.android)
    implementation(libs.nabinbhandari.permissions)
    implementation(libs.androidx.icons.extended)

    debugImplementation("androidx.compose.ui:ui-tooling")
}
