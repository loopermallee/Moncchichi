package com.loopermallee.moncchichi

import android.app.Application

class MoncchichiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            CrashHandler.init(this)
        }
    }
}
