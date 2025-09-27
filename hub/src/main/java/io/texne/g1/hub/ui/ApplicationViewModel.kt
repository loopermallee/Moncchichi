package io.texne.g1.hub.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.basis.client.G1ServiceCommon.GlassesStatus
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

    enum class DeviceStatus(val label: String) {
        DISCONNECTED("Disconnected"),
        CONNECTING("Connecting"),
        CONNECTED("Connected"),
        DISCONNECTING("Disconnecting"),
        ERROR("Error")
    }

    data class Device(
        val id: String?,
        val name: String,
        val status: DeviceStatus,
        val batteryPercentage: Int?,
        val firmwareVersion: String?
    ) {
        val canPair: Boolean
            get() = id != null && when (status) {
                DeviceStatus.DISCONNECTED, DeviceStatus.ERROR -> true
                DeviceStatus.CONNECTING, DeviceStatus.CONNECTED, DeviceStatus.DISCONNECTING -> false
            }

        val isBusy: Boolean
            get() = status == DeviceStatus.CONNECTING || status == DeviceStatus.DISCONNECTING
    }

    data class State(
        val devices: List<Device> = emptyList(),
        val isLooking: Boolean = false,
        val serviceError: Boolean = false
    )

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    val state = repository.getServiceStateFlow()
        .map { serviceState ->
            val glasses = serviceState?.glasses.orEmpty()
            State(
                devices = glasses.map { it.toDevice() },
                isLooking = serviceState?.status == ServiceStatus.LOOKING,
                serviceError = serviceState?.status == ServiceStatus.ERROR
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

    fun connectAvailableGlasses() {
        viewModelScope.launch {
            val target = state.value.devices.firstOrNull { it.canPair }
            val result = target?.id?.let { identifier ->
                runCatching { repository.connectGlasses(identifier) }
            }

            if (result == null) {
                repository.connectSelectedGlasses()
            } else {
                val success = result.getOrNull()
                if (success != true || result.isFailure) {
                    _messages.emit("Unable to connect to the glasses")
                }
            }
        }
    }

    fun disconnectGlasses() {
        viewModelScope.launch {
            val connectedDevices = state.value.devices.filter { it.status == DeviceStatus.CONNECTED }
            if (connectedDevices.isEmpty()) {
                repository.disconnectGlasses()
            } else {
                connectedDevices.forEach { device ->
                    device.id?.let { identifier ->
                        repository.disconnectGlasses(identifier)
                    }
                }
            }
        }
    }

    private fun G1ServiceCommon.Glasses.toDevice(): Device {
        val safeName = name?.takeIf { it.isNotBlank() } ?: "Unnamed glasses"
        return Device(
            id = id,
            name = safeName,
            status = status.toDeviceStatus(),
            batteryPercentage = batteryPercentage,
            firmwareVersion = null
        )
    }

    private fun GlassesStatus.toDeviceStatus(): DeviceStatus = when (this) {
        GlassesStatus.UNINITIALIZED, GlassesStatus.DISCONNECTED -> DeviceStatus.DISCONNECTED
        GlassesStatus.CONNECTING -> DeviceStatus.CONNECTING
        GlassesStatus.CONNECTED -> DeviceStatus.CONNECTED
        GlassesStatus.DISCONNECTING -> DeviceStatus.DISCONNECTING
        GlassesStatus.ERROR -> DeviceStatus.ERROR
    }
}
