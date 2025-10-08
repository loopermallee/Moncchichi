package com.loopermallee.moncchichi.hub

import android.app.Application
import com.loopermallee.moncchichi.CrashHandler
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import com.loopermallee.moncchichi.hub.BuildConfig

@Module
@InstallIn(SingletonComponent::class)
object GlobalModule

@HiltAndroidApp
class G1HubApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            CrashHandler.init(this)
        }
    }
}
