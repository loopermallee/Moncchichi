package com.loopermallee.moncchichi

import android.app.Application
import android.util.Log
import com.loopermallee.moncchichi.BuildConfig

class MoncchichiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            MoncchichiCrashReporter.init(this) {
                BuildConfig.GITHUB_TOKEN.takeIf { it.isNotBlank() }
            }
        }.onFailure { error ->
            Log.e("AppBoot", "Failed to initialize crash reporter", error)
        }
        Log.i("AppBoot", "MoncchichiApp initialized safely.")
    }
}
