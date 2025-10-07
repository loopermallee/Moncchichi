package io.texne.g1.hub.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.basis.client.G1ServiceManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Singleton
class Repository @Inject constructor(
    @ApplicationContext private val applicationContext: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _serviceState = MutableStateFlow<G1ServiceCommon.State?>(null)
    private var stateJob: Job? = null

    fun getServiceStateFlow(): StateFlow<G1ServiceCommon.State?> = _serviceState

    fun bindService(): Boolean {
        val manager = G1ServiceManager.open(applicationContext) ?: return false
        service = manager
        stateJob?.cancel()
        stateJob = scope.launch {
            manager.state.collect { value ->
                _serviceState.value = value
            }
        }
        return true
    }

    fun unbindService() {
        stateJob?.cancel()
        stateJob = null
        _serviceState.value = null
        service?.close()
        service = null
    }

    fun startLooking() =
        boundService.lookForGlasses()

    suspend fun connectGlasses(id: String) =
        boundService.connect(id)

    fun disconnectGlasses(id: String) =
        boundService.disconnect(id)

    fun connectSelectedGlasses() =
        boundService.connectPreferredGlasses()

    fun disconnectGlasses() =
        boundService.disconnectPreferredGlasses()

    fun isConnected() =
        boundService.isConnected()

    fun sendMessage(message: String) =
        boundService.sendMessage(message)

    suspend fun displayTextPage(id: String, page: List<String>) =
        boundService.displayTextPage(id, page)

    suspend fun stopDisplaying(id: String) =
        boundService.stopDisplaying(id)

    private var service: G1ServiceManager? = null

    // The hub UI should only touch the repository after bindService() succeeds, so we fail fast otherwise.
    private val boundService: G1ServiceManager
        get() = requireNotNull(service) { "G1ServiceManager is not bound; call bindService() before using the repository." }
}
