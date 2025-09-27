package io.texne.g1.basis.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.texne.g1.basis.core.G1State
import io.texne.g1.basis.service.protocol.IG1Service
import io.texne.g1.basis.service.protocol.IG1StateCallback
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class G1ServiceManager private constructor(context: Context): G1ServiceCommon<IG1Service>(context) {

    companion object {
        fun open(context: Context): G1ServiceManager? {
            val client = G1ServiceManager(context)
            val intent = Intent("io.texne.g1.basis.service.protocol.IG1Service")
            intent.setClassName(context.packageName, "io.texne.g1.basis.service.G1Service")
            if (context.bindService(
                    intent,
                    client.serviceConnection,
                    Context.BIND_AUTO_CREATE
                )
            ) {
                return client
            }
            return null
        }
    }

    override val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            name: ComponentName?,
            binder: IBinder?
        ) {
            service = IG1Service.Stub.asInterface(binder)
            service?.observeState(object : IG1StateCallback.Stub() {
                override fun onStateChanged(status: Int, deviceId: String?) {
                    val serviceStatus = when (status) {
                        G1State.READY -> ServiceStatus.READY
                        G1State.LOOKING -> ServiceStatus.LOOKING
                        G1State.LOOKED -> ServiceStatus.LOOKED
                        else -> ServiceStatus.ERROR
                    }
                    val glasses = if (deviceId != null) {
                        val glassesStatus = when (serviceStatus) {
                            ServiceStatus.LOOKED -> GlassesStatus.CONNECTED
                            ServiceStatus.LOOKING -> GlassesStatus.CONNECTING
                            else -> GlassesStatus.DISCONNECTED
                        }
                        listOf(
                            Glasses(
                                id = deviceId,
                                name = deviceId,
                                status = glassesStatus,
                                batteryPercentage = -1
                            )
                        )
                    } else {
                        emptyList()
                    }
                    writableState.value = State(
                        status = serviceStatus,
                        glasses = glasses
                    )
                }
            })
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun lookForGlasses() {
        service?.lookForGlasses()
    }

    suspend fun connect(id: String) = suspendCoroutine<Boolean> { continuation ->
        service?.connectGlasses(
            id,
            object : io.texne.g1.basis.service.protocol.OperationCallback.Stub() {
                override fun onResult(success: Boolean) {
                    continuation.resume(success)
                }
            })
    }

    fun disconnect(id: String) {
        service?.disconnectGlasses(id, null)
    }

    fun connectGlasses() {
        service?.connectGlasses()
    }

    fun disconnectGlasses() {
        service?.disconnectGlasses()
    }

    fun isConnected(): Boolean = service?.isConnected() ?: false

    fun sendMessage(message: String) {
        service?.sendMessage(message)
    }

    override suspend fun displayTextPage(id: String, page: List<String>) =
        suspendCoroutine<Boolean> { continuation ->
            service?.displayTextPage(
                id,
                page.toTypedArray(),
                object : io.texne.g1.basis.service.protocol.OperationCallback.Stub() {
                    override fun onResult(success: Boolean) {
                        continuation.resume(success)
                    }
                })
        }

    override suspend fun stopDisplaying(id: String) = suspendCoroutine<Boolean> { continuation ->
        service?.stopDisplaying(
            id,
            object : io.texne.g1.basis.service.protocol.OperationCallback.Stub() {
                override fun onResult(success: Boolean) {
                    continuation.resume(success)
                }
            })
    }
}