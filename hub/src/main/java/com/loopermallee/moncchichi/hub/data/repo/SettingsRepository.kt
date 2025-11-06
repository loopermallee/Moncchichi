package com.loopermallee.moncchichi.hub.data.repo

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.roundToInt

private const val PREF_FILE_NAME = "moncchichi_settings"
private const val KEY_TELEPROMPTER_TEXT = "teleprompter:text"
private const val KEY_TELEPROMPTER_SPEED = "teleprompter:speed"
private const val KEY_TELEPROMPTER_MIRROR = "teleprompter:mirror"
private const val DEFAULT_SPEED = 48

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)

    fun getTeleprompterText(): String =
        prefs.getString(KEY_TELEPROMPTER_TEXT, "").orEmpty()

    fun setTeleprompterText(value: String) {
        prefs.edit().putString(KEY_TELEPROMPTER_TEXT, value).apply()
    }

    fun getTeleprompterSpeed(): Int =
        prefs.getInt(KEY_TELEPROMPTER_SPEED, DEFAULT_SPEED)

    fun setTeleprompterSpeed(value: Int) {
        prefs.edit().putInt(KEY_TELEPROMPTER_SPEED, value).apply()
    }

    fun getTeleprompterMirror(): Boolean =
        prefs.getBoolean(KEY_TELEPROMPTER_MIRROR, false)

    fun setTeleprompterMirror(value: Boolean) {
        prefs.edit().putBoolean(KEY_TELEPROMPTER_MIRROR, value).apply()
    }

    fun teleprompterText(): String = getTeleprompterText()

    fun updateTeleprompterText(value: String) = setTeleprompterText(value)

    fun teleprompterSpeed(): Float = getTeleprompterSpeed().toFloat()

    fun updateTeleprompterSpeed(value: Float) = setTeleprompterSpeed(value.roundToInt())

    fun teleprompterMirror(): Boolean = getTeleprompterMirror()

    fun updateTeleprompterMirror(value: Boolean) = setTeleprompterMirror(value)
}
