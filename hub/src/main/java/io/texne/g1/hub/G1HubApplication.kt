package io.texne.g1.hub

import android.app.Application
import com.loopermallee.moncchichi.CrashHandler
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent

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
