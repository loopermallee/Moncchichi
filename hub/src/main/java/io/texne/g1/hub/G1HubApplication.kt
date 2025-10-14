package com.loopermallee.moncchichi.hub

import android.app.Application
import android.util.Log
import com.loopermallee.moncchichi.CrashHandler
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import com.loopermallee.moncchichi.hub.BuildConfig
import com.loopermallee.moncchichi.hub.R

@Module
@InstallIn(SingletonComponent::class)
object GlobalModule

@HiltAndroidApp
class G1HubApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            val appLabel = getString(R.string.app_name)
            Log.i("G1HubApplication", "$appLabel enabling crash handler")
            CrashHandler.init(this)
        }
    }
}
