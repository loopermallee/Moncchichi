package com.loopermallee.moncchichi.hub.ui.hud

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.loopermallee.moncchichi.hub.model.Repository
import okhttp3.OkHttpClient

class HudVmFactory(
    private val context: Context,
    private val repository: Repository,
    private val prefs: SharedPreferences,
    private val httpClient: OkHttpClient,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(HudViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return HudViewModel(
            appContext = context,
            repository = repository,
            prefs = prefs,
            httpClient = httpClient,
        ) as T
    }
}
