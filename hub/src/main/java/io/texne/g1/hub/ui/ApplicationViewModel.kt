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
        val glasses: G1ServiceCommon.Glasses? = null,
        val serviceStatus: ServiceStatus = ServiceStatus.READY,
        val isLooking: Boolean = false,
        val serviceError: Boolean = false
    )

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    val state = repository.getServiceStateFlow()
        .map { serviceState ->
            val status = serviceState?.status ?: ServiceStatus.READY
            State(
                glasses = serviceState?.glasses?.firstOrNull(),
                serviceStatus = status,
                isLooking = status == ServiceStatus.LOOKING,
                serviceError = status == ServiceStatus.ERROR
            )
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, State())

    fun refreshDevices() {
        try {
            repository.startLooking()
        } catch (error: IllegalArgumentException) {
            viewModelScope.launch {
                _messages.emit("Service not ready yet")
            }
        }
    }

    fun connectGlasses() {
        val glassesId = state.value.glasses?.id
        if (glassesId == null) {
            viewModelScope.launch {
                _messages.emit("No glasses available to connect")
            }
            return
        }

        viewModelScope.launch {
            val result = runCatching { repository.connectGlasses(glassesId) }
            val success = result.getOrNull()
            if (success != true || result.isFailure) {
                _messages.emit("Unable to connect to the selected glasses")
            }
        }
    }

    fun disconnectGlasses() {
        val glassesId = state.value.glasses?.id
        viewModelScope.launch {
            if (glassesId != null) {
                repository.disconnectGlasses(glassesId)
            } else {
                repository.disconnectGlasses()
            }
        }
    }
}
