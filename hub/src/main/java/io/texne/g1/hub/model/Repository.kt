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
        service.state

    fun bindService(): Boolean {
        service = G1ServiceManager.open(applicationContext) ?: return false
        return true
    }

    fun unbindService() =
        service.close()

    fun startLooking() =
        service.lookForGlasses()

    suspend fun connectGlasses(id: String) =
        service.connect(id)

    fun disconnectGlasses(id: String) =
        service.disconnect(id)

    fun connectSelectedGlasses() =
        service.connectPreferredGlasses()

    fun disconnectGlasses() =
        service.disconnectPreferredGlasses()

    fun isConnected() =
        service.isConnected()

    fun sendMessage(message: String) =
        service.sendMessage(message)

    private lateinit var service: G1ServiceManager
}