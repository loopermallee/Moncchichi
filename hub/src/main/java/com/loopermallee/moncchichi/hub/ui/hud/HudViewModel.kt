package com.loopermallee.moncchichi.hub.ui.hud

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loopermallee.moncchichi.client.G1ServiceCommon
import com.loopermallee.moncchichi.hub.model.Repository
import io.texne.g1.basis.service.G1DisplayService
import io.texne.g1.basis.service.protocol.G1Glasses
import io.texne.g1.basis.service.protocol.IG1DisplayService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val ALL_CONNECTED_TARGET = "hud_all"
private const val PREF_PREFIX = "hud_config_"
private const val PREF_SHOW_TIME = "${PREF_PREFIX}time"
private const val PREF_SHOW_WEATHER = "${PREF_PREFIX}weather"
private const val PREF_SHOW_TEMPERATURE = "${PREF_PREFIX}temperature"
private const val PREF_SHOW_NOTIFICATIONS = "${PREF_PREFIX}notifications"
private const val PREF_TILE_ORDER = "${PREF_PREFIX}order"

class HudViewModel(
    private val appContext: Context,
    private val repository: Repository,
    private val prefs: android.content.SharedPreferences,
    private val httpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(HudUiState())
    val state: StateFlow<HudUiState> = _state.asStateFlow()

    private var displayService: IG1DisplayService? = null
    private var isDisplayServiceBound: Boolean = false

    private val displayConnection = object : ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
            displayService = IG1DisplayService.Stub.asInterface(service)
            isDisplayServiceBound = true
            refreshMirror()
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            displayService = null
            isDisplayServiceBound = false
            _state.update { it.copy(connectionStatus = HudConnectionStatus.DISCONNECTED) }
        }
    }

    init {
        loadStoredConfig()
        bindRepository()
        bindDisplayService()
        observeServiceState()
        observeNotifications()
        refreshNotificationAccess()
        viewModelScope.launch { refreshWeather() }
        viewModelScope.launch { mirrorPollingLoop() }
        viewModelScope.launch { weatherRefreshLoop() }
    }

    fun updateMessageDraft(text: String) {
        _state.update { it.copy(messageDraft = text, errorMessage = null) }
    }

    fun selectTarget(targetId: String) {
        _state.update { it.copy(selectedTargetId = targetId) }
    }

    fun toggleTile(tile: HudTile) {
        val updated = state.value.hudConfig.toggle(tile)
        persistConfig(updated)
        _state.update { it.copy(hudConfig = updated) }
    }

    fun moveTileUp(tile: HudTile) {
        val updated = state.value.hudConfig.moveUp(tile)
        persistConfig(updated)
        _state.update { it.copy(hudConfig = updated) }
    }

    fun moveTileDown(tile: HudTile) {
        val updated = state.value.hudConfig.moveDown(tile)
        persistConfig(updated)
        _state.update { it.copy(hudConfig = updated) }
    }

    fun refreshNotificationAccess() {
        val granted = HudNotificationListenerService.isAccessGranted(appContext)
        _state.update { state ->
            state.copy(
                isNotificationAccessGranted = granted,
                notifications = if (granted) state.notifications else emptyList(),
            )
        }
    }

    fun refreshWeather() {
        viewModelScope.launch(ioDispatcher) {
            _state.update { it.copy(isWeatherLoading = true) }
            val request = Request.Builder()
                .url("https://api.open-meteo.com/v1/forecast?latitude=37.7749&longitude=-122.4194&current=temperature_2m,weather_code")
                .build()
            val result = runCatching { httpClient.newCall(request).execute() }
            val response = result.getOrNull()
            if (response == null) {
                _state.update { it.copy(isWeatherLoading = false, errorMessage = "Unable to contact weather service") }
                return@launch
            }
            response.use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) {
                    _state.update { it.copy(isWeatherLoading = false, errorMessage = "Weather service returned an error") }
                    return@use
                }
                runCatching {
                    val json = JSONObject(body)
                    val current = json.getJSONObject("current")
                    val temp = current.optDouble("temperature_2m", Double.NaN)
                    val code = current.optInt("weather_code")
                    val description = mapWeatherCode(code)
                    _state.update {
                        it.copy(
                            isWeatherLoading = false,
                            weatherDescription = description,
                            temperatureCelsius = if (temp.isNaN()) null else temp,
                            weatherLastUpdated = System.currentTimeMillis(),
                            errorMessage = null,
                        )
                    }
                }.onFailure {
                    _state.update { it.copy(isWeatherLoading = false, errorMessage = "Unable to parse weather data") }
                }
            }
        }
    }

    fun sendHudMessage() {
        val draft = state.value.messageDraft.trim()
        if (draft.isEmpty()) {
            _state.update { it.copy(errorMessage = "Message cannot be empty") }
            return
        }
        val targetIds = resolveTargetIds()
        if (targetIds.isEmpty()) {
            _state.update { it.copy(errorMessage = "No connected glasses available") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSending = true, errorMessage = null) }
            val page = draft.lineSequence()
                .flatMap { line -> line.chunked(40).asSequence() }
                .filter { it.isNotBlank() }
                .take(5)
                .toList()
            if (page.isEmpty()) {
                _state.update { it.copy(isSending = false, errorMessage = "Message cannot be empty") }
                return@launch
            }

            var allSuccess = true
            for (id in targetIds) {
                val result = runCatching { repository.displayTextPage(id, page) }
                if (result.getOrNull() != true) {
                    allSuccess = false
                }
            }

            val now = System.currentTimeMillis()
            _state.update {
                it.copy(
                    isSending = false,
                    lastMessageTimestamp = now,
                    mirrorText = draft,
                    messageDraft = if (allSuccess) "" else it.messageDraft,
                    errorMessage = if (allSuccess) null else "Unable to deliver message to all targets",
                )
            }
            refreshMirror()
        }
    }

    fun stopHudMessage() {
        val targetIds = resolveTargetIds()
        if (targetIds.isEmpty()) {
            _state.update { it.copy(errorMessage = "No connected glasses available") }
            return
        }
        viewModelScope.launch {
            var allSuccess = true
            for (id in targetIds) {
                val result = runCatching { repository.stopDisplaying(id) }
                if (result.getOrNull() != true) {
                    allSuccess = false
                }
            }
            _state.update {
                it.copy(
                    errorMessage = if (allSuccess) null else "Unable to stop display on every device",
                )
            }
            refreshMirror()
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.unbindService()
        if (isDisplayServiceBound) {
            runCatching { appContext.unbindService(displayConnection) }
        }
        displayService = null
        isDisplayServiceBound = false
    }

    private fun bindRepository() {
        val bound = repository.bindService()
        if (!bound) {
            _state.update { it.copy(errorMessage = "Unable to bind to glasses service") }
        }
    }

    private fun bindDisplayService() {
        if (isDisplayServiceBound) return
        val intent = Intent(appContext, G1DisplayService::class.java)
        runCatching {
            val bound = appContext.bindService(intent, displayConnection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                _state.update { it.copy(errorMessage = "Unable to connect to display service") }
            }
        }.onFailure {
            _state.update { it.copy(errorMessage = "Display service binding failed") }
        }
    }

    private fun observeServiceState() {
        viewModelScope.launch {
            repository.getServiceStateFlow().collectLatest { serviceState ->
                val glasses = serviceState?.glasses ?: emptyList()
                val connected = glasses.filter { it.status == G1ServiceCommon.GlassesStatus.CONNECTED }
                val options = buildList {
                    if (connected.isNotEmpty()) {
                        add(
                            HudTargetOption(
                                id = ALL_CONNECTED_TARGET,
                                label = "All connected (${connected.size})",
                                glasses = null,
                            )
                        )
                    }
                    glasses.forEach { g ->
                        val id = g.id.orEmpty()
                        if (id.isEmpty()) return@forEach
                        val label = buildString {
                            g.name?.takeIf { it.isNotBlank() }?.let { append(it) }
                            if (isNotEmpty()) {
                                append(" • ")
                            }
                            append(id)
                        }
                        add(HudTargetOption(id, label, g))
                    }
                }
                val selected = state.value.selectedTargetId
                val nextSelection = when {
                    selected != null && options.any { it.id == selected } -> selected
                    options.isNotEmpty() -> options.first().id
                    else -> null
                }
                val connectionStatus = when {
                    state.value.isDisplaying -> HudConnectionStatus.DISPLAYING
                    connected.isNotEmpty() -> HudConnectionStatus.CONNECTED
                    else -> HudConnectionStatus.DISCONNECTED
                }
                _state.update {
                    it.copy(
                        availableTargets = options,
                        selectedTargetId = nextSelection,
                        connectionStatus = connectionStatus,
                    )
                }
            }
        }
    }

    private suspend fun mirrorPollingLoop() {
        while (isActive) {
            refreshMirror()
            delay(TimeUnit.SECONDS.toMillis(5))
        }
    }

    private suspend fun weatherRefreshLoop() {
        while (isActive) {
            delay(TimeUnit.MINUTES.toMillis(30))
            refreshWeather()
        }
    }

    private fun refreshMirror() {
        val service = displayService ?: return
        val info = try {
            service.glassesInfo
        } catch (error: RemoteException) {
            _state.update { it.copy(errorMessage = "Unable to read glasses state") }
            return
        }
        _state.update {
            val status = when {
                info.isDisplaying -> HudConnectionStatus.DISPLAYING
                info.connectionState == G1Glasses.STATE_CONNECTED -> HudConnectionStatus.CONNECTED
                else -> HudConnectionStatus.DISCONNECTED
            }
            it.copy(
                mirrorText = info.currentText.orEmpty(),
                isDisplaying = info.isDisplaying,
                scrollSpeed = info.scrollSpeed,
                connectedLensId = info.id.takeIf { id -> id.isNotBlank() } ?: it.connectedLensId,
                connectedLensName = info.name.takeIf { name -> name.isNotBlank() } ?: it.connectedLensName,
                connectionStatus = status,
            )
        }
    }

    private fun observeNotifications() {
        viewModelScope.launch {
            HudNotificationListenerService.notificationFlow.collectLatest { notifications ->
                val granted = HudNotificationListenerService.isAccessGranted(appContext)
                _state.update {
                    it.copy(
                        notifications = if (granted) notifications else emptyList(),
                        isNotificationAccessGranted = granted,
                    )
                }
            }
        }
    }

    private fun resolveTargetIds(): List<String> {
        val selection = state.value.selectedTargetId
        val options = state.value.availableTargets
        if (selection == null && options.isEmpty()) {
            return emptyList()
        }
        if (selection == null || selection == ALL_CONNECTED_TARGET) {
            return options
                .mapNotNull { option -> option.glasses }
                .filter { it.status == G1ServiceCommon.GlassesStatus.CONNECTED }
                .mapNotNull { it.id }
                .filter { it.isNotBlank() }
        }
        val selectedOption = options.firstOrNull { it.id == selection }
        val glasses = selectedOption?.glasses
        return when {
            glasses == null -> listOf(selection)
            glasses.status == G1ServiceCommon.GlassesStatus.CONNECTED -> listOf(selection)
            else -> emptyList()
        }
    }

    private fun loadStoredConfig() {
        val storedOrder = prefs.getString(PREF_TILE_ORDER, null)
        val order = storedOrder?.split(',')
            ?.mapNotNull { runCatching { HudTile.valueOf(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: HudConfig.defaultTileOrder()
        val config = HudConfig(
            showTime = prefs.getBoolean(PREF_SHOW_TIME, true),
            showWeather = prefs.getBoolean(PREF_SHOW_WEATHER, true),
            showTemperature = prefs.getBoolean(PREF_SHOW_TEMPERATURE, true),
            showNotifications = prefs.getBoolean(PREF_SHOW_NOTIFICATIONS, true),
            tileOrder = order,
        )
        _state.update { it.copy(hudConfig = config) }
    }

    private fun persistConfig(config: HudConfig) {
        prefs.edit()
            .putBoolean(PREF_SHOW_TIME, config.showTime)
            .putBoolean(PREF_SHOW_WEATHER, config.showWeather)
            .putBoolean(PREF_SHOW_TEMPERATURE, config.showTemperature)
            .putBoolean(PREF_SHOW_NOTIFICATIONS, config.showNotifications)
            .putString(PREF_TILE_ORDER, config.tileOrder.joinToString(",") { it.name })
            .apply()
    }

    private fun mapWeatherCode(code: Int): String {
        return when (code) {
            0 -> "Clear sky"
            1, 2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63, 65 -> "Rain"
            66, 67 -> "Freezing rain"
            71, 73, 75 -> "Snow"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm w/ hail"
            else -> "Weather code $code"
        }
    }
}
