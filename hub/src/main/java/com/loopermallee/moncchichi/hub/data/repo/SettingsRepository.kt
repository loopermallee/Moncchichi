package com.loopermallee.moncchichi.hub.data.repo

import android.content.Context
import android.content.SharedPreferences
import com.loopermallee.moncchichi.hub.di.AppLocator
import kotlin.math.roundToInt

private const val PREF_FILE_NAME = "moncchichi_settings"
private const val KEY_TELEPROMPTER_TEXT = "teleprompter:text"
private const val KEY_TELEPROMPTER_SPEED = "teleprompter:speed"
private const val KEY_TELEPROMPTER_MIRROR = "teleprompter:mirror"
private const val DEFAULT_SPEED = 48

object SettingsRepository {

    private val prefs: SharedPreferences by lazy {
        AppLocator.applicationContext.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
    }

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
}
