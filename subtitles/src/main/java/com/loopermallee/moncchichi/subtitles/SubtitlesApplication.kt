package com.loopermallee.moncchichi.subtitles

import android.app.Application
import android.content.Context
import com.loopermallee.moncchichi.subtitles.model.Recognizer
import com.loopermallee.moncchichi.subtitles.model.Repository

class SubtitlesApplication : Application() {

    lateinit var appContainer: SubtitlesAppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = SubtitlesAppContainer(this)
    }
}

class SubtitlesAppContainer(context: Context) {
    private val applicationContext = context.applicationContext

    private val recognizer = Recognizer(applicationContext)
    val repository = Repository(applicationContext, recognizer)
}
