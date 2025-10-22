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

        testInstrumentationRunner = "android.support.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = false
    }

    buildTypes {
        debug {
            // ⚡ Fast debug builds without shrinking
            isMinifyEnabled = false
        }
        release {
            // 🧩 Keep release artifacts optimized via R8 (resource shrinker unsupported for libraries)
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
    // BLE
    implementation(libs.nordic.ble.ktx)
    implementation(libs.nordic.ble.common)
    implementation(libs.nordic.scanner)
    implementation(libs.material)
    implementation(libs.coroutines.android)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
