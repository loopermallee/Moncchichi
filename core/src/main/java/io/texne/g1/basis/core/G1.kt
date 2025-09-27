package io.texne.g1.basis.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings
import kotlin.time.Duration

@OptIn(DelicateCoroutinesApi::class)
internal fun <T1, T2, R> StateFlow<T1>.combineState(
    flow2: StateFlow<T2>,
    scope: CoroutineScope = GlobalScope,
    sharingStarted: SharingStarted = SharingStarted.Eagerly,
    transform: (T1, T2) -> R
): StateFlow<R> = this.combine(flow2) {
        o1, o2 -> transform.invoke(o1, o2)
}.stateIn(scope, sharingStarted, transform.invoke(this.value, flow2.value))

class G1 {

    // internals -----------------------------------------------------------------------------------

    private val right: G1Device
    private val left: G1Device

    // externally visible --------------------------------------------------------------------------

    val id: String
    val name: String

    enum class ConnectionState { UNINITIALIZED, DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }
    data class State(
        val connectionState: ConnectionState,
        val batteryPercentage: Int?
    )
    val state: StateFlow<State>
    private var currentState: State? = null

    // construction --------------------------------------------------------------------------------

    private constructor(right: G1Device, left: G1Device) {
        val splitL = left.name.split("_")
        this.id = "${left.address}${right.address}".filter { it != ':' }
        this.name = if (splitL.size >= 2) {
            "${splitL[0]}.${splitL[1]}"
        } else {
            left.name
        }
        this.right = right
        this.left = left
        this.state = right.state.combineState(left.state) { l, r ->
            val lConnectionState = l.connectionState
            val rConnectionState = r.connectionState
            val connectionState = when {
                lConnectionState == ConnectionState.CONNECTED && rConnectionState == ConnectionState.CONNECTED -> ConnectionState.CONNECTED
                lConnectionState == ConnectionState.DISCONNECTING || rConnectionState == ConnectionState.DISCONNECTING -> ConnectionState.DISCONNECTING
                lConnectionState == ConnectionState.DISCONNECTED && rConnectionState == ConnectionState.CONNECTED -> ConnectionState.DISCONNECTING
                lConnectionState == ConnectionState.CONNECTED && rConnectionState == ConnectionState.DISCONNECTED -> ConnectionState.DISCONNECTING
                lConnectionState == ConnectionState.CONNECTING || rConnectionState == ConnectionState.CONNECTING -> ConnectionState.CONNECTING
                lConnectionState == ConnectionState.DISCONNECTED && rConnectionState == ConnectionState.DISCONNECTED -> ConnectionState.DISCONNECTED
                lConnectionState == ConnectionState.ERROR || rConnectionState == ConnectionState.ERROR -> ConnectionState.ERROR
                else -> ConnectionState.UNINITIALIZED
            }

            val batteryPercentage = if(l.batteryPercentage == null || r.batteryPercentage == null) null else l.batteryPercentage.coerceAtMost(
                r.batteryPercentage
            )

            val current = currentState
            if(
                current != null &&
                connectionState == current.connectionState &&
                batteryPercentage == current.batteryPercentage
            ) {
                current
            } else {
                val newState = State(
                    connectionState = connectionState,
                    batteryPercentage = batteryPercentage
                )
                Log.d("G1", "G1_STATE - composing - ${l} and ${r} = ${newState}")
                currentState = newState
                newState
            }
        }
    }

    // connect / disconnect ------------------------------------------------------------------------

    suspend fun connect(context: Context, scope: CoroutineScope) = coroutineScope<Boolean> {
        if(state.value.connectionState == ConnectionState.CONNECTED) {
            true
        } else {
            val results = awaitAll(
                async { left.connect(context, scope) },
                async { right.connect(context, scope) }
            )
            if (results[0] == true && results[1] == true) {
                true
            } else {
                left.disconnect()
                right.disconnect()
                false
            }
        }
    }

    suspend fun disconnect() {
        left.disconnect()
        right.disconnect()
    }

    // requests ------------------------------------------------------------------------------------

    suspend fun displayTextPage(page: List<String>): Boolean {
        val singleStringPage = page.joinToString("\n")
        if(left.sendRequestForResponse(
                SendTextPacket(
                    singleStringPage,
                    1,
                    1
                )
            ) == null) {
            return false
        }
        if(right.sendRequestForResponse(
                SendTextPacket(
                    singleStringPage,
                    1,
                    1
                )
            ) == null) {
            return false
        }
        return true
    }

    suspend fun stopDisplaying(): Boolean {
        left.sendRequest(ExitRequestPacket())
        right.sendRequest(ExitRequestPacket())
        return true
    }

    // find devices --------------------------------------------------------------------------------

    companion object {
        fun find(duration: Duration) = callbackFlow<G1?> {
            data class FoundPair(
                val left: ScanResult? = null,
                val right: ScanResult? = null
            )

            val scanner = BluetoothLeScannerCompat.getScanner()
            val settings = ScanSettings.Builder()
                .setLegacy(false)
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(5000)
                .setUseHardwareFilteringIfSupported(true)
                .build()
            val foundAddresses = mutableListOf<String>()
            val foundPairs = mutableMapOf<String, FoundPair>()
            val callback = object: ScanCallback() {
                @SuppressLint("MissingPermission")
                override fun onBatchScanResults(results: List<ScanResult?>) {
                    val validResults = results.mapNotNull { result ->
                        val device = result?.device ?: return@mapNotNull null
                        val name = device.name ?: return@mapNotNull null
                        if (!name.startsWith(DEVICE_NAME_PREFIX)) {
                            return@mapNotNull null
                        }
                        if (foundAddresses.contains(device.address)) {
                            return@mapNotNull null
                        }
                        result
                    }

                    validResults
                        .distinctBy { it.device.address }
                        .groupBy { result ->
                            result.device.name?.split("_")?.getOrNull(1) ?: result.device.address
                        }
                        .forEach { (key, group) ->
                            group.forEach { found ->
                                foundAddresses.add(found.device.address)
                            }
                            val pair = foundPairs[key]
                            val left = pair?.left ?: group.firstOrNull { found ->
                                found.device.name?.split('_')?.getOrNull(2) == "L"
                            }
                            val right = pair?.right ?: group.firstOrNull { found ->
                                found.device.name?.split('_')?.getOrNull(2) == "R"
                            }
                            if(left != null && right != null) {
                                foundPairs.remove(key)
                                trySendBlocking(G1(
                                    G1Device(right),
                                    G1Device(left)
                                ))
                            } else {
                                foundPairs[key] = FoundPair(left, right)
                            }
                        }
                    }
            }
            scanner.startScan(listOf(), settings, callback)
            Handler(Looper.getMainLooper()).postDelayed({
                scanner.stopScan(callback)
                trySendBlocking(null)
            }, duration.inWholeMilliseconds)
            awaitClose {
                scanner.stopScan(callback)
            }
        }
    }
}