package io.texne.g1.hub.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.basis.client.G1ServiceCommon.ServiceStatus
import io.texne.g1.hub.model.Repository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ApplicationViewModel @Inject constructor(
    private val repository: Repository
) : ViewModel() {

    data class State(
        val glasses: List<G1ServiceCommon.Glasses> = emptyList(),
        val serviceStatus: ServiceStatus = ServiceStatus.READY,
        val isLooking: Boolean = false,
        val serviceError: Boolean = false
    )

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    private val serviceStateFlow = repository.getServiceStateFlow()

    val state = serviceStateFlow
        .map { serviceState ->
            val status = serviceState?.status ?: ServiceStatus.READY
            State(
                glasses = serviceState?.glasses ?: emptyList(),
                serviceStatus = status,
                isLooking = status == ServiceStatus.LOOKING,
                serviceError = status == ServiceStatus.ERROR
            )
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, State())

    fun lookForGlasses() {
        viewModelScope.launch {
            startLookingSafely(showMessage = true)
        }
    }

    fun connect(glassesId: String? = null, glassesName: String? = null) {
        viewModelScope.launch {
            if (glassesId != null) {
                val label = glassesName?.takeIf { it.isNotBlank() } ?: "glasses"
                _messages.emit("Connecting to $label…")
            }
            val result = if (glassesId != null) {
                runCatching { repository.connectGlasses(glassesId) }
            } else {
                runCatching { repository.connectSelectedGlasses() }
            }

            val success = result.getOrNull()
            if (result.isFailure || (success is Boolean && success != true)) {
                _messages.emit("Unable to connect to the selected glasses")
            }
        }
    }

    fun disconnect(glassesId: String? = null) {
        viewModelScope.launch {
            val result = runCatching {
                if (glassesId != null) {
                    repository.disconnectGlasses(glassesId)
                } else {
                    repository.disconnectGlasses()
                }
            }

            if (result.isFailure) {
                _messages.emit("Unable to disconnect from the selected glasses")
            }
        }
    }

    fun refreshGlasses() {
        viewModelScope.launch {
            repository.disconnectGlasses()
            val started = startLookingSafely(showMessage = false)
            if (started) {
                _messages.emit("Refreshing glasses…")
            } else {
                _messages.emit("Unable to refresh glasses")
            }
        }
    }

    fun displayText(
        message: String,
        glassesIds: List<String>,
        onResult: (Boolean) -> Unit = {}
    ) {
        val trimmedMessage = message.trim()
        if (trimmedMessage.isEmpty()) {
            viewModelScope.launch {
                _messages.emit("Message cannot be empty")
            }
            onResult(false)
            return
        }

        viewModelScope.launch {
            if (glassesIds.isEmpty()) {
                _messages.emit("No device connected")
                onResult(false)
                return@launch
            }

            var allSuccess = true
            for (id in glassesIds) {
                val result = runCatching { repository.displayTextPage(id, listOf(trimmedMessage)) }
                val success = result.getOrNull() == true
                if (!success) {
                    allSuccess = false
                }
            }

            if (allSuccess) {
                _messages.emit("Message sent")
            } else {
                _messages.emit("Failed to send")
            }

            onResult(allSuccess)
        }
    }

    fun stopDisplaying(glassesIds: List<String>, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            if (glassesIds.isEmpty()) {
                _messages.emit("No device connected")
                onResult(false)
                return@launch
            }

            var allSuccess = true
            for (id in glassesIds) {
                val result = runCatching { repository.stopDisplaying(id) }
                val success = result.getOrNull() == true
                if (!success) {
                    allSuccess = false
                }
            }

            if (allSuccess) {
                _messages.emit("Stopped displaying")
            } else {
                _messages.emit("Failed to stop")
            }
            onResult(allSuccess)
        }
    }

    fun onScreenReady() {
        // No-op: user actions drive discovery and connection.
    }

    fun onBluetoothStateChanged(isOn: Boolean) {
        // No-op: waiting for explicit user actions.
    }

    private suspend fun startLookingSafely(showMessage: Boolean): Boolean {
        return try {
            repository.startLooking()
            true
        } catch (error: IllegalArgumentException) {
            if (showMessage) {
                _messages.emit("Service not ready yet")
            }
            false
        }
    }
}
