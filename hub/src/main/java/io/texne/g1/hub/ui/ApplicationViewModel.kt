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

    private val serviceStateFlow = repository.getServiceStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    private var allowAutoReconnect = true
    private var lastServiceStatus: ServiceStatus? = null

    val state = serviceStateFlow
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

    init {
        viewModelScope.launch {
            serviceStateFlow.collect { serviceState ->
                val status = serviceState?.status ?: ServiceStatus.READY
                if (status != lastServiceStatus) {
                    handleServiceStatusChange(status)
                    lastServiceStatus = status
                }
            }
        }

        refreshDevices()
    }

    fun refreshDevices(autoReconnect: Boolean = true) {
        allowAutoReconnect = autoReconnect
        startDeviceScan(autoReconnect)
    }

    fun connectGlasses() {
        allowAutoReconnect = true
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
        allowAutoReconnect = false
        val glassesId = state.value.glasses?.id
        viewModelScope.launch {
            val disconnectResult = runCatching {
                if (glassesId != null) {
                    repository.disconnectGlasses(glassesId)
                } else {
                    repository.disconnectGlasses()
                }
            }

            if (disconnectResult.isFailure) {
                _messages.emit("Unable to disconnect glasses")
            }

            startDeviceScan(shouldAutoReconnect = false)
        }
    }

    private fun startDeviceScan(shouldAutoReconnect: Boolean) {
        val started = try {
            repository.startLooking()
            true
        } catch (error: IllegalArgumentException) {
            viewModelScope.launch {
                _messages.emit("Service not ready yet")
            }
            false
        }

        if (shouldAutoReconnect && started) {
            repository.connectSelectedGlasses()
        }
    }

    private fun handleServiceStatusChange(status: ServiceStatus) {
        when (status) {
            ServiceStatus.READY -> {
                startDeviceScan(allowAutoReconnect)
            }
            ServiceStatus.ERROR -> {
                allowAutoReconnect = true
            }
            ServiceStatus.LOOKED -> {
                allowAutoReconnect = true
            }
            ServiceStatus.LOOKING -> Unit
        }
    }
}
