package com.loopermallee.moncchichi.hub.tools.impl

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.loopermallee.moncchichi.hub.tools.TtsTool
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val TTS_TAG = "TtsTool"

class TtsToolImpl(context: Context) : TtsTool, TextToSpeech.OnInitListener {

    private val ready = AtomicBoolean(false)
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val langStatus = tts.setLanguage(Locale.US)
            if (langStatus == TextToSpeech.LANG_MISSING_DATA || langStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TTS_TAG, "English locale not supported; continuing with default")
            }
            ready.set(true)
        } else {
            Log.e(TTS_TAG, "Initialization failed: status=$status")
        }
    }

    override fun speak(text: String) {
        if (!ready.get()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
    }

    override fun stop() {
        if (!ready.get()) return
        tts.stop()
    }

    override fun isReady(): Boolean = ready.get()
}
