package io.texne.g1.hub.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.basis.client.G1ServiceManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Repository @Inject constructor(
    @ApplicationContext private val applicationContext: Context
) {
    fun getServiceStateFlow() =
        boundService.state

    fun bindService(): Boolean {
        val manager = G1ServiceManager.open(applicationContext) ?: return false
        service = manager
        return true
    }

    fun unbindService() {
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

    private var service: G1ServiceManager? = null

    // The hub UI should only touch the repository after bindService() succeeds, so we fail fast otherwise.
    private val boundService: G1ServiceManager
        get() = requireNotNull(service) { "G1ServiceManager is not bound; call bindService() before using the repository." }
}
