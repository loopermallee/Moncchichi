package com.teleprompter.ui

import androidx.lifecycle.ViewModel
import io.texne.g1.basis.service.protocol.G1Glasses
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ServiceStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
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
    val status: ServiceStatus = ServiceStatus.CONNECTING,
    val connectedGlasses: List<GlassesSummary> = emptyList(),
    val currentText: String = DEFAULT_DISPLAY_TEXT,
    val scrollSpeed: Float = G1Glasses.DEFAULT_SCROLL_SPEED,
    val lastUpdatedEpochMillis: Long? = null,
    val snackbarMessage: String? = null,
    val persistentErrorMessage: String? = null,
    val isRetryVisible: Boolean = false
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
            current.copy(
                status = status,
                persistentErrorMessage = if (status == ServiceStatus.DISCONNECTED) {
                    current.persistentErrorMessage
                } else {
                    null
                },
                isRetryVisible = if (status == ServiceStatus.DISCONNECTED) {
                    current.isRetryVisible
                } else {
                    false
                }
            )
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
                lastUpdatedEpochMillis = System.currentTimeMillis(),
                persistentErrorMessage = null,
                isRetryVisible = false
            )
        }
    }

    fun clearConnectedGlasses() {
        _uiState.update { current ->
            current.copy(
                connectedGlasses = emptyList(),
                lastUpdatedEpochMillis = System.currentTimeMillis(),
                persistentErrorMessage = null,
                isRetryVisible = false
            )
        }
    }

    fun markServiceDisconnected(
        reason: String? = null,
        showRetry: Boolean = true
    ) {
        _uiState.update { current ->
            val message = reason ?: if (showRetry) {
                "Display service disconnected."
            } else {
                null
            }

            current.copy(
                status = ServiceStatus.DISCONNECTED,
                connectedGlasses = emptyList(),
                lastUpdatedEpochMillis = System.currentTimeMillis(),
                snackbarMessage = message,
                persistentErrorMessage = if (showRetry) {
                    "Display service disconnected. Tap retry to reconnect."
                } else {
                    null
                },
                isRetryVisible = showRetry
            )
        }
    }

    fun onServiceBindFailed() {
        _uiState.update { current ->
            current.copy(
                status = ServiceStatus.DISCONNECTED,
                connectedGlasses = emptyList(),
                snackbarMessage = "Failed to bind to display service.",
                persistentErrorMessage = "Unable to connect to the display service. Tap retry to try again.",
                isRetryVisible = true
            )
        }
    }

    fun showSnackbar(message: String) {
        _uiState.update { current ->
            current.copy(snackbarMessage = message)
        }
    }

    fun onSnackbarShown() {
        _uiState.update { current ->
            if (current.snackbarMessage == null) {
                current
            } else {
                current.copy(snackbarMessage = null)
            }
        }
    }

    companion object {
        private const val DEFAULT_GLASSES_NAME = "G1 Glasses"
    }
}
