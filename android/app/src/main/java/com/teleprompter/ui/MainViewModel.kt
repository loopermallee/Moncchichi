package com.teleprompter.ui

import androidx.lifecycle.ViewModel
import io.texne.g1.basis.service.protocol.G1Glasses
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ServiceStatus {
    DISCONNECTED,
    LOOKING,
    READY,
    DISPLAYING,
    PAUSED
}

data class GlassesSummary(
    val id: String,
    val name: String,
    val batteryPercentage: Int?,
    val firmwareVersion: String?,
    val isDisplaying: Boolean,
    val isPaused: Boolean,
    val scrollSpeed: Float,
    val currentText: String
)

data class ServiceUiState(
    val status: ServiceStatus = ServiceStatus.LOOKING,
    val connectedGlasses: List<GlassesSummary> = emptyList(),
    val currentText: String = DEFAULT_DISPLAY_TEXT,
    val scrollSpeed: Float = G1Glasses.DEFAULT_SCROLL_SPEED,
    val lastUpdatedEpochMillis: Long? = null
) {
    companion object {
        const val DEFAULT_DISPLAY_TEXT = "Welcome to Moncchichi Hub"
    }
}

class MainViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ServiceUiState())
    val uiState: StateFlow<ServiceUiState> = _uiState.asStateFlow()

    fun setServiceStatus(status: ServiceStatus) {
        _uiState.update { current ->
            current.copy(status = status)
        }
    }

    fun updateFromGlasses(glasses: G1Glasses) {
        _uiState.update { current ->
            val status = when {
                glasses.connectionState == G1Glasses.STATE_DISCONNECTED -> ServiceStatus.DISCONNECTED
                glasses.isDisplaying -> ServiceStatus.DISPLAYING
                glasses.isPaused -> ServiceStatus.PAUSED
                else -> ServiceStatus.READY
            }

            val summary = GlassesSummary(
                id = glasses.id,
                name = glasses.name.ifBlank { DEFAULT_GLASSES_NAME },
                batteryPercentage = glasses.batteryPercentage.takeIf { it >= 0 },
                firmwareVersion = glasses.firmwareVersion.ifBlank { null },
                isDisplaying = glasses.isDisplaying,
                isPaused = glasses.isPaused,
                scrollSpeed = glasses.scrollSpeed,
                currentText = glasses.currentText
            )

            val displayText = glasses.currentText.takeIf { it.isNotBlank() } ?: current.currentText

            current.copy(
                status = status,
                connectedGlasses = listOf(summary),
                currentText = displayText,
                scrollSpeed = glasses.scrollSpeed,
                lastUpdatedEpochMillis = System.currentTimeMillis()
            )
        }
    }

    fun clearConnectedGlasses() {
        _uiState.update { current ->
            current.copy(
                connectedGlasses = emptyList(),
                lastUpdatedEpochMillis = System.currentTimeMillis()
            )
        }
    }

    fun markServiceDisconnected() {
        _uiState.update { current ->
            current.copy(
                status = ServiceStatus.DISCONNECTED,
                connectedGlasses = emptyList(),
                lastUpdatedEpochMillis = System.currentTimeMillis()
            )
        }
    }

    companion object {
        private const val DEFAULT_GLASSES_NAME = "G1 Glasses"
    }
}
