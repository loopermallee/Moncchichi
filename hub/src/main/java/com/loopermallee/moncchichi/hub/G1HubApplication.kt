package com.loopermallee.moncchichi.hub

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.loopermallee.moncchichi.service.G1DisplayService
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class G1HubApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val intent = Intent(this, G1DisplayService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}
