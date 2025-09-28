package io.texne.g1.hub.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.basis.client.G1ServiceCommon.ServiceStatus
import io.texne.g1.hub.model.Repository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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

    private var autoReconnectJob: Job? = null

    init {
        viewModelScope.launch {
            serviceStateFlow
                .map { it?.status ?: ServiceStatus.READY }
                .distinctUntilChanged()
                .collect { status ->
                    when (status) {
                        ServiceStatus.READY,
                        ServiceStatus.LOOKED -> attemptAutoReconnect(status)
                        else -> Unit
                    }
                }
        }
    }

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

    fun refreshDevices() {
        viewModelScope.launch {
            startLookingSafely(showMessage = true)
        }
    }

    fun connectGlasses() {
        viewModelScope.launch {
            val glassesId = state.value.glasses.firstOrNull()?.id
            if (glassesId != null) {
                val result = runCatching { repository.connectGlasses(glassesId) }
                val success = result.getOrNull()
                if (success != true || result.isFailure) {
                    _messages.emit("Unable to connect to the selected glasses")
                }
            } else {
                val attempt = runCatching { repository.connectSelectedGlasses() }
                if (attempt.isFailure) {
                    _messages.emit("No glasses available to connect")
                }
            }
        }
    }

    fun disconnectGlasses() {
        val glassesId = state.value.glasses.firstOrNull()?.id
        viewModelScope.launch {
            if (glassesId != null) {
                repository.disconnectGlasses(glassesId)
            } else {
                repository.disconnectGlasses()
            }
        }
    }

    fun sendMessage(message: String, onResult: (Boolean) -> Unit = {}) {
        val trimmedMessage = message.trim()
        if (trimmedMessage.isEmpty()) {
            viewModelScope.launch {
                _messages.emit("Message cannot be empty")
            }
            onResult(false)
            return
        }

        val targetGlasses = state.value.glasses.firstOrNull()
        val glassesId = targetGlasses?.id
        val isConnected = targetGlasses?.status == G1ServiceCommon.GlassesStatus.CONNECTED

        viewModelScope.launch {
            if (glassesId == null || !isConnected) {
                _messages.emit("No device connected")
                onResult(false)
                return@launch
            }

            val page = trimmedMessage.chunked(40).take(5)
            val result = runCatching { repository.displayTextPage(glassesId, page) }
            val success = result.getOrNull() == true

            if (success) {
                _messages.emit("Message sent")
            } else {
                _messages.emit("Failed to send")
            }

            onResult(success)
        }
    }

    fun onScreenReady() {
        attemptAutoReconnect(ServiceStatus.READY)
    }

    private fun attemptAutoReconnect(triggerStatus: ServiceStatus) {
        autoReconnectJob?.cancel()
        autoReconnectJob = viewModelScope.launch {
            val started = if (triggerStatus == ServiceStatus.READY) {
                startLookingSafely(showMessage = false)
            } else {
                true
            }

            if (started) {
                runCatching { repository.connectSelectedGlasses() }
            }
        }
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
