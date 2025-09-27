package io.texne.g1.basis.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.texne.g1.basis.service.protocol.G1Glasses
import io.texne.g1.basis.service.protocol.G1ServiceState
import io.texne.g1.basis.service.protocol.IG1ServiceClient
import io.texne.g1.basis.service.protocol.ObserveStateCallback
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class G1ServiceClient private constructor(context: Context): G1ServiceCommon<IG1ServiceClient>(context) {

    companion object {
        fun open(context: Context): G1ServiceClient? {
            val client = G1ServiceClient(context)
            val intent = Intent("io.texne.g1.basis.service.protocol.IG1ServiceClient")
            intent.setClassName("io.texne.g1.hub", "io.texne.g1.basis.service.G1Service")
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

        fun openHub(context: Context) {
            context.startActivity(Intent(Intent.ACTION_MAIN).also {
                it.setClassName("io.texne.g1.hub", "io.texne.g1.hub.MainActivity")
            })
        }
    }

    override val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            name: ComponentName?,
            binder: IBinder?
        ) {
            service = IG1ServiceClient.Stub.asInterface(binder)
            service?.observeState(object : ObserveStateCallback.Stub() {
                override fun onStateChange(newState: G1ServiceState?) {
                    if (newState != null) {
                        val glasses = mutableListOf<Glasses>()
                        val discovered = newState.glasses
                        if (discovered != null) {
                            for (glass in discovered) {
                                if (glass != null) {
                                    val status = when (glass.connectionState) {
                                        G1Glasses.UNINITIALIZED -> GlassesStatus.UNINITIALIZED
                                        G1Glasses.DISCONNECTED -> GlassesStatus.DISCONNECTED
                                        G1Glasses.CONNECTING -> GlassesStatus.CONNECTING
                                        G1Glasses.CONNECTED -> GlassesStatus.CONNECTED
                                        G1Glasses.DISCONNECTING -> GlassesStatus.DISCONNECTING
                                        else -> GlassesStatus.ERROR
                                    }
                                    val id = glass.id
                                    val name = glass.name ?: id
                                    glasses += Glasses(
                                        id = id,
                                        name = name,
                                        status = status,
                                        // AIDL uses -1 when the battery percentage is unknown.
                                        batteryPercentage = glass.batteryPercentage.takeIf { it >= 0 },
                                    )
                                }
                            }
                        }

                        writableState.value = State(
                            status = when (newState.status) {
                                G1ServiceState.READY -> ServiceStatus.READY
                                G1ServiceState.LOOKING -> ServiceStatus.LOOKING
                                G1ServiceState.LOOKED -> ServiceStatus.LOOKED
                                else -> ServiceStatus.ERROR
                            },
                            glasses = glasses
                        )
                    }
                }
            })
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
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