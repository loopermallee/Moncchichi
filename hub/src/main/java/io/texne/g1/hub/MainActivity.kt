package io.texne.g1.hub

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.StrictMode
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private var bound = false
    private var binder: G1DisplayService.G1Binder? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bound = true
            binder = service as? G1DisplayService.G1Binder
            ServiceRepository.setConnected()
            Log.d("Boot", "Service connected on binder thread")
            runOnUiThread { status.setText(R.string.boot_service_connected) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            binder = null
            ServiceRepository.setDisconnected()
            Log.w("Boot", "Service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )

        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)

        when (ServiceRepository.state.value) {
            ServiceState.IDLE, ServiceState.DISCONNECTED -> status.setText(R.string.boot_wait)
            ServiceState.BINDING -> status.setText(R.string.boot_service_binding)
            ServiceState.CONNECTED -> status.setText(R.string.boot_service_connected)
            ServiceState.ERROR -> status.text = "Error"
        }

        lifecycleScope.launch {
            ServiceRepository.setBinding()
            status.setText(R.string.boot_service_binding)

            val ok = withTimeoutOrNull(5000) { bindServiceAwait() } ?: false
            if (!ok) {
                Log.e("Boot", "Service bind timeout")
                ServiceRepository.setError()
                status.setText(R.string.boot_service_timeout)
            }
        }

        lifecycleScope.launch {
            ServiceRepository.state.collect { state ->
                when (state) {
                    ServiceState.CONNECTED -> {
                        Log.d("Boot", "Repository state = CONNECTED")
                        status.text = "Connected. Initializing data..."
                        delay(1000)
                        try {
                            val msg = g1PingService()
                            status.text = msg
                        } catch (t: Throwable) {
                            status.text = "Ping failed: ${t.message}"
                        }
                    }
                    ServiceState.DISCONNECTED -> status.text = "Disconnected"
                    ServiceState.ERROR -> status.text = "Error - check logs"
                    ServiceState.BINDING -> status.setText(R.string.boot_service_binding)
                    else -> Unit
                }
            }
        }
    }

    private suspend fun bindServiceAwait(): Boolean = suspendCancellableCoroutine { cont ->
        val intent = Intent(this, G1DisplayService::class.java)
        val started = bindService(intent, conn, Context.BIND_AUTO_CREATE)
        if (!started) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        lifecycleScope.launch(Dispatchers.Default) {
            repeat(100) {
                if (bound) {
                    if (!cont.isCompleted) cont.resume(true)
                    return@launch
                }
                delay(50)
            }
            if (!cont.isCompleted) cont.resume(bound)
        }
        cont.invokeOnCancellation {
            if (bound) {
                unbindService(conn)
                bound = false
                binder = null
                ServiceRepository.setDisconnected()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            unbindService(conn)
            bound = false
            binder = null
            ServiceRepository.setDisconnected()
        }
    }

    private suspend fun g1PingService(): String = withContext(Dispatchers.IO) {
        return@withContext try {
            binder?.pingBinder() ?: throw IllegalStateException("Binder unavailable")
            Log.d("Boot", "Ping OK")
            "G1 Display ready ✅"
        } catch (t: Throwable) {
            Log.e("Boot", "Ping failed", t)
            "Ping failed ❌"
        }
    }
}
