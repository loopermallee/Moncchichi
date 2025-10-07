package io.texne.g1.hub

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ServiceState { IDLE, BINDING, CONNECTED, DISCONNECTED, ERROR }

object ServiceRepository {
    private val _state = MutableStateFlow(ServiceState.IDLE)
    val state: StateFlow<ServiceState> = _state

    fun setBinding()           { _state.value = ServiceState.BINDING }
    fun setConnected()         { _state.value = ServiceState.CONNECTED }
    fun setDisconnected()      { _state.value = ServiceState.DISCONNECTED }
    fun setError()             { _state.value = ServiceState.ERROR }
}
