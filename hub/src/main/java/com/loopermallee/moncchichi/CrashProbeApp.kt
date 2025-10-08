package com.loopermallee.moncchichi

import android.app.Application
import android.util.Log

class CrashProbeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("CrashProbe", "✅ CrashProbeApp started successfully")
    }
}
