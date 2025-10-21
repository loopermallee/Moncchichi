package com.loopermallee.moncchichi.subtitles

import android.app.Application
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object GlobalModule

@HiltAndroidApp
class SubtitlesApplication : Application() {
}
