package com.loopermallee.moncchichi.hub.data

import android.content.SharedPreferences

private const val KEY_TELEPROMPTER_TEXT = "teleprompter:text"
private const val KEY_TELEPROMPTER_SPEED = "teleprompter:speed"
private const val KEY_TELEPROMPTER_MIRROR = "teleprompter:mirror"
private const val DEFAULT_SPEED = 48f

class SettingsRepository(private val prefs: SharedPreferences) {

    fun teleprompterText(): String =
        prefs.getString(KEY_TELEPROMPTER_TEXT, "").orEmpty()

    fun teleprompterSpeed(): Float =
        prefs.getFloat(KEY_TELEPROMPTER_SPEED, DEFAULT_SPEED)

    fun teleprompterMirror(): Boolean =
        prefs.getBoolean(KEY_TELEPROMPTER_MIRROR, false)

    fun updateTeleprompterText(text: String) {
        prefs.edit().putString(KEY_TELEPROMPTER_TEXT, text).apply()
    }

    fun updateTeleprompterSpeed(speed: Float) {
        prefs.edit().putFloat(KEY_TELEPROMPTER_SPEED, speed).apply()
    }

    fun updateTeleprompterMirror(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TELEPROMPTER_MIRROR, enabled).apply()
    }
}
