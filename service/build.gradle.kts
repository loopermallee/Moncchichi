plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.loopermallee.moncchichi"
    compileSdk = 35

    defaultConfig {
        minSdk = 23

        aarMetadata {
            minCompileSdk = 33
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = false        // ✅ avoid duplicate BuildConfig when sharing the app namespace
        androidResources = true   // ✅ allows generation of R class for service
    }

    buildTypes {
        debug {
            // ⚡ Fast debug builds: no code shrinking/resources stripping
            isMinifyEnabled = false
        }
        release {
            // 🧩 Production builds remain optimized via R8 (resource shrinker unsupported for libraries)
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

    lint {
        abortOnError = false
    }
}

dependencies {
    // ✅ Core Android libraries
    implementation("androidx.core:core-ktx:1.13.1")
    implementation(libs.coroutines.android)
    implementation(libs.nabinbhandari.permissions)
    implementation(libs.androidx.datastore)

    // ✅ Moncchichi internal modules (no circular dependency)
    implementation(project(":core"))
    implementation(project(":client"))
    implementation(project(":aidl"))

    // ✅ Optional helpers (service notifications, workers)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
