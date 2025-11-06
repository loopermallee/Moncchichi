package com.loopermallee.moncchichi.hub.ui.teleprompter

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loopermallee.moncchichi.client.G1ServiceCommon.GlassesStatus
import com.loopermallee.moncchichi.hub.data.SettingsRepository
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository
import com.loopermallee.moncchichi.hub.model.Repository
import com.loopermallee.moncchichi.hub.ui.glasses.LensSide
import com.loopermallee.moncchichi.hub.ui.glasses.PairedGlasses
import com.loopermallee.moncchichi.hub.ui.glasses.connectedLensIds
import com.loopermallee.moncchichi.hub.ui.glasses.toPairedGlasses
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val AUTO_SCROLL_INTERVAL_MS = 16L
private const val HUD_SYNC_INTERVAL_MS = 2_500L
private const val MIN_SPEED = 20f
private const val MAX_SPEED = 200f
private const val SPEED_STEP = 8f

class TeleprompterViewModel(
    private val settingsRepository: SettingsRepository,
    private val repository: Repository,
    private val memoryRepository: MemoryRepository,
) : ViewModel() {

    data class TeleprompterUiState(
        val text: String = "",
        val speed: Float = settingsRepository.teleprompterSpeed(),
        val isPlaying: Boolean = false,
        val isMirror: Boolean = settingsRepository.teleprompterMirror(),
        val isHudSyncEnabled: Boolean = false,
        val visibleLine: String = "",
        val hudStatusMessage: String = "HUD sync idle",
        val connectedLensCount: Int = 0,
        val lastHudTimestamp: Long? = null,
        val lastHudSuccess: Boolean? = null,
    )

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _uiState = MutableStateFlow(
        TeleprompterUiState(
            text = settingsRepository.teleprompterText(),
            speed = settingsRepository.teleprompterSpeed(),
            isMirror = settingsRepository.teleprompterMirror(),
        )
    )
    val uiState: StateFlow<TeleprompterUiState> = _uiState.asStateFlow()

    private val _scrollOffset = MutableStateFlow(0)
    val scrollOffset: StateFlow<Int> = _scrollOffset.asStateFlow()

    private val visibleLine = MutableStateFlow("")
    private val hudSender = TeleprompterHudSender(repository, memoryRepository)

    private var autoScrollJob: Job? = null
    private var hudSyncJob: Job? = null

    private var hudTargets: List<TeleprompterHudSender.HudTarget> = emptyList()
    private var lastHudResult: TeleprompterHudSender.HudSendResult? = null

    init {
        startAutoScroll()
        startHudSync()
        observeServiceState()
    }

    fun updateText(newText: String) {
        _uiState.update { it.copy(text = newText) }
        settingsRepository.updateTeleprompterText(newText)
        resetScroll()
        updateVisibleLine(newText.lineSequence().firstOrNull()?.trim().orEmpty())
    }

    fun start() {
        _uiState.update { it.copy(isPlaying = true) }
    }

    fun pause() {
        _uiState.update { it.copy(isPlaying = false) }
    }

    fun reset() {
        _uiState.update { it.copy(isPlaying = false) }
        resetScroll()
        updateVisibleLine(_uiState.value.text.lineSequence().firstOrNull()?.trim().orEmpty())
    }

    fun decreaseSpeed() {
        setSpeed(_uiState.value.speed - SPEED_STEP)
    }

    fun increaseSpeed() {
        setSpeed(_uiState.value.speed + SPEED_STEP)
    }

    fun setSpeed(value: Float) {
        val clamped = min(MAX_SPEED, max(MIN_SPEED, value))
        _uiState.update { it.copy(speed = clamped) }
        settingsRepository.updateTeleprompterSpeed(clamped)
    }

    fun toggleMirror(enabled: Boolean) {
        _uiState.update { it.copy(isMirror = enabled) }
        settingsRepository.updateTeleprompterMirror(enabled)
    }

    fun toggleHudSync(enabled: Boolean) {
        _uiState.update { it.copy(isHudSyncEnabled = enabled) }
        updateHudStatus()
    }

    fun onScrollPositionChanged(offset: Int) {
        _scrollOffset.value = max(0, offset)
    }

    fun updateVisibleLine(line: String?) {
        val normalized = line?.trim().orEmpty()
        if (visibleLine.value == normalized) {
            return
        }
        visibleLine.value = normalized
        _uiState.update { it.copy(visibleLine = normalized) }
    }

    private fun startAutoScroll() {
        autoScrollJob?.cancel()
        autoScrollJob = viewModelScope.launch {
            var lastTick = SystemClock.elapsedRealtime()
            while (true) {
                delay(AUTO_SCROLL_INTERVAL_MS)
                val state = _uiState.value
                if (!state.isPlaying) {
                    lastTick = SystemClock.elapsedRealtime()
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                val deltaMs = now - lastTick
                lastTick = now
                if (deltaMs <= 0) continue
                val pxPerSecond = state.speed
                val delta = (pxPerSecond * (deltaMs / 1000f)).roundToInt().coerceAtLeast(1)
                _scrollOffset.update { current -> max(0, current + delta) }
            }
        }
    }

    private fun startHudSync() {
        hudSyncJob?.cancel()
        hudSyncJob = viewModelScope.launch {
            while (true) {
                delay(HUD_SYNC_INTERVAL_MS)
                val state = _uiState.value
                if (!state.isHudSyncEnabled) {
                    continue
                }
                val line = visibleLine.value
                if (line.isBlank()) {
                    continue
                }
                val targets = hudTargets
                val result = hudSender.send(line, targets)
                if (result != null) {
                    lastHudResult = result
                    updateHudStatus(result)
                }
            }
        }
    }

    private fun observeServiceState() {
        viewModelScope.launch {
            repository.getServiceStateFlow().collectLatest { state ->
                val targets = state?.glasses?.toPairedGlasses()?.flatMap { pair ->
                    buildTargets(pair)
                } ?: emptyList()
                hudTargets = targets
                updateHudStatus()
            }
        }
    }

    private fun buildTargets(pair: PairedGlasses): List<TeleprompterHudSender.HudTarget> {
        val targets = mutableListOf<TeleprompterHudSender.HudTarget>()
        pair.left?.let { lens ->
            val id = lens.id
            if (!id.isNullOrBlank() && lens.status == GlassesStatus.CONNECTED) {
                targets += TeleprompterHudSender.HudTarget(id, pair.leftSide)
            }
        }
        pair.right?.let { lens ->
            val id = lens.id
            if (!id.isNullOrBlank() && lens.status == GlassesStatus.CONNECTED) {
                targets += TeleprompterHudSender.HudTarget(id, pair.rightSide)
            }
        }
        if (targets.isEmpty() && pair.connectedLensIds.isNotEmpty()) {
            pair.connectedLensIds.forEach { id ->
                targets += TeleprompterHudSender.HudTarget(id, LensSide.UNKNOWN)
            }
        }
        return targets
    }

    private fun updateHudStatus(result: TeleprompterHudSender.HudSendResult? = null) {
        result?.let { lastHudResult = it }
        val currentResult = lastHudResult
        val connectedCount = hudTargets.size
        val statusMessage = when {
            !_uiState.value.isHudSyncEnabled -> "HUD sync paused"
            connectedCount == 0 -> "No connected lenses"
            currentResult == null -> "Ready to sync"
            currentResult.success -> "Synced at ${formatTime(currentResult.timestamp)}"
            else -> "Last sync failed @ ${formatTime(currentResult.timestamp)}"
        }
        _uiState.update {
            it.copy(
                hudStatusMessage = statusMessage,
                connectedLensCount = connectedCount,
                lastHudTimestamp = currentResult?.timestamp,
                lastHudSuccess = currentResult?.success,
            )
        }
    }

    private fun resetScroll() {
        _scrollOffset.value = 0
    }

    private fun formatTime(timestamp: Long): String = timeFormat.format(Date(timestamp))
}
