package com.loopermallee.moncchichi.hub.ui.hud

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.client.G1ServiceCommon
import com.loopermallee.moncchichi.hub.data.telemetry.BleTelemetryRepository
import com.loopermallee.moncchichi.hub.model.Repository
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class HudViewModel(
    private val appContext: Context,
    private val repository: Repository,
    private val prefs: SharedPreferences,
    private val httpClient: OkHttpClient,
    private val telemetry: BleTelemetryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HudUiState())
    val uiState: StateFlow<HudUiState> = _uiState

    private var weatherJob: Job? = null

    init {
        loadConfig()
        bindRepository()
        observeRepository()
        observeTelemetry()
        observeNotifications()
        refreshPermissions()
        scheduleWeatherRefresh()
    }

    fun refreshPermissions() {
        val listenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(appContext)
            .contains(appContext.packageName)
        val postNotificationsGranted = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        _uiState.update {
            it.copy(
                notificationListenerEnabled = listenerEnabled,
                postNotificationPermissionGranted = postNotificationsGranted,
            )
        }
    }

    fun onPostNotificationPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(postNotificationPermissionGranted = granted) }
    }

    fun updateSelectedTarget(targetId: String?) {
        _uiState.update { it.copy(selectedTargetId = targetId) }
    }

    fun toggleTile(tile: HudTile, enabled: Boolean) {
        val current = _uiState.value.hudConfig
        val updated = when (tile) {
            HudTile.TIME -> current.copy(showTime = enabled)
            HudTile.WEATHER -> current.copy(showWeather = enabled)
            HudTile.TEMPERATURE -> current.copy(showTemperature = enabled)
            HudTile.NOTIFICATIONS -> current.copy(showNotifications = enabled)
            HudTile.MIRROR -> current
        }
        persistConfig(updated)
    }

    fun moveTile(tile: HudTile, offset: Int) {
        val config = _uiState.value.hudConfig
        val order = config.tileOrder.toMutableList()
        val index = order.indexOf(tile)
        if (index == -1) return
        val targetIndex = (index + offset).coerceIn(0, order.lastIndex)
        if (index == targetIndex) return
        order.removeAt(index)
        order.add(targetIndex, tile)
        persistConfig(config.copy(tileOrder = order.toList()))
    }

    fun sendHudMessage(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(sendError = "Message cannot be empty") }
            return
        }
        val devices = currentTargets()
        if (devices.isEmpty()) {
            _uiState.update { it.copy(sendError = "No connected glasses available") }
            return
        }
        _uiState.update { it.copy(isSendingMessage = true, sendError = null) }
        viewModelScope.launch {
            val page = buildDisplayPage(trimmed)
            val results = devices.map { id ->
                runCatching { repository.displayTextPage(id, page) }.getOrDefault(false)
            }
            val success = results.all { it }
            val now = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    isSendingMessage = false,
                    sendError = if (success) null else "Failed to send HUD message",
                    lastMessageTimestamp = if (success) now else it.lastMessageTimestamp,
                    lastMessage = if (success) trimmed else it.lastMessage,
                    mirrorState = if (success) {
                        it.mirrorState.copy(
                            text = trimmed,
                            isDisplaying = true,
                            isPaused = false,
                            lastUpdatedMillis = now,
                        )
                    } else {
                        it.mirrorState
                    },
                )
            }
        }
    }

    fun stopHudMessage() {
        val devices = currentTargets()
        if (devices.isEmpty()) {
            _uiState.update { it.copy(sendError = "No connected glasses available") }
            return
        }
        viewModelScope.launch {
            val results = devices.map { id ->
                runCatching { repository.stopDisplaying(id) }.getOrDefault(false)
            }
            val success = results.all { it }
            val now = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    sendError = if (success) null else "Failed to stop display",
                    mirrorState = if (success) {
                        it.mirrorState.copy(
                            isDisplaying = false,
                            isPaused = false,
                            text = it.mirrorState.text,
                            lastUpdatedMillis = now,
                        )
                    } else {
                        it.mirrorState
                    },
                )
            }
        }
    }

    fun refreshWeather() {
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingWeather = true) }
            val snapshot = fetchWeather()
            _uiState.update {
                it.copy(
                    weather = snapshot ?: it.weather,
                    isRefreshingWeather = false,
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(sendError = null) }
    }

    override fun onCleared() {
        super.onCleared()
        repository.unbindService()
    }

    private fun observeRepository() {
        viewModelScope.launch {
            repository.getServiceStateFlow().collectLatest { state ->
                val status = when (state?.status) {
                    G1ServiceCommon.ServiceStatus.READY -> HudConnectionStatus.DISCONNECTED
                    G1ServiceCommon.ServiceStatus.LOOKING -> HudConnectionStatus.CONNECTING
                    G1ServiceCommon.ServiceStatus.LOOKED -> HudConnectionStatus.CONNECTED
                    G1ServiceCommon.ServiceStatus.ERROR -> HudConnectionStatus.ERROR
                    null -> HudConnectionStatus.DISCONNECTED
                }
                val connectedGlasses = state?.glasses
                    ?.filter { it.status == G1ServiceCommon.GlassesStatus.CONNECTED }
                    ?.mapNotNull { glasses ->
                        val id = glasses.id ?: return@mapNotNull null
                        val name = glasses.name?.takeIf { it.isNotBlank() } ?: "Glasses"
                        HudDevice(id, name)
                    } ?: emptyList()
                _uiState.update {
                    val selected = it.selectedTargetId?.takeIf { selectedId ->
                        connectedGlasses.any { device -> device.id == selectedId }
                    }
                    it.copy(
                        connectionStatus = status,
                        connectedDevices = connectedGlasses,
                        selectedTargetId = selected,
                    )
                }
            }
        }
    }

    private fun observeTelemetry() {
        viewModelScope.launch {
            combine(
                telemetry.wearingStatus,
                telemetry.inCaseStatus,
                telemetry.caseOpenStatus,
                telemetry.chargingStatus,
                telemetry.caseBatteryPercentStatus,
            ) { wearing, inCase, caseOpen, charging, caseBattery ->
                MoncchichiBleService.Lens.values().map { lens ->
                    HudLensStatus(
                        id = lens.name,
                        label = lensLabel(lens),
                        wearing = wearing.valueFor(lens),
                        inCase = inCase.valueFor(lens),
                        caseOpen = caseOpen.valueFor(lens),
                        charging = charging.valueFor(lens),
                        caseBatteryPercent = caseBattery.valueFor(lens),
                    )
                }
            }.collectLatest { statuses ->
                _uiState.update { it.copy(lensStatus = statuses) }
            }
        }
    }

    private fun lensLabel(lens: MoncchichiBleService.Lens): String {
        return when (lens) {
            MoncchichiBleService.Lens.LEFT -> "Left"
            MoncchichiBleService.Lens.RIGHT -> "Right"
        }
    }

    private fun observeNotifications() {
        viewModelScope.launch {
            HudNotificationCenter.notifications.collectLatest { notifications ->
                _uiState.update { it.copy(notifications = notifications) }
            }
        }
    }

    private fun loadConfig() {
        val showTime = prefs.getBoolean(KEY_SHOW_TIME, true)
        val showWeather = prefs.getBoolean(KEY_SHOW_WEATHER, true)
        val showTemperature = prefs.getBoolean(KEY_SHOW_TEMPERATURE, true)
        val showNotifications = prefs.getBoolean(KEY_SHOW_NOTIFICATIONS, true)
        val order = prefs.getString(KEY_TILE_ORDER, null)?.split(',')
            ?.mapNotNull { value -> runCatching { HudTile.valueOf(value) }.getOrNull() }
            ?.ifEmpty { null }
        val config = HudConfig(
            showTime = showTime,
            showWeather = showWeather,
            showTemperature = showTemperature,
            showNotifications = showNotifications,
            tileOrder = order ?: HudConfig().tileOrder,
        )
        _uiState.update { it.copy(hudConfig = config) }
    }

    private fun persistConfig(config: HudConfig) {
        prefs.edit()
            .putBoolean(KEY_SHOW_TIME, config.showTime)
            .putBoolean(KEY_SHOW_WEATHER, config.showWeather)
            .putBoolean(KEY_SHOW_TEMPERATURE, config.showTemperature)
            .putBoolean(KEY_SHOW_NOTIFICATIONS, config.showNotifications)
            .putString(KEY_TILE_ORDER, config.tileOrder.joinToString(",") { it.name })
            .apply()
        _uiState.update { it.copy(hudConfig = config) }
    }

    private fun scheduleWeatherRefresh() {
        refreshWeather()
        viewModelScope.launch {
            while (true) {
                delay(TimeUnit.MINUTES.toMillis(30))
                refreshWeather()
            }
        }
    }

    private suspend fun fetchWeather(): HudWeatherSnapshot? = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=37.7749&longitude=-122.4194&current=temperature_2m,weather_code"
        val request = Request.Builder().url(url).get().build()
        return@withContext try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = JSONObject(body)
                val current = json.optJSONObject("current") ?: return@use null
                val temperature = current.optDouble("temperature_2m", Double.NaN)
                val weatherCode = current.optInt("weather_code")
                if (temperature.isNaN()) return@use null
                val description = weatherDescription(weatherCode)
                HudWeatherSnapshot(
                    temperatureCelsius = temperature,
                    description = description,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
        } catch (exception: IOException) {
            null
        }
    }

    private fun weatherDescription(code: Int): String {
        return when (code) {
            0 -> "Clear skies"
            1, 2 -> "Partly cloudy"
            3 -> "Overcast"
            in 45..48 -> "Fog"
            in 51..67 -> "Rain"
            in 71..77 -> "Snow"
            in 80..82 -> "Rain showers"
            in 95..99 -> "Thunderstorms"
            else -> "Unknown"
        }
    }

    private fun buildDisplayPage(message: String): List<String> {
        val chunks = message.chunked(40)
        val limited = chunks.take(5)
        return when {
            limited.size >= 5 -> limited
            else -> limited + List(5 - limited.size) { "" }
        }
    }

    private fun currentTargets(): List<String> {
        val devices = _uiState.value.connectedDevices
        val selected = _uiState.value.selectedTargetId
        return if (selected == null) {
            devices.map { it.id }
        } else {
            devices.firstOrNull { it.id == selected }?.let { listOf(it.id) } ?: emptyList()
        }
    }

    private fun bindRepository() {
        viewModelScope.launch {
            repository.bindService()
        }
    }

    companion object {
        private const val KEY_SHOW_TIME = "hud_show_time"
        private const val KEY_SHOW_WEATHER = "hud_show_weather"
        private const val KEY_SHOW_TEMPERATURE = "hud_show_temperature"
        private const val KEY_SHOW_NOTIFICATIONS = "hud_show_notifications"
        private const val KEY_TILE_ORDER = "hud_tile_order"
    }
}
