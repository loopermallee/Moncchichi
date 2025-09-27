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
): ViewModel() {

    data class State(
        val connectedGlasses: G1ServiceCommon.Glasses? = null,
        val error: Boolean = false,
        val scanning: Boolean = false,
        val nearbyGlasses: List<G1ServiceCommon.Glasses>? = null
    )

    val state = repository.getServiceStateFlow().map {
        State(
            connectedGlasses = it?.glasses?.filter { it.status == G1ServiceCommon.GlassesStatus.CONNECTED }?.firstOrNull(),
            error = it?.status == ServiceStatus.ERROR,
            scanning = it?.status == ServiceStatus.LOOKING,
            nearbyGlasses = if(it == null || it.status == ServiceStatus.READY) null else it.glasses
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, State())

    sealed class Event {
        data class ConnectionResult(val success: Boolean) : Event()
        data object Disconnected : Event()
    }

    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()

    fun scan() {
        repository.startLooking()
    }

    fun connect(id: String) {
        viewModelScope.launch {
            val success = repository.connectGlasses(id)
            _events.emit(Event.ConnectionResult(success))
        }
    }

    fun disconnect(id: String) {
        repository.disconnectGlasses(id)
        viewModelScope.launch { _events.emit(Event.Disconnected) }
    }

    fun disconnectSelected() {
        repository.disconnectGlasses()
        viewModelScope.launch { _events.emit(Event.Disconnected) }
    }

    fun sendMessage(message: String) {
        repository.sendMessage(message)
    }
}