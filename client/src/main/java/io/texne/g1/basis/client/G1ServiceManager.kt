package com.loopermallee.moncchichi.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.loopermallee.moncchichi.service.protocol.G1Glasses
import com.loopermallee.moncchichi.service.protocol.G1ServiceState
import com.loopermallee.moncchichi.service.protocol.IG1Service
import com.loopermallee.moncchichi.service.protocol.IG1StateCallback
import kotlin.collections.buildList
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class G1ServiceManager private constructor(context: Context): G1ServiceCommon<IG1Service>(context) {

    companion object {
        fun open(context: Context): G1ServiceManager? {
            val client = G1ServiceManager(context)
            val intent = Intent("com.loopermallee.moncchichi.service.protocol.IG1Service")
            intent.setClassName(context.packageName, "com.loopermallee.moncchichi.service.G1Service")
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
                override fun onStateChanged(status: Int, glassesPayload: Array<out G1Glasses>?) {
                    val serviceStatus = when (status) {
                        G1ServiceState.READY -> ServiceStatus.READY
                        G1ServiceState.LOOKING -> ServiceStatus.LOOKING
                        G1ServiceState.LOOKED -> ServiceStatus.LOOKED
                        else -> ServiceStatus.ERROR
                    }
                    val glasses = buildList {
                        glassesPayload?.forEach { glass ->
                            val connectionStatus = when (glass.connectionState) {
                                G1Glasses.UNINITIALIZED -> GlassesStatus.UNINITIALIZED
                                G1Glasses.DISCONNECTED -> GlassesStatus.DISCONNECTED
                                G1Glasses.CONNECTING -> GlassesStatus.CONNECTING
                                G1Glasses.CONNECTED -> GlassesStatus.CONNECTED
                                G1Glasses.DISCONNECTING -> GlassesStatus.DISCONNECTING
                                else -> GlassesStatus.ERROR
                            }
                            val id = glass.id
                            val name = glass.name ?: id
                            add(
                                Glasses(
                                    id = id,
                                    name = name,
                                    status = connectionStatus,
                                    batteryPercentage = glass.batteryPercentage.takeIf { it >= 0 },
                                    firmwareVersion = glass.firmwareVersion,
                                )
                            )
                        }
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
            object : com.loopermallee.moncchichi.service.protocol.OperationCallback.Stub() {
                override fun onResult(success: Boolean) {
                    continuation.resume(success)
                }
            })
    }

    fun disconnect(id: String) {
        service?.disconnectGlasses(id, null)
    }

    fun connectPreferredGlasses() {
        service?.connectPreferredGlasses()
    }

    fun disconnectPreferredGlasses() {
        service?.disconnectPreferredGlasses()
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
                object : com.loopermallee.moncchichi.service.protocol.OperationCallback.Stub() {
                    override fun onResult(success: Boolean) {
                        continuation.resume(success)
                    }
                })
        }

    override suspend fun stopDisplaying(id: String) = suspendCoroutine<Boolean> { continuation ->
        service?.stopDisplaying(
            id,
            object : com.loopermallee.moncchichi.service.protocol.OperationCallback.Stub() {
                override fun onResult(success: Boolean) {
                    continuation.resume(success)
                }
            })
    }
}