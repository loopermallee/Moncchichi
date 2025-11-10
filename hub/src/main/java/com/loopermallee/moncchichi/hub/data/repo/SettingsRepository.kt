package com.loopermallee.moncchichi.hub.data.repo

import android.content.Context
import android.content.SharedPreferences
import com.loopermallee.moncchichi.hub.audio.AudioSink
import com.loopermallee.moncchichi.hub.audio.MicSource
import com.loopermallee.moncchichi.hub.di.AppLocator
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn

private const val PREF_FILE_NAME = "moncchichi_settings"
private const val KEY_TELEPROMPTER_TEXT = "teleprompter:text"
private const val KEY_TELEPROMPTER_SPEED = "teleprompter:speed"
private const val KEY_TELEPROMPTER_MIRROR = "teleprompter:mirror"
private const val KEY_MIC_ENABLED = "mic_enabled"
private const val KEY_VOICE_WAKE_ON_LIFT = "voice_wake_on_lift"
private const val KEY_MIC_SOURCE = "audio:mic_source"
private const val KEY_AUDIO_SINK = "audio:audio_sink"
private const val KEY_AUDIBLE_RESPONSES = "audio:audible_responses"
private const val KEY_PREFER_PHONE_MIC = "audio:prefer_phone_mic"
private const val DEFAULT_SPEED = 48

object SettingsRepository {

    private val prefs: SharedPreferences by lazy {
        AppLocator.applicationContext.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val preferenceChanges = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            trySend(key).isSuccess
        }
        trySend(null).isSuccess
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.shareIn(scope, SharingStarted.Eagerly, replay = 1)

    val micSourceFlow: Flow<MicSource> = preferenceChanges
        .filter { it == null || it == KEY_MIC_SOURCE }
        .map { getMicSource() }
        .onStart { emit(getMicSource()) }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    val audioSinkFlow: Flow<AudioSink> = preferenceChanges
        .filter { it == null || it == KEY_AUDIO_SINK }
        .map { getAudioSink() }
        .onStart { emit(getAudioSink()) }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    val audibleResponsesFlow: Flow<Boolean> = preferenceChanges
        .filter { it == null || it == KEY_AUDIBLE_RESPONSES }
        .map { isAudibleResponsesEnabled() }
        .onStart { emit(isAudibleResponsesEnabled()) }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    val preferPhoneMicFlow: Flow<Boolean> = preferenceChanges
        .filter { it == null || it == KEY_PREFER_PHONE_MIC }
        .map { isPreferPhoneMicEnabled() }
        .onStart { emit(isPreferPhoneMicEnabled()) }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    fun getTeleprompterText(): String =
        prefs.getString(KEY_TELEPROMPTER_TEXT, "").orEmpty()

    fun setTeleprompterText(value: String) {
        prefs.edit().putString(KEY_TELEPROMPTER_TEXT, value).apply()
    }

    fun getTeleprompterSpeed(): Float =
        prefs.getInt(KEY_TELEPROMPTER_SPEED, DEFAULT_SPEED).toFloat()

    fun setTeleprompterSpeed(value: Float) {
        prefs.edit().putInt(KEY_TELEPROMPTER_SPEED, value.roundToInt()).apply()
    }

    fun getTeleprompterMirror(): Boolean =
        prefs.getBoolean(KEY_TELEPROMPTER_MIRROR, false)

    fun setTeleprompterMirror(value: Boolean) {
        prefs.edit().putBoolean(KEY_TELEPROMPTER_MIRROR, value).apply()
    }

    fun isMicEnabled(): Boolean = prefs.getBoolean(KEY_MIC_ENABLED, false)

    fun setMicEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_MIC_ENABLED, value).apply()
    }

    fun isVoiceWakeOnLiftEnabled(): Boolean = prefs.getBoolean(KEY_VOICE_WAKE_ON_LIFT, false)

    fun setVoiceWakeOnLiftEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE_WAKE_ON_LIFT, value).apply()
    }

    fun isAudibleResponsesEnabled(): Boolean = prefs.getBoolean(KEY_AUDIBLE_RESPONSES, true)

    fun setAudibleResponsesEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUDIBLE_RESPONSES, value).apply()
    }

    fun isPreferPhoneMicEnabled(): Boolean = prefs.getBoolean(KEY_PREFER_PHONE_MIC, false)

    fun setPreferPhoneMicEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_PREFER_PHONE_MIC, value).apply()
    }

    fun getMicSource(): MicSource {
        val stored = prefs.getString(KEY_MIC_SOURCE, null)
        return stored?.let { value ->
            runCatching { MicSource.valueOf(value.uppercase(Locale.US)) }.getOrNull()
        } ?: MicSource.GLASSES
    }

    fun setMicSource(value: MicSource) {
        prefs.edit().putString(KEY_MIC_SOURCE, value.name.lowercase(Locale.US)).apply()
    }

    fun getAudioSink(): AudioSink {
        val stored = prefs.getString(KEY_AUDIO_SINK, null)
        return stored?.let { value ->
            runCatching { AudioSink.valueOf(value.uppercase(Locale.US)) }.getOrNull()
        } ?: AudioSink.GLASSES
    }

    fun setAudioSink(value: AudioSink) {
        prefs.edit().putString(KEY_AUDIO_SINK, value.name.lowercase(Locale.US)).apply()
    }
}
