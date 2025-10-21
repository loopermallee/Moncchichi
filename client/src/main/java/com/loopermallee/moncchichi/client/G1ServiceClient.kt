package com.loopermallee.moncchichi.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.loopermallee.moncchichi.service.protocol.G1Glasses
import com.loopermallee.moncchichi.service.protocol.G1ServiceState
import com.loopermallee.moncchichi.service.protocol.IG1ServiceClient
import com.loopermallee.moncchichi.service.protocol.ObserveStateCallback
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class G1ServiceClient constructor(context: Context) :
    G1ServiceCommon<IG1ServiceClient>(context) {

    interface Listener {
        fun onConnected(name: String?) {}
        fun onDisconnected() {}
        fun onMessage(bytes: ByteArray) {}
        fun onError(err: String) {}
    }

    private var listener: Listener? = null

    fun setListener(
        onConnected: (String?) -> Unit = {},
        onDisconnected: () -> Unit = {},
        onMessage: (ByteArray) -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        listener = object : Listener {
            override fun onConnected(name: String?) = onConnected(name)
            override fun onDisconnected() = onDisconnected()
            override fun onMessage(bytes: ByteArray) = onMessage(bytes)
            override fun onError(err: String) = onError(err)
        }
    }

    companion object {
        fun open(context: Context): G1ServiceClient? {
            val client = G1ServiceClient(context)
            val intent = Intent("com.loopermallee.moncchichi.service.protocol.IG1ServiceClient")

            // ✅ Fixed target: bind to Moncchichi’s internal display service
            intent.setClassName(
                "com.loopermallee.moncchichi",
                "com.loopermallee.moncchichi.service.G1DisplayService"
            )

            if (
                context.bindService(
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
                it.setClassName(
                    "com.loopermallee.moncchichi",
                    "com.loopermallee.moncchichi.hub.ui.HubMainActivity"
                )
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

    fun startScanAndConnect(
        onDeviceFound: (name: String?, addr: String) -> Unit = { _, _ -> },
        onFail: (String) -> Unit = {},
    ) {
        // TODO: replace with your real scan/connect flow
        // Call listener?.onConnected(deviceName) when connected
        // Call listener?.onError(msg) on errors
        onFail("Scan not implemented")
    }

    fun write(data: ByteArray) {
        // TODO: forward to your UART write
    }

    fun disconnect() {
        // TODO: forward to your disconnect logic
        listener?.onDisconnected()
    }
}